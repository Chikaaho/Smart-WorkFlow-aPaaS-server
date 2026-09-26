package com.sw.ck.system.service;

import com.sw.ck.system.entity.NotifySubjectBinding;

import java.util.List;

/**
 * 通知 Provider 主体映射服务（I6 G5a-I）。
 * <p>主体只经服务端加密落库（不落明文/SQL 日志）；查询响应只回摘要；
 * 重复绑定 / 伪 Provider / 跨租户 / 停用绑定均明确失败。</p>
 */
public interface NotifySubjectBindingService {

    /** 建立绑定（重复 ACTIVE 绑定抛 PARAM_ERROR，可判定重复绑定）。 */
    NotifySubjectBinding bind(Long userId, String provider, String subject);

    /** 当前租户指定 Provider 的绑定列表（主体仅回摘要）。 */
    List<NotifySubjectBinding> list(String provider);

    /** 启停绑定（DISABLED 后 resolveProviderSubject 返回 empty → 渠道明确失败）。 */
    void toggle(Long id, boolean enabled);

    /** 解绑（逻辑删除）。 */
    void unbind(Long id);
}
