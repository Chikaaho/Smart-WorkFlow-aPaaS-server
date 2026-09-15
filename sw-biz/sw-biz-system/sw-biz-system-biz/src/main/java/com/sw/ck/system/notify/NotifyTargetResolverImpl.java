package com.sw.ck.system.notify;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.common.crypto.AesGcmCipher;
import com.sw.ck.notify.api.NotifyTargetResolver;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.mapper.NotifySubjectBindingMapper;
import com.sw.ck.system.mapper.SysUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

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

    @Autowired
    private NotifySubjectBindingMapper subjectBindingMapper;

    @Autowired
    private AesGcmCipher notifySubjectCipher;

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
        if (user == null || user.getPhone() == null || user.getPhone().isBlank()) {
            return null;
        }
        Long count = sysUserMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getStatus, STATUS_ACTIVE)
                .eq(SysUser::getPhone, user.getPhone().trim()));
        return count != null && count == 1L ? user.getPhone().trim() : null;
    }

    @Override
    public com.sw.ck.notify.api.NotifyTargetResolution resolvePhoneForTenant(Long tenantId, Long userId) {
        if (tenantId == null || userId == null) {
            return com.sw.ck.notify.api.NotifyTargetResolution.of("INVALID", "SYS_USER", null);
        }
        SysUser user = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getId, userId)
                .eq(SysUser::getTenantId, tenantId)
                .eq(SysUser::getStatus, STATUS_ACTIVE));
        if (user == null) {
            return com.sw.ck.notify.api.NotifyTargetResolution.of("USER_NOT_FOUND", "SYS_USER", null);
        }
        if (user.getPhone() == null || user.getPhone().isBlank()) {
            return com.sw.ck.notify.api.NotifyTargetResolution.of("MISSING", "SYS_USER", null);
        }
        String normalized = user.getPhone().trim();
        Long count = sysUserMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getTenantId, tenantId)
                .eq(SysUser::getStatus, STATUS_ACTIVE)
                .eq(SysUser::getPhone, normalized));
        if (count == null || count != 1L) {
            return com.sw.ck.notify.api.NotifyTargetResolution.of("AMBIGUOUS", "SYS_USER", null);
        }
        return com.sw.ck.notify.api.NotifyTargetResolution.of("RESOLVED", "SYS_USER", digest(normalized));
    }

    @Override
    public String resolveProviderSubject(Long tenantId, Long userId, String provider) {
        // I6 G5a-I：sw_notify_subject_binding 为通知主体权威（与 I5 SSO 摘要语义
        // 独立，不复用/不反解 sys_sso_user_binding）；仅 ACTIVE 绑定可解析，
        // DISABLED / 未绑定 / 跨租户 → null → 渠道适配器明确失败。
        if (tenantId == null || userId == null || provider == null || provider.isBlank()) {
            return null;
        }
        // 异步投递线程无 LoginUserHolder：租户由投递请求权威携带（显式 tenant_id 条件）
        com.sw.ck.system.entity.NotifySubjectBinding binding =
                subjectBindingMapper.selectActive(tenantId, userId, provider.trim().toUpperCase());
        if (binding == null || !"ACTIVE".equals(binding.getBindStatus())) {
            return null;
        }
        try {
            return notifySubjectCipher.decrypt(binding.getSubjectCipher());
        } catch (Exception e) {
            // 密文无法解密（密钥轮换后遗留等）→ 明确失败，不静默用摘要冒充
            return null;
        }
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }
}
