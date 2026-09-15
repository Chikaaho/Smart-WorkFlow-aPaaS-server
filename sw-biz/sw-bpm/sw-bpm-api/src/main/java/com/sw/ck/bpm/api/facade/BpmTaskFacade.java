package com.sw.ck.bpm.api.facade;

import com.sw.ck.bpm.api.dto.BpmTaskDTO;

import java.util.List;
import java.util.Map;

/**
 * BPM 任务门面 —— 封装流程引擎 TaskService + 部分 RuntimeService 查询。
 * <p>
 * 定义待办查询、任务完成、流程状态查询等操作契约。
 * 实现类位于 sw-bpm-engine（闭源），由 Spring 注入。
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
     * @return 待办任务列表
     */
    List<BpmTaskDTO> queryTodo(String tenantId, String assignee);

    /**
     * 分页查询待办任务。
     *
     * @param tenantId 租户 ID
     * @param assignee 处理人
     * @param offset   偏移量
     * @param limit    每页条数
     * @return 待办任务列表
     */
    List<BpmTaskDTO> queryTodoPage(String tenantId, String assignee, int offset, int limit);

    /**
     * 统计待办任务总数。
     *
     * @param tenantId 租户 ID
     * @param assignee 处理人
     * @return 待办任务总数
     */
    long countTodo(String tenantId, String assignee);

    /**
     * 按流程实例 id 精确查询该实例下的任务（发起后取任务、实例任务列表用）。
     *
     * @param processInstanceId 流程实例 ID
     * @return 任务列表
     */
    List<BpmTaskDTO> queryByProcessInstance(String processInstanceId);

    /**
     * 获取单个任务详情。
     *
     * @param taskId 任务 ID
     * @return 任务 DTO
     */
    BpmTaskDTO getTask(String taskId);

    /**
     * 完成任务。
     *
     * @param taskId    任务 ID
     * @param variables 流程变量
     */
    void complete(String taskId, Map<String, Object> variables);

    /** 以当前用户身份原子认领并完成候选任务。 */
    void completeAsUser(String taskId, String userId, Map<String, Object> variables);

    /** 驳回后终止流程实例，确保没有未配置驳回分支时仍进入终态。 */
    void terminateProcess(String processInstanceId, String reason);

    /** 实例级挂起（I4 §3.3 运营干预）：运行中实例暂停推进，与定义级挂起互不混同。幂等。 */
    void suspendProcessInstance(String processInstanceId);

    /** 实例级恢复：解除挂起状态。幂等。 */
    void resumeProcessInstance(String processInstanceId);

    /** 查询实例运行时挂起状态：true=挂起。 */
    boolean isProcessInstanceSuspended(String processInstanceId);

    /** 判断用户是否是任务 assignee 或 candidate。 */
    boolean canHandle(String taskId, String userId);

    /** 将人工任务退回到已经过且被定义允许的节点。 */
    void returnTask(String taskId, String targetNodeId);

    /**
     * 判断流程实例是否活跃。
     *
     * @param processInstanceId 流程实例 ID
     * @return true 表示流程仍在运行
     */
    boolean isProcessActive(String processInstanceId);

    /**
     * 获取流程变量。
     *
     * @param processInstanceId 流程实例 ID
     * @param name              变量名
     * @return 变量值字符串
     */
    String getVariable(String processInstanceId, String name);

    /**
     * 获取流程实例的业务键。
     *
     * @param processInstanceId 流程实例 ID
     * @return 业务键
     */
    String getBusinessKey(String processInstanceId);

    /**
     * 获取流程实例的全部变量。
     *
     * @param processInstanceId 流程实例 ID
     * @return 流程变量 Map
     */
    Map<String, Object> getVariables(String processInstanceId);

    /** 写入受控流程结果变量（节点结果函数白名单出口，I3 §4.9）。 */
    void setVariable(String processInstanceId, String name, Object value);

    /**
     * 读取流程实例的历史变量（含已结束实例）。实例仍在运行时同样返回当前值。
     * 引擎无该实例历史时返回空映射，不抛异常（只读详情场景容忍缺失）。
     */
    Map<String, Object> getHistoricVariables(String processInstanceId);

    /**
     * 分页查询已办任务（历史任务）。
     *
     * @param tenantId 租户 ID
     * @param assignee 处理人
     * @param offset   偏移量（从 0 开始）
     * @param limit    每页条数
     * @return 已办任务列表（含 endTime）
     */
    List<BpmTaskDTO> queryProcessedPage(String tenantId, String assignee, int offset, int limit);

    /**
     * 统计已办任务总数。
     *
     * @param tenantId 租户 ID
     * @param assignee 处理人
     * @return 已办任务总数
     */
    long countProcessed(String tenantId, String assignee);

    /**
     * 查询流程实例的审批历史（所有已完成的历史任务节点）。
     *
     * @param processInstanceId 流程实例 ID
     * @return 历史任务列表（按完成时间倒序）
     */
    List<BpmTaskDTO> queryHistoryByProcessInstance(String processInstanceId);

    // ==================== I3 任务生命周期（转办/委托/代理） ====================

    /**
     * 转办：将任务 assignee 改为另一有效办理人；转出人失去当前办理权，
     * 任务对象不被复制成双活。要求任务存在且当前活跃。
     */
    void setAssignee(String taskId, String userId);

    /**
     * 委托：保留原责任人（owner），受托人完成引导回原责任人的
     * Flowable delegation 语义（PENDING → 完成后回到 owner RESOLVED）。
     */
    void delegateTask(String taskId, String userId);

    /**
     * 查询任务委托状态：delegated 状态返回 "DELEGATED"；owner 返回 ownerId。
     */
    String getTaskOwner(String taskId);

    /**
     * 追加候选办理人（加签 PARALLEL 投递）。
     */
    void addCandidateUser(String taskId, String userId);
}
