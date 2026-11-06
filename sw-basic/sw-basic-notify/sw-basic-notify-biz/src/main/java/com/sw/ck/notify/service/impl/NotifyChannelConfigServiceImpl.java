package com.sw.ck.notify.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.crypto.AesGcmCipher;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.api.NotifyChannelAdapter;
import com.sw.ck.notify.dto.NotifyChannelStatusDTO;
import com.sw.ck.notify.entity.NotifyChannelConfig;
import com.sw.ck.notify.mapper.NotifyChannelConfigMapper;
import com.sw.ck.notify.service.NotifyChannelConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 租户级渠道启停服务实现（I6；0.2.4 追加渠道凭据密文入库）。 */
@Service
public class NotifyChannelConfigServiceImpl implements NotifyChannelConfigService {

    private static final Logger log = LoggerFactory.getLogger(NotifyChannelConfigServiceImpl.class);

    private static final ObjectMapper JSON_OBJECT_MAPPER = new ObjectMapper();

    /** 系统级已装配适配器（未装配=生产无投递能力，租户不可启用）。 */
    private final List<NotifyChannelAdapter> adapters;
    private final NotifyChannelConfigMapper configMapper;

    /** 渠道凭据加密主键：优先独立键，缺省复用 SW_CIPHER_KEY（与模型 Key 同口径）。 */
    private final String cipherKey;

    public NotifyChannelConfigServiceImpl(List<NotifyChannelAdapter> adapters,
                                          NotifyChannelConfigMapper configMapper,
                                          @Value("${sw.notify.channel.cipher-key:${SW_CIPHER_KEY:}}")
                                          String cipherKey) {
        this.adapters = adapters;
        this.configMapper = configMapper;
        this.cipherKey = cipherKey;
    }

    @Override
    public List<NotifyChannelStatusDTO> listChannelStatus() {
        Set<String> live = new HashSet<>();
        for (NotifyChannelAdapter a : adapters) {
            live.add(channelOf(a).name());
        }
        Set<String> tenantRows = new HashSet<>();
        List<NotifyChannelStatusDTO> list = new ArrayList<>();
        for (NotifyChannelConfig row : configMapper.selectList(Wrappers.emptyWrapper())) {
            tenantRows.add(row.getChannel());
            NotifyChannelStatusDTO dto = new NotifyChannelStatusDTO();
            dto.setChannel(row.getChannel());
            dto.setSystemConfigured(live.contains(row.getChannel()) || "IN_APP".equalsIgnoreCase(row.getChannel()));
            dto.setTenantEnabled(Boolean.TRUE.equals(row.getEnabled()));
            dto.setSenderDisplay(row.getSenderDisplay());
            dto.setConfigSummary(row.getConfigSummary());
            dto.setHasSecret(row.getConfigCipher() != null && !row.getConfigCipher().isBlank());
            list.add(dto);
        }
        return list;
    }

    @Override
    public void updateTenantChannel(String channel, boolean enabled, String senderDisplay, String configSummary) {
        updateTenantChannel(channel, enabled, senderDisplay, configSummary, null);
    }

    @Override
    public void updateTenantChannel(String channel, boolean enabled, String senderDisplay,
                                    String configSummary, String configSecret) {
        String value;
        try {
            value = NotifyChannel.valueOf(channel.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "未知渠道: " + channel);
        }
        if (enabled && !tenantChannelAvailable(value)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                    "生产渠道适配器未装配或租户配置校验未通过，禁止启用: " + value);
        }
        String cipher = null;
        if (configSecret != null && !configSecret.isBlank()) {
            cipher = requireCipher().encrypt(configSecret);
        }
        NotifyChannelConfig existing = configMapper.selectOne(Wrappers.<NotifyChannelConfig>lambdaQuery()
                .eq(NotifyChannelConfig::getChannel, value));
        if (existing == null) {
            NotifyChannelConfig row = new NotifyChannelConfig();
            row.setChannel(value);
            row.setEnabled(enabled);
            row.setSenderDisplay(senderDisplay);
            row.setConfigSummary(configSummary);
            row.setConfigCipher(cipher);
            configMapper.insert(row);
        } else {
            existing.setEnabled(enabled);
            existing.setSenderDisplay(senderDisplay);
            existing.setConfigSummary(configSummary);
            if (cipher != null) {
                existing.setConfigCipher(cipher);
            }
            configMapper.updateById(existing);
        }
    }

    @Override
    public boolean tenantEnabled(String channel) {
        if (channel == null) {
            return false;
        }
        if ("IN_APP".equalsIgnoreCase(channel)) {
            return true;
        }
        NotifyChannelConfig row = configMapper.selectOne(Wrappers.<NotifyChannelConfig>lambdaQuery()
                .eq(NotifyChannelConfig::getChannel, channel));
        return row != null && Boolean.TRUE.equals(row.getEnabled());
    }

    @Override
    public Optional<Map<String, String>> resolveChannelConfig(String channel) {
        if (channel == null || channel.isBlank()) {
            return Optional.empty();
        }
        NotifyChannelConfig row;
        try {
            row = configMapper.selectOne(Wrappers.<NotifyChannelConfig>lambdaQuery()
                    .eq(NotifyChannelConfig::getChannel, channel.trim().toUpperCase(Locale.ROOT)));
        } catch (Exception e) {
            log.warn("读取渠道配置失败（按未配置处理）: channel={}, errorClass={}",
                    channel, e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (row == null || row.getConfigCipher() == null || row.getConfigCipher().isBlank()) {
            return Optional.empty();
        }
        try {
            String plain = requireCipher().decrypt(row.getConfigCipher());
            JsonNode node = JSON_OBJECT_MAPPER.readTree(plain);
            if (node == null || !node.isObject()) {
                return Optional.empty();
            }
            Map<String, String> config = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isNull()) {
                    config.put(entry.getKey(), entry.getValue().asText());
                }
            });
            return Optional.of(config);
        } catch (Exception e) {
            // 密文不可解（加密主键变更/配置损坏）：按未配置处理并留可诊断日志，不阻断通知主链
            log.warn("渠道凭据解密失败（按未配置处理，回退 yml/env）: channel={}, errorClass={}",
                    channel, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** 渠道凭据加密主键：优先 sw.notify.channel.cipher-key，否则复用 SW_CIPHER_KEY。 */
    private AesGcmCipher requireCipher() {
        if (cipherKey == null || cipherKey.isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                    "渠道凭据加密主键未配置（sw.notify.channel.cipher-key / SW_CIPHER_KEY）");
        }
        return new AesGcmCipher(cipherKey);
    }

    private boolean tenantChannelAvailable(String value) {
        if ("IN_APP".equalsIgnoreCase(value)) {
            return true;
        }
        for (NotifyChannelAdapter a : adapters) {
            if (channelOf(a).name().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    /** 适配器身份是注册期契约：缺失渠道标识属装配错误，明确失败而不静默跳过。 */
    private static NotifyChannel channelOf(NotifyChannelAdapter adapter) {
        return adapter.channel().orElseThrow(
                () -> new IllegalStateException("通知渠道适配器缺少渠道标识"));
    }
}
