package com.sw.ck.form.api.exception;

import com.sw.ck.common.exception.ErrorCode;

/**
 * 表单模块错误码。
 */
public enum FormErrorCode implements ErrorCode {

    // ==================== 通用（1000-1099） ====================
    FORM_NOT_FOUND(1000, "表单不存在"),
    FORM_KEY_DUPLICATE(1001, "表单标识已存在"),
    FORM_NAME_DUPLICATE(1002, "表单名称已存在"),

    // ==================== 状态机（1100-1199） ====================
    FORM_ALREADY_PUBLISHED(1100, "表单已发布，不能修改"),
    FORM_ALREADY_DRAFT(1101, "表单处于草稿态，不能执行此操作"),
    FORM_NOT_PUBLISHED(1102, "表单未发布，不能提交数据"),

    // ==================== 发布校验（1200-1299） ====================
    INVALID_COLUMN_NAME(1200, "字段名不合法"),
    DUPLICATE_COLUMN(1201, "字段名重复"),
    TABLE_ALREADY_EXISTS(1202, "动态宽表已存在"),
    PUBLISH_FAILED(1203, "表单发布失败"),
    FIELD_TYPE_UNKNOWN(1204, "字段类型未知"),
    FIELD_TYPE_DISABLED(1205, "字段类型暂不允许发布"),
    FIELD_ATTR_MISSING(1206, "字段缺少必要属性"),
    FIELD_NESTED_TABLE(1207, "表格字段不能嵌套"),
    DEFINITION_INVALID(1208, "表单定义配置异常"),

    // ==================== 渲染（1300-1399） ====================
    CONFIG_NOT_FOUND(1300, "表单配置未找到"),
    SNAPSHOT_NOT_FOUND(1301, "表单版本快照不存在"),

    // ==================== 提交校验（1400-1499） ====================
    SUBMIT_FIELD_UNKNOWN(1400, "提交了未定义的字段"),
    SUBMIT_FIELD_REQUIRED(1401, "必填字段缺失"),
    SUBMIT_FIELD_TYPE_MISMATCH(1402, "字段类型不匹配"),
    SUBMIT_DICT_INVALID(1403, "字典值不在允许范围内"),
    SUBMIT_FAILED(1499, "表单提交失败"),
    SUBMIT_DEFINITION_INVALID(1404, "表单定义配置异常"),

    // ==================== 数据查询（1500-1599） ====================
    QUERY_FORM_NOT_EXIST(1500, "表单不存在或未发布"),
    QUERY_FILTER_FIELD_UNKNOWN(1501, "过滤字段不在表单定义中"),
    QUERY_FILTER_FIELD_NOT_FILTERABLE(1502, "该字段类型不支持筛选"),
    QUERY_FILTER_OP_TYPE_MISMATCH(1503, "过滤操作符与字段类型不匹配"),
    QUERY_FILTER_OP_NOT_SUPPORTED(1504, "该过滤操作符 v1 暂不支持"),

    // ==================== 数据删除（1505-1509） ====================
    DELETE_RESTRICT_REFERENCED(1505, "记录被其他表单引用，不能删除"),
    DELETE_RECORD_NOT_EXIST(1506, "记录不存在或已删除"),

    // ==================== 数据更新 / 详情（1507-1509） ====================
    RECORD_NOT_FOUND(1507, "记录不存在或已删除"),
    VERSION_CONFLICT(1508, "数据版本冲突，请刷新后重试");

    private final int code;
    private final String message;

    FormErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
