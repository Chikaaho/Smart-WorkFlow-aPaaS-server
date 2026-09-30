package com.sw.ck.bpm.process.queue;

import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import lombok.Data;

/**
 * 命令信封：消息边界上传输的最小单元。
 * <p>
 * payload 为各 Handler 解释的 JSON 字符串；身份/租户为可审计字段，
 * 不承载 Token/Cookie 等凭证。
 * </p>
 */
@Data
public class CommandEnvelope {

    /** 受理标识（sw_bpm_command.id）。 */
    private Long commandId;

    private CommandTypeEnum commandType;

    private CommandChannelEnum channel;

    /** 幂等键。 */
    private String commandKey;

    private Long tenantId;

    private Long initiatorId;

    /** 命令 payload（JSON）。 */
    private String payload;

    /** 已重试次数。 */
    private int retryCount;

    /** 当前状态（PENDING/PROCESSING/COMPLETED/FAILED），回查用。 */
    private String status;

    /** 处理结果（JSON），回查用。 */
    private String result;

    /** 失败原因，回查用。 */
    private String failureReason;

    /** 本次领取的租约令牌（claimDue 签发；写回时校验，防止旧持有者迟到写回）。 */
    private String claimToken;

    /** 统一逻辑命令身份（跨通道同一操作；可选，受理时冻结）。 */
    private String logicalCommandId;

    /** 载荷指纹（sha256；与 logicalCommandId 配合判重放/冲突）。 */
    private String payloadFingerprint;

    /** 执行形态/等级（受理时冻结）。 */
    private String tier;

    /** 业务完成点声明（受理时冻结）。 */
    private String completionPoint;

    /** 准入截止秒数（受理输入；1—300，默认 30）。 */
    private int deadlineSeconds = 30;

    /** 准入截止时间（受理时冻结；回查用）。 */
    private java.time.LocalDateTime deadlineAt;

    /** 执行中超期标记（回查用）。 */
    private java.time.LocalDateTime overdueAt;
}
