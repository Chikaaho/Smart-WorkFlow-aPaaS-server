package com.sw.ck.system.mapper;

import com.sw.ck.common.mapper.BaseMapperX;
import com.sw.ck.system.entity.SsoProviderConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * SSO Provider 配置 Mapper（I5）。按租户隔离（租户级配置）。
 */
@Mapper
public interface SsoProviderConfigMapper extends BaseMapperX<SsoProviderConfig> {
}
