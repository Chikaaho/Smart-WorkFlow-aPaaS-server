package com.sw.ck.form.txn.model;

import com.sw.ck.form.api.exception.FormErrorCode;

/**
 * 发布校验错误项（结构化定位：字段路径 + 可读原因 + 错误码）。
 */
public record TxnPublishError(String field, String message, int code) {

    public static TxnPublishError of(FormErrorCode errorCode, String field, String message) {
        return new TxnPublishError(field, message, errorCode.getCode());
    }
}
