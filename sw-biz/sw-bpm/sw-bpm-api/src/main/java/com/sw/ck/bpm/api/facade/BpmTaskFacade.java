package com.sw.ck.bpm.api.facade;

import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.result.MutationOutcome;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * BPM 任务门面 —— 封装流程引擎 TaskService + 部分 RuntimeService 查询。
 * <p>
 * 定义待办查询、任务完成、流程状态查询等操作契约。
 * 实现类位于 sw-bpm-engine（闭源），由 Spring 注入。
 * </p>
 * <p>
 * 模块内部调用边界统一返回非空 {@link Optional}：{@code Optional.empty()} 只表达
 * "查询目标/上下文不存在"（任务或实例不存在、查询上下文缺失）；合法零匹配以
 * present 的空集合表达；参数非法、状态冲突（任务已被处理）、权限与引擎失败继续抛明确异常。
 * </p>
 *
 * @since 1.0.0
 */
public interface BpmTaskFacade {

    /**
     * 查询待办任务列表。
     *
     * @param tenantId 租户 ID
     * @param assignee 处理人
     * @return present = 待办任务列表（无匹配时为空列表，属合法零匹配）；
     *         empty = 租户或处理人上下文缺失，无法确定查询范围
     */
    Optional<List<BpmTaskDTO>> queryTodo(String tenantId, String assignee);

    /**
     * 分页查询待办任务。
     *
     * @param tenantId 租户 ID
     * @param assignee 处理人
     * @param offset   偏移量
     * @param limit    每页条数
     * @return present = 待办任务列表（该页零条为空列表）；empty = 租户或处理人上下文缺失
     */
    Optional<List<BpmTaskDTO>> queryTodoPage(String tenantId, String assignee, int offset, int limit);

    /**
     * 统计待办任务总数。
     *
     * @param tenantId 租户 ID
     * @param assignee 处理人
     * @return present = 待办任务总数（0 为合法零匹配）；empty = 租户或处理人上下文缺失
     */
    Optional<Long> countTodo(String tenantId, String assignee);

    /**
     * 按流程实例 id 精确查询该实例下的任务（发起后取任务、实例任务列表用）。
     *
     * @param processInstanceId 流程实例 ID
     * @return present = 任务列表（实例存在但无任务时为空列表）；empty = 实例标识缺失
     */
    Optional<List<BpmTaskDTO>> queryByProcessInstance(String processInstanceId);

    /**
     * 获取单个任务详情。
     *
     * @param taskId 任务 ID
     * @return present = 任务 DTO；empty = 该任务不存在（原 null 返回路径）
     */
    Optional<BpmTaskDTO> getTask(String taskId);

    /**
     * 完成任务。
     *
     * @param taskId    任务 ID
     * @param variables 流程变量
     * @return present = {@link MutationOutcome#APPLIED} 本次完成已生效；
     *         任务不存在或已被处理抛状态冲突异常（不以上空表达）
     */
    Optional<MutationOutcome> complete(String taskId, Map<String, Object> variables);

    /**
     * 以当前用户身份原子认领并完成候选任务。
     *
     * @return present = {@link MutationOutcome#APPLIED} 本次完成已生效；
     *         任务不存在、已被处理或当前用户无权处理抛明确异常
     */
    Optional<MutationOutcome> completeAsUser(String taskId, String userId, Map<String, Object> variables);

    /**
     * 驳回后终止流程实例，确保没有未配置驳回分支时仍进入终态。
     *
     * @return present = {@link MutationOutcome#APPLIED} 本次已终止运行实例 /
     *         {@link MutationOutcome#ALREADY_APPLIED} 该实例已无运行期记录（合法幂等）；
     *         实例标识缺失抛参数异常
     */
    Optional<MutationOutcome> terminateProcess(String processInstanceId, String reason);

    /**
     * 实例级挂起（I4 §3.3 运营干预）：运行中实例暂停推进，与定义级挂起互不混同。幂等。
     *
     * @return present = {@link MutationOutcome#APPLIED} 本次已挂起 /
     *         {@link MutationOutcome#ALREADY_APPLIED} 实例已挂起或已无运行期记录（合法幂等）；
     *         实例标识缺失抛参数异常
     */
    Optional<MutationOutcome> suspendProcessInstance(String processInstanceId);

    /**
     * 实例级恢复：解除挂起状态。幂等。
     *
     * @return present = {@link MutationOutcome#APPLIED} 本次已恢复 /
     *         {@link MutationOutcome#ALREADY_APPLIED} 实例未挂起或已无运行期记录（合法幂等）；
     *         实例标识缺失抛参数异常
     */
    Optional<MutationOutcome> resumeProcessInstance(String processInstanceId);

