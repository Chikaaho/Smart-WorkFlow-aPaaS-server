package com.sw.ck.bpm.process.entity;

/**
 * 流程业务命令类型。
 */
public enum CommandTypeEnum {

    /** 流程发起（表单提交 / 草稿提交 / 定时触发统一入口）。 */
    FLOW_START("FLOW_START"),

    /**
     * 定时任务流程发起（Phase 4 可靠业务事件）：与 {@link #FLOW_START} 的区别是发起目标由
     * {@code flow_def_key} 反查启用表单绑定，业务键派生自 {@code jobId + fireTime}（无表单记录）。
     */
    SCHEDULED_FLOW_START("SCHEDULED_FLOW_START"),

    /** 草稿正式提交（落表单数据 + 触发 FLOW_START）。 */
    DRAFT_SUBMIT("DRAFT_SUBMIT"),

    /** 审批同意。 */
    TASK_APPROVE("TASK_APPROVE"),

    /** 审批驳回。 */
    TASK_REJECT("TASK_REJECT"),

    /** 审批退回。 */
    TASK_RETURN("TASK_RETURN");

    private final String code;

    CommandTypeEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static CommandTypeEnum of(String code) {
        for (CommandTypeEnum type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知命令类型: " + code);
    }
}
