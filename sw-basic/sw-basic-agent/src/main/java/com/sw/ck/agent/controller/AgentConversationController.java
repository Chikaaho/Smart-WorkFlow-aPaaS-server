package com.sw.ck.agent.controller;

import org.springframework.beans.factory.annotation.Autowired;

import com.sw.ck.agent.dto.AgentConversationDTO;
import com.sw.ck.agent.dto.AgentConversationMessageDTO;
import com.sw.ck.agent.service.AgentConversationService;
import com.sw.ck.common.response.R;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话查询 Controller（M07 Step4 F04，两个只读端点）。
 * <p>
 * 权限码沿用 {@code agent:model:view}（只读操作，不新增权限码，对齐 D51 三段拆分：
 * 查询不属于 manage 级别）。响应统一 {@code R<T>} 包装。
 * </p>
 */
@RestController
@RequestMapping("/agent/conversations")
public class AgentConversationController {

    @Autowired
    private AgentConversationService agentConversationService;

    /** 会话列表（当前租户 + 当前用户，按 create_time 倒序；可按配置 id 过滤） */
    @GetMapping
    @PreAuthorize("@ss.hasPermi('agent:model:view')")
    public R<List<AgentConversationDTO>> list(
            @RequestParam(required = false) Long agentModelConfigId) {
        return R.ok(agentConversationService.listConversations(agentModelConfigId));
    }

    /** 会话内消息列表（按 msg_order 升序，不分页；会话不存在/跨租户 → 404 语义） */
    @GetMapping("/{sessionId}/messages")
    @PreAuthorize("@ss.hasPermi('agent:model:view')")
    public R<List<AgentConversationMessageDTO>> messages(@PathVariable Long sessionId) {
        return R.ok(agentConversationService.listMessages(sessionId));
    }
}
