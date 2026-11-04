package com.sw.ck.agent.service;

import com.sw.ck.agent.dto.AgentProviderDTO;
import com.sw.ck.agent.dto.AgentProviderSaveReqDTO;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;

import java.util.List;

/**
 * 供应商管理 Service（F1 阶段 1，D022-02）：名称、API 格式、地址、密钥（加密）、启停。
 */
public interface AgentProviderService {

    /** 分页查询（密钥脱敏） */
    PageResult<AgentProviderDTO> pageProviders(PageParam pageParam, String nameKeyword);

    /** 全量列表（模型编辑下拉用，密钥脱敏） */
    List<AgentProviderDTO> listEnabled();

    /** 详情（密钥脱敏） */
    AgentProviderDTO getById(Long id);

    /** 新增（apiKey 明文入参，Service 层加密落库） */
    Long create(AgentProviderSaveReqDTO req);

    /** 编辑（apiKey 留空保留旧密文） */
    void update(Long id, AgentProviderSaveReqDTO req);

    /** 删除（逻辑删除；下属模型由调用方/前端先处理） */
    void delete(Long id);
}
