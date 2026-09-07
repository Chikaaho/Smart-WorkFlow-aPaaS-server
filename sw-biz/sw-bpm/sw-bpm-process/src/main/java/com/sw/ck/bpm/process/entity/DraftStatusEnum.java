package com.sw.ck.bpm.process.entity;

/**
 * 业务草稿状态机。
 * <p>
 * EDITING ⇄ FAILED 可编辑；SUBMITTING 为受理中快照冻结（不可修改/删除）；
 * SUBMITTED 终态（成功后不再作为可重复提交草稿）。
 * </p>
 */
public enum DraftStatusEnum {

    EDITING("EDITING"),
    SUBMITTING("SUBMITTING"),
    SUBMITTED("SUBMITTED"),
    FAILED("FAILED");

    private final String code;

    DraftStatusEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
