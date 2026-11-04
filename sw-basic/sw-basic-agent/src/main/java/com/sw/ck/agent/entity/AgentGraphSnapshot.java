package com.sw.ck.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent 图发布快照（D022-06）—— 对应 {@code sw_agent_graph_snapshot} 表（V0.2.3）。
 * <p>
 * 每次发布把当时的完整 {@code ProcessGraph} 固化一行；执行与调试按
 * {@code graphDefId + defVersion} 读取快照，不再读取可被草稿保存覆盖的
 * {@code sw_agent_graph_def.graph_json}。快照不可修改、不单独删除。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_agent_graph_snapshot")
public class AgentGraphSnapshot extends BaseEntity {

    /** 图定义 id（sw_agent_graph_def） */
    private Long graphDefId;

    /** 发布版本号（与 sw_agent_graph_def.def_version 对齐） */
    private Integer defVersion;

    /** 发布时图业务 key */
    private String graphKey;

    /** 发布时图名称 */
    private String name;

    /** 该版本的完整 ProcessGraph JSON 快照 */
    private String graphJson;
}
