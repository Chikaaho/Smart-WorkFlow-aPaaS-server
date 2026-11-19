package com.sw.ck.agent.orchestration;

import lombok.Getter;

/**
 * Agent 执行结果通用词表（执行生命周期终态；iot 桥接与监听共用）。
 */
@Getter
public enum AgentExecutionOutcomeEnum {

    QUEUED("QUEUED", "已排队"),
    RUNNING("RUNNING", "执行中"),
    SUCCESS("SUCCESS", "执行成功"),
    FAILED("FAILED", "执行失败"),
    STOPPED("STOPPED", "已中止"),
    EXPIRED("EXPIRED", "已过期"),
    COMPLETED("COMPLETED", "调试完成"),
    ;

    private final String code;
    private final String label;

    AgentExecutionOutcomeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
