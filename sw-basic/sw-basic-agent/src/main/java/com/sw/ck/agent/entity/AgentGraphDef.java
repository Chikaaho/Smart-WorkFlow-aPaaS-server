package com.sw.ck.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent 图定义实体（M07-F02 Step7）—— 对应 {@code sw_agent_graph_def} 表（V25）。
 * <p>
 * 存储图设计器的图模型（{@code ProcessGraph} JSON，见 {@code com.sw.ck.agent.dto.graph}），
 * 本 Step 纯存储+管理骨架，无执行语义。继承 {@link BaseEntity}（含
 * id/tenantId/createTime/createBy/updateTime/updateBy/deleted/version），id 由
 * MyBatis-Plus {@code IdType.ASSIGN_ID} 生成雪花 ID，审计字段由
 * {@code CommonMetaObjectHandler} 填充。
 * </p>
 * <p>
 * 生命周期（对齐 sw-bpm {@code BpmProcessDef} 先例）：create 生成 {@code agent_} 前缀
 * graphKey + 初始图（START→END）+ defVersion=1 + DRAFT；草稿保存全量覆盖 graph_json
 * 不跑校验；发布递增 defVersion、置 PUBLISHED、graph_key 冻结（已发布后不可变更）。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_agent_graph_def")
public class AgentGraphDef extends BaseEntity {

    /** 图业务 key（服务端生成 agent_ 前缀，租户内唯一，发布后冻结） */
    private String graphKey;

    /** 图名称 */
    private String name;

    /** 定义版本号（每次发布递增，DB 默认 1） */
    private Integer defVersion;

    /** 状态：DRAFT（草稿）/ PUBLISHED（已发布） */
    private String status;

    /** 图 JSON 文档（ProcessGraph 序列化，节点 config/style 不透明透传） */
    private String graphJson;

    /** 图级执行超时（秒，DB 默认 300；执行超时置 FAILED 并返回超时分类） */
    private Integer executionTimeoutSeconds;
}
