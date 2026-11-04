package com.sw.ck.agent.service;

import com.sw.ck.agent.dto.AgentModelDTO;
import com.sw.ck.agent.dto.AgentModelSaveReqDTO;
import com.sw.ck.agent.dto.AgentModelTestConnectionRespDTO;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;

import java.util.List;

/**
 * 模型管理 Service（F1 阶段 1，D022-02/05）：挂在供应商下，含采样参数与多 Key 轮换字段。
 */
public interface AgentModelService {

    /** 分页查询（可按供应商过滤、名称关键字） */
    PageResult<AgentModelDTO> pageModels(PageParam pageParam, Long providerId, String nameKeyword);

    /** 启用中的模型列表（图设计器 LLM 节点选择用，可按能力过滤） */
    List<AgentModelDTO> listEnabled(String capability);

    /** 详情 */
    AgentModelDTO getById(Long id);

    /** 新增 */
    Long create(AgentModelSaveReqDTO req);

    /** 编辑 */
    void update(Long id, AgentModelSaveReqDTO req);

    /** 删除（逻辑删除） */
    void delete(Long id);

    /**
     * 连通性测试（F1 阶段 1.5）：对所选模型发起一次真实的最小调用——成功才判可用；
     * 鉴权失败 / 模型不存在 / 超时分别给出可读原因，不回传密钥。
     */
    AgentModelTestConnectionRespDTO testConnection(Long id);
}
