package com.sw.ck.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent 图执行记录（M07 Step12 图执行历史持久化）—— 对应
 * {@code sw_agent_graph_execution} 表（V27）。
 * <p>
 * 每次图执行（{@code POST /agent/graph-defs/{id}/execute}）进入执行阶段即建一行
 * {@code RUNNING}，执行后回写终态 {@code SUCCESS} / {@code FAILED}——成功与失败两类
 * 路径均落库（区别于 F04 只在成功分支写入）。状态用字符串（varchar + String，
 * 仓库惯例，不建 enum）；大文本列对应 H2=CLOB / PG=TEXT。
 * </p>
 * <p>
 * 错误分类 {@code errorCategory} 由解释器 {@code GraphExecutionException} 携带
 * （STEP_LIMIT / LOOP_LIMIT / UNDEFINED_VARIABLE / CONDITION_NO_MATCH /
 * TOPOLOGY_INVALID / MODEL_CALL_FAILED / TOOL_CALL_FAILED / UNKNOWN），不靠文本匹配。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_agent_graph_execution")
public class AgentGraphExecution extends BaseEntity {

    /** 图定义 id（sw_agent_graph_def） */
    private Long graphDefId;

    /** 执行时图定义版本快照（发布锚点） */
    private Integer graphDefVersion;

    /** 父执行记录 id（G3 子图节点；顶层执行为 null） */
    private Long parentExecutionId;

    /** 执行状态：RUNNING（执行前建行）/ SUCCESS / FAILED */
    private String status;

    /** 执行入参文本（大文本） */
    private String input;

    /**
     * 最终输出文本（成功时）。列名 {@code result_text}：避开 {@code output} 保留字
     * （MyBatis-Plus 租户拦截器 JSqlParser 解析 UPDATE SET 时 output 为非法 token，
     * 实测踩坑，Step12 执行回执 §6 记录）。
     */
    private String resultText;

    /** 错误分类（失败时，解释器携带） */
    private String errorCategory;

    /** 失败原因摘要（不含明文 API Key） */
    private String errorMessage;

    /** 整次执行耗时（毫秒） */
    private Long latencyMs;

    /** 本次图执行全部 LLM 节点输入 Token 汇总（未知时不参与计算） */
    private Long inputTokens;

    /** 本次图执行全部 LLM 节点输出 Token 汇总（未知时不参与计算） */
    private Long outputTokens;
}
