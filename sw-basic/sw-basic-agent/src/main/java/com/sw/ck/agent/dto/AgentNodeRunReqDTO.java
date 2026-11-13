package com.sw.ck.agent.dto;

import lombok.Data;

import java.util.Map;

/**
 * 单节点运行请求（G1-8）：在已发布图的快照上单独执行一个节点。
 * <p>
 * 不读写图定义、不产生正式执行记录（调试语义）；{@code inputs} 为该节点运行时
 * 可见的变量表（变量名 → 值），至少应包含节点 inputVar 所需变量。
 * </p>
 */
@Data
public class AgentNodeRunReqDTO {

    /** 图定义 id */
    private Long graphDefId;

    /** 目标节点 id（必须存在于图快照中） */
    private String nodeId;

    /** 调试变量表（变量名 → 值） */
    private Map<String, String> inputs;
}
