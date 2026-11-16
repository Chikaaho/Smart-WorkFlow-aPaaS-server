package com.sw.ck.agent.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * 图调试会话 DTO（M07-F02-04 单步调试）。
 */
@Data
public class AgentGraphDebugSessionVO {

    private Long id;

    private Long graphDefId;

    private Integer graphDefVersion;

    private String status;

    private String input;

    /** 断点集合（nodeId 集合） */
    private Set<String> breakpoints;

    /** 当前变量表快照（从 stateJson 解析） */
    private Map<String, String> variables;

    /** 已执行节点数（debug_node 计数） */
    private Integer traceCount;

    /** 下一个待执行节点 id（activePoints 队首，PAUSED 时有效，终态为 null） */
    private String nextNodeId;

    /** 下一个待执行分支标识（activePoints 队首 branchPath） */
    private String nextBranchId;

    private String resultText;

    private String errorCategory;

    private String errorMessage;

    private Long latencyMs;

    private Long inputTokens;

    private Long outputTokens;

    private LocalDateTime expiresAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long version;

    /** 是否终态（COMPLETED / FAILED / STOPPED / EXPIRED） */
    public boolean isTerminal() {
        return "COMPLETED".equals(status)
                || "FAILED".equals(status)
                || "STOPPED".equals(status)
                || "EXPIRED".equals(status);
    }
}
