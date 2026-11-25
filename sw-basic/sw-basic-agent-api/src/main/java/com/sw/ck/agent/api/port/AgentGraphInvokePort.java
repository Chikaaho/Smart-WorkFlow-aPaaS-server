package com.sw.ck.agent.api.port;

import java.util.List;
import java.util.Optional;

/**
 * Graph 执行器跨模块调用端口（1.2.0 P67：Graph 作为执行器）。
 *
 * <p>消费方（sw-bpm 流程服务节点、sw-biz-form 服务端动作）经本端口以<b>固定版本</b>
 * 同步调用已发布 Graph；租户隔离由实现侧租户拦截器按当前
 * {@code LoginUserHolder} 上下文强制（跨租户一律按不存在处理，发布期与运行期双校验）。
 * 同步调用方须先设置系统身份（userId={@code 0}、username 标注来源、tenantId=业务租户），
 * 与 IoT 桥（AgentGraphTriggerListener）同一口径。</p>
 *
 * <p>契约对齐 sw-basic-iot-api：仅使用 JDK 类型，不依赖 sw-common。</p>
 */
public interface AgentGraphInvokePort {

    /**
     * 发布校验用绑定描述：租户内按 id + 固定版本解析已发布图（读不可变版本快照）。
     * 不存在、跨租户、未发布或版本无快照时返回 empty（发布校验按「不可绑定」报错）。
     */
    Optional<AgentGraphVersionBinding> describeBinding(Long graphDefId, Integer defVersion);

    /**
     * 同步调用：受理（幂等键去重、有界队列）→ 有界等待终态 → 返回结果。
     * 超时（不超过图自身执行超时上限）时尽力取消并返回超时失败；
     * 图进入人工等待（发布期已拒绝，此处为运行期兜底）按失败返回。
     * 不抛业务异常，失败语义全部经 {@link AgentGraphInvocationResult} 表达。
     */
    AgentGraphInvocationResult invoke(AgentGraphInvocation invocation);

    /** E1-7：按流程实例 ID 反查关联执行（租户内，新在前）。 */
    List<AgentGraphExecutionBrief> listByProcessInstance(String processInstanceId);

    /** E1-7：按表单记录 ID 反查关联执行（租户内，新在前）。 */
    List<AgentGraphExecutionBrief> listByFormRecord(Long formRecordId);
}
