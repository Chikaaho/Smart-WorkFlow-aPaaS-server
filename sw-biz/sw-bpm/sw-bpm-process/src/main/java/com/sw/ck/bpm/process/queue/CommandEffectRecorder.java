package com.sw.ck.bpm.process.queue;

import com.sw.ck.bpm.process.entity.BpmCommand;
import com.sw.ck.bpm.process.entity.BpmCommandEffect;
import com.sw.ck.bpm.process.entity.CommandStatusEnum;
import com.sw.ck.bpm.process.mapper.BpmCommandEffectMapper;
import com.sw.ck.bpm.process.mapper.BpmCommandMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 命令效果权威记录器（P62 分级执行与统一命令）。
 * <p>
 * 解决既有"业务效果事务与完成记录事务分离"窗口：调用方在<strong>业务效果所在事务内</strong>
 * 调用 {@link #record}，本类先以行锁校验执行权（{@link #assertLease}），再写入
 * {@code sw_bpm_command_effect} 权威行。效果与权威行同成同败；命令完成记录缺失时，
 * 恢复路径（{@code TieredCommandReconcileJob}）据权威行确定收敛，不重做业务、不猜结果。
 * </p>
 * <p>
 * 执行权语义：{@link Propagation#MANDATORY} 要求调用方已有事务（否则直接失败，不产生
 * "独立提交的成功记录"）；行锁 + 状态/令牌校验保证租约被回收（转 PENDING 或新令牌）后，
 * 旧执行者无法再提交效果。旧执行者若在回收前已持锁，其提交先完成、回收随后生效，
 * 新领取者据既有权威行跳过业务（效果至多一次）——该"先查权威行再执行"由各接入路径承担，
 * 本类提供 {@link #findEffect} 查询。
 * </p>
 */
@Component
public class CommandEffectRecorder {

    private static final Logger log = LoggerFactory.getLogger(CommandEffectRecorder.class);

    private final BpmCommandMapper commandMapper;
    private final BpmCommandEffectMapper effectMapper;

    public CommandEffectRecorder(BpmCommandMapper commandMapper, BpmCommandEffectMapper effectMapper) {
        this.commandMapper = commandMapper;
        this.effectMapper = effectMapper;
    }

    /**
     * 当前事务内校验命令执行权（持锁 + 状态 PROCESSING + 令牌一致）。
     * 不满足即抛出并回滚当前业务事务：旧执行者（租约已回收/转手）不得提交效果。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertLease(Long commandId, String claimToken) {
        BpmCommand locked = commandMapper.lockLeaseById(commandId);
        if (locked == null
                || !CommandStatusEnum.PROCESSING.getCode().equals(locked.getStatus())
                || !Objects.equals(locked.getClaimToken(), claimToken)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                    "命令执行权已失效：旧执行者禁止提交业务效果（commandId=" + commandId + "）");
        }
    }

    /**
     * 与业务效果同事务记录权威结果。同一命令重复记录保留首次（效果至多一次）。
     *
     * @param commandId       命令标识（sw_bpm_command.id）
     * @param claimToken      本次领取令牌（须与命令表当前令牌一致）
     * @param logicalCommandId 统一逻辑命令身份（可空）
     * @param resultJson      权威结果（JSON，与命令完成结果一致）
     * @param bizRef          业务对象引用（记录/任务/批次项，可空）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long commandId, String claimToken, String logicalCommandId,
                       String resultJson, String bizRef) {
        assertLease(commandId, claimToken);
        BpmCommandEffect existing = effectMapper.selectById(commandId);
        if (existing != null) {
            log.info("命令效果已存在，保留首次权威结果: commandId={}, bizRef={}", commandId, existing.getBizRef());
            return;
        }
        BpmCommandEffect effect = new BpmCommandEffect();
        effect.setCommandId(commandId);
        effect.setLogicalCommandId(logicalCommandId);
        effect.setClaimToken(claimToken);
        effect.setResultJson(resultJson);
        effect.setBizRef(bizRef);
        effect.setTenantId(tenantIdOf(commandId));
        effect.setCreateTime(LocalDateTime.now());
        try {
            effectMapper.insert(effect);
            log.info("命令效果权威记录已写入（与业务同事务）: commandId={}, bizRef={}", commandId, bizRef);
        } catch (DuplicateKeyException e) {
            // 并发同命令（交接窗口）：首次生效，后续保留既有权威行
            log.warn("并发写入命令效果，保留既有权威行: commandId={}", commandId);
        }
    }

    /** 效果权威行查询（新执行者幂等判断 / 恢复路径）。 */
    public BpmCommandEffect findEffect(Long commandId) {
        return effectMapper.selectById(commandId);
    }

    private Long tenantIdOf(Long commandId) {
        BpmCommand command = commandMapper.selectById(commandId);
        return command == null || command.getTenantId() == null ? 0L : command.getTenantId();
    }
}
