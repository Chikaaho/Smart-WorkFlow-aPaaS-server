package com.sw.ck.bpm.process.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 审批历史条目 DTO。
 * <p>
 * 代表一个已完成的审批节点，记录谁在什么时候做了审批。
 * </p>
 */
@Data
public class ApprovalHistoryItemDTO {

    /** Flowable task ID */
    private String taskId;

    /** 任务名称（如"审批"） */
    private String taskName;

    /** 稳定节点标识；退回只能选择已经过的人工节点。 */
    private String nodeKey;

    /** 处理人用户 ID */
    private String assignee;
    /** 审批人展示名（可读身份回显） */
    private String assigneeName;

    /** 任务创建时间 */
    private LocalDateTime createTime;

    /** 任务完成时间 */
    private LocalDateTime endTime;

    /** APPROVE / RETURN / REJECT；旧 Flowable 历史没有该字段时为 null。 */
    private String action;

    /** APPROVED / REJECTED；退回及旧历史数据为 null。 */
    private String approvalResult;

    /** 不可变审批意见快照。 */
    private Map<String, Object> opinionData;

    /** 审批动作记录标识：意见附件受控下载的定位键（旧响应缺省）。 */
    private Long actionRecordId;

    private String opinionFormId;
    private String opinionFormVersion;

    /**
     * 意见表单字段展示视图（按提交时冻结的表单配置顺序，供详情/流转记录直接渲染）。
     * <p>
     * 已解析人员姓名、本地时间格式与多选拼接；旧动作记录缺快照或未填意见时为空列表。
     * </p>
     */
    private List<OpinionFieldView> opinionFields;

    /** 单个意见字段的展示视图：中文标签 + 原始值 + 可直接展示的文本。 */
    @Data
    public static class OpinionFieldView {
        /** 字段键（意见数据键名，契约定位用） */
        private String name;
        /** 中文标签（冻结表单配置） */
        private String label;
        /** 字段类型（RADIO/USER/TEXTAREA/CHECKBOX/NUMBER/DATE/DATETIME…） */
        private String type;
        /** 原始值（人员为 ID、多选为数组；与 opinionData 同源） */
        private Object value;
        /** 展示文本：人员姓名、本地时间、多选拼接 */
        private String displayValue;
    }
}

