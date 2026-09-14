package com.sw.ck.notify.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.api.NotifyChannelAdapter;
import com.sw.ck.notify.dto.NotifyChannelStatusDTO;
import com.sw.ck.notify.entity.NotifyChannelConfig;
import com.sw.ck.notify.mapper.NotifyChannelConfigMapper;
import com.sw.ck.notify.service.NotifyChannelConfigService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 租户级渠道启停服务实现（I6）。 */
@Service
public class NotifyChannelConfigServiceImpl implements NotifyChannelConfigService {

    /** 系统级已装配适配器（未装配=生产无投递能力，租户不可启用）。 */
    private final List<NotifyChannelAdapter> adapters;
    private final NotifyChannelConfigMapper configMapper;

    public NotifyChannelConfigServiceImpl(List<NotifyChannelAdapter> adapters,
                                          NotifyChannelConfigMapper configMapper) {
        this.adapters = adapters;
        this.configMapper = configMapper;
    }

    @Override
    public List<NotifyChannelStatusDTO> listChannelStatus() {
        Set<String> live = new HashSet<>();
        for (NotifyChannelAdapter a : adapters) {
            live.add(a.channel().name());
        }
        Set<String> tenantRows = new HashSet<>();
        List<NotifyChannelStatusDTO> list = new ArrayList<>();
        for (NotifyChannelConfig row : configMapper.selectList(Wrappers.emptyWrapper())) {
            tenantRows.add(row.getChannel());
            NotifyChannelStatusDTO dto = new NotifyChannelStatusDTO();
            dto.setChannel(row.getChannel());
            dto.setSystemConfigured(live.contains(row.getChannel()) || "IN_APP".equalsIgnoreCase(row.getChannel()));
            dto.setTenantEnabled(row.getEnabled() != null && row.getEnabled() == 1);
            dto.setSenderDisplay(row.getSenderDisplay());
            dto.setConfigSummary(row.getConfigSummary());
            list.add(dto);
        }
        return list;
    }

    @Override
    public void updateTenantChannel(String channel, boolean enabled, String senderDisplay, String configSummary) {
        String value;
        try {
            value = NotifyChannel.valueOf(channel.trim().toUpperCase(java.util.Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "未知渠道: " + channel);
        }
        if (enabled && !tenantChannelAvailable(value)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                    "生产渠道适配器未装配或租户配置校验未通过，禁止启用: " + value);
        }
        NotifyChannelConfig existing = configMapper.selectOne(Wrappers.<NotifyChannelConfig>lambdaQuery()
                .eq(NotifyChannelConfig::getChannel, value));
        if (existing == null) {
            NotifyChannelConfig row = new NotifyChannelConfig();
            row.setChannel(value);
            row.setEnabled(enabled ? 1 : 0);
            row.setSenderDisplay(senderDisplay);
            row.setConfigSummary(configSummary);
            configMapper.insert(row);
        } else {
            existing.setEnabled(enabled ? 1 : 0);
            existing.setSenderDisplay(senderDisplay);
            existing.setConfigSummary(configSummary);
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
        return row != null && row.getEnabled() != null && row.getEnabled() == 1;
    }

    private boolean tenantChannelAvailable(String value) {
        if ("IN_APP".equalsIgnoreCase(value)) {
            return true;
        }
        for (NotifyChannelAdapter a : adapters) {
            if (a.channel().name().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
