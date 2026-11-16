package com.sw.ck.agent.service;

import com.sw.ck.agent.vo.AgentConversationVO;
import com.sw.ck.agent.vo.AgentConversationMessageVO;

import java.util.List;

/**
 * 会话查询 Service（M07 Step4 F04，两个只读端点）。
 * <p>
 * 不含 run 逻辑（会话创建/消息写入在 {@code AgentOrchestrationServiceImpl}）：
 * 租户隔离由 MyBatis-Plus 租户拦截器自动完成，用户级过滤（当前登录用户）在
 * 实现内显式完成。
 * </p>
 */
public interface AgentConversationService {

    /**
     * 当前租户 + 当前用户的会话列表（按 create_time 倒序）。
     *
     * @param agentModelConfigId 可选过滤：仅返回该配置下的会话；null 返回全部
     * @return 会话列表（按 create_time DESC）
     */
    List<AgentConversationVO> listConversations(Long agentModelConfigId);

    /**
     * 会话内消息列表（按 msg_order 升序，不分页）。
     *
     * @param sessionId 会话 id；不存在/跨租户 → 404 语义
     * @return 消息列表（msg_order ASC）
     */
    List<AgentConversationMessageVO> listMessages(Long sessionId);
}
