package com.sw.ck.form.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.dto.ExtQueryResult;
import com.sw.ck.form.api.exception.ExternalDatasourceResultLimitExceededException;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.api.port.ExtDatasourceGrantPort;
import com.sw.ck.form.api.port.ExtDatasourceQueryPort;
import com.sw.ck.form.dto.ExtQueryContractView;
import com.sw.ck.form.entity.FormExtQueryEntity;
import com.sw.ck.form.mapper.FormExtQueryMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 受控外部数据源表单链路服务（I2，方向 §4.3）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>查询契约注册表维护（版本化；变更=新版本，不原地改写）。</li>
 *   <li>发布期绑定校验：queryKey/version 存在、启用、输出包含 value/display 字段。</li>
 *   <li>设计预览与运行读取走同一服务端入口（本服务），执行统一委托
 *       {@link ExtDatasourceQueryPort}，SQL 绝不来自客户端。</li>
 *   <li>提交期对象解析：按 valueField 匹配真实行，回填 displayField，
 *       伪造/失效对象 → {@link FormErrorCode#EXT_OBJECT_NOT_FOUND}。</li>
 * </ul>
 *
 * <h3>红线</h3>
 * <ul>
 *   <li>表单 definition 只存 queryKey/version/valueField/displayField 稳定标识。</li>
 *   <li>密钥、连接串、SQL 不出服务端、不进响应、不进日志。</li>
 *   <li>历史回看不重新请求外部源：提交时已冻结 {value,display,queryKey,version}。</li>
 * </ul>
 */
@Service
public class FormExtDataService {

    private static final Logger log = LoggerFactory.getLogger(FormExtDataService.class);

    private final FormExtQueryMapper extQueryMapper;
    private final ObjectProvider<ExtDatasourceQueryPort> queryPort;
    private final ObjectProvider<ExtDatasourceGrantPort> grantPort;
    private final ObjectMapper objectMapper;

    public FormExtDataService(FormExtQueryMapper extQueryMapper,
                              ObjectProvider<ExtDatasourceQueryPort> queryPort,
                              ObjectProvider<ExtDatasourceGrantPort> grantPort,
                              ObjectMapper objectMapper) {
        this.extQueryMapper = extQueryMapper;
        this.queryPort = queryPort;
        this.grantPort = grantPort;
        this.objectMapper = objectMapper;
    }

    // ==================== 契约注册表 ====================

    /**
     * 登记或升版本一个查询契约。同 key 已存在且 sql/output 变化时生成新版本。
     *
     * @return 契约实体（含最终 queryVersion）
     */
    public FormExtQueryEntity registerQuery(Long datasourceId, String queryKey, String sqlText,
                                            List<Map<String, String>> outputFields) {
        requirePort();
        if (datasourceId == null || datasourceId <= 0) {
            throw new BaseException(FormErrorCode.LIST_CONFIG_INVALID.getCode(),
                    "datasourceId 非法");
        }
        requireReferenceAllowed(datasourceId);
        if (queryKey == null || !queryKey.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new BaseException(FormErrorCode.LIST_CONFIG_INVALID.getCode(),
                    "queryKey 必须匹配 [a-z][a-z0-9_]{0,63}");
        }
        if (sqlText == null || sqlText.isBlank()) {
            throw new BaseException(FormErrorCode.LIST_CONFIG_INVALID.getCode(),
                    "sqlText 不能为空");
        }
        String outputJson = serializeOutput(outputFields);

        FormExtQueryEntity latest = findLatest(queryKey);
        if (latest != null && latest.getSqlText().equals(sqlText.trim())
                && latest.getOutputFields().equals(outputJson)
                && latest.getDatasourceId().equals(datasourceId)) {
            return latest; // 幂等：完全相同契约直接复用当前版本
        }
        FormExtQueryEntity entity = new FormExtQueryEntity();
        entity.setDatasourceId(datasourceId);
        entity.setQueryKey(queryKey);
        entity.setQueryVersion(latest == null ? 1 : latest.getQueryVersion() + 1);
        entity.setSqlText(sqlText.trim());
        entity.setOutputFields(outputJson);
        entity.setEnabled(1);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        entity.setTenantId(currentTenantId());
        entity.setDeleted(0);
        entity.setVersion(0L);
        extQueryMapper.insert(entity);
        log.info("Registered ext query contract: key={}, version={}, ds={}",
                queryKey, entity.getQueryVersion(), datasourceId);
        return entity;
    }

    /** 按稳定标识解析启用中的契约（运行路径）。 */
    public FormExtQueryEntity resolveEnabled(String queryKey, Integer version) {
        FormExtQueryEntity entity = findLatest(queryKey);
        if (entity == null) {
            throw new BaseException(FormErrorCode.EXT_QUERY_NOT_FOUND,
                    "外部数据源查询契约不存在: " + queryKey);
        }
        if (version != null && !version.equals(entity.getQueryVersion())) {
            // 表单冻结的是历史版本：历史解释只用已存值，不重新执行；仅运行期新读取要求启用版本
            throw new BaseException(FormErrorCode.EXT_QUERY_NOT_FOUND,
                    "外部数据源查询契约版本不存在: " + queryKey + " v" + version);
        }
        if (entity.getEnabled() == null || entity.getEnabled() != 1) {
            throw new BaseException(FormErrorCode.EXT_QUERY_DISABLED,
                    "外部数据源查询契约已停用: " + queryKey);
        }
        return entity;
    }

    // ==================== 契约清单与启停（F020） ====================

    /**
     * 契约清单（每个 queryKey 取最新版本）：按当前登录主体的引用授权过滤，
     * 数据源管理员可见全部；SQL 文本不进入视图。
     */
    public List<ExtQueryContractView> listContracts(String queryKey, Integer enabled) {
        requireGrantPort();
        List<FormExtQueryEntity> rows = extQueryMapper.selectList(
                Wrappers.lambdaQuery(FormExtQueryEntity.class)
                        .like(queryKey != null && !queryKey.isBlank(), FormExtQueryEntity::getQueryKey,
                                queryKey == null ? null : queryKey.trim())
                        .orderByAsc(FormExtQueryEntity::getQueryKey)
                        .orderByDesc(FormExtQueryEntity::getQueryVersion));
        Set<Long> allowed = grantPort.getIfAvailable().referenceAllowedDatasourceIds();
        Map<String, FormExtQueryEntity> latestByKey = new LinkedHashMap<>();
        for (FormExtQueryEntity row : rows) {
            latestByKey.putIfAbsent(row.getQueryKey(), row);
        }
        List<ExtQueryContractView> views = new ArrayList<>();
        for (FormExtQueryEntity row : latestByKey.values()) {
            if (enabled != null && !enabled.equals(row.getEnabled())) {
                continue;
            }
            if (!allowed.contains(row.getDatasourceId())) {
                continue;
            }
            views.add(new ExtQueryContractView(row.getQueryKey(), row.getQueryVersion(),
                    row.getDatasourceId(), outputFieldsAsMaps(row), row.getEnabled()));
        }
        return views;
    }

    /**
     * 契约启停（作用于最新版本）：停用后设计预览与运行读取返回既有
     * {@link FormErrorCode#EXT_QUERY_DISABLED} 语义，历史回看不受影响。
     */
    public FormExtQueryEntity setContractEnabled(String queryKey, boolean enabled) {
        FormExtQueryEntity entity = findLatest(queryKey);
        if (entity == null) {
            throw new BaseException(FormErrorCode.EXT_QUERY_NOT_FOUND,
                    "外部数据源查询契约不存在: " + queryKey);
        }
        entity.setEnabled(enabled ? 1 : 0);
        extQueryMapper.updateById(entity);
        log.info("Ext query contract {}: key={}, version={}", enabled ? "enabled" : "disabled",
                queryKey, entity.getQueryVersion());
        return entity;
    }

    // ==================== 绑定校验（发布期） ====================

    /**
     * 校验 definition 中 dsBinding：契约存在、启用、输出 schema 包含 value/display 字段。
     */
    public void validateBinding(String queryKey, Integer version, String valueField, String displayField) {
        FormExtQueryEntity entity = findLatest(queryKey);
        if (entity == null) {
            throw new BaseException(FormErrorCode.EXT_QUERY_NOT_FOUND,
                    "外部数据源查询契约不存在: " + queryKey);
        }
        if (version != null && !version.equals(entity.getQueryVersion())) {
            throw new BaseException(FormErrorCode.EXT_QUERY_NOT_FOUND,
                    "外部数据源查询契约版本不存在: " + queryKey + " v" + version);
        }
        if (entity.getEnabled() == null || entity.getEnabled() != 1) {
            throw new BaseException(FormErrorCode.EXT_QUERY_DISABLED,
                    "外部数据源查询契约已停用: " + queryKey);
        }
        requireReferenceAllowed(entity.getDatasourceId());
        Set<String> outputNames = outputFieldNames(entity);
        if (!outputNames.contains(valueField) || !outputNames.contains(displayField)) {
            throw new BaseException(FormErrorCode.EXT_OUTPUT_MISMATCH,
                    "dsBinding 的 valueField/displayField 不在契约输出 schema 内: " + queryKey);
        }
    }

    // ==================== 预览与对象解析（同一服务端入口） ====================

    /**
     * 设计预览/运行读取：执行契约查询（行数已由执行引擎上限约束）。
     */
    public ExtQueryResult preview(String queryKey, Integer version) {
        FormExtQueryEntity entity = resolveEnabled(queryKey, version);
        ExtDatasourceQueryPort port = requirePort();
        LoginUser user = LoginUserHolder.get();
        ExtQueryResult result;
        try {
            // empty = 数据源未登记或已停用（查询未执行）：保持原 IllegalArgumentException 语义，
            // 由统一异常处理器落 400 受控参数错误，不伪装成查询结果、不静默降级。
            result = port.executeQuery(entity.getDatasourceId(), entity.getSqlText(),
                    user == null ? null : user.getUserId(),
                    user == null ? null : user.getUsername())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "External datasource not found or disabled: id=" + entity.getDatasourceId()));
        } catch (ExternalDatasourceResultLimitExceededException e) {
            log.warn("External query rejected because result exceeds configured row limit: key={}, maxRows={}",
                    queryKey, e.getMaxRows());
            throw new BaseException(FormErrorCode.EXT_RESULT_LIMIT_EXCEEDED,
                    "外部数据源返回结果超过行数上限，未返回不完整数据");
        }
        assertOutputMatches(entity, result);
        return result;
    }

    /**
     * 提交期对象解析：按 valueField 匹配真实行，返回 displayField 值。
     * 伪造/越权/失效对象在此被拒绝（不产生任何业务写入副作用）。
     */
    public String resolveSelection(String queryKey, Integer version,
                                   String valueField, String displayField, String value) {
        ExtQueryResult result = preview(queryKey, version);
        for (Map<String, Object> row : result.getRows()) {
            Object candidate = row.get(valueField);
            if (candidate != null && String.valueOf(candidate).equals(value)) {
                Object display = row.get(displayField);
                return display == null ? value : String.valueOf(display);
            }
        }
        throw new BaseException(FormErrorCode.EXT_OBJECT_NOT_FOUND,
                "外部数据对象不存在或不可见: value='" + value + "'");
    }

    // ==================== 内部 ====================

    private FormExtQueryEntity findLatest(String queryKey) {
        return extQueryMapper.selectOne(Wrappers.lambdaQuery(FormExtQueryEntity.class)
                .eq(FormExtQueryEntity::getQueryKey, queryKey)
                .orderByDesc(FormExtQueryEntity::getQueryVersion)
                .last("LIMIT 1"));
    }

    private Set<String> outputFieldNames(FormExtQueryEntity entity) {
        Set<String> names = new LinkedHashSet<>();
        try {
            JsonNode arr = objectMapper.readTree(entity.getOutputFields());
            if (arr.isArray()) {
                arr.forEach(n -> {
                    JsonNode name = n.get("name");
                    if (name != null) {
                        names.add(name.asText());
                    }
                });
            }
        } catch (Exception e) {
            throw new BaseException(FormErrorCode.EXT_OUTPUT_MISMATCH,
                    "契约输出 schema 解析失败: " + queryKeyOf(entity));
        }
        return names;
    }

    private void assertOutputMatches(FormExtQueryEntity entity, ExtQueryResult result) {
        if (result.getRows() == null) {
            return;
        }
        Set<String> expected = outputFieldNames(entity);
        for (Map<String, Object> row : result.getRows()) {
            for (String col : row.keySet()) {
                if (!expected.contains(col)) {
                    throw new BaseException(FormErrorCode.EXT_OUTPUT_MISMATCH,
                            "外部查询输出列不在契约 schema 内: " + col);
                }
            }
        }
    }

    private ExtDatasourceQueryPort requirePort() {
        ExtDatasourceQueryPort port = queryPort.getIfAvailable();
        if (port == null) {
            throw new BaseException(FormErrorCode.EXT_QUERY_NOT_FOUND.getCode(),
                    "外部数据源执行引擎未装配");
        }
        return port;
    }

    private ExtDatasourceGrantPort requireGrantPort() {
        ExtDatasourceGrantPort port = grantPort.getIfAvailable();
        if (port == null) {
            throw new BaseException(FormErrorCode.EXT_DATASOURCE_UNAUTHORIZED.getCode(),
                    "外部数据源引用授权未装配");
        }
        return port;
    }

    /** 设计期引用授权校验：契约登记与发布绑定共用；运行期查询不回查授权。 */
    private void requireReferenceAllowed(Long datasourceId) {
        if (!requireGrantPort().isReferenceAllowed(datasourceId)) {
            LoginUser user = LoginUserHolder.get();
            log.warn("Ext datasource reference denied: datasourceId={}, operator={}",
                    datasourceId, user == null ? "anonymous" : user.getUsername());
            throw new BaseException(FormErrorCode.EXT_DATASOURCE_UNAUTHORIZED,
                    "未授权引用该外部数据源");
        }
    }

    private List<Map<String, Object>> outputFieldsAsMaps(FormExtQueryEntity entity) {
        try {
            JsonNode arr = objectMapper.readTree(entity.getOutputFields());
            return objectMapper.convertValue(arr,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
        } catch (Exception e) {
            throw new BaseException(FormErrorCode.EXT_OUTPUT_MISMATCH.getCode(),
                    "契约输出 schema 解析失败: " + entity.getQueryKey());
        }
    }

    private String serializeOutput(List<Map<String, String>> outputFields) {
        try {
            if (outputFields == null || outputFields.isEmpty()) {
                throw new BaseException(FormErrorCode.LIST_CONFIG_INVALID.getCode(),
                        "outputFields 不能为空");
            }
            for (Map<String, String> field : outputFields) {
                if (field == null || field.get("name") == null
                        || !field.get("name").matches("[a-zA-Z_][a-zA-Z0-9_]{0,62}")) {
                    throw new BaseException(FormErrorCode.LIST_CONFIG_INVALID.getCode(),
                            "outputFields.name 非法");
                }
            }
            return objectMapper.writeValueAsString(outputFields);
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            throw new BaseException(FormErrorCode.LIST_CONFIG_INVALID.getCode(),
                    "outputFields 序列化失败");
        }
    }

    private Long currentTenantId() {
        LoginUser user = LoginUserHolder.get();
        if (user == null || user.getTenantId() == null) {
            // 无租户上下文 fail closed：外部数据源契约按租户隔离，缺失即拒绝
            throw new BaseException(FormErrorCode.EXT_QUERY_NOT_FOUND.getCode(),
                    "租户上下文缺失，无法解析外部数据源契约");
        }
        return user.getTenantId();
    }

    private String queryKeyOf(FormExtQueryEntity entity) {
        return entity == null ? "?" : entity.getQueryKey();
    }
}
