package com.sw.ck.agent.event;

import java.io.Serializable;

/**
 * Agent 图执行终态事件（finish() 落库成功后发布，进程内 Spring 事件）。
 * <p>
 * 供跨模块联动方（如 IoT 失败升级承接）在执行到达终态后采取后续动作；
 * 发布方不关心消费结果，消费方自行保证幂等（重复消费不得产生重复副作用）。
 * CANCELLED/INTERRUPTED 也会发布，消费方按 status 自行过滤。
 * </p>
 */
public class AgentGraphExecutionTerminalEvent implements Serializable {

    /** 执行请求 id（sw_agent_graph_execution_request.id） */
    private Long executionId;

    /** 图定义 id */
    private Long graphDefId;

    /** 租户 */
    private Long tenantId;

    /** 发起人（字符串化 userId） */
    private String operatorId;

    /** 终态：SUCCESS / FAILED / CANCELLED / INTERRUPTED */
    private String status;

    /** 失败类别（FAILED 时） */
    private String errorCategory;

    /** 失败信息（FAILED 时） */
    private String errorMessage;

    public AgentGraphExecutionTerminalEvent() {
    }

    public AgentGraphExecutionTerminalEvent(Long executionId, Long graphDefId, Long tenantId,
                                            String operatorId, String status,
                                            String errorCategory, String errorMessage) {
        this.executionId = executionId;
        this.graphDefId = graphDefId;
        this.tenantId = tenantId;
        this.operatorId = operatorId;
        this.status = status;
        this.errorCategory = errorCategory;
        this.errorMessage = errorMessage;
    }

    public Long getExecutionId() {
        return executionId;
    }

    public Long getGraphDefId() {
        return graphDefId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public String getOperatorId() {
        return operatorId;
    }

    public String getStatus() {
        return status;
    }

    public String getErrorCategory() {
        return errorCategory;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
