package com.sw.ck.agent.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话消息响应项（M07 Step4 F04，只读端点；按 msg_order 升序返回）。
 */
@Data
public class AgentConversationMessageVO {

    /** 消息 id */
    private Long id;

    /** 消息角色：USER / ASSISTANT */
    private String role;

    /** 消息内容 */
    private String content;

    /** 会话内顺序号（0-based） */
    private Integer msgOrder;

    /** 供应商返回的输入 Token 数（未知时为 null，不为 0） */
    private Long inputTokens;

    /** 供应商返回的输出 Token 数（未知时为 null，不为 0） */
    private Long outputTokens;

    /** 创建时间 */
    private LocalDateTime createTime;
}
