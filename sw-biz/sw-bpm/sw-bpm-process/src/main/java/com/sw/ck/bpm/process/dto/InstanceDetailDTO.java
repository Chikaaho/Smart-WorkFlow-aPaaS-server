package com.sw.ck.bpm.process.dto;

import com.sw.ck.bpm.api.dto.BpmActivityDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 流程实例详情响应 DTO。
 * <p>
 * 在 {@link InstanceListItemDTO} 字段基础上追加：
 * <ul>
 *   <li>{@code activeNodeIds} — 当前活跃节点 activity ID 列表（用于 bpmn-js 绿色高亮）</li>
 *   <li>{@code flowTrace} — 全部历史活动节点（用于流转时间线展示）</li>
 * </ul>
 * 两个列表均可能为空（已结束的实例 activeNodeIds 为空；刚启动的实例 flowTrace 可能仅含开始事件）。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class InstanceDetailDTO extends InstanceListItemDTO {

    /** 当前活跃节点 activity ID 列表（用于流程图绿色高亮）。实例已结束时为空列表 */
    private List<String> activeNodeIds;

    /** 全部历史活动节点（含已完成 + 进行中），按结束时间升序。进行中节点 endTime=null 排末尾 */
    private List<BpmActivityDTO> flowTrace;

    /**
     * 流程变量中的表单数据（P21 G3b：IoT 触发流程的表单详情回查）。
     */
    private java.util.Map<String, Object> formData;
}
