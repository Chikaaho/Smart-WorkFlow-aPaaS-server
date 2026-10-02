package com.sw.ck.bpm.process.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.bpm.process.entity.ApprovalActionRecord;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.service.ApprovalActionService;
import com.sw.ck.bpm.process.service.TaskActionService;
import com.sw.ck.common.exception.BaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 审批动作命令处理器：消费 TASK_APPROVE/TASK_REJECT/TASK_RETURN 受理，
 * 汇入 {@link TaskActionService} 唯一执行核心（与同步 HTTP 入口共享语义）。
 * <p>
 * 幂等（提示05 §4 断言3）：执行前按 taskId 查既有动作记录——记录与当前命令同源
 *（command_id 相等）= 同命令确认丢失后的重投，执行核心直接恢复自身已提交结果，
 * 结果以 status=RECOVERED + 原动作记录标识回写命令，可回查、无第二次审批效果；
 * 记录属于其他命令 = 已被处理的确定性冲突。结果 JSON 恒携带 actionRecordId，
 * 命令结果与业务效果可互相同位。
 * </p>
 */
@Component
public class TaskActionCommandHandler implements BpmCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(TaskActionCommandHandler.class);

    private final TaskActionService taskActionService;
    private final ApprovalActionService approvalActionService;
    private final ObjectMapper objectMapper;

    /** 兼容既有隔离测试构造器（无动作记录服务时结果不携带 actionRecordId）。 */
    public TaskActionCommandHandler(TaskActionService taskActionService,
                                    ObjectMapper objectMapper) {
        this(taskActionService, null, objectMapper);
    }

    /** 生产构造器（Spring 注入动作记录服务，结果可携带 actionRecordId）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public TaskActionCommandHandler(TaskActionService taskActionService,
                                    ApprovalActionService approvalActionService,
                                    ObjectMapper objectMapper) {
        this.taskActionService = taskActionService;
        this.approvalActionService = approvalActionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public java.util.Set<CommandTypeEnum> types() {
        // 三种审批动作共用本 handler，动作取自 payload.action
        return java.util.Set.of(CommandTypeEnum.TASK_APPROVE, CommandTypeEnum.TASK_REJECT,
                CommandTypeEnum.TASK_RETURN);
    }

    @Override
    public String handle(CommandEnvelope envelope) throws Exception {
        ApprovalActionRequest request = objectMapper.readValue(
                envelope.getPayload() == null ? "{}" : envelope.getPayload(),
                ApprovalActionRequest.class);
        String taskId = request.getTaskId();

        // 执行前快照：记录已存在且与当前命令同源 → 本次是确认丢失后的重放
        ApprovalActionRecord prior = approvalActionService == null
                ? null : approvalActionService.findByTaskId(taskId);
        boolean replay = prior != null && envelope.getCommandId() != null
                && envelope.getCommandId().equals(prior.getCommandId());
        // 统一幂等身份（复核04 G3b）：同一 (任务, 操作人, 动作) 的既有动作记录即同一逻辑业务
        // 操作；运行期任务已不存在时本命令注定不产生新效果——直接按 RECOVERED 恢复原结果，
        // 不进入执行核心（避免同窗口 runtime 视图差异把幂等恢复放大成"已被处理"失败）。
        // 任务仍在运行期（另一入口尚未提交/不同操作人）时保持原路径，由执行核心判定冲突。
        boolean sameOperationPrior = sameOperation(envelope, request, prior);
        if (sameOperationPrior && !taskActionService.isTaskInRuntime(taskId)) {
            // 跨入口恢复前置（提示05 G3b1）：同身份且载荷一致才按 RECOVERED 恢复原结果；
            // 同身份异载荷不得吞成成功——明确拒绝（COMMAND_PAYLOAD_MISMATCH），由调度器
            // 按失败终态处理，不进入执行核心产生任何效果。
            if (taskActionService.auditPayloadConsistent(request, prior)) {
                String recovered = "{\"status\":\"RECOVERED\",\"actionRecordId\":" + prior.getId() + "}";
                log.info("同身份重放恢复自身已提交结果（跨入口）: commandId={}, taskId={}, "
                                + "actionRecordId={}, actor={}, action={}",
                        envelope.getCommandId(), taskId, prior.getId(), prior.getActorId(),
                        prior.getAction());
                return recovered;
            }
            log.warn("同身份异载荷拒绝（跨入口恢复分支）: commandId={}, taskId={}, "
                            + "actionRecordId={}, actor={}, action={}",
                    envelope.getCommandId(), taskId, prior.getId(), prior.getActorId(),
                    prior.getAction());
            throw new BaseException(
                    com.sw.ck.bpm.api.exception.BpmErrorCode.COMMAND_PAYLOAD_MISMATCH);
        }
        taskActionService.execute(taskId, request, envelope.getCommandId());

        // 同身份既有记录 = 本命令未产生新效果：结果按 RECOVERED 回写并携带原动作记录标识，
        // 供跨通道结果关联与回查（不是第二次审批）。
        boolean recoveredByIdentity = replay || sameOperationPrior;
        ApprovalActionRecord committed = recoveredByIdentity ? prior
                : (approvalActionService == null ? null : approvalActionService.findByTaskId(taskId));
        String result = "{\"status\":\"" + (recoveredByIdentity ? "RECOVERED" : "DONE") + "\""
                + (committed == null ? "" : ",\"actionRecordId\":" + committed.getId())
                + "}";
        log.info("审批命令已执行: commandId={}, taskId={}, action={}, result={}",
                envelope.getCommandId(), taskId, request.getAction(), result);
        return result;
    }

    /** 同一逻辑业务操作判定：既有动作记录与当前命令同为同一操作人、同一动作。 */
    private static boolean sameOperation(CommandEnvelope envelope, ApprovalActionRequest request,
                                         ApprovalActionRecord prior) {
        return prior != null
                && envelope.getInitiatorId() != null
                && envelope.getInitiatorId().equals(prior.getActorId())
                && request.getAction() != null
                && request.getAction().name().equals(prior.getAction());
    }
}
