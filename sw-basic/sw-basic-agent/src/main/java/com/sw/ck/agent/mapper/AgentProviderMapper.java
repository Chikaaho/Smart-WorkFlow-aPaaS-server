package com.sw.ck.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sw.ck.agent.entity.AgentProvider;
import org.apache.ibatis.annotations.Mapper;

/**
 * 供应商 Mapper（F1 阶段 1）。租户隔离由全局 TenantLine 拦截器完成。
 */
@Mapper
public interface AgentProviderMapper extends BaseMapper<AgentProvider> {
}
