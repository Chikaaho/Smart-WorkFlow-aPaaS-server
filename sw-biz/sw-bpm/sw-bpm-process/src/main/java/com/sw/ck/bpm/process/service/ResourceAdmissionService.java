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
    private final TransactionTemplate usageEnsureTemplate;

    /**
     * 已建立计数行的租户（进程内加速）：计数行建后不再删除，稳态下受理路径零额外事务与
     * 零唯一键冲突（RA02 时效：原实现每次受理尝试插入-捕获冲突，突发负载下每秒数十次
     * 异常+独立事务，构成可观测的尾延迟成本）。多进程部署不属本阶段画像，届时失效。
     */
    private final java.util.Set<Long> ensuredTenants = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
        TransactionTemplate ensureTemplate = new TransactionTemplate(transactionManager);
        ensureTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.usageEnsureTemplate = ensureTemplate;
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
        ensureUsageRowsFast(tenantId);
        // 死锁防治（RA02 复算修正）：usage 行锁只覆盖本行原子条件 UPDATE（纳秒级持锁）；
        // 全序预锁会把全部受理串行化在 5 行上（突发 100 次/s vs 受保护 10 次/s，PG 锁等待 12—16，
        // 受保护 p99 尾尖 929ms）且持锁跨越后续命令行唯一索引等待，已移除。
        // 防死锁契约=所有 usage 写路径（admit 补偿/release/reoccupy/对账 CAS）严格按
        // segment→GLOBAL TOTAL→TENANT TOTAL 同序单行更新；调用方必须保证 admit 不在持有
        // usage 行锁时等待命令行唯一索引（admit 置于 enqueue 之后，见各受理接缝）。

        String[] rejectScope = new String[1];
        AdmissionTicket ticket = tryOccupy(tenantId, resourceClass, units, policy, rejectScope);
        if (ticket == null && !usageRowPresent(tenantId)) {
            // 计数行被外部清空（测试隔离重置/运维清理）时的自愈：重建行后重试一次；
            // 真额度满时行存在，不重试、不放大为额外写入
            forceEnsureUsageRows(tenantId);
            rejectScope[0] = null;
            ticket = tryOccupy(tenantId, resourceClass, units, policy, rejectScope);
        }
        if (ticket != null) {
            // 受理热点路径不逐笔输出 INFO（突发画像下每秒数十笔，日志格式化与写盘进入
            // 共享 CPU 预算；逐笔准入事实由命令行冻结字段与占用计数持久承载）
            if (log.isDebugEnabled()) {
                log.debug("资源准入成功: tenant={}, class={}, units={}, segment={}, policyVersion={}",
                        tenantId, resourceClass.getCode(), units, ticket.segment(),
                        policy.getPolicyVersion());
            }
            return ticket;
        }
        auditReject(tenantId, policy, resourceClass, units,
                rejectScope[0] == null ? "QUOTA_SEGMENT" : rejectScope[0],
                "适用额度：全局 " + policy.getGlobalMaxOutstanding() + "、每租户 "
                        + policy.getTenantMaxOutstanding() + " 工作单位，当前已满，请稍后重试");
        throw new BaseException(BpmErrorCode.RESOURCE_QUOTA_EXCEEDED);
    }

    /** 容量裁决单次尝试：成功返回凭证；失败返回 null 并把拒绝域写入 rejectScope[0]。 */
    private AdmissionTicket tryOccupy(Long tenantId, ResourceClassEnum resourceClass, int units,
                                      BpmResourcePolicy policy, String[] rejectScope) {
        List<ResourceSegmentEnum> preference = ResourceSegmentEnum.preference(resourceClass);
        for (ResourceSegmentEnum segment : preference) {
            long segmentCap = segmentCapacity(policy, segment);
            // 非锁定预读：额度明显不足的段直接跳过（不发起条件更新）。条件更新在段满/并发
            // 冲突时仍会取到行锁并等待对方事务结束，而各形态类别的段回退顺序不同
            // （PROD 先生产保留、OA 先 OA 保留），多条路径交叉持锁会形成死锁环（PG 40P01，
            // 被中止一方放大为受理 500）。预读把「满段的条件更新等待」降为不取锁的读。
            if (usageSectionOverflow("GLOBAL", 0L, segment.getCode(), units, segmentCap)) {
                rejectScope[0] = rejectScope[0] == null ? "QUOTA_SEGMENT" : rejectScope[0];
                continue;
            }
            if (usageMapper.incrementWithinCap("GLOBAL", 0, segment.getCode(), units, segmentCap) != 1) {
                rejectScope[0] = rejectScope[0] == null ? "QUOTA_SEGMENT" : rejectScope[0];
                continue;
            }
            if (usageMapper.incrementWithinCap("GLOBAL", 0, "TOTAL", units,
                    policy.getGlobalMaxOutstanding()) != 1) {
                usageMapper.decrement("GLOBAL", 0, segment.getCode(), units);
                rejectScope[0] = "QUOTA_TOTAL";
                continue;
            }
            if (usageMapper.incrementWithinCap("TENANT", tenantId, "TOTAL", units,
                    policy.getTenantMaxOutstanding()) != 1) {
                usageMapper.decrement("GLOBAL", 0, "TOTAL", units);
                usageMapper.decrement("GLOBAL", 0, segment.getCode(), units);
                rejectScope[0] = "QUOTA_TENANT";
                continue;
            }
            return new AdmissionTicket(segment.getCode(), policy.getPolicyVersion(), units);
        }
        return null;
    }

    /** 非锁定预读：段已用 + 本次单位数是否超过段上限（读失败按未超限处理，交由条件更新裁决）。 */
    private boolean usageSectionOverflow(String scope, long scopeKey, String segment, int units,
                                         long cap) {
        try {
            BpmResourceUsage row = usageMapper.selectOne(Wrappers.<BpmResourceUsage>lambdaQuery()
                    .eq(BpmResourceUsage::getScope, scope)
                    .eq(BpmResourceUsage::getScopeKey, scopeKey)
                    .eq(BpmResourceUsage::getSegment, segment));
            if (row == null || row.getOutstanding() == null) {
                return false;
            }
            return row.getOutstanding() + units > cap;
        } catch (Exception unreadable) {
            return false;
        }
    }

    /** 租户计数行是否存在（仅拒绝路径调用，用于区分「额度满」与「行被清空」）。 */
    private boolean usageRowPresent(Long tenantId) {
        Long present = usageMapper.selectCount(Wrappers.<BpmResourceUsage>lambdaQuery()
                .eq(BpmResourceUsage::getScope, "TENANT")
                .eq(BpmResourceUsage::getScopeKey, tenantId)
                .eq(BpmResourceUsage::getSegment, "TOTAL"));
        return present != null && present > 0;
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
        ensureUsageRowsFast(tenantId);
        if (reoccupyAttempt(tenantId, segment, units, policy)) {
            return;
        }
        if (!usageRowPresent(tenantId)) {
            forceEnsureUsageRows(tenantId);
            if (reoccupyAttempt(tenantId, segment, units, policy)) {
                return;
            }
        }
        // 部分成功的占用在同一调用方事务内整体回滚（调用方抛错回滚）
        throw new BaseException(BpmErrorCode.RESOURCE_QUOTA_EXCEEDED);
    }

    private boolean reoccupyAttempt(Long tenantId, String segment, int units, BpmResourcePolicy policy) {
        long segmentCap = segmentCapacity(policy,
                ResourceSegmentEnum.of(segment).orElse(ResourceSegmentEnum.SHARED));
        if (usageMapper.incrementWithinCap("GLOBAL", 0, segment, units, segmentCap) != 1) {
            return false;
        }
        if (usageMapper.incrementWithinCap("GLOBAL", 0, "TOTAL", units,
                policy.getGlobalMaxOutstanding()) != 1) {
            usageMapper.decrement("GLOBAL", 0, segment, units);
            return false;
        }
        if (usageMapper.incrementWithinCap("TENANT", tenantId, "TOTAL", units,
                policy.getTenantMaxOutstanding()) != 1) {
            usageMapper.decrement("GLOBAL", 0, "TOTAL", units);
            usageMapper.decrement("GLOBAL", 0, segment, units);
            return false;
        }
        return true;
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

    /**
     * 惰性建立租户计数行（全局行由迁移种子；重复调用幂等）。
     * <p>插入在 REQUIRES_NEW 独立短事务执行：PG 下唯一键冲突会中止当前事务的后续语句，
     * 若在调用方业务事务内直接「插入-捕获冲突」将毒化业务事务（H2 宽松、PG 严格）；
     * 冲突只回滚内层计数行事务，调用方事务不受影响。</p>
     */
    public void ensureUsageRows(Long tenantId) {
        // 显式调用（测试隔离重置/运维重建）总是真实重建，不走进程内加速
        forceEnsureUsageRows(tenantId);
    }

    /** 受理路径用加速版：稳态零额外语句；行被外部清空由 admit 自愈路径兜底。 */
    private void ensureUsageRowsFast(Long tenantId) {
        if (ensuredTenants.contains(tenantId)) {
            return;
        }
        forceEnsureUsageRows(tenantId);
    }

    /** 强制重建租户计数行（显式调用/自愈路径；幂等）。 */
    private void forceEnsureUsageRows(Long tenantId) {
        try {
            usageEnsureTemplate.executeWithoutResult(status ->
                    usageMapper.insertNew(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),
                            "TENANT", tenantId, "TOTAL"));
        } catch (org.springframework.dao.DuplicateKeyException alreadyExists) {
            // 计数行已存在：另一并发首次受理已建立（或重启后首遇），幂等返回
        }
        ensuredTenants.add(tenantId);
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
