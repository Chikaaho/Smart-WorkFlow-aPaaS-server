package com.sw.ck.form.txn.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import org.apache.ibatis.type.JdbcType;
import com.sw.ck.form.entity.FormBaseEntity;
import com.sw.ck.form.handler.JsonStringTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 事务动作调用记录：稳定幂等身份 + 请求指纹 + 结果回查。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sw_form_txn_invocation", autoResultMap = true)
public class TxnInvocationEntity extends FormBaseEntity {

    /** 关联 sw_form_txn_action.id */
    private String actionId;

    /** 调用命中的动作版本（版本固定语义） */
    private Integer actionVersion;

    /** 调用幂等键（租户+动作内唯一） */
    private String invocationKey;

    /** 请求指纹（同键不同输入判冲突） */
    private String requestHash;

    /** 目标记录 id（可空） */
    private String bizRecordId;

    /** 状态：SUCCEEDED/REJECTED/CONFLICT/FAILED */
    private String status;

    /** 失败/拒绝错误码 */
    private Integer errorCode;

    /** 失败/拒绝错误信息 */
    private String errorMsg;

    /** 结果快照（JSON） */
    @TableField(value = "result_json", jdbcType = JdbcType.OTHER,
            typeHandler = JsonStringTypeHandler.class)
    private String resultJson;

    /** 服务端耗时（毫秒） */
    private Long durationMs;
}
