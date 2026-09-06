package com.sw.ck.form.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.crypto.AesGcmCipher;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.event.FormSubmittedEvent;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.api.port.FlowStartPort;
import com.sw.ck.form.dynamic.ColumnValidation;
import com.sw.ck.form.dynamic.DynamicTableManager;
import com.sw.ck.form.dynamic.FieldType;
import com.sw.ck.form.entity.*;
import com.sw.ck.form.mapper.FormConfigMapper;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.form.mapper.FormTraceMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.api.dict.DictFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 表单提交服务。
 * <p>
 * 完成表单提交的完整链路：
 * <ol>
 *   <li>校验（必填/类型/字典值域/未知字段）</li>
 *   <li>写入动态宽表（主表 + TABLE 子表 + REFERENCE 外键）</li>
 *   <li>写入 {@code sw_form_trace} 溯源记录</li>
 *   <li>发布 {@link FormSubmittedEvent}（经 {@link DomainEventPublisher}，供 workflow 消费）</li>
 * </ol>
 * </p>
 *
 * <h3>红线</h3>
 * <ul>
 *   <li>用户提交值一律 {@link PreparedStatement} 占位符，绝不拼入 SQL</li>
 *   <li>{@code tenant_id} 从 {@link LoginUserHolder} 取并手动写入（MyBatis-Plus 拦截器对动态宽表失效）</li>
 *   <li>动态宽表查询手动带 {@code WHERE tenant_id = ? AND deleted = 0}</li>
 * </ul>
 */
@Service
public class FormSubmitService {

    private static final Logger log = LoggerFactory.getLogger(FormSubmitService.class);

    private final FormDefMapper formDefMapper;
    private final FormTraceMapper formTraceMapper;
    private final DynamicTableManager dynamicTableManager;
    private final FormIdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final DictFacade dictFacade;
    private final DomainEventPublisher eventPublisher;
    private final AesGcmCipher aesCipher;
    private final FormFieldValidator formFieldValidator;
    private final FlowStartPort flowStartPort;

    public FormSubmitService(FormDefMapper formDefMapper,
                             FormTraceMapper formTraceMapper,
                             DynamicTableManager dynamicTableManager,
                             FormIdGenerator idGenerator,
                             ObjectMapper objectMapper,
                             JdbcTemplate jdbcTemplate,
                             DictFacade dictFacade,
                             DomainEventPublisher eventPublisher,
                             Optional<AesGcmCipher> aesCipher,
                             FormFieldValidator formFieldValidator,
                             org.springframework.beans.factory.ObjectProvider<FlowStartPort> flowStartPort) {
        this.formDefMapper = formDefMapper;
        this.formTraceMapper = formTraceMapper;
        this.dynamicTableManager = dynamicTableManager;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.dictFacade = dictFacade;
        this.eventPublisher = eventPublisher;
        this.aesCipher = aesCipher.orElse(null);
        this.formFieldValidator = formFieldValidator;
        this.flowStartPort = flowStartPort.getIfAvailable();
    }

    // ==================== 主入口 ====================

