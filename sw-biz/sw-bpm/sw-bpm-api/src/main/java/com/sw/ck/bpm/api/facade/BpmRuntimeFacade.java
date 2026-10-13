package com.sw.ck.bpm.api.facade;

import com.sw.ck.bpm.api.dto.BpmActivityDTO;
import com.sw.ck.bpm.api.result.BpmProcessStatus;
import com.sw.ck.bpm.api.result.MutationOutcome;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * BPM 运行时门面 —— 封装流程引擎 RuntimeService。
 * <p>
 * 定义流程启动、活跃节点查询、历史活动查询等运行时操作契约。
 * 实现类位于 sw-bpm-engine（闭源），由 Spring 注入。
 * </p>
 * <p>
 * 模块内部调用边界统一返回非空 {@link Optional}：{@code Optional.empty()} 只表达
 * "查询目标/上下文不存在"（实例未部署、实例不存在、查询上下文缺失）；
 * 请求非法、引擎执行失败继续抛明确异常，不得以 empty 吞异常。
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
     * @return present = 流程实例 ID；empty = 该 key/租户下没有已发布的流程定义（启动目标缺失，
     *         未产生实例）；参数非法（缺少 key/租户）与引擎执行失败继续抛明确异常
     */
    Optional<String> startProcess(String processDefKey, String businessKey,
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
     * @return present = 活跃节点 activity ID 列表（实例存在但当前无活跃节点时为空列表，
     *         属合法零匹配）；empty = 实例标识缺失，或该实例不存在运行期记录（已结束/不存在），
     *         无法给出活跃节点
     */
    Optional<List<String>> getActiveActivityIds(String processInstanceId);

    /**
     * 查询流程实例的全部历史活动节点（含已完成 + 进行中）。
     * <p>
     * 按结束时间升序排列（配流转时间线从上到下展示）。
     * 同时返回已完成节点（有 endTime）和进行中节点（endTime=null），
     * 前端据此区分：已完成节点 灰色、进行中节点 绿色。
     * </p>
     *
     * @param processInstanceId Flowable 流程实例 ID
     * @return present = 活动节点列表（按结束时间升序，进行中节点排在末尾；实例存在但无历史活动时为空列表）；
     *         empty = 实例标识缺失或该实例无历史记录
     */
    Optional<List<BpmActivityDTO>> queryHistoricActivities(String processInstanceId);

    /**
     * 读取流程实例全部变量（P21 G3b：流程侧表单详情回查）。
     *
     * @param processInstanceId 流程实例 ID
     * @return present = 变量名 → 值（实例存在但无变量时为空 Map）；empty = 实例标识缺失或
     *         该实例在运行期与历史均不存在
     */
    Optional<Map<String, Object>> getProcessVariables(String processInstanceId);

    /**
     * 读取流程实例状态（I4 §3.4 外部状态查询口径）。
     *
     * @param processInstanceId 流程实例 ID
     * @return present = 状态（含运行中与各终态）；empty = 实例不存在（原 "NOT_FOUND" 字符串哨兵已移除）
     */
    Optional<BpmProcessStatus> getProcessInstanceStatus(String processInstanceId);

    /**
     * 唤醒等待节点（P64 阶段Ⅱ A05：SUBFLOW_WAIT ReceiveTask；子流程批次结算后推进父主链）。
     * <p>
     * 幂等：该实例当前没有停留在指定等待节点的执行流时返回 ALREADY_ADVANCED，不视为失败；
     * 实例不存在/未运行时 empty。并发重复唤醒由引擎侧吸收。
     * </p>
     *
     * @param processInstanceId 父流程实例 ID
     * @param activityId        等待节点 BPMN 元素 ID
     * @return present = MutationOutcome（APPLIED=已唤醒；ALREADY_APPLIED=无等待执行流，幂等吸收）；
     *         empty = 实例标识缺失或实例不在运行期
     */
    Optional<MutationOutcome> signalWaitNode(String processInstanceId, String activityId);
}
