package com.sw.ck.form.dynamic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 动态宽表运行时 DDL 管理器。
 * <p>
 * 负责按表单定义在运行时建表/加列，不归 Flyway 管理（system.md §6.2 唯一例外）。
 * 仅依赖 {@link JdbcTemplate}，不引入 Hibernate DDL 或 JPA。
 * </p>
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li>生成符合规则的物理表名（{@code sw_form_}/{@code sw_form_table_} + nanoId）</li>
 *   <li>列名白名单校验（防 SQL 注入 + 防系统列冲突）</li>
 *   <li>字段类型 → 方言列类型映射</li>
 *   <li>系统固定列自动注入（id / tenant_id / deleted / create_time / create_by / update_time / update_by / version）</li>
 *   <li>关系类字段处理（REFERENCE → {@code ref_{field}_id} 列；TABLE → 创建 {@code sw_form_table_} 子表）</li>
 *   <li>增量加列</li>
 * </ul>
 *
 * <h3>红线</h3>
 * <ul>
 *   <li>❌ 用户输入的表名/字段名绝不直接拼入 DDL（均过 {@link ColumnValidation#validateColumnName} 与
 *       {@link DynamicTableSql#requireTableName}）</li>
 *   <li>❌ DDL 不走 Flyway</li>
 *   <li>❌ 不实现表单删除逻辑（后续步骤实现）</li>
 * </ul>
 */
@Component
public class DynamicTableManager {

    private static final Logger log = LoggerFactory.getLogger(DynamicTableManager.class);

    // ==================== 常量 ====================

    /**
     * 系统固定列定义（对齐 FormBaseEntity 语义）。
     * <p>
     * id 使用 VARCHAR(36)（UUID），create_by/update_by 保持 BIGINT（指向 sys_user 雪花主键）。
     * </p>
     */
    static final List<SystemColumnDef> SYSTEM_COLUMNS = List.of(
            new SystemColumnDef("id", "VARCHAR(36)", "NOT NULL"),
            new SystemColumnDef("tenant_id", "BIGINT", "NOT NULL DEFAULT 0"),
            new SystemColumnDef("deleted", "SMALLINT", "NOT NULL DEFAULT 0"),
            new SystemColumnDef("create_time", "TIMESTAMP", "NOT NULL DEFAULT CURRENT_TIMESTAMP"),
            new SystemColumnDef("create_by", "BIGINT", ""),
            new SystemColumnDef("update_time", "TIMESTAMP", "NOT NULL DEFAULT CURRENT_TIMESTAMP"),
            new SystemColumnDef("update_by", "BIGINT", ""),
            new SystemColumnDef("version", "BIGINT", "NOT NULL DEFAULT 0")
    );

    // 列名校验常量与方法均收敛至 ColumnValidation（单一常量源），
    // 本类仅保留 DDL 构建所需的 SYSTEM_COLUMNS（含类型与约束）。
    // validateColumnName() 委托给 ColumnValidation.validateColumnName()。

    // ==================== 实例状态 ====================

    private final JdbcTemplate jdbcTemplate;
    private final VendorDialect dialect;

    // ==================== 构造 ====================

    /**
     * @param jdbcTemplate JDBC 模板（依赖其 DataSource 做方言检测）
     */
    public DynamicTableManager(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        Objects.requireNonNull(jdbcTemplate.getDataSource(),
                "JdbcTemplate must have a non-null DataSource for dialect detection");
        this.dialect = VendorDialect.detect(jdbcTemplate.getDataSource());
        log.info("DynamicTableManager initialized with dialect: {}", dialect);
    }

    // ==================== 表名生成 ====================

    /**
     * 生成一个符合规则的动态宽表物理表名。
     *
     * @param isSubTable true → {@code sw_form_table_ + nanoId}，false → {@code sw_form_ + nanoId}
     * @return 完整表名，如 {@code sw_form_abcdef1234}
     */
    public String generateTableName(boolean isSubTable) {
        String prefix = isSubTable ? "sw_form_table_" : "sw_form_";
        String nanoId = NanoIdGenerator.generate();
        String name = prefix + nanoId;
        // 真实校验（非 assert）：生成结果必须符合受控入口同一正则，否则拒绝建表
        return DynamicTableSql.requireTableName(name);
    }

    // ==================== 列名校验 ====================

    /**
     * 校验物理列名是否合法。
     * <p>
     * 此为 SQL 注入红线：任何用户输入的字段名在进入 DDL 前必须通过此方法。
     * </p>
     *
     * @param name 待校验的列名
     * @throws IllegalArgumentException 列名不符合规则时抛出
     */
    public void validateColumnName(String name) {
        ColumnValidation.validateColumnName(name);
    }

    // ==================== 建表 ====================

    /**
     * 根据规格创建动态宽表（不带子表名收集）。
     * <p>
     * 幂等策略：若表已存在则跳过并 {@code warn} 日志，不抛异常。
     * </p>
     *
     * @param spec 表规格描述
     * @return 生成的物理表名
     */
    public String createFormTable(FormTableSpec spec) {
        return createFormTable(spec, null);
    }

    /**
     * 根据规格创建动态宽表（带子表名收集）。
     * <p>
     * 幂等策略：若表已存在则跳过并 {@code warn} 日志，不抛异常。
     * 通过 {@code subTableNameSink} 收集 TABLE 类型字段名 → 子表物理名的映射，
     * 供 {@code FormDefServiceImpl.publish()} 持久化，以便提交时定位子表。
     * </p>
     *
     * @param spec              表规格描述
     * @param subTableNameSink  非 null 时，将 TABLE 字段名 → 子表名写入此 map
     * @return 生成的物理表名
     */
    public String createFormTable(FormTableSpec spec, Map<String, String> subTableNameSink) {
        String tableName = generateTableName(spec.isSubTable());

        // —— 幂等检查 ——
        if (tableExists(tableName)) {
            log.warn("Table already exists, skipping creation: {}", tableName);
            return tableName;
        }

        // —— 分离 TABLE / LABEL 类型字段（TABLE 需创建子表、LABEL 非输入不产列，均不在主表加列） ——
        List<FieldSpec> regularFields = new ArrayList<>();
        List<FieldSpec> tableFields = new ArrayList<>();
        for (FieldSpec field : spec.getFields()) {
            if (field.getFieldType() == FieldType.TABLE) {
                tableFields.add(field);
            } else if (field.getFieldType() == FieldType.LABEL) {
                // 说明文字：纯展示，无列、无子表
            } else {
                regularFields.add(field);
            }
        }

        // —— 构建 DDL ——
        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE ").append(dialect.wrapIdentifier(tableName)).append(" (\n");

        // 系统列
        for (int i = 0; i < SYSTEM_COLUMNS.size(); i++) {
            SystemColumnDef col = SYSTEM_COLUMNS.get(i);
            ddl.append("  ").append(dialect.wrapIdentifier(col.name()))
                    .append(" ").append(col.type())
                    .append(" ").append(col.constraints());
            ddl.append(",\n");
        }

        // 子表专用：parent_record_id（VARCHAR(36) 匹配父表 UUID 主键）
        if (spec.isSubTable()) {
            ddl.append("  ").append(dialect.wrapIdentifier("parent_record_id"))
                    .append(" VARCHAR(36) NOT NULL,\n");
        }

        // 用户列
        for (FieldSpec field : regularFields) {
            ddl.append("  ").append(buildColumnDef(field)).append(",\n");
        }

        // 主键
        ddl.append("  PRIMARY KEY (").append(dialect.wrapIdentifier("id")).append(")\n");
        ddl.append(")");

        // —— 执行 DDL ——
        String sql = ddl.toString();
        log.info("Executing DDL: {}", sql);
        DynamicTableSql.ddl(jdbcTemplate, tableName, sql);
        log.info("Created table: {}", tableName);

        // —— 处理 TABLE 字段：创建子表 ——
        for (FieldSpec tableField : tableFields) {
            List<FieldSpec> subFields = tableField.getSubFields();
            if (subFields == null || subFields.isEmpty()) {
                log.warn("TABLE field '{}' has no sub-fields, skipping sub-table creation", tableField.getFieldName());
                continue;
            }
            // 递归创建子表（subTable = true）
            FormTableSpec subSpec = new FormTableSpec(true, subFields);
            String subTableName = createFormTable(subSpec, subTableNameSink);
            if (subTableNameSink != null) {
                subTableNameSink.put(tableField.getFieldName(), subTableName);
            }
            log.info("Created sub-table '{}' for TABLE field '{}'", subTableName, tableField.getFieldName());
        }

        return tableName;
    }

    // ==================== 加列 ====================

    /**
     * 向已有动态宽表增加一列（发布时增量加字段）。
     * <p>
     * 幂等策略：若列已存在则跳过。
     * </p>
     *
     * @param tableName 目标表名
     * @param field     字段规格（不允许 TABLE 类型）
     */
    public void addColumn(String tableName, FieldSpec field) {
        if (field.getFieldType() == FieldType.TABLE) {
            throw new IllegalArgumentException("addColumn does not support TABLE type; use createFormTable instead");
        }
        // 入参表名/列名一律在 SQL 构造边界校验（不再信任调用方）
        DynamicTableSql.requireTableName(tableName);
        String colName = field.getPhysicalColumnName();
        DynamicTableSql.validatePhysicalColumn(colName);

        // —— 幂等检查 ——
        if (columnExists(tableName, colName)) {
            log.warn("Column already exists, skipping: {}.{}", tableName, colName);
            return;
        }

        // —— 执行 ALTER ——
        String sql = "ALTER TABLE " + dialect.wrapIdentifier(tableName)
                + " ADD COLUMN " + buildColumnDef(field);
        log.info("Executing DDL: {}", sql);
        DynamicTableSql.ddl(jdbcTemplate, tableName, sql);
        log.info("Added column {}.{}", tableName, colName);
    }

    // ==================== 内部方法 ====================

    /**
     * 构建单列定义 SQL 段（不含前导空格和逗号）。
     * <p>
     * 对 REFERENCE 类型自动使用 {@code ref_{fieldName}_id} 作为列名。
     * </p>
     */
    String buildColumnDef(FieldSpec field) {
        String colName = field.getPhysicalColumnName();
        String colType;
        if (field.getFieldType() == FieldType.DICT && field.getDictType() != null) {
            // DICT 类型存字典值字符串
            colType = dialect.columnType(field.getFieldType());
        } else {
            colType = dialect.columnType(field.getFieldType());
        }
        return dialect.wrapIdentifier(colName) + " " + colType;
    }

    /** 表是否存在（查询 information_schema） */
    boolean tableExists(String tableName) {
        String sql = "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, tableName);
        return count != null && count > 0;
    }

    /** 列是否存在（查询 information_schema） */
    boolean columnExists(String tableName, String columnName) {
        String sql = "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_name = ? AND column_name = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, tableName, columnName);
        return count != null && count > 0;
    }

    // ==================== 内部类型 ====================

    /** 系统固定列定义 */
    public record SystemColumnDef(String name, String type, String constraints) {
        public SystemColumnDef {
            java.util.Objects.requireNonNull(name, "name must not be null");
            java.util.Objects.requireNonNull(type, "type must not be null");
            java.util.Objects.requireNonNull(constraints, "constraints must not be null");
        }
    }

    // ==================== 查询支持 ====================

    /**
     * 获取当前检测到的方言（供测试/日志使用）。
     */
    public VendorDialect getDialect() {
        return dialect;
    }
}