    /**
     * 提交前校验（与 submitForm 共用同一校验实现，只读不落库）。
     * <p>
     * D3：审批命令受理前调用；失败抛业务异常并定位到字段，调用方据此拒绝受理，
     * 不产生命令、不落表单数据、不启动流程。
     * </p>
     */
    public void validateSubmission(String formKey, Map<String, Object> submittedData) {
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null) {
            throw new BaseException(com.sw.ck.common.exception.CommonErrorCode.UNAUTHORIZED, "未登录");
        }
        FormDefEntity formDef = formDefMapper.selectOne(
                Wrappers.lambdaQuery(FormDefEntity.class).eq(FormDefEntity::getFormKey, formKey));
        if (formDef == null) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND, "表单 '" + formKey + "' 不存在");
        }
        if (!FormStatusEnum.PUBLISHED.getCode().equals(formDef.getStatus())) {
            throw new BaseException(FormErrorCode.FORM_NOT_PUBLISHED, "表单 '" + formKey + "' 未发布，不能提交");
        }
        if (!isVisibleToCurrentUser(formDef)) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND, "表单 '" + formKey + "' 不存在");
        }
        Map<String, FormFieldValidator.FieldDef> fieldDefs =
                formFieldValidator.loadAndParseFieldDefs(formDef.getId(), submittedData);
        formFieldValidator.validateFields(fieldDefs, submittedData, dictFacade);
    }

    /**
     * 提交表单数据。
     * <p>
     * 事务边界涵盖：校验 → 动态宽表写入 → 子表写入 → trace 写入，
     * 事件发布在事务内完成，由 {@code @TransactionalEventListener(AFTER_COMMIT)} 消费。
     * </p>
     *
     * @param formKey           表单业务标识
     * @param submittedData     提交数据（字段名 → 值）
     * @param submitIp          提交者 IP（AES 加密存储）
     * @param deviceFingerprint 设备指纹（有则 SHA-256 哈希）
     * @param userAgent         User-Agent 字符串
     * @return 主表记录 UUID（recordId）
     */
    @Transactional(rollbackFor = Exception.class)
    public String submitForm(String formKey,
                             Map<String, Object> submittedData,
                             String submitIp,
                             String deviceFingerprint,
                             String userAgent) {
        return submitForm(formKey, submittedData, submitIp, deviceFingerprint, userAgent, null);
    }

    /**
     * 提交表单数据（带提交幂等键）。
     * <p>
     * {@code idempotencyKey} 非空时：同一租户内已存在同键提交则直接返回既有
     * recordId，不重复落表单数据、不重复受理流程发起（重试/重投安全）。
     * </p>
     */
    @Transactional(rollbackFor = Exception.class)
    public String submitForm(String formKey,
                             Map<String, Object> submittedData,
                             String submitIp,
                             String deviceFingerprint,
                             String userAgent,
                             String idempotencyKey) {
        return submitForm(formKey, submittedData, submitIp, deviceFingerprint, userAgent,
                idempotencyKey, null, null);
    }

    /**
     * 提交表单数据并携带受控的流程发起通道。该通道只影响同事务受理的 FLOW_START，
     * 不改变表单校验、落库与幂等规则。
     */
    @Transactional(rollbackFor = Exception.class)
    public String submitForm(String formKey,
                             Map<String, Object> submittedData,
                             String submitIp,
                             String deviceFingerprint,
                             String userAgent,
                             String idempotencyKey,
                             String dispatchChannel) {
        return submitForm(formKey, submittedData, submitIp, deviceFingerprint, userAgent,
                idempotencyKey, dispatchChannel, null);
    }

    /**
     * 提交表单并携带统一命令解析出的流程绑定快照。
     */
    @Transactional(rollbackFor = Exception.class)
    public String submitForm(String formKey,
                             Map<String, Object> submittedData,
                             String submitIp,
                             String deviceFingerprint,
                             String userAgent,
                             String idempotencyKey,
                             String dispatchChannel,
                             String processDefKey) {
        // ==========================================================
        // Step 1: 获取当前用户
        // ==========================================================
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null) {
            throw new BaseException(com.sw.ck.common.exception.CommonErrorCode.UNAUTHORIZED, "未登录");
        }
        Long tenantId = loginUser.getTenantId();
        Long userId = loginUser.getUserId();

        // 幂等前置检查：同键提交已存在时返回既有 recordId，不产生任何写入
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            FormTraceEntity existing = formTraceMapper.selectOne(Wrappers.lambdaQuery(FormTraceEntity.class)
                    .eq(FormTraceEntity::getTenantId, tenantId)
                    .eq(FormTraceEntity::getSubmitIdempotencyKey, idempotencyKey)
                    .last("LIMIT 1"));
            if (existing != null) {
                log.info("表单提交幂等命中: formKey={}, idempotencyKey={}, recordId={}",
                        formKey, idempotencyKey, existing.getRecordId());
                return existing.getRecordId();
            }
        }

        log.info("Form submit start: formKey={}, userId={}, tenantId={}", formKey, userId, tenantId);

        // ==========================================================
        // Step 2: 加载表单定义 + 校验状态
        // ==========================================================
        LambdaQueryWrapper<FormDefEntity> defQuery = Wrappers.lambdaQuery(FormDefEntity.class)
                .eq(FormDefEntity::getFormKey, formKey);
        FormDefEntity formDef = formDefMapper.selectOne(defQuery);
        if (formDef == null) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND, "表单 '" + formKey + "' 不存在");
        }
        if (!FormStatusEnum.PUBLISHED.getCode().equals(formDef.getStatus())) {
            throw new BaseException(FormErrorCode.FORM_NOT_PUBLISHED, "表单 '" + formKey + "' 未发布，不能提交");
        }
        if (!isVisibleToCurrentUser(formDef)) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND, "表单 '" + formKey + "' 不存在");
        }
        String tableName = formDef.getPhysicalTableName();
        if (tableName == null || tableName.isBlank()) {
            throw new BaseException(FormErrorCode.SUBMIT_FAILED, "表单 '" + formKey + "' 无物理表，无法提交");
        }

        // ==========================================================
        // Step 3: 加载表单配置并解析字段定义
        // ==========================================================
        Map<String, FormFieldValidator.FieldDef> fieldDefs = formFieldValidator.loadAndParseFieldDefs(formDef.getId(), submittedData);

        // ==========================================================
        // Step 4: 校验字段（全部校验通过才落库）
        // ==========================================================
        formFieldValidator.validateFields(fieldDefs, submittedData, dictFacade);

        // ==========================================================
        // Step 5: 构建系统列 + 用户列值
        // ==========================================================
        String recordId = idGenerator.generate();
        Map<String, Object> systemCols = buildSystemColumns(recordId, tenantId, userId);

        // 用户列（按 fieldDefs 顺序构建，排除 TABLE 类型）
        Map<String, String> subTableMapping = parseSubTableMapping(formDef.getSubTableMapping());
        List<String> userColumns = new ArrayList<>();
        List<Object> userValues = new ArrayList<>();
        List<String> tableFieldNames = new ArrayList<>(); // TABLE 字段名列表（需单独处理）

        for (Map.Entry<String, FormFieldValidator.FieldDef> entry : fieldDefs.entrySet()) {
            String fieldName = entry.getKey();
            FormFieldValidator.FieldDef def = entry.getValue();

            if ("TABLE".equals(def.type())) {
                tableFieldNames.add(fieldName);
                continue; // TABLE 不在主表加列
            }

            String colName = ColumnValidation.physicalColumnName(fieldName, FieldType.valueOf(def.type()));
            Object value = submittedData.get(fieldName);

            // BOOL 类型转换：true/false → 1/0
            if ("BOOL".equals(def.type())) {
                value = FormFieldValidator.convertBoolValue(value);
            }

            userColumns.add(colName);
            userValues.add(value);
        }

        // ==========================================================
        // Step 6: INSERT 主表
        // ==========================================================
        List<String> allColumns = new ArrayList<>(systemCols.keySet());
        List<Object> allValues = new ArrayList<>(systemCols.values());
        allColumns.addAll(userColumns);
        allValues.addAll(userValues);

        String insertSql = buildInsertSql(tableName, allColumns);
        jdbcTemplate.update(insertSql, allValues.toArray());
        log.debug("Inserted main record: table={}, recordId={}", tableName, recordId);

        // ==========================================================
        // Step 7: 处理 TABLE 子表
        // ==========================================================
        for (String tableFieldName : tableFieldNames) {
            String subTableName = subTableMapping.get(tableFieldName);
            if (subTableName == null) {
                log.warn("No sub-table mapping for TABLE field '{}', skipping", tableFieldName);
                continue;
            }

            Object rawValue = submittedData.get(tableFieldName);
            if (rawValue == null) {
                continue;
            }

            List<Map<String, Object>> rows;
            if (rawValue instanceof List<?> rawList) {
                rows = new ArrayList<>();
                for (Object item : rawList) {
                    if (item instanceof Map<?, ?> itemMap) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> typedMap = (Map<String, Object>) itemMap;
                        rows.add(typedMap);
                    }
                }
            } else {
                log.warn("TABLE field '{}' value is not a List, skipping", tableFieldName);
                continue;
            }

            // 获取子表字段定义
            FormFieldValidator.FieldDef tableFieldDef = fieldDefs.get(tableFieldName);
            List<String> subUserColumns = new ArrayList<>();
            if (tableFieldDef != null && tableFieldDef.subFields() != null) {
                for (FormFieldValidator.FieldDef subDef : tableFieldDef.subFields()) {
                    subUserColumns.add(ColumnValidation.physicalColumnName(subDef.name(), FieldType.valueOf(subDef.type())));
                }
            }

            for (Map<String, Object> row : rows) {
                String subRecordId = idGenerator.generate();
                Map<String, Object> subSysCols = buildSystemColumns(subRecordId, tenantId, userId);
                subSysCols.put("parent_record_id", recordId);

                List<String> subCols = new ArrayList<>(subSysCols.keySet());
                List<Object> subVals = new ArrayList<>(subSysCols.values());

                for (int i = 0; i < subUserColumns.size(); i++) {
                    subCols.add(subUserColumns.get(i));
                    // BOOL 类型转换
                    String subFieldName = tableFieldDef.subFields().get(i).name();
                    String subFieldType = tableFieldDef.subFields().get(i).type();
                    Object val = row.get(subFieldName);
                    if ("BOOL".equals(subFieldType)) {
                        val = FormFieldValidator.convertBoolValue(val);
                    }
                    subVals.add(val);
                }

                String subInsertSql = buildInsertSql(subTableName, subCols);
                jdbcTemplate.update(subInsertSql, subVals.toArray());
            }
            log.debug("Inserted {} rows into sub-table '{}' for field '{}'", rows.size(), subTableName, tableFieldName);
        }

        // ==========================================================
        // Step 8: 写入 sw_form_trace
        // ==========================================================
        FormTraceEntity trace = new FormTraceEntity();
        trace.setId(idGenerator.generate());
        trace.setFormId(formDef.getId());
        trace.setRecordId(recordId);
        trace.setSubmitUserId(userId);
        trace.setSubmitIp(encryptIp(submitIp));
        trace.setSubmitTime(LocalDateTime.now());
        trace.setDeviceFingerprint(deviceFingerprint);
        trace.setUserAgent(userAgent);
        trace.setTenantId(tenantId);
        trace.setDeleted(0);
        trace.setSubmitIdempotencyKey(idempotencyKey);
        trace.setCreateTime(LocalDateTime.now());
        trace.setCreateBy(userId);
        trace.setUpdateTime(LocalDateTime.now());
        trace.setUpdateBy(userId);
        trace.setVersion(0L);
        formTraceMapper.insert(trace);
        log.debug("Inserted trace record: formId={}, recordId={}", formDef.getId(), recordId);

        // ==========================================================
        // Step 9: 流程发起受理（统一命令边界）
        // BPM 模块在位时于本事务内持久化受理事实（表单落库 ⇒ 发起命令可回查、不丢失）；
        // 仅当无 FlowStartPort（BPM 未装配）时保留历史进程内事件路径兜底。
        // ==========================================================
        String submitterStr = String.valueOf(userId);
        FormSubmittedEvent event = new FormSubmittedEvent(formKey, submittedData, submitterStr, recordId,
                tenantId, dispatchChannel, processDefKey);
        if (flowStartPort != null) {
            Long commandId = flowStartPort.acceptFlowStart(event);
            log.info("Flow start accepted in-tx: formKey={}, recordId={}, commandId={}",
                    formKey, recordId, commandId);
        } else {
            eventPublisher.publish(event);
        }
        log.info("Form submit completed: formKey={}, recordId={}, submitter={}",
                formKey, recordId, submitterStr);

        return recordId;
    }

    private boolean isVisibleToCurrentUser(FormDefEntity formDef) {
        LoginUser current = LoginUserHolder.get();
        if (current == null || current.getUserId() == null) {
            return false;
        }
        String scope = formDef.getVisibilityScope();
        if (scope == null || scope.isBlank()) {
            return true;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode ids = objectMapper.readTree(scope).get("userIds");
            if (ids == null || !ids.isArray()) {
                return false;
            }
            for (com.fasterxml.jackson.databind.JsonNode id : ids) {
                if (current.getUserId().toString().equals(id.asText())) {
                    return true;
                }
            }
            return false;
        } catch (JsonProcessingException ex) {
            log.error("Invalid form visibility scope: formKey={}", formDef.getFormKey(), ex);
            return false;
        }
    }

    // ==================== 校验 ====================

    // ==================== 系统列填充 ====================

    /**
     * 构建动态宽表的系统列键值对（MyBatis-Plus 拦截器对动态宽表失效，需手动填充）。
     * <p>
     * 主表和子表共用此方法。
     * </p>
     *
     * @param recordId 记录 UUID
     * @param tenantId 当前租户 ID
     * @param userId   当前用户 ID
     * @return 有序的列名→值映射
     */
    Map<String, Object> buildSystemColumns(String recordId, Long tenantId, Long userId) {
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("id", recordId);
        cols.put("tenant_id", tenantId);
        cols.put("deleted", 0);
        cols.put("create_time", LocalDateTime.now());
        cols.put("create_by", userId);
        cols.put("update_time", LocalDateTime.now());
        cols.put("update_by", userId);
        cols.put("version", 0L);
        return cols;
    }

    // ==================== 内部工具方法 ====================

    /**
     * 构建 INSERT SQL（PreparedStatement 占位符）。
     */
    private String buildInsertSql(String tableName, List<String> columns) {
        String quotedCols = columns.stream()
                .map(c -> "\"" + c + "\"")
                .collect(Collectors.joining(", "));
        String placeholders = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
        return "INSERT INTO \"" + tableName + "\" (" + quotedCols + ") VALUES (" + placeholders + ")";
    }

    // convertToColumnName() 已删除，改为调用 ColumnValidation.physicalColumnName() 单一出口

    /**
     * 解析子表映射 JSON。
     *
     * @param subTableMappingJson FormDefEntity.subTableMapping 的 JSON 字串
     * @return 字段名 → 子表名映射
     */
    private Map<String, String> parseSubTableMapping(String subTableMappingJson) {
        if (subTableMappingJson == null || subTableMappingJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(subTableMappingJson,
                    new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse sub-table mapping JSON: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * AES 加密 IP 地址；加密失败时回退明文存储。
     */
    private String encryptIp(String ip) {
        if (ip == null || ip.isBlank()) return ip;
        if (aesCipher == null) {
            log.warn("AesGcmCipher not configured, storing IP in plaintext");
            return ip;
        }
        try {
            return aesCipher.encrypt(ip);
        } catch (Exception e) {
            log.warn("IP encryption failed, storing as plaintext: {}", e.getMessage());
            return ip;
        }
    }

}
