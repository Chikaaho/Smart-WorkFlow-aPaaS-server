package com.sw.ck.form.dynamic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 单字段规格描述。
 * <p>
 * 描述一个表单字段应映射为何种列（或子表），作为 {@link FormTableSpec} 的组成部分。
 * 使用静态工厂方法构建实例，避免构造器参数歧义。
 * </p>
 *
 * <pre>{@code
 * FieldSpec.text("full_name")
 * FieldSpec.dict("gender", "sys_user_sex")
 * FieldSpec.ref("department", "it_application")
 * FieldSpec.table("items", List.of(FieldSpec.text("item_name"), FieldSpec.number("qty")))
 * }</pre>
 */
public class FieldSpec {

    /** 字段逻辑名（过白名单后成为列名；REFERENCE 类型生成 ref_{name}_id） */
    private final String fieldName;

    /** 字段类型 */
    private final FieldType fieldType;

    /** 字典类型编码（仅 DICT 类型有用） */
    private final String dictType;

    /** 关联目标表单标识（仅 REFERENCE 类型有用，供删除路径 RESTRICT 校验用） */
    private final String refTargetFormId;

    /** 表格子字段列表（仅 TABLE 类型有用） */
    private final List<FieldSpec> subFields;

    // ============ 构造 ============

    private FieldSpec(String fieldName, FieldType fieldType, String dictType,
                      String refTargetFormId, List<FieldSpec> subFields) {
        this.fieldName = Objects.requireNonNull(fieldName, "fieldName must not be null");
        this.fieldType = Objects.requireNonNull(fieldType, "fieldType must not be null");
        this.dictType = dictType;
        this.refTargetFormId = refTargetFormId;
        this.subFields = subFields != null
                ? Collections.unmodifiableList(new ArrayList<>(subFields))
                : null;
    }

    // ============ 静态工厂 ============

    public static FieldSpec text(String name) {
        return new FieldSpec(name, FieldType.TEXT, null, null, null);
    }

    public static FieldSpec richText(String name) {
        return new FieldSpec(name, FieldType.RICH_TEXT, null, null, null);
    }

    public static FieldSpec number(String name) {
        return new FieldSpec(name, FieldType.NUMBER, null, null, null);
    }

    public static FieldSpec date(String name) {
        return new FieldSpec(name, FieldType.DATE, null, null, null);
    }

    public static FieldSpec bool(String name) {
        return new FieldSpec(name, FieldType.BOOL, null, null, null);
    }

    public static FieldSpec dict(String name, String dictType) {
        return new FieldSpec(name, FieldType.DICT,
                Objects.requireNonNull(dictType, "dictType must not be null for DICT field"),
                null, null);
    }

    /** REFERENCE：列名自动生成为 {@code ref_{name}_id} */
    public static FieldSpec ref(String name, String targetFormId) {
        return new FieldSpec(name, FieldType.REFERENCE, null,
                Objects.requireNonNull(targetFormId, "targetFormId must not be null for REFERENCE field"),
                null);
    }

    /** TABLE：在当前表不生成列，改为创建 {@code sw_form_table_} 子表 */
    public static FieldSpec table(String name, List<FieldSpec> subFields) {
        return new FieldSpec(name, FieldType.TABLE, null, null,
                Objects.requireNonNull(subFields, "subFields must not be null for TABLE field"));
    }

    /** 多选：字符串列表值，落库 JSON 数组字符串（VARCHAR(1000)） */
    public static FieldSpec multiselect(String name) {
        return new FieldSpec(name, FieldType.MULTISELECT, null, null, null);
    }

    /** 附件：[{storageKey,name}] JSON（CLOB/TEXT） */
    public static FieldSpec attachment(String name) {
        return new FieldSpec(name, FieldType.ATTACHMENT, null, null, null);
    }

    /** 图片：[{storageKey,name}] JSON（CLOB/TEXT） */
    public static FieldSpec image(String name) {
        return new FieldSpec(name, FieldType.IMAGE, null, null, null);
    }

    /** 说明文字：非输入字段，不产生列（DDL/提交路径均跳过） */
    public static FieldSpec label(String name) {
        return new FieldSpec(name, FieldType.LABEL, null, null, null);
    }

    /** 时间：HH:mm:ss → TIME 列 */
    public static FieldSpec time(String name) {
        return new FieldSpec(name, FieldType.TIME, null, null, null);
    }

    /** 人员选择：值=有效用户 ID → VARCHAR(64) */
    public static FieldSpec user(String name) {
        return new FieldSpec(name, FieldType.USER, null, null, null);
    }

    /** 部门选择：值=有效部门 ID → VARCHAR(64) */
    public static FieldSpec dept(String name) {
        return new FieldSpec(name, FieldType.DEPT, null, null, null);
    }

    /** 公式：服务端重算，落 NUMERIC(20,6) 结果列；expression 在 definition 冻结 */
    public static FieldSpec formula(String name) {
        return new FieldSpec(name, FieldType.FORMULA, null, null, null);
    }

    /** 受控外部数据源：dsBinding 在 definition 冻结，值列存服务端解析摘要 JSON */
    public static FieldSpec datasource(String name) {
        return new FieldSpec(name, FieldType.DATASOURCE, null, null, null);
    }

    // ============ 查询 ============

    public String getFieldName() {
        return fieldName;
    }

    public FieldType getFieldType() {
        return fieldType;
    }

    public String getDictType() {
        return dictType;
    }

    public String getRefTargetFormId() {
        return refTargetFormId;
    }

    public List<FieldSpec> getSubFields() {
        return subFields;
    }

    /**
     * 获取该字段的实际物理列名（委托 {@link ColumnValidation#physicalColumnName} 单一出口）。
     * <ul>
     *   <li>普通字段 → fieldName 原值</li>
     *   <li>REFERENCE → {@code ref_{fieldName}_id}</li>
     *   <li>TABLE → 无列名（抛异常）</li>
     * </ul>
     */
    public String getPhysicalColumnName() {
        return ColumnValidation.physicalColumnName(fieldName, fieldType);
    }
}
