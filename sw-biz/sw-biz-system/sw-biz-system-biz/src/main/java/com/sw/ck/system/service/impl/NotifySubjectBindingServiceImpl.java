package com.sw.ck.system.service.impl;

import com.sw.ck.common.crypto.AesGcmCipher;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.system.entity.NotifySubjectBinding;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.mapper.NotifySubjectBindingMapper;
import com.sw.ck.system.mapper.SysUserMapper;
import com.sw.ck.system.service.NotifySubjectBindingService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 通知 Provider 主体映射服务实现（I6 G5a-I）。
 * <p>规则（方向 §3.6/§D-14）：</p>
 * <ul>
 *   <li>主体标识只在服务端加密（AES-GCM，SW_CIPHER_KEY）后落库；
 *       不进日志、不进明文回显，查询响应只回 SHA-256 摘要；</li>
 *   <li>仅登记三 Provider（FEISHU / DINGTALK / WECHAT_WORK），其他 Provider 明确失败；</li>
 *   <li>用户必须为当前租户内启用用户；重复 ACTIVE 绑定明确失败；</li>
 *   <li>全部读写挂当前租户（TenantLineHandler + 显式 tenant_id 条件），跨租户 fail closed。</li>
 * </ul>
 */
@Service
public class NotifySubjectBindingServiceImpl implements NotifySubjectBindingService {

    private static final Logger log = LoggerFactory.getLogger(NotifySubjectBindingServiceImpl.class);

    /** I6 三 Provider 独立 Adapter（方向 §3.5 不复用 SSO 凭据语义） */
    private static final Set<String> SUPPORTED_PROVIDERS = Set.of("FEISHU", "DINGTALK", "WECHAT_WORK");

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";

    private final NotifySubjectBindingMapper bindingMapper;
    private final SysUserMapper sysUserMapper;
    private final AesGcmCipher notifySubjectCipher;

    @Autowired
    public NotifySubjectBindingServiceImpl(NotifySubjectBindingMapper bindingMapper,
                                           SysUserMapper sysUserMapper,
                                           AesGcmCipher notifySubjectCipher) {
        this.bindingMapper = bindingMapper;
        this.sysUserMapper = sysUserMapper;
        this.notifySubjectCipher = notifySubjectCipher;
    }

    @Override
    public NotifySubjectBinding bind(Long userId, String provider, String subject) {
        if (userId == null || provider == null || provider.isBlank() || subject == null || subject.isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "用户/Provider/主体不能为空");
        }
        String providerKey = provider.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_PROVIDERS.contains(providerKey)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "未支持的 Provider: " + providerKey);
        }
        // 用户必须为当前租户有效用户（同租户权威；TenantLineHandler 注入租户）
        SysUser user = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getId, userId)
                .eq(SysUser::getStatus, 0));
        if (user == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "用户不存在或已停用");
        }
        NotifySubjectBinding existing = bindingMapper.selectActive(user.getTenantId(), userId, providerKey);
        if (existing != null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "该用户已存在同 Provider 的有效绑定");
        }
        NotifySubjectBinding binding = new NotifySubjectBinding();
        binding.setUserId(userId);
        binding.setProvider(providerKey);
        binding.setSubjectCipher(notifySubjectCipher.encrypt(subject));
        binding.setSubjectDigest(sha256(subject));
        binding.setBindStatus(STATUS_ACTIVE);
        bindingMapper.insert(binding);
        log.info("通知主体已绑定: bindingId={}, userId={}, provider={}, digest={}",
                binding.getId(), userId, providerKey, binding.getSubjectDigest());
        return binding;
    }

    @Override
    public List<NotifySubjectBinding> list(String provider) {
        return bindingMapper.selectList(Wrappers.<NotifySubjectBinding>lambdaQuery()
                .eq(provider != null && !provider.isBlank(), NotifySubjectBinding::getProvider,
                        provider == null ? null : provider.trim().toUpperCase(Locale.ROOT))
                .orderByDesc(NotifySubjectBinding::getId));
    }

    @Override
    public void toggle(Long id, boolean enabled) {
        NotifySubjectBinding entity = bindingMapper.selectById(id);
        if (entity == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND, "绑定不存在");
        }
        entity.setBindStatus(enabled ? STATUS_ACTIVE : STATUS_DISABLED);
        bindingMapper.updateById(entity);
    }

    @Override
    public void unbind(Long id) {
        NotifySubjectBinding entity = bindingMapper.selectById(id);
        if (entity == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND, "绑定不存在");
        }
        bindingMapper.deleteById(id);
    }

    /** 审计/展示只允许摘要。 */
    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
