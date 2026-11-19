package com.sw.ck.agent.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话列表响应项（M07 Step4 F04，只读端点）。
 */
@Data
public class AgentConversationVO {

    /** 会话 id */
    private Long id;

    /** 大模型接入配置 id */
    private Long agentModelConfigId;

    /** 会话标题（自动生成留后续迭代，当前为 null） */
    private String title;

    /** 会话状态（ACTIVE） */
    private String status;

    /** 创建时间 */
    private LocalDateTime createTime;
}