    /**
     * 查询实例运行时挂起状态：true=挂起。
     *
     * @return present = 判定结果（true 挂起 / false 未挂起或已无运行期记录）；
     *         empty = 实例标识缺失，无法判定
     */
    Optional<Boolean> isProcessInstanceSuspended(String processInstanceId);

    /**
     * 判断用户是否是任务 assignee 或 candidate。
     *
     * @return present = 判定结果（true 可处理 / false 不可处理，含任务不存在）；
     *         empty = 任务标识或用户标识缺失，无法判定
     */
    Optional<Boolean> canHandle(String taskId, String userId);

    /**
     * 将人工任务退回到已经过且被定义允许的节点。
     *
     * @return present = {@link MutationOutcome#APPLIED} 本次退回已生效；
     *         任务不存在、目标节点非法或未经过抛明确异常
     */
    Optional<MutationOutcome> returnTask(String taskId, String targetNodeId);

    /**
     * 判断流程实例是否活跃。
     *
     * @param processInstanceId 流程实例 ID
     * @return present = 判定结果（true 仍在运行 / false 已结束或不存在）；
     *         empty = 实例标识缺失，无法判定
     */
    Optional<Boolean> isProcessActive(String processInstanceId);

    /**
     * 获取流程变量。
     *
     * @param processInstanceId 流程实例 ID
     * @param name              变量名
     * @return present = 变量值字符串（值存在即 present）；empty = 运行期与历史均无该变量
     */
    Optional<String> getVariable(String processInstanceId, String name);

    /**
     * 获取流程实例的业务键。
     *
     * @param processInstanceId 流程实例 ID
     * @return present = 业务键；empty = 运行期与历史均无该实例，或该实例无业务键
     */
    Optional<String> getBusinessKey(String processInstanceId);

    /**
     * 获取流程实例的全部变量。
     *
     * @param processInstanceId 流程实例 ID
     * @return present = 流程变量 Map（实例存在但无变量时为空 Map）；empty = 实例标识缺失或
     *         该实例不存在
     */
    Optional<Map<String, Object>> getVariables(String processInstanceId);

    /**
     * 写入受控流程结果变量（节点结果函数白名单出口，I3 §4.9）。
     *
     * @return present = {@link MutationOutcome#APPLIED} 本次写入已生效；
     *         实例不存在抛明确异常
     */
    Optional<MutationOutcome> setVariable(String processInstanceId, String name, Object value);

    /**
     * 读取流程实例的历史变量（含已结束实例）。实例仍在运行时同样返回当前值。
     *
     * @param processInstanceId 流程实例 ID
     * @return present = 历史变量 Map（引擎无该实例历史时为空 Map，属合法零匹配）；
     *         当前契约恒 present
     */
    Optional<Map<String, Object>> getHistoricVariables(String processInstanceId);

    /**
     * 分页查询已办任务（历史任务）。
     *
     * @param tenantId 租户 ID
     * @param assignee 处理人
     * @param offset   偏移量（从 0 开始）
     * @param limit    每页条数
     * @return present = 已办任务列表（含 endTime；该页零条为空列表）；
     *         empty = 租户或处理人上下文缺失
     */
    Optional<List<BpmTaskDTO>> queryProcessedPage(String tenantId, String assignee, int offset, int limit);

    /**
     * 统计已办任务总数。
     *
     * @param tenantId 租户 ID
     * @param assignee 处理人
     * @return present = 已办任务总数（0 为合法零匹配）；empty = 租户或处理人上下文缺失
     */
    Optional<Long> countProcessed(String tenantId, String assignee);

    /**
     * 查询流程实例的审批历史（所有已完成的历史任务节点）。
     *
     * @param processInstanceId 流程实例 ID
     * @return present = 历史任务列表（按完成时间倒序；无历史时为空列表）；
     *         empty = 实例标识缺失
     */
    Optional<List<BpmTaskDTO>> queryHistoryByProcessInstance(String processInstanceId);

    // ==================== I3 任务生命周期（转办/委托/代理） ====================

    /**
     * 转办：将任务 assignee 改为另一有效办理人；转出人失去当前办理权，
     * 任务对象不被复制成双活。要求任务存在且当前活跃。
     *
     * @return present = {@link MutationOutcome#APPLIED} 本次转办已生效；
     *         任务不存在或已被处理抛状态冲突异常
     */
    Optional<MutationOutcome> setAssignee(String taskId, String userId);

    /**
     * 委托：保留原责任人（owner），受托人完成引导回原责任人的
     * Flowable delegation 语义（PENDING → 完成后回到 owner RESOLVED）。
     *
     * @return present = {@link MutationOutcome#APPLIED} 本次委托已生效；
     *         任务不存在、已被处理或已在委托链中抛明确异常
     */
    Optional<MutationOutcome> delegateTask(String taskId, String userId);
}
