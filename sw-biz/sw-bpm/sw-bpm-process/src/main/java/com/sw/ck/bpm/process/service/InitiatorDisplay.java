package com.sw.ck.bpm.process.service;

import java.util.Map;

/**
 * 流程发起人显示名解析（1.0.0 F4-1）。
 * <p>
 * IoT 触发实例的发起人落库为 {@code initiatorId=0}（无对应用户行），历史显示回落为
 * 数字 {@code 0} 或空白。监控列表/详情、「我发起的」、待办三处统一经本工具解析：
 * {@code 0} 显示「系统（IoT）」，其余按用户展示名映射，查不到为 null。
 * </p>
 */
public final class InitiatorDisplay {

    /** IoT 系统身份的统一展示名（Owner 默认文案，三处一致）。 */
    public static final String SYSTEM_IOT = "系统（IoT）";

    private InitiatorDisplay() {
    }

    /** initiatorId=0 → 系统（IoT）；其余查映射，未命中 null。 */
    public static String resolve(Long initiatorId, Map<Long, String> displayNames) {
        if (initiatorId == null) {
            return null;
        }
        if (initiatorId == 0L) {
            return SYSTEM_IOT;
        }
        return displayNames.get(initiatorId);
    }
}
