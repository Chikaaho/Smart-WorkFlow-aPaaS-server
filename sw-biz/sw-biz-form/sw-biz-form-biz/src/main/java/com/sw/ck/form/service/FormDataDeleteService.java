package com.sw.ck.form.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.dynamic.DynamicTableSql;
import com.sw.ck.form.dynamic.FieldType;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 表单数据软删除服务。
 *
 * <p>对已发布表单的动态宽表执行单条记录的软删除，含：
 * <ol>
 *   <li>RESTRICT 跨表反查 — 扫描全部已发布表单 definition，若其他表单的
 *       REFERENCE 字段指向本表单且有有效引用，则拒绝删除</li>
 *   <li>CASCADE 子表连带 — 本表单 TABLE 字段对应的子表记录同步软删</li>
 *   <li>主记录软删 — {@code SET deleted = 1 WHERE id = ? AND deleted = 0 AND tenant_id = ?}</li>
 * </ol>
 * </p>
 *
 * <h3>红线</h3>
 * <ul>
 *   <li>列名/表名过白名单（{@link DynamicTableSql#requireColumn(String, FieldType)}）</li>
 *   <li>值一律 PreparedStatement ? 参数化绑定</li>
 *   <li>裸 SQL 手写 WHERE deleted = 0 AND tenant_id = ?（不吃拦截器），并由
 *       {@link DynamicTableSql} 受控入口机械校验</li>
 *   <li>①②③ 同一 {@code @Transactional}，RESTRICT 命中回滚不留半删</li>
 *   <li><b>fail closed</b>：元数据扫描、引用检查、级联删除任一失败即中止并回滚，
 *       绝不“记录告警后继续删除”</li>
 *   <li><b>REFERENCE 串行化</b>：删除前对本行加锁（与引用写入侧同一锁身份），
 *       消除“检查后、删除前新增引用”的并发窗口</li>
 * </ul>
 *
 * <h3>幂等</h3>
 * 记录不存在或已软删时影响行数 = 0，视为成功不抛错。
 */
@Service
public class FormDataDeleteService {

    private static final Logger log = LoggerFactory.getLogger(FormDataDeleteService.class);

    /** 固定元数据表（由 Flyway 建，非动态宽表）：RESTRICT 反查需跨租户可见 */
    static final String FORM_CONFIG_TABLE = "sw_form_config";

    private final FormDefService formDefService;
    private final FormDefMapper formDefMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public FormDataDeleteService(FormDefService formDefService,
                                  FormDefMapper formDefMapper,
                                  JdbcTemplate jdbcTemplate,
                                  ObjectMapper objectMapper) {
        this.formDefService = formDefService;
        this.formDefMapper = formDefMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ==================== 主入口 ====================

    /**
     * 软删除一条表单记录（含 RESTRICT 反查 + CASCADE 子表连带）。
     *
     * @param formKey  表单业务标识
     * @param recordId 主表记录 UUID
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteRecord(String formKey, String recordId) {
        // —— Step 1: 获取当前用户 ——
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null) {
            throw new BaseException(com.sw.ck.common.exception.CommonErrorCode.UNAUTHORIZED, "未登录");
        }
        Long tenantId = loginUser.getTenantId();

        // —— Step 2: 解析 formKey → FormDefDTO ——
        FormDefDTO formDef = formDefService.getFormDefByKey(formKey);
        if (formDef == null) {
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST, "表单 '" + formKey + "' 不存在");
        }
        if (!"PUBLISHED".equals(formDef.getStatus())) {
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST, "表单 '" + formKey + "' 未发布，不能操作");
        }
        String tableName = formDef.getPhysicalTableName();
        if (tableName == null || tableName.isBlank()) {
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST,
                    "该表单尚未完成数据表初始化，请联系管理员处理");
        }
        validateTableName(tableName);

        // —— Step 3: 锁定目标父行（REFERENCE 串行化锚点） ——
        // 与引用写入侧（FormFieldEnrichmentService / FormImportExportService）共用同一锁身份
        // （租户 + 物理表 + 记录 id），使“检查引用 → 软删”与“校验目标 → 写入引用”互斥：
        //   引用先提交 → 本处的 RESTRICT 反查必然可见该引用；
        //   删除先提交 → 写侧加锁查询已看不到存活父行，引用写入被拒绝。
        boolean liveRow = DynamicTableSql.tryLockLiveRow(jdbcTemplate, tableName, recordId, tenantId);
        if (!liveRow) {
            log.debug("目标记录不存在或已软删，按幂等继续（无锁可持）: table={}, recordId={}", tableName, recordId);
        }

        // —— Step 4: RESTRICT 反查（删之前先拦；失败即 fail closed） ——
        checkRestrictReferences(formKey, tableName, recordId, tenantId);

        // —— Step 5: CASCADE 软删子表（失败即 fail closed） ——
        cascadeDeleteSubTableRecords(formDef.getId(), tableName, recordId, tenantId);

        // —— Step 6: 软删主记录（I2：WHERE 叠加记录数据范围，服务端权威强制） ——
        StringBuilder deleteWhere = new StringBuilder(
                "\"id\" = ? AND \"deleted\" = 0 AND \"tenant_id\" = ?");
        List<Object> deleteParams = new ArrayList<>(List.of(recordId, tenantId));
        FormDataScopeSupport.appendWhere(deleteWhere, deleteParams,
                FormDataScopeSupport.resolve(loginUser, null, null));
        String deleteSql = "UPDATE " + DynamicTableSql.quote(tableName) + " SET \"deleted\" = 1 WHERE " + deleteWhere;
        int affected;
        try {
            affected = DynamicTableSql.update(jdbcTemplate, tableName, deleteSql, deleteParams.toArray());
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            log.error("Soft-delete failed: table={}, recordId={}", tableName, recordId, e);
            throw new BaseException(FormErrorCode.DELETE_RECORD_NOT_EXIST, "删除记录时系统未能完成，请稍后重试");
        }

        if (affected == 0) {
            // 幂等：记录不存在或已软删 → 视为成功
            log.debug("Soft-delete affected 0 rows (already deleted or not found): table={}, recordId={}",
                    tableName, recordId);
        } else {
            log.info("Soft-deleted record: table={}, recordId={}, affected={}", tableName, recordId, affected);
        }
    }

    // ==================== RESTRICT 反查 ====================

    /**
     * 扫描全部已发布表单 definition，找出 REFERENCE 字段指向本表单的引用方，
     * 并检查是否存在有效（deleted=0、同租户）引用记录。
     * <p>
     * 任意引用方命中有效引用 → 抛 {@link FormErrorCode#DELETE_RESTRICT_REFERENCED}，整事务回滚。
     * </p>
     *
     * @param formKey   被删记录所属表单的 formKey
     * @param tableName 被删记录所属表单的物理表名
     * @param recordId  被删记录 UUID
     * @param tenantId  当前租户 ID
     */
    private void checkRestrictReferences(String formKey, String tableName, String recordId, Long tenantId) {
        // 用裸 JDBC 扫 sw_form_config，穿透租户拦截器（需要看到所有租户的表单定义）
        List<Map<String, Object>> configRows;
        try {
            // 注：sw_form_config 为固定元数据表（由 Flyway 建，非动态宽表），
            // 列名在各数据库中的实际大小写取决于建表 DDL（H2 无引号=大写，PG 无引号=小写）。
            // 此处不加引号交由驱动按数据库默认折叠，以保证跨 H2/PG 兼容。
            configRows = DynamicTableSql.query(jdbcTemplate, FORM_CONFIG_TABLE,
                    "SELECT table_name, definition FROM sw_form_config"
                            + " WHERE deleted = 0 AND table_name IS NOT NULL");
        } catch (Exception e) {
            // fail closed：元数据不可用时无法判定引用，必须中止而不是放行删除
            log.error("Failed to scan sw_form_config for RESTRICT check: {}", e.getMessage(), e);
            throw new BaseException(FormErrorCode.DYNAMIC_TABLE_METADATA_UNAVAILABLE,
                    "引用检查所需的表单元数据当前不可用，已中止删除，请稍后重试");
        }

        for (Map<String, Object> row : configRows) {
            String refTableName = (String) row.get("table_name");
            if (refTableName == null || refTableName.isBlank()) continue;

            // 防御性表名校验：非法元数据不得进入 SQL 构造
            if (!DynamicTableSql.isValidTableName(refTableName)) {
                log.error("Refusing to run RESTRICT check against invalid table_name: {}", refTableName);
                throw new BaseException(FormErrorCode.DYNAMIC_TABLE_METADATA_UNAVAILABLE,
                        "表单元数据存在非法的数据表标识，已中止删除，请联系管理员处理");
            }

            Object defObj = row.get("definition");
            if (defObj == null) continue;
            String definitionJson = defObj.toString();
            if (definitionJson.isBlank() || "{}".equals(definitionJson)) {
                continue;
            }

            // 解析 definition → 找出 targetFormId == formKey 的 REFERENCE 字段
            List<String> refColumnNames = parseReferenceColumns(definitionJson, formKey);
            if (refColumnNames.isEmpty()) continue;

            // 对每个 REFERENCE 列查是否存在有效引用
            for (String logicalName : refColumnNames) {
                // 逻辑名 → 物理列名（ref_{name}_id）：映射 + 白名单校验
                String colName;
                try {
                    colName = DynamicTableSql.requireColumn(logicalName, FieldType.REFERENCE);
                } catch (BaseException e) {
                    log.error("Invalid REFERENCE column name '{}' in table '{}'", logicalName, refTableName);
                    throw new BaseException(FormErrorCode.DYNAMIC_TABLE_METADATA_UNAVAILABLE,
                            "表单元数据存在非法的关联字段标识，已中止删除，请联系管理员处理");
                }

                // 构建查询：同表自引用时排除被删记录自身
                StringBuilder checkSql = new StringBuilder();
                checkSql.append("SELECT 1 FROM ").append(DynamicTableSql.quote(refTableName))
                        .append(" WHERE ").append(DynamicTableSql.quote(colName)).append(" = ?")
                        .append(" AND \"deleted\" = 0 AND \"tenant_id\" = ?");
                List<Object> params = new ArrayList<>();
                params.add(recordId);
                params.add(tenantId);

                if (refTableName.equals(tableName)) {
                    // 同表自引用：排除被删记录自身（防止记录自引用误拦）
                    checkSql.append(" AND \"id\" != ?");
                    params.add(recordId);
                }
                checkSql.append(" LIMIT 1");

                List<Map<String, Object>> result;
                try {
                    result = DynamicTableSql.query(jdbcTemplate, refTableName, checkSql.toString(), params.toArray());
                } catch (BaseException e) {
                    throw e;
                } catch (Exception e) {
                    // fail closed：引用检查失败不得放行删除
                    log.error("RESTRICT check query failed for table={}, col={}: {}",
                            refTableName, colName, e.getMessage(), e);
                    throw new BaseException(FormErrorCode.DYNAMIC_TABLE_METADATA_UNAVAILABLE,
                            "引用检查未能完成，已中止删除，请稍后重试");
                }

                if (!result.isEmpty()) {
                    // P61：对用户只给业务可识别信息（引用方表单名称 + 关联字段名）；
                    // 物理表名与物理列名只进日志，不把库表结构暴露给业务用户。
                    String refFormName = resolveFormNameByPhysicalTable(refTableName);
                    log.warn("RESTRICT 命中: targetFormKey={}, refTable={}, refColumn={}, refFormName={}",
                            formKey, refTableName, colName, refFormName);
                    throw new BaseException(FormErrorCode.DELETE_RESTRICT_REFERENCED,
                            "该记录已被表单「" + refFormName + "」的关联字段「" + logicalName
                                    + "」引用，不能删除");
                }
            }
        }
    }

    /**
     * 由物理表名解析引用方表单的展示名称。
     * <p>查不到时退化为中性表述，绝不把物理表名回显给用户。</p>
     */
    private String resolveFormNameByPhysicalTable(String physicalTableName) {
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            FormDefEntity entity = formDefMapper.selectOne(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<FormDefEntity>lambdaQuery()
                            .eq(FormDefEntity::getPhysicalTableName, physicalTableName)
                            .last("LIMIT 1"));
            if (entity != null && entity.getName() != null && !entity.getName().isBlank()) {
                return entity.getName();
            }
        } catch (Exception e) {
            log.warn("解析引用方表单名称失败: physicalTable={}, error={}", physicalTableName, e.getMessage());
        }
        return "其他表单";
    }

    /**
     * 从 definition JSON 中解析出 targetFormId 匹配指定 formKey 的 REFERENCE 字段名列表。
     * <p>
     * 兼容两种 definition 格式：
     * <ol>
     *   <li>主表单格式：{@code {"fields": [{"name":"x","type":"REFERENCE","targetFormId":"key"},...]}}</li>
     *   <li>子表格式（FieldSpec 序列化）：{@code [{"fieldName":"x","fieldType":"REFERENCE","refTargetFormId":"key"},...]}</li>
     * </ol>
     * </p>
     *
     * @param definitionJson definition JSON 字串
     * @param targetFormKey  要匹配的目标 formKey
     * @return REFERENCE 字段的逻辑名列表（用于构建 ref_{name}_id 列名）
     */
    List<String> parseReferenceColumns(String definitionJson, String targetFormKey) {
        List<String> names = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(definitionJson);

            // 格式 1：{"fields": [...]}
            JsonNode fieldsArray = root.get("fields");
            if (fieldsArray != null && fieldsArray.isArray()) {
                for (JsonNode fieldNode : fieldsArray) {
                    collectReferenceFieldName(fieldNode, targetFormKey,
                            "type", "REFERENCE",
                            "targetFormId", "name", names);
                }
                return names;
            }

            // 格式 2：[{...}] 顶层数组（子表 definition）
            if (root.isArray()) {
                for (JsonNode fieldNode : root) {
                    collectReferenceFieldName(fieldNode, targetFormKey,
                            "fieldType", "REFERENCE",
                            "refTargetFormId", "fieldName", names);
                }
                return names;
            }
        } catch (JsonProcessingException e) {
            log.debug("Failed to parse definition JSON for RESTRICT scan: {}", e.getMessage());
        }
        return names;
    }

    /**
     * 从单个字段 JSON 节点中提取 REFERENCE 字段名（若类型匹配且 targetFormId 命中）。
     */
    private void collectReferenceFieldName(JsonNode fieldNode, String targetFormKey,
                                           String typeKey, String typeValue,
                                           String targetKey, String nameKey,
                                           List<String> sink) {
        if (fieldNode == null || !fieldNode.isObject()) return;

        JsonNode typeNode = fieldNode.get(typeKey);
        if (typeNode == null || !typeValue.equals(typeNode.asText())) return;

        JsonNode targetNode = fieldNode.get(targetKey);
        if (targetNode == null || !targetFormKey.equals(targetNode.asText())) return;

        JsonNode nameNode = fieldNode.get(nameKey);
        if (nameNode != null && !nameNode.asText().isBlank()) {
            sink.add(nameNode.asText());
        }
    }

    // ==================== CASCADE 子表软删 ====================

    /**
     * 软删除本表单所有 TABLE 子表中关联到指定主记录的行。
     * <p>
     * 子表定位：从 {@code FormDefEntity.subTableMapping} JSON 解析字段名→子表名映射。
     * </p>
     *
     * @param formId    表单 UUID
     * @param tableName 主表物理名
     * @param recordId  主记录 UUID
     * @param tenantId  当前租户 ID
     */
    private void cascadeDeleteSubTableRecords(String formId, String tableName,
                                               String recordId, Long tenantId) {
        FormDefEntity formDefEntity = formDefMapper.selectById(formId);
        if (formDefEntity == null) return;

        Map<String, String> subTableMapping = parseSubTableMapping(formDefEntity.getSubTableMapping());
        if (subTableMapping.isEmpty()) return;

        for (Map.Entry<String, String> entry : subTableMapping.entrySet()) {
            String subTableName = entry.getValue();
            if (subTableName == null || subTableName.isBlank()) continue;

            // 表名防御性校验：非法元数据不得进入 SQL 构造
            try {
                validateTableName(subTableName);
            } catch (BaseException e) {
                log.error("Invalid sub-table name '{}' in subTableMapping", subTableName);
                throw new BaseException(FormErrorCode.DYNAMIC_TABLE_METADATA_UNAVAILABLE,
                        "该表单的子表配置异常，已中止删除，请联系管理员处理");
            }

            String sql = "UPDATE " + DynamicTableSql.quote(subTableName)
                    + " SET \"deleted\" = 1 WHERE \"parent_record_id\" = ?"
                    + " AND \"deleted\" = 0 AND \"tenant_id\" = ?";
            int affected;
            try {
                affected = DynamicTableSql.update(jdbcTemplate, subTableName, sql, recordId, tenantId);
            } catch (BaseException e) {
                throw e;
            } catch (Exception e) {
                // fail closed：级联软删失败不得静默跳过，否则主记录删除会留下存活子行
                log.error("CASCADE soft-delete failed for sub-table '{}': {}", subTableName, e.getMessage(), e);
                throw new BaseException(FormErrorCode.DYNAMIC_TABLE_METADATA_UNAVAILABLE,
                        "级联删除子表数据时系统未能完成，请稍后重试");
            }
            if (affected > 0) {
                log.info("CASCADE soft-deleted {} rows in sub-table '{}' for parent record {}",
                        affected, subTableName, recordId);
            }
        }
    }

    /**
     * 解析子表映射 JSON。
     *
     * @param subTableMappingJson FormDefEntity.subTableMapping 的 JSON 字串
     * @return 字段名 → 子表物理名映射
     */
    private Map<String, String> parseSubTableMapping(String subTableMappingJson) {
        if (subTableMappingJson == null || subTableMappingJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(subTableMappingJson,
                    new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            // fail closed：子表映射不可解析时级联范围不可知，必须中止而不是“无子表”继续删
            log.error("Failed to parse sub-table mapping JSON: {}", e.getMessage(), e);
            throw new BaseException(FormErrorCode.DYNAMIC_TABLE_METADATA_UNAVAILABLE,
                    "该表单的子表配置异常，已中止删除，请联系管理员处理");
        }
    }

    // ==================== 表名校验 ====================

    /**
     * 防御性表名校验（表名来自注册表，发布期已校验，此处为纵深防御）。
     * <p>与 {@link DynamicTableSql} 共用同一正则常量；受控入口在执行前会再校验一次。</p>
     */
    private void validateTableName(String tableName) {
        if (!DynamicTableSql.isValidTableName(tableName)) {
            log.error("Table name '{}' does not match expected pattern '{}'",
                    tableName, DynamicTableSql.TABLE_NAME_PATTERN);
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST, "该表单的数据表配置异常，请联系管理员处理");
        }
    }
}
