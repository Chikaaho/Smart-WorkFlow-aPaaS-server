package com.sw.ck.bpm.process.vo;

import lombok.Data;

/**
 * 「我发起的」详情中的当前活动任务节点。
 * <p>
 * 字段与历史 Map 契约一致；任务名、节点 key、办理人为 null 时保持空串，
 * 避免前端对 null 做额外兜底。
 * </p>
 */
@Data
public class MyInstanceProgressNodeVO {

    private String taskId;

    private String taskName;

    private String nodeKey;

    private String assignee;
}
