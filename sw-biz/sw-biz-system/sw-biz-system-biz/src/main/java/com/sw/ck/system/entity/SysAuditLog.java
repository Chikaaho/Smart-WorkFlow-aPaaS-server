package com.sw.ck.system.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 平台审计日志（1.0.0 F3）：登录（LOGIN）/管理操作（OPERATION）/服务端异常（EXCEPTION）。
 * <p>
 * 只追加不更新：无 update_by/update_time/deleted/version 列，保留期由清理任务物理删除。
 * 不落完整堆栈与请求体；密码、令牌、密钥不得进入任何文本列（写入方负责剔除）。
 * 预登录写入（登录失败、挑战失败）租户未知时 tenant_id=0，仅超级管理员可见。
 * </p>
 */
@Data
@TableName("sys_audit_log")
public class SysAuditLog implements Serializable {

    public static final String TYPE_LOGIN = "LOGIN";
    public static final String TYPE_OPERATION = "OPERATION";
    public static final String TYPE_EXCEPTION = "EXCEPTION";

    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_FAIL = "FAIL";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 租户；预登录（tenant_id=0）行仅超管可见，查询由 TenantLineHandler 常规隔离 */
    @TableField("tenant_id")
    private Long tenantId;

    /** 日志类型：LOGIN / OPERATION / EXCEPTION */
    @TableField("log_type")
    private String logType;

    /** 账号；登录失败时为用户尝试输入的账号 */
    @TableField("username")
    private String username;

    /** 操作人 ID；预登录未知为空 */
    @TableField("user_id")
    private Long userId;

    /** 结果：SUCCESS / FAIL */
    @TableField("result")
    private String result;

    /** 失败原因（登录失败语义、业务拒绝结论），成功为空 */
    @TableField("fail_reason")
    private String failReason;

    /** 模块：system / workflow / form 等（取请求路径首段） */
    @TableField("module")
    private String module;

    /** 动作：登录子场景（LOGIN/LOGOUT/CHANGE_PASSWORD）或 HTTP 方法 */
    @TableField("action")
    private String action;

    /** 对象：请求路径 + 查询串（含对象 ID），截断至 255 */
    @TableField("target")
    private String target;

    @TableField("ip")
    private String ip;

    /** 浏览器信息，截断至 255 */
    @TableField("user_agent")
    private String userAgent;

    /** 异常类型（类简名），仅 EXCEPTION */
    @TableField("exception_type")
    private String exceptionType;

    /** 异常摘要（安全结论文案，不含堆栈/请求体），仅 EXCEPTION */
    @TableField("summary")
    private String summary;

    /** 事件引用号（eventRef），与结构化日志关联 */
    @TableField("trace_id")
    private String traceId;
}
