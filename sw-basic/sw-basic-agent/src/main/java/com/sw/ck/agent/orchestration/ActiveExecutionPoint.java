package com.sw.ck.agent.orchestration;

/**
 * 活跃执行点（Step11 多活跃点模型 + Step12 分支路径）：节点 id + 分支路径。
 * <p>
 * 自 AgentGraphInterpreter 私有嵌套类调整为同包包级可见（行为不变）：解释器主循环
 * 与人工/子图节点暂停状态构造都需要直接读写其字段。
 * </p>
 */
class ActiveExecutionPoint {

    final String nodeId;

    final String branchPath;

    ActiveExecutionPoint(String nodeId, String branchPath) {
        this.nodeId = nodeId;
        this.branchPath = branchPath;
    }
}
