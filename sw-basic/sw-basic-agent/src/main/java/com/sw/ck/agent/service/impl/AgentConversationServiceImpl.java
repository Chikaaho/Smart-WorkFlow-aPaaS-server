package com.sw.ck.agent.service.impl;

import org.springframework.beans.factory.annotation.Autowired;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.agent.vo.AgentConversationVO;
import com.sw.ck.agent.vo.AgentConversationMessageVO;
import com.sw.ck.agent.entity.AgentMessage;
import com.sw.ck.agent.entity.AgentSession;
import com.sw.ck.agent.mapper.AgentMessageMapper;
import com.sw.ck.agent.mapper.AgentSessionMapper;
import com.sw.ck.agent.service.AgentConversationService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会话查询 Service 实现（M07 Step4 F04）。
 * <p>
 * 租户隔离：所有查询经 MyBatis-Plus 租户拦截器自动追加 {@code tenant_id}（与模块全部
 * 查询同路径）；用户级过滤：会话列表显式按 {@code create_by = 当前用户} 过滤（无登录态
 * 时返回空列表，不泄漏任何会话）。消息查询先校验会话存在（跨租户/已删除 → 404 语义）。
 * </p>
 */
@Service
public class AgentConversationServiceImpl implements AgentConversationService {

    @Autowired
    private AgentSessionMapper sessionMapper;
    @Autowired
    private AgentMessageMapper messageMapper;

    @Override
    public List<AgentConversationVO> listConversations(Long agentModelConfigId) {
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null || loginUser.getUserId() == null) {
            // 无登录态（系统初始化等场景）：不泄漏任何会话
            return List.of();
        }
        // create_by 列为 VARCHAR(64)：按字符串比较（与 MetaObjectHandler 填充的 Long userId 一致）
        LambdaQueryWrapper<AgentSession> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AgentSession::getCreateBy, String.valueOf(loginUser.getUserId()));
        wrapper.eq(agentModelConfigId != null, AgentSession::getAgentModelConfigId, agentModelConfigId);
        wrapper.orderByDesc(AgentSession::getCreateTime);
        return sessionMapper.selectList(wrapper).stream().map(this::toDTO).toList();
    }

    @Override
    public List<AgentConversationMessageVO> listMessages(Long sessionId) {
        // selectById 经租户拦截器自动过滤 tenant_id：跨租户/已删除/不存在 → null → 404 语义
        AgentSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND, "会话不存在");
        }
        return messageMapper.selectList(
                        Wrappers.<AgentMessage>lambdaQuery()
                                .eq(AgentMessage::getSessionId, sessionId)
                                .orderByAsc(AgentMessage::getMsgOrder))
                .stream().map(this::toMessageDTO).toList();
    }

    private AgentConversationVO toDTO(AgentSession session) {
        AgentConversationVO dto = new AgentConversationVO();
        dto.setId(session.getId());
        dto.setAgentModelConfigId(session.getAgentModelConfigId());
        dto.setTitle(session.getTitle());
        dto.setStatus(session.getStatus());
        dto.setCreateTime(session.getCreateTime());
        return dto;
    }

    private AgentConversationMessageVO toMessageDTO(AgentMessage message) {
        AgentConversationMessageVO dto = new AgentConversationMessageVO();
        dto.setId(message.getId());
        dto.setRole(message.getRole());
        dto.setContent(message.getContent());
        dto.setMsgOrder(message.getMsgOrder());
        // M07-F04-02: 添加 token 字段
        dto.setInputTokens(message.getInputTokens());
        dto.setOutputTokens(message.getOutputTokens());
        dto.setCreateTime(message.getCreateTime());
        return dto;
    }
}
