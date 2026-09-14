package com.sw.ck.system.notify;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.notify.api.NotifyTargetResolver;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.mapper.SysUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * NotifyTargetResolver SPI 实现（I6：从同租户有效用户权威解析联系方式）。
 * <p>租户条件由 TenantLineHandler 自动注入；停用/删除用户返回 null →
 * 渠道适配器明确失败（方向 §5-14：不接受客户端原始地址旁路）。</p>
 */
@Component
public class NotifyTargetResolverImpl implements NotifyTargetResolver {

    /** status=0 为启用（UserFacadeImpl 同源语义）。 */
    private static final int STATUS_ACTIVE = 0;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Override
    public String resolveEmail(Long userId) {
        if (userId == null) {
            return null;
        }
        SysUser user = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getId, userId)
                .eq(SysUser::getStatus, STATUS_ACTIVE));
        return user == null ? null : user.getEmail();
    }

    @Override
    public String resolvePhone(Long userId) {
        if (userId == null) {
            return null;
        }
        SysUser user = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getId, userId)
                .eq(SysUser::getStatus, STATUS_ACTIVE));
        return user == null ? null : user.getPhone();
    }

    @Override
    public String resolveProviderSubject(Long userId, String provider) {
        // I5 认证链权威：sys_sso_user_binding.external_id 仅存 SHA-256 摘要（不落明文）。
        // 通知渠道所需的明文主体标识（open_id / userid）需独立的映射源，Owner 侧裁决。
        // 当前返回 null → 适配器明确失败（不计入成功；等待 Owner 提供映射源后接入）。
        return null;
    }
}
