package com.sw.ck.bpm.process.vo;

import com.sw.ck.agent.api.port.AgentGraphExecutionBrief;
import com.sw.ck.bpm.api.vo.BpmActivityVO;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import com.sw.ck.common.value.DynamicData;

/**
 * 流程实例详情响应 DTO。
 * <p>
 * 在 {@link InstanceListItemVO} 字段基础上追加：
 * <ul>
 *   <li>{@code activeNodeIds} — 当前活跃节点 activity ID 列表（用于 bpmn-js 绿色高亮）</li>
 *   <li>{@code flowTrace} — 全部历史活动节点（用于流转时间线展示）</li>
 *   <li>{@code graphExecutions} — 关联的 Graph 执行摘要（1.2.0 E1-7：Graph 执行器节点触发的
 *       执行编号/耗时/结果，可跳转 Graph 执行详情；无关联或智能体模块未装配时为 null）</li>
 * </ul>
 * 两个列表均可能为空（已结束的实例 activeNodeIds 为空；刚启动的实例 flowTrace 可能仅含开始事件）。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class InstanceDetailVO extends InstanceListItemVO {

    /** 当前活跃节点 activity ID 列表（用于流程图绿色高亮）。实例已结束时为空列表 */
    private List<String> activeNodeIds;

    /** 全部历史活动节点（含已完成 + 进行中），按结束时间升序。进行中节点 endTime=null 排末尾 */
    private List<BpmActivityVO> flowTrace;

    /**
     * 流程变量中的表单数据（P21 G3b：IoT 触发流程的表单详情回查）。
     */
    private DynamicData formData;

    /** 关联 Graph 执行摘要（1.2.0 E1-7，新在前；智能体模块未装配时保持 null） */
    private List<AgentGraphExecutionBrief> graphExecutions;
}
