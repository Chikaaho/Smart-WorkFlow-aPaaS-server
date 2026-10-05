package com.sw.ck.form.api.port;

import java.util.Map;

/**
 * 实时动作运行时画像 Port（form-api 定义，form-biz 实现）。
 * <p>
 * 供 BPM 资源保障运行画像回读与启用预算一致性检查使用（只读，不承载准入裁决——
 * 实时动作并发闸的唯一运行强制点在 form 侧实时入口）。跨模块暴露最小只读集合，
 * 不泄漏调用身份明细（明细经授权查询端点另行关联）。
 * </p>
 */
public interface TxnActionRuntimePort {

    /**
     * 实时并发闸有效画像（实际生效配置与当前在途）。
     * <p>P62 最终交付 FD02（按 Optional 合同修复）：恒返回有值
     * {@code Optional.of(画像)}——画像由生效配置即时构造、恒可构造；
     * Port 未装配以调用方 {@code getIfAvailable()} 判定，不用 empty 表达。</p>
     */
    java.util.Optional<RealtimeGuardProfile> realtimeGuardProfile();

    record RealtimeGuardProfile(int globalMaxConcurrent, int tenantMaxConcurrent,
                                int globalInFlight, Map<Long, Integer> tenantInFlight) {
    }
}
