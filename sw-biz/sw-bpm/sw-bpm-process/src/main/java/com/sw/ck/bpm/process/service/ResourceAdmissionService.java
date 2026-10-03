package com.sw.ck.bpm.process.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.process.entity.BpmResourcePolicy;
import com.sw.ck.bpm.process.entity.BpmResourceRejectLog;
import com.sw.ck.bpm.process.entity.BpmResourceUsage;
import com.sw.ck.bpm.process.entity.ResourceClassEnum;
import com.sw.ck.bpm.process.entity.ResourceSegmentEnum;
import com.sw.ck.bpm.process.mapper.BpmResourcePolicyMapper;
import com.sw.ck.bpm.process.mapper.BpmResourceRejectLogMapper;
import com.sw.ck.bpm.process.mapper.BpmResourceUsageMapper;
import com.sw.ck.common.exception.BaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 资源准入与占用会计（P62 资源保障）。
 * <p>
 * <b>准入</b>：策略未启用时零行为变化（返回 null，调用方不冻结资源字段、不计数）；
 * 启用后新受理按「停新受理 → 速率桶 → 容量段+全局上限+租户上限」裁决，全部通过才
 * 占位成功。占用以原子条件更新完成（并发受理不突破额度），拒绝走独立短事务审计，
 * 并抛出明确错误（key/原因/适用额度/可重试提示），调用方事务整体回滚——拒绝不留下
 * 可执行命令、业务写入或成功幂等占位。
 * </p>
 * <p>
 * <b>段语义</b>：生产/普通 OA 各有保留容量（不可被新增低等级工作占满），空闲可借用、
 * 需求返回停止新增借用（段选择顺序 {@link ResourceSegmentEnum#preference}）；
 * BULK 只占共享段。占用计数跨策略版本共享（usage 不按版本分账）。
 * </p>
 * <p>
 * <b>释放</b>：命令终态/批量项终态时按受理冻结的段与单位数回收；占用事实以持久行为
 * 权威，计数漂移由对账修复（重启后可从持久事实恢复占用，不双计）。
 * </p>
 */
@Service
public class ResourceAdmissionService {

    private static final Logger log = LoggerFactory.getLogger(ResourceAdmissionService.class);

    private final BpmResourcePolicyMapper policyMapper;
    private final BpmResourceUsageMapper usageMapper;
    private final BpmResourceRejectLogMapper rejectLogMapper;
    private final TenantRateBuckets rateBuckets;
    private final TransactionTemplate rejectAuditTemplate;

    /** 准入凭证：受理冻结到命令行的段与策略版本。 */
    public record AdmissionTicket(String segment, Integer policyVersion, int units) {
    }

    public ResourceAdmissionService(BpmResourcePolicyMapper policyMapper,
                                    BpmResourceUsageMapper usageMapper,
                                    BpmResourceRejectLogMapper rejectLogMapper,
                                    TenantRateBuckets rateBuckets,
                                    org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.policyMapper = policyMapper;
        this.usageMapper = usageMapper;
        this.rejectLogMapper = rejectLogMapper;
        this.rateBuckets = rateBuckets;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.rejectAuditTemplate = template;
    }

    /**
     * 准入裁决：占用 units 个工作单位。
     *
     * @return 准入凭证；策略未启用时返回 null（零行为变化，不占用不冻结）
     * @throws BaseException 超限/速率/停受理拒绝（拒绝审计已独立提交）
     */
    public AdmissionTicket admit(Long tenantId, ResourceClassEnum resourceClass, int units, String commandKey) {
        BpmResourcePolicy policy = findActivePolicy();
        if (policy == null) {
            return null;
        }
        if (Boolean.TRUE.equals(policy.getStopAcceptance())) {
            auditReject(tenantId, policy, resourceClass, units, "STOPPED",
                    "资源策略已停新受理（policyVersion=" + policy.getPolicyVersion() + "）");
            throw new BaseException(BpmErrorCode.RESOURCE_ACCEPTANCE_STOPPED);
        }
        if (!rateBuckets.tryConsume(tenantId, policy.getTenantRatePerSec(),
                policy.getTenantBurst(), units)) {
            auditReject(tenantId, policy, resourceClass, units, "RATE",
                    "速率额度 " + policy.getTenantRatePerSec() + " 单位/s（突发 "
                            + policy.getTenantBurst() + "），请稍后重试");
            throw new BaseException(BpmErrorCode.RESOURCE_RATE_EXCEEDED);
        }
        ensureUsageRows(tenantId);

        String rejectScope = null;
        List<ResourceSegmentEnum> preference = ResourceSegmentEnum.preference(resourceClass);
        for (ResourceSegmentEnum segment : preference) {
            long segmentCap = segmentCapacity(policy, segment);
            if (usageMapper.incrementWithinCap("GLOBAL", 0, segment.getCode(), units, segmentCap) != 1) {
                rejectScope = rejectScope == null ? "QUOTA_SEGMENT" : rejectScope;
                continue;
            }
            if (usageMapper.incrementWithinCap("GLOBAL", 0, "TOTAL", units,
                    policy.getGlobalMaxOutstanding()) != 1) {
                usageMapper.decrement("GLOBAL", 0, segment.getCode(), units);
                rejectScope = "QUOTA_TOTAL";
                continue;
            }
            if (usageMapper.incrementWithinCap("TENANT", tenantId, "TOTAL", units,
                    policy.getTenantMaxOutstanding()) != 1) {
                usageMapper.decrement("GLOBAL", 0, "TOTAL", units);
                usageMapper.decrement("GLOBAL", 0, segment.getCode(), units);
                rejectScope = "QUOTA_TENANT";
                continue;
            }
            log.info("资源准入成功: tenant={}, class={}, units={}, segment={}, policyVersion={}",
                    tenantId, resourceClass.getCode(), units, segment.getCode(), policy.getPolicyVersion());
            return new AdmissionTicket(segment.getCode(), policy.getPolicyVersion(), units);
        }
        auditReject(tenantId, policy, resourceClass, units,
                rejectScope == null ? "QUOTA_SEGMENT" : rejectScope,
                "适用额度：全局 " + policy.getGlobalMaxOutstanding() + "、每租户 "
                        + policy.getTenantMaxOutstanding() + " 工作单位，当前已满，请稍后重试");
        throw new BaseException(BpmErrorCode.RESOURCE_QUOTA_EXCEEDED);
    }

    /**
     * FAILED 命令重新入队时的再占用（受理冻结字段不变，按当前上限重新占位；
     * 失败则重新入队被拒，命令保持 FAILED，不突破额度）。
     */
    public void reoccupy(Long tenantId, String segment, int units) {
        BpmResourcePolicy policy = findActivePolicy();
        if (policy == null) {
            return;
        }
        ensureUsageRows(tenantId);
        if (usageMapper.incrementWithinCap("GLOBAL", 0, segment, units, segmentCapacity(policy,
                ResourceSegmentEnum.of(segment).orElse(ResourceSegmentEnum.SHARED))) != 1
                || usageMapper.incrementWithinCap("GLOBAL", 0, "TOTAL", units,
                policy.getGlobalMaxOutstanding()) != 1
                || usageMapper.incrementWithinCap("TENANT", tenantId, "TOTAL", units,
                policy.getTenantMaxOutstanding()) != 1) {
            // 部分成功的占用在同一调用方事务内整体回滚（调用方抛错回滚）
            throw new BaseException(BpmErrorCode.RESOURCE_QUOTA_EXCEEDED);
        }
    }

    /**
     * 释放占用（命令终态/批量项终态/对账释放；调用方事务内，与终态写入原子提交）。
     */
    public void release(Long tenantId, String segmentCode, long units) {
        if (units <= 0) {
            return;
        }
        usageMapper.decrement("GLOBAL", 0, segmentCode, units);
        usageMapper.decrement("GLOBAL", 0, "TOTAL", units);
        usageMapper.decrement("TENANT", tenantId, "TOTAL", units);
    }

    /** 惰性建立租户计数行（全局行由迁移种子；重复调用幂等，唯一键冲突即已存在）。 */
    public void ensureUsageRows(Long tenantId) {
        try {
            usageMapper.insertNew(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),
                    "TENANT", tenantId, "TOTAL");
        } catch (org.springframework.dao.DuplicateKeyException alreadyExists) {
            // 计数行已存在：幂等返回
        }
    }

    /** 当前生效策略（enabled + ACTIVE；全局唯一，供画像与测试回读）。 */
    public BpmResourcePolicy findActivePolicy() {
        return policyMapper.selectOne(Wrappers.<BpmResourcePolicy>lambdaQuery()
                .eq(BpmResourcePolicy::getTenantId, 0L)
                .eq(BpmResourcePolicy::getEnabled, true)
                .eq(BpmResourcePolicy::getStatus, "ACTIVE")
                .orderByDesc(BpmResourcePolicy::getPolicyVersion)
                .last("LIMIT 1"));
    }

    private long segmentCapacity(BpmResourcePolicy policy, ResourceSegmentEnum segment) {
        return switch (segment) {
            case PROD_RESERVED -> policy.getProdReserved();
            case OA_RESERVED -> policy.getOaReserved();
            case SHARED -> policy.getSharedCapacity();
        };
    }

    /**
     * 拒绝审计（REQUIRES_NEW 独立短事务）：拒绝不产生业务效果但保留可查证据，
     * 先于异常抛出提交，不受调用方事务回滚影响。
     */
    private void auditReject(Long tenantId, BpmResourcePolicy policy, ResourceClassEnum resourceClass,
                             int units, String rejectScope, String detail) {
        try {
            rejectAuditTemplate.execute(status -> {
                BpmResourceRejectLog entry = new BpmResourceRejectLog();
                entry.setTenantId(tenantId);
                entry.setPolicyVersion(policy.getPolicyVersion());
                entry.setResourceClass(resourceClass.getCode());
                entry.setRejectScope(rejectScope);
                entry.setRequestedUnits(units);
                entry.setReasonCode(switch (rejectScope) {
                    case "STOPPED" -> BpmErrorCode.RESOURCE_ACCEPTANCE_STOPPED.getErrorKey();
                    case "RATE" -> BpmErrorCode.RESOURCE_RATE_EXCEEDED.getErrorKey();
                    default -> BpmErrorCode.RESOURCE_QUOTA_EXCEEDED.getErrorKey();
                });
                entry.setDetail(detail);
                rejectLogMapper.insert(entry);
                return null;
            });
        } catch (Exception e) {
            // 审计失败不放大为受理失败（拒绝语义已由异常承载），但必须留下失败日志
            log.error("资源拒绝审计写入失败: tenant={}, scope={}", tenantId, rejectScope, e);
        }
    }

}
