package com.sw.ck.bpm.process.entity;

import lombok.Getter;

/**
 * 任务动作命令结果标记（命令 result 载荷 status 字段契约）。
 * <p>DONE 本命令产生新效果；RECOVERED 同身份幂等恢复，回写原动作记录标识，
 * 不产生第二次审批效果。</p>
 */
@Getter
public enum BpmCommandResultStatusEnum {

    DONE("DONE", "已执行"),
    RECOVERED("RECOVERED", "幂等恢复"),
    ;

    private final String code;
    private final String label;

    BpmCommandResultStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
