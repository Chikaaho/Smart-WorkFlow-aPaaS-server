package com.sw.ck.bpm.api.participant;

/** 统一人员型节点参与人策略标识。配置值属于产品契约，不是 Bean/class 名称。 */
public final class ParticipantStrategy {

    public static final String FIXED_USER = "FIXED_USER";
    public static final String ROLE = "ROLE";
    public static final String EXPRESSION = "EXPRESSION";
    public static final String ADAPTER = "ADAPTER";

    private ParticipantStrategy() {
    }
}
