package com.sw.ck.form.dynamic;

/**
 * 表单字段类型全集枚举。
 * <p>
 * 每位成员带 {@code enabled} 标记：v1 仅点亮已实现 8 类（enabled=true），
 * 其余占位成员 enabled=false，无列映射、无渲染、无校验分支。
 * </p>
 *
 * <h3>已启用的字段类型（v1）</h3>
 * <ul>
 *   <li>{@link #TEXT} — 文本（短文本）→ VARCHAR(1000)</li>
 *   <li>{@link #RICH_TEXT} — 富文本 → H2:CLOB / PG:TEXT</li>
 *   <li>{@link #NUMBER} — 数字 → NUMERIC(20,6)</li>
 *   <li>{@link #DATE} — 日期 → TIMESTAMP</li>
 *   <li>{@link #BOOL} — 布尔 → SMALLINT</li>
 *   <li>{@link #DICT} — 字典（存 dict_value 字符串）→ VARCHAR(100)</li>
 *   <li>{@link #REFERENCE} — 关联（外键列 ref_{name}_id，存目标表单记录 id）→ VARCHAR(36)</li>
 *   <li>{@link #TABLE} — 表格子表（非列类型，触发 {@code sw_form_table_} 子表创建）</li>
 * </ul>
 *
 * <h3>占位成员（v1 disabled，列映射抛异常）</h3>
 * <ul>
 *   <li>{@link #MULTISELECT} — 多选（多值存储 TODO，本刀不实现）</li>
 *   <li>{@link #ATTACHMENT} — 附件（本刀不实现）</li>
 *   <li>{@link #IMAGE} — 图片（本刀不实现）</li>
 *   <li>{@link #LABEL} — 说明文字（纯展示，本刀不实现）</li>
 *   <li>{@link #EMAIL} — 邮箱（本刀不实现）</li>
 *   <li>{@link #PHONE} — 电话（本刀不实现）</li>
 *   <li>{@link #URL} — 链接（本刀不实现）</li>
 *   <li>{@link #RATE} — 评分（本刀不实现）</li>
 *   <li>{@link #SLIDER} — 滑块（本刀不实现）</li>
 * </ul>
 *
 * <p>RADIO 不单独立类型 → DICT + renderAs=radio。</p>
 *
 * @see VendorDialect#columnType(FieldType)
 * @see ColumnValidation#validFieldTypes()
 */
public enum FieldType {

    // ==================== v1 已实现 ====================
    TEXT(true),
    RICH_TEXT(true),
    NUMBER(true),
    DATE(true),
    BOOL(true),
    DICT(true),
    REFERENCE(true),
    TABLE(true),

    // ==================== v0.0.2 OA 启用 ====================
    /** 多选：值=字符串列表，落库为 JSON 数组字符串 → VARCHAR(1000)。 */
    MULTISELECT(true),
    /** 附件：值=[{storageKey,name}] JSON → CLOB/TEXT；上传/查看/下载走附件端点对象权限。 */
    ATTACHMENT(true),
    /** 图片：值=[{storageKey,name}] JSON → CLOB/TEXT。 */
    IMAGE(true),
    /** 说明文字：非输入字段，不产生列、不参与提交载荷。 */
    LABEL(true),

    // ==================== I2 低代码表单收口启用 ====================
    /** 时间：值 HH:mm:ss → TIME 列。 */
    TIME(true),
    /** 人员选择：值=有效用户 ID（服务端经 UserQueryFacade 校验同租户启用）→ VARCHAR(64)。 */
    USER(true),
    /** 部门选择：值=有效部门 ID（服务端经 DeptQueryFacade 校验同租户正常状态）→ VARCHAR(64)。 */
    DEPT(true),
    /**
     * 公式：definition 携带 expression，仅登记字段+白名单函数；
     * 服务端提交/更新时重算并落 NUMERIC(20,6) 结果列，客户端值一律不消费。
     */
    FORMULA(true),
    /**
     * 受控外部数据源：definition 携带 dsBinding{queryKey,version,valueField,displayField}；
     * 值列存服务端解析后的 {value,display,queryKey,version} JSON → CLOB/TEXT。
     */
    DATASOURCE(true),

    // ==================== 占位（enabled=false） ====================
    EMAIL(false),
    PHONE(false),
    URL(false),
    RATE(false),
    SLIDER(false);

    private final boolean enabled;

    FieldType(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 该字段类型在 v1 是否启用。
     * 禁用类型无列映射、无渲染、无校验分支。
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 该类型是否为非输入/非列类型（TABLE / LABEL / FORMULA 不由用户直接输入落列）。
     * FORMULA 有结果列但值由服务端重算，不消费客户端值。
     */
    public boolean isNonInputType() {
        return this == TABLE || this == LABEL || this == FORMULA;
    }
}
