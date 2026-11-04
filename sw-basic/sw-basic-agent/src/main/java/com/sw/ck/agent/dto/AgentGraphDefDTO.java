package com.sw.ck.agent.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 图定义响应 DTO（列表/发布结果）。
 * <p>
 * 安全/性能约束：本 DTO <b>不含</b> {@code graphJson} 大字段——列表返回剥离
 * graph_json（对齐 sw-bpm listDefs 先例，DTO 编译期防线）；详情/回显走
 * {@code GET /agent/graph-defs/{id}} 返回解析后的 {@code ProcessGraph} 对象。
 * </p>
 */
@Data
public class AgentGraphDefDTO {

    private Long id;

    /** 图业务 key（发布后冻结） */
    private String graphKey;

    /** 图名称 */
    private String name;

    /** 定义版本号（每次发布递增） */
    private Integer defVersion;

    /** 状态：DRAFT / PUBLISHED */
    private String status;

    /** 图级执行超时（秒） */
    private Integer executionTimeoutSeconds;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
