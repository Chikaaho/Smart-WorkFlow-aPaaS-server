package com.sw.ck.form.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.dynamic.ColumnValidation;
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
 *   <li>列名/表名过白名单（{@link ColumnValidation#physicalColumnName(String, FieldType)}）</li>
 *   <li>值一律 PreparedStatement ? 参数化绑定</li>
 *   <li>裸 SQL 手写 WHERE deleted = 0 AND tenant_id = ?（不吃拦截器）</li>
 *   <li>①②③ 同一 {@code @Transactional}，RESTRICT 命中回滚不留半删</li>
 * </ul>
 *
 * <h3>幂等</h3>
 * 记录不存在或已软删时影响行数 = 0，视为成功不抛错。
 */
@Service
public class FormDataDeleteService {

    private static final Logger log = LoggerFactory.getLogger(FormDataDeleteService.class);

    /** 表名校验正则（对齐 DynamicTableManager.generateTableName 的 assert 模式） */
    private static final String TABLE_NAME_PATTERN = "^sw_form(_table)?_[a-z][a-z0-9]{9}$";

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

        // —— Step 3: RESTRICT 反查（删之前先拦） ——
        checkRestrictReferences(formKey, tableName, recordId, tenantId);

        // —— Step 4: CASCADE 软删子表 ——
        cascadeDeleteSubTableRecords(formDef.getId(), tableName, recordId, tenantId);

        // —— Step 5: 软删主记录（I2：WHERE 叠加记录数据范围，服务端权威强制） ——
        StringBuilder deleteWhere = new StringBuilder(
                "\"id\" = ? AND \"deleted\" = 0 AND \"tenant_id\" = ?");
        List<Object> deleteParams = new ArrayList<>(List.of(recordId, tenantId));
        FormDataScopeSupport.appendWhere(deleteWhere, deleteParams,
                FormDataScopeSupport.resolve(loginUser, null, null));
        String deleteSql = "UPDATE \"" + tableName + "\" SET \"deleted\" = 1 WHERE " + deleteWhere;
        int affected;
        try {
            affected = jdbcTemplate.update(deleteSql, deleteParams.toArray());
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
            configRows = jdbcTemplate.queryForList(
                    "SELECT table_name, definition FROM sw_form_config"
                            + " WHERE deleted = 0 AND table_name IS NOT NULL");
        } catch (Exception e) {
            log.warn("Failed to scan sw_form_config for RESTRICT check, skip: {}", e.getMessage());
            return; // 元数据表不可用时放行（不阻塞删除）
        }

        for (Map<String, Object> row : configRows) {
            String refTableName = (String) row.get("table_name");
            if (refTableName == null || refTableName.isBlank()) continue;

            // 防御性表名校验
            if (!refTableName.matches(TABLE_NAME_PATTERN)) {
                log.warn("Skipping config row with invalid table_name pattern: {}", refTableName);
                continue;
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
                // 逻辑名 → 物理列名（ref_{name}_id）
                String colName;
                try {
                    colName = ColumnValidation.physicalColumnName(logicalName, FieldType.REFERENCE);
                    ColumnValidation.validateColumnName(colName);
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid REFERENCE column name '{}' in table '{}', skip", logicalName, refTableName);
                    continue;
                }

                // 构建查询：同表自引用时排除被删记录自身
                StringBuilder checkSql = new StringBuilder();
                checkSql.append("SELECT 1 FROM \"").append(refTableName).append("\"")
                        .append(" WHERE \"").append(colName).append("\" = ?")
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
                    result = jdbcTemplate.queryForList(checkSql.toString(), params.toArray());
                } catch (Exception e) {
                    log.warn("RESTRICT check query failed for table={}, col={}: {}",
                            refTableName, colName, e.getMessage());
                    continue; // 查询失败放行，不阻塞删除
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

            // 表名防御性校验
            try {
                validateTableName(subTableName);
            } catch (BaseException e) {
                log.warn("Invalid sub-table name '{}' in subTableMapping, skip", subTableName);
                continue;
            }

            String sql = "UPDATE \"" + subTableName
                    + "\" SET \"deleted\" = 1 WHERE \"parent_record_id\" = ?"
                    + " AND \"deleted\" = 0 AND \"tenant_id\" = ?";
            int affected;
            try {
                affected = jdbcTemplate.update(sql, recordId, tenantId);
            } catch (Exception e) {
                log.warn("CASCADE soft-delete failed for sub-table '{}': {}", subTableName, e.getMessage());
                continue;
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
            log.warn("Failed to parse sub-table mapping JSON: {}", e.getMessage());
            return Map.of();
        }
    }

    // ==================== 表名校验 ====================

    /**
     * 防御性表名校验（表名来自注册表，发布期已校验，此处为纵深防御）。
     */
    private void validateTableName(String tableName) {
        if (!tableName.matches(TABLE_NAME_PATTERN)) {
            log.error("Table name '{}' does not match expected pattern '{}'", tableName, TABLE_NAME_PATTERN);
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST, "该表单的数据表配置异常，请联系管理员处理");
        }
    }
}
