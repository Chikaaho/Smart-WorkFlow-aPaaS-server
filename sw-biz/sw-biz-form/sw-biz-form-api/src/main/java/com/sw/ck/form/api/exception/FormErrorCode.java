package com.sw.ck.form.api.exception;

import com.sw.ck.common.exception.ErrorCode;

/**
 * 表单模块错误码。
 * <p>
 * 数值码保持 0.1.0 兼容；{@link #getErrorKey()} 为 P61 新增全局唯一语义标识。
 * 注意 1204-1208 的既有语义为「字段类型未知 / 类型禁用 / 缺少属性 / 表格嵌套 / 定义异常」，
 * 与早期 Web 兜底表按「发布校验」登记的文案不同——Web 侧已于 P61 按本枚举真实码义对齐。
 */
public enum FormErrorCode implements ErrorCode {

    // ==================== 通用（1000-1099） ====================
    FORM_NOT_FOUND(1000, "form.not_found", "表单不存在"),
    FORM_KEY_DUPLICATE(1001, "form.key_duplicate", "表单标识已存在"),
    FORM_NAME_DUPLICATE(1002, "form.name_duplicate", "表单名称已存在"),

    // ==================== 状态机（1100-1199） ====================
    FORM_ALREADY_PUBLISHED(1100, "form.already_published", "表单已发布，不能修改"),
    FORM_ALREADY_DRAFT(1101, "form.already_draft", "表单处于草稿态，不能执行此操作"),
    FORM_NOT_PUBLISHED(1102, "form.not_published", "表单未发布，不能提交数据"),
    FORM_DISABLED(1103, "form.disabled", "表单已停用，不能填报或提交"),
    FORM_DELETE_RESTRICTED(1104, "form.delete_restricted", "表单存在有效引用，不能删除"),
    FIELD_EDIT_DENIED(1105, "form.field_edit_denied", "当前身份无该字段编辑权限"),
    FIELD_VIEW_DENIED(1106, "form.field_view_denied", "当前身份无该字段查看权限"),

    // ==================== 发布校验（1200-1299） ====================
    INVALID_COLUMN_NAME(1200, "form.invalid_column_name", "字段名不合法"),
    DUPLICATE_COLUMN(1201, "form.duplicate_column", "字段名重复"),
    TABLE_ALREADY_EXISTS(1202, "form.table_already_exists", "动态宽表已存在"),
    PUBLISH_FAILED(1203, "form.publish_failed", "表单发布失败"),
    FIELD_TYPE_UNKNOWN(1204, "form.field_type_unknown", "字段类型未知"),
    FIELD_TYPE_DISABLED(1205, "form.field_type_disabled", "字段类型暂不允许发布"),
    FIELD_ATTR_MISSING(1206, "form.field_attr_missing", "字段缺少必要属性"),
    FIELD_NESTED_TABLE(1207, "form.field_nested_table", "表格字段不能嵌套"),
    DEFINITION_INVALID(1208, "form.definition_invalid", "表单定义配置异常"),
    FORMULA_INVALID(1209, "form.formula_invalid", "公式表达式非法"),
    FORMULA_CYCLE(1210, "form.formula_cycle", "公式存在循环依赖"),
    FORMULA_UNKNOWN_FIELD(1211, "form.formula_unknown_field", "公式引用了未定义字段"),
    EXT_QUERY_NOT_FOUND(1212, "form.ext_query_not_found", "外部数据源查询契约不存在"),
    EXT_QUERY_DISABLED(1213, "form.ext_query_disabled", "外部数据源查询契约已停用"),
    EXT_OUTPUT_MISMATCH(1214, "form.ext_output_mismatch", "外部数据源输出与契约不匹配"),
    EXT_OBJECT_NOT_FOUND(1215, "form.ext_object_not_found", "外部数据对象不存在或不可见"),
    LIST_CONFIG_INVALID(1216, "form.list_config_invalid", "列表展示配置非法"),
    REFERENCE_OBJECT_NOT_FOUND(1217, "form.reference_object_not_found", "引用记录不存在或不可见"),
    ATTACHMENT_FILE_NOT_FOUND(1218, "form.attachment_file_not_found", "附件/图片文件不存在或不可见"),
    EXT_RESULT_LIMIT_EXCEEDED(1219, "form.ext_result_limit_exceeded", "外部数据源结果超过行数上限"),

    // ==================== 渲染（1300-1399） ====================
    CONFIG_NOT_FOUND(1300, "form.config_not_found", "表单配置未找到"),
    SNAPSHOT_NOT_FOUND(1301, "form.snapshot_not_found", "表单版本快照不存在"),

    // ==================== 提交校验（1400-1499） ====================
    SUBMIT_FIELD_UNKNOWN(1400, "form.submit_field_unknown", "提交了未定义的字段"),
    SUBMIT_FIELD_REQUIRED(1401, "form.submit_field_required", "必填字段缺失"),
    SUBMIT_FIELD_TYPE_MISMATCH(1402, "form.submit_field_type_mismatch", "字段类型不匹配"),
    SUBMIT_DICT_INVALID(1403, "form.submit_dict_invalid", "字典值不在允许范围内"),
    SUBMIT_FAILED(1499, "form.submit_failed", "表单提交失败"),
    SUBMIT_DEFINITION_INVALID(1404, "form.submit_definition_invalid", "表单定义配置异常"),

    // ==================== 数据查询（1500-1599） ====================
    QUERY_FORM_NOT_EXIST(1500, "form.query_form_not_exist", "表单不存在或未发布"),
    QUERY_FILTER_FIELD_UNKNOWN(1501, "form.query_filter_field_unknown", "过滤字段不在表单定义中"),
    QUERY_FILTER_FIELD_NOT_FILTERABLE(1502, "form.query_filter_field_not_filterable", "该字段类型不支持筛选"),
    QUERY_FILTER_OP_TYPE_MISMATCH(1503, "form.query_filter_op_type_mismatch", "过滤操作符与字段类型不匹配"),
    QUERY_FILTER_OP_NOT_SUPPORTED(1504, "form.query_filter_op_not_supported", "该过滤操作符 v1 暂不支持"),

    // ==================== 数据删除（1505-1509） ====================
    DELETE_RESTRICT_REFERENCED(1505, "form.delete_restrict_referenced", "记录被其他表单引用，不能删除"),
    DELETE_RECORD_NOT_EXIST(1506, "form.delete_record_not_exist", "记录不存在或已删除"),

    // ==================== 数据更新 / 详情（1507-1509） ====================
    RECORD_NOT_FOUND(1507, "form.record_not_found", "记录不存在或已删除"),
    VERSION_CONFLICT(1508, "form.version_conflict", "数据版本冲突，请刷新后重试");

    private final int code;
    private final String errorKey;
    private final String message;

    FormErrorCode(int code, String errorKey, String message) {
        this.code = code;
        this.errorKey = errorKey;
        this.message = message;
    }

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public String getErrorKey() {
        return errorKey;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
