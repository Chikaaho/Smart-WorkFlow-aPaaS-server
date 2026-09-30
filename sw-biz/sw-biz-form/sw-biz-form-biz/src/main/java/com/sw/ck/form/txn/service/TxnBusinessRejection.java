package com.sw.ck.form.txn.service;

import com.sw.ck.form.api.exception.FormErrorCode;

/**
 * 事务动作业务拒绝（可判定失败，如可用量不足/预占非 ACTIVE）。
 * <p>该异常在事务边界内抛出以回滚半成品写入；由执行器在外层以独立事务记录 REJECTED 调用结果。</p>
 */
public class TxnBusinessRejection extends RuntimeException {

    private final FormErrorCode errorCode;

    public TxnBusinessRejection(FormErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public FormErrorCode getErrorCode() {
        return errorCode;
    }
}
