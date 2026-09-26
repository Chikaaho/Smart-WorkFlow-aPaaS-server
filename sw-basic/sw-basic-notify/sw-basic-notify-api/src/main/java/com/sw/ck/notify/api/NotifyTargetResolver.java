package com.sw.ck.notify.api;

import java.util.Optional;

/**
 * 通知目标解析器 SPI。
 * <p>
 * 将用户 ID 解析为通知目标（邮箱、手机号等）。
 * 由 sw-biz-system-biz 提供实现，sw-basic-notify-biz 消费。
 * <p>
 * 本接口定义在 -api 模块，确保 notify 不直接依赖 system。
 * </p>
 * <p>
 * 模块内部调用边界统一返回非空 {@link Optional}：无法解析以 empty 表达，
 * 调用方不得以 empty 获取明文地址以外的旧哨兵语义。
 * </p>
 */
public interface NotifyTargetResolver {

    /**
     * 根据用户 ID 解析邮箱地址。
     *
     * @param userId 用户 ID
     * @return present = 邮箱地址；empty = 用户不存在、已停用或未登记邮箱（无法解析）
     */
    Optional<String> resolveEmail(Long userId);

    /**
     * 根据用户 ID 解析手机号。
     *
     * @param userId 用户 ID
     * @return present = 手机号；empty = 用户不存在、已停用、未登记手机号或号码不唯一（无法解析）
     */
    Optional<String> resolvePhone(Long userId);

    /**
     * 按明确租户解析 PHONE，并返回不含明文号码的判定结果。
     * <p>判定值（RESOLVED / UNRESOLVED / USER_NOT_FOUND / MISSING / AMBIGUOUS）本身就是
     * 业务状态，必须以 present 保留；租户或用户上下文缺失时返回 empty。</p>
     *
     * @return present = 判定结果；empty = 租户/用户上下文缺失，无法判定
     */
    default Optional<NotifyTargetResolution> resolvePhoneForTenant(Long tenantId, Long userId) {
        if (tenantId == null || userId == null) {
            return Optional.empty();
        }
        return resolvePhone(userId)
                .map(phone -> phone.isBlank()
                        ? NotifyTargetResolution.of("UNRESOLVED", "SYS_USER", null)
                        : NotifyTargetResolution.of("RESOLVED", "SYS_USER", null))
                .or(() -> Optional.of(NotifyTargetResolution.of("UNRESOLVED", "SYS_USER", null)));
    }

    // ==================== I6 扩展 ====================

    /**
     * 解析用户在某 Provider 的稳定外部主体标识（open_id / userid 等）。
     * <p>服务端按当前租户权威解析；明确失败；不接受客户端原始地址旁路。</p>
     *
     * @return present = 稳定主体标识；empty = 未绑定、绑定失效、跨租户或上下文缺失（无法解析）
     */
    default Optional<String> resolveProviderSubject(Long tenantId, Long userId, String provider) {
        return Optional.empty();
    }
}
