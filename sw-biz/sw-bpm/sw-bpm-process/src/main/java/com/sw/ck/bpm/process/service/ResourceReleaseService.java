package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.process.entity.BpmCommand;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资源占用释放服务（P62 资源保障）。
 * <p>
 * 命令到达终态时的占用回收判定（与终态写入同事务，调用方事务内执行）：
 * <ul>
 *   <li>无资源冻结字段（旧对象/策略未启用受理）→ 不参与会计；</li>
 *   <li>BULK（批量）→ 命令级不释放整批单位：占用按项终态逐项回收
 *       （受理冻结 units 仅为容量预占，释放以非终态项事实为准）；</li>
 *   <li>PROD 且完成点=TARGET_ACTION_DONE（生产轻流程）→ 命令完成不释放：
 *       目标仍在引擎队列时保持占用，由资源对账按引擎队列事实释放
 *       （仅因启动命令 SUCCEEDED 释放即违反方向合同）；</li>
 *   <li>其余（OA/PROD 命令完成、FAILED/EXPIRED 终态）→ 按冻结段与单位数释放。</li>
 * </ul>
 * 失败重试（回到 PENDING）不释放、不重复加账。
 * </p>
 */
@Service
public class ResourceReleaseService {

    private static final Logger log = LoggerFactory.getLogger(ResourceReleaseService.class);

    /** 方向合同：轻流程完成点（受理冻结；目标动作完成才释放占用）。 */
    public static final String COMPLETION_POINT_TARGET_DONE = "TARGET_ACTION_DONE";

    private final ResourceAdmissionService admissionService;
    private final com.sw.ck.bpm.process.service.BpmCommandService commandService;
    private final JdbcTemplate jdbcTemplate;

    public ResourceReleaseService(ResourceAdmissionService admissionService,
                                  com.sw.ck.bpm.process.service.BpmCommandService commandService,
                                  JdbcTemplate jdbcTemplate) {
        this.admissionService = admissionService;
        this.commandService = commandService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 命令终态释放判定（命令行已写入终态后调用，同一事务）。
     *
     * @param command 终态后命令行（含冻结资源字段）
     */
    @Transactional
    public void onTerminal(BpmCommand command) {
        if (command.getResourceUnits() == null || command.getResourceUnits() <= 0) {
            return;
        }
        if (CommandTypeEnum.BATCH_INVOKE.getCode().equals(command.getCommandType())) {
            // 批量占用按项回收：结算批次命令完成时项已全部终态（剩余 0）；
            // 终态失败/过期时释放剩余非终态项对应的占用
            long remaining = batchRemainingUnits(command.getId());
            if (remaining > 0) {
                admissionService.release(command.getTenantId(), command.getResourceSegment(), remaining);
            }
            return;
        }
        if (isLightProcessTargetPending(command)) {
            // 轻流程：目标仍在引擎队列时保持占用；对账按引擎事实释放
            return;
        }
        admissionService.release(command.getTenantId(), command.getResourceSegment(),
                command.getResourceUnits());
    }

    /**
     * 批量项终态逐项回收（每项 1 个单位；调用方为逐项独立事务）。
     */
    @Transactional
    public void onBatchItemTerminal(BpmCommand batchCommand) {
        if (batchCommand.getResourceUnits() == null || batchCommand.getResourceUnits() <= 0) {
            return;
        }
        admissionService.release(batchCommand.getTenantId(), batchCommand.getResourceSegment(), 1);
    }

    /**
     * 轻流程目标占用释放（对账据引擎队列事实调用）：CAS 释放标记 + 计数扣减同事务，
     * 并发对账/重复扫描不双计。
     *
     * @return true=本次调用完成释放；false=已被其他轮次释放
     */
    @Transactional
    public boolean releaseLightProcessTarget(BpmCommand command) {
        boolean updated = commandService.lambdaUpdate()
                .eq(BpmCommand::getId, command.getId())
                .isNull(BpmCommand::getResourceReleasedAt)
                .set(BpmCommand::getResourceReleasedAt, java.time.LocalDateTime.now())
                .update();
        if (!updated) {
            return false;
        }
        admissionService.release(command.getTenantId(), command.getResourceSegment(), 1);
        log.info("轻流程目标占用已按引擎事实释放: commandId={}, tenant={}",
                command.getId(), command.getTenantId());
        return true;
    }

    /** 批量命令剩余非终态项数（占用事实；批次重入/终态失败时占用的权威口径）。 */
    public long batchRemainingUnits(long batchCommandId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_command_batch_item i "
                        + "JOIN sw_bpm_command_batch b ON b.id = i.batch_id "
                        + "WHERE b.command_id = ? AND i.status = 'PENDING'",
                Long.class, batchCommandId);
        return count == null ? 0 : count;
    }

    private boolean isLightProcessTargetPending(BpmCommand command) {
        return "PROD".equals(command.getResourceClass())
                && COMPLETION_POINT_TARGET_DONE.equals(command.getCompletionPoint());
    }
}
