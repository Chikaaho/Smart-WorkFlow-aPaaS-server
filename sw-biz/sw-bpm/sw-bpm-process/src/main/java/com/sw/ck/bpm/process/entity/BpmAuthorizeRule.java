package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 授权代理规则（I3 §4.6）：带生效期、流程范围、节点/业务条件与授权主体的代理规则。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_authorize_rule")
public class BpmAuthorizeRule extends BaseEntity {

    /** 授权主体（原责任人）。 */
    @TableField("principal_id")
    private Long principalId;

    /** 被代理人（代理人）。 */
    @TableField("agent_id")
    private Long agentId;

    /** 范围类型：GLOBAL / PROCESS / NODE / BUSINESS。 */
    @TableField("scope_type")
    private String scopeType;

    /** 范围限定：流程定义 key（PROCESS/NODE/BUSINESS 时生效）。 */
    @TableField("process_def_key")
    private String processDefKey;

    /** 范围限定：节点 key（NODE 时生效）。 */
    @TableField("node_key")
    private String nodeKey;

    /** 范围限定：业务键（BUSINESS 时生效）。 */
    @TableField("business_key")
    private String businessKey;

    /** 受控条件 JSON（白名单变量；发布/保存期校验）。 */
    @TableField("condition_json")
    private String conditionJson;

    /** 生效期起。 */
    @TableField("start_at")
    private java.time.LocalDateTime startAt;

    /** 生效期止。 */
    @TableField("end_at")
    private java.time.LocalDateTime endAt;

    /** 状态：ACTIVE / REVOKED / EXPIRED。 */
    @TableField("status")
    private String status;

    /** 说明。 */
    @TableField("detail")
    private String detail;
}
