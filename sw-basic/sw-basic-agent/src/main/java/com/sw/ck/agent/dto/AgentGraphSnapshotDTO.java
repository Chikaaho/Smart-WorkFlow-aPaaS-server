package com.sw.ck.agent.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 图发布版本列表项 DTO（D022-06，只读端点）。
 * <p>
 * 不含 {@code graphJson} 大字段；图内容经执行/调试按版本绑定，不在版本列表回传。
 * </p>
 */
@Data
public class AgentGraphSnapshotDTO {

    /** 发布版本号 */
    private Integer defVersion;

    /** 发布时图业务 key */
    private String graphKey;

    /** 发布时图名称 */
    private String name;

    /** 发布时间（快照创建时间） */
    private LocalDateTime createTime;
}
