package com.sw.ck.bpm.api.facade;

import com.sw.ck.bpm.api.dto.BpmActivityDTO;

import java.util.List;
import java.util.Map;

/**
 * BPM 运行时门面 —— 封装流程引擎 RuntimeService。
 * <p>
 * 定义流程启动、活跃节点查询、历史活动查询等运行时操作契约。
 * 实现类位于 sw-bpm-engine（闭源），由 Spring 注入。
 * </p>
 *
 * @since 1.0.0
 */
public interface BpmRuntimeFacade {

    /**
     * 启动流程实例。
     *
     * @param processDefKey 流程定义 key
     * @param businessKey   业务键（如表单 recordId）
     * @param variables     流程变量
     * @param tenantId      租户 ID
     * @return 流程实例 ID
     */
    String startProcess(String processDefKey, String businessKey,
                        Map<String, Object> variables, String tenantId);

    /**
     * 获取流程实例当前活跃节点 ID 列表。
     * <p>
     * 活跃节点 = Flowable Runtime 中尚未完成的 Activity 实例。
     * 前端直接用返回的 activityId 调用 bpmn-js highlight() 高亮对应 BPMN 元素。
     * 返回顺序无保证，按 Flowable 内部执行顺序。
     * </p>
     *
     * @param processInstanceId Flowable 流程实例 ID
     * @return 活跃节点 activity ID 列表（实例不存在或已结束时返回空列表，不抛异常）
     */
    List<String> getActiveActivityIds(String processInstanceId);

    /**
     * 查询流程实例的全部历史活动节点（含已完成 + 进行中）。
     * <p>
     * 按结束时间升序排列（配流转时间线从上到下展示）。
     * 同时返回已完成节点（有 endTime）和进行中节点（endTime=null），
     * 前端据此区分：已完成节点 灰色、进行中节点 绿色。
     * </p>
     *
     * @param processInstanceId Flowable 流程实例 ID
     * @return 活动节点列表（按结束时间升序，进行中节点排在末尾），实例不存在时返回空列表
     */
    List<BpmActivityDTO> queryHistoricActivities(String processInstanceId);

    /**
     * 读取流程实例全部变量（P21 G3b：流程侧表单详情回查）。
     *
     * @param processInstanceId 流程实例 ID
     * @return 变量名 → 值；实例不存在返回空 Map
     */
    java.util.Map<String, Object> getProcessVariables(String processInstanceId);

    /**
     * 读取流程实例状态（I4 §3.4 外部状态查询口径）。
     *
     * @param processInstanceId 流程实例 ID
     * @return RUNNING / APPROVED / REJECTED / FAILED / WITHDRAWN / DISCARDED / TERMINATED / UNKNOWN；
     *         实例不存在返回 "NOT_FOUND"
     */
    String getProcessInstanceStatus(String processInstanceId);
}
