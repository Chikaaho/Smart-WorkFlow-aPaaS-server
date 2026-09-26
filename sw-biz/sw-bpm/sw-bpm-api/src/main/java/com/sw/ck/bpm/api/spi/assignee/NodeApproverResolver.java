package com.sw.ck.bpm.api.spi.assignee;

import java.util.List;
import java.util.Optional;

/**
 * 节点审批人解析 SPI —— 供 TaskListener 运行时分配审批人。
 * <p>
 * 定义在 {@code sw-bpm-api}（system.md §1 跨模块通信），
 * 各实现（固定审批人、角色、脚本等）注册于 {@code sw-bpm-engine}，
 * 经 {@code Map<String, NodeApproverResolver>} 按 {@link NodeApproverType} 分发。
 * </p>
 * <p>
 * 签名零 Flowable：不 import 任何 Flowable 类型。
 * </p>
 *
 * @see NodeApproverContext
 * @see NodeApproverType
 */
public interface NodeApproverResolver {

    /**
     * 根据任务上下文解析审批人用户 ID 列表。
     *
     * @param context 任务节点上下文（租户、流程实例、节点 key、formKey、approver 配置值等）
     * @return present = 审批人用户 ID 列表（字符串形式，对应 Flowable assignee）；当前契约恒 present，
     *         解析结果为空列表表示该配置无可用审批人（交由调用方按发布/运行策略处置），
     *         解析失败抛明确异常，不得返回空值
     * @throws IllegalArgumentException 解析失败时抛出包含明确错误码的异常
     */
    Optional<List<String>> resolve(NodeApproverContext context);
}
