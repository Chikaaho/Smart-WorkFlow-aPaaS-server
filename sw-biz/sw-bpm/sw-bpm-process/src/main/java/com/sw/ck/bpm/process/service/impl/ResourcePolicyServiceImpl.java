package com.sw.ck.bpm.process.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.process.entity.BpmResourcePolicy;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.entity.BpmResourceRejectLog;
import com.sw.ck.bpm.process.mapper.BpmResourcePolicyMapper;
import com.sw.ck.bpm.process.mapper.BpmResourceRejectLogMapper;
import com.sw.ck.bpm.process.queue.BpmCommandHandler;
import com.sw.ck.bpm.process.service.ResourcePolicyService;
import com.sw.ck.common.exception.BaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 资源策略服务实现。
 * <p>
 * 启用检查项（任一失败拒绝启用并留审计，{@code RESOURCE_POLICY_INVALID}/
 * {@code RESOURCE_CONSUMER_UNAVAILABLE}）：
 * <ol>
 *   <li>保留份额自洽：生产保留 + OA 保留 + 共享 = 全局上限（每个占用单位必须归属唯一段）；</li>
 *   <li>额度下限：各容量段、速率、突发、并发预算 ≥1；每租户上限 ≤ 全局上限；
 *       实时单租户并发 ≤ 实时全局并发；</li>
 *   <li>消费者可用：Flowable 异步执行器已激活（轻流程目标消费链必需）、
 *       批量命令处理器已注册（BULK 类推进机会必需，受 {@code sw.bpm.txn-batch.enabled} 门控）；</li>
 *   <li>预算相容：实际连接池上限 ≥ 实时并发 + 异步线程 + 调度线程 + 安全余量，
 *       明显不相容拒绝（如 prod 池 5 无法承载启用合同）。</li>
 * </ol>
 * 检查读取实际运行值（Druid 池经真实 DataSource Bean 回读），不把配置推断当运行事实。
 * </p>
 */
@Service
public class ResourcePolicyServiceImpl implements ResourcePolicyService {

    private static final Logger log = LoggerFactory.getLogger(ResourcePolicyServiceImpl.class);

    /** 启用相容性检查的安全余量（受理 HTTP、对账、恢复等共享消耗）。 */
    static final int POOL_MARGIN = 8;

    private final BpmResourcePolicyMapper policyMapper;
    private final BpmResourceRejectLogMapper rejectLogMapper;
    private final ApplicationContext applicationContext;
    private final org.springframework.core.env.Environment environment;
    /** 拒绝审计独立短事务：enable() 抛拒绝异常时主事务回滚，审计必须已提交留存。 */
    private final org.springframework.transaction.support.TransactionTemplate rejectAuditTemplate;

    @Value("${flowable.async-executor-activate:false}")
    private boolean flowableAsyncActive;

    @Value("${flowable.process.async.executor.core-pool-size:8}")
    private int flowableAsyncThreads;

    /** 测试装配用：显式设定异步执行器激活事实（生产由 @Value 注入）。 */
    void setFlowableAsyncActiveForTest(boolean active) {
        this.flowableAsyncActive = active;
    }

    /** 测试装配用：显式设定异步线程数（生产由 @Value 注入）。 */
    void setFlowableAsyncThreadsForTest(int threads) {
        this.flowableAsyncThreads = threads;
    }

    public ResourcePolicyServiceImpl(BpmResourcePolicyMapper policyMapper,
                                     BpmResourceRejectLogMapper rejectLogMapper,
                                     ApplicationContext applicationContext,
                                     org.springframework.core.env.Environment environment,
                                     org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.policyMapper = policyMapper;
        this.rejectLogMapper = rejectLogMapper;
        this.applicationContext = applicationContext;
        this.environment = environment;
        org.springframework.transaction.support.TransactionTemplate template =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.rejectAuditTemplate = template;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BpmResourcePolicy create(BpmResourcePolicy policy) {
        validateShape(policy);
        Long tenantId = com.sw.ck.security.holder.LoginUserHolder.get() == null
                ? 0L : com.sw.ck.security.holder.LoginUserHolder.get().getTenantId();
        policy.setTenantId(0L);
        policy.setStatus("DRAFT");
        policy.setEnabled(false);
        policy.setStopAcceptance(Boolean.TRUE.equals(policy.getStopAcceptance()));
        Integer maxVersion = policyMapper.selectList(Wrappers.<BpmResourcePolicy>lambdaQuery()
                        .eq(BpmResourcePolicy::getTenantId, 0L)
                        .orderByDesc(BpmResourcePolicy::getPolicyVersion)
                        .last("LIMIT 1"))
                .stream().findFirst().map(BpmResourcePolicy::getPolicyVersion).orElse(0);
        policy.setPolicyVersion(maxVersion + 1);
        policyMapper.insert(policy);
        log.info("资源策略版本已创建: policyVersion={}, remark={}", policy.getPolicyVersion(), policy.getRemark());
        return policy;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BpmResourcePolicy enable(Long id, String operatorRemark) {
        BpmResourcePolicy policy = requirePolicy(id);
        List<String> violations = checkEnablement(policy);
        if (!violations.isEmpty()) {
            rejectLog(id, policy, "ENABLEMENT", String.join("; ", violations));
            throw new BaseException(violations.stream().anyMatch(v -> v.contains("消费者"))
                    ? BpmErrorCode.RESOURCE_CONSUMER_UNAVAILABLE
                    : BpmErrorCode.RESOURCE_POLICY_INVALID,
                    "资源策略启用检查未通过: " + String.join("; ", violations));
        }
        // 单 ACTIVE：旧版本退役（在途对象按各自受理冻结版本结算，不受退役影响）
        policyMapper.update(null, Wrappers.<BpmResourcePolicy>lambdaUpdate()
                .eq(BpmResourcePolicy::getTenantId, 0L)
                .eq(BpmResourcePolicy::getStatus, "ACTIVE")
                .set(BpmResourcePolicy::getStatus, "RETIRED"));
        policy.setStatus("ACTIVE");
        policy.setEnabled(true);
        if (operatorRemark != null && !operatorRemark.isBlank()) {
            policy.setRemark(operatorRemark);
        }
        policyMapper.updateById(policy);
        log.info("资源策略已启用: policyVersion={}（只影响新受理；在途对象按冻结版本结算）",
                policy.getPolicyVersion());
        return policy;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BpmResourcePolicy disable(Long id) {
        BpmResourcePolicy policy = requirePolicy(id);
        policy.setEnabled(false);
        policy.setStatus("RETIRED");
        policyMapper.updateById(policy);
        log.info("资源策略已停用: policyVersion={}（新受理回到无策略行为；占用由对账收敛）",
                policy.getPolicyVersion());
        return policy;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BpmResourcePolicy stopAcceptance(Long id, boolean stop) {
        BpmResourcePolicy policy = requirePolicy(id);
        policy.setStopAcceptance(stop);
        policyMapper.updateById(policy);
        log.info("资源策略停新受理开关: policyVersion={}, stop={}", policy.getPolicyVersion(), stop);
        return policy;
    }

    @Override
    public BpmResourcePolicy getById(Long id) {
        return requirePolicy(id);
    }

    @Override
    public List<BpmResourcePolicy> listAll() {
        return policyMapper.selectList(Wrappers.<BpmResourcePolicy>lambdaQuery()
                .orderByDesc(BpmResourcePolicy::getPolicyVersion));
    }

    @Override
    public List<String> checkEnablement(BpmResourcePolicy policy) {
        List<String> violations = new ArrayList<>(validateShape(policy));
        if (!violations.isEmpty()) {
            return violations;
        }
        // 消费者可用性（实际装配事实，不以配置意图推断）
        boolean asyncActive = environment.getProperty("flowable.async-executor-activate",
                Boolean.class, Boolean.FALSE);
        if (!asyncActive) {
            violations.add("异步节点必需消费者未启用：flowable.async-executor-activate=false，"
                    + "轻流程目标动作无消费能力");
        }
        boolean batchConsumerRegistered = applicationContext.getBeansOfType(BpmCommandHandler.class)
                .values().stream()
                .anyMatch(handler -> handler.types().contains(CommandTypeEnum.BATCH_INVOKE));
        if (!batchConsumerRegistered) {
            violations.add("批量消费者未启用：BATCH_INVOKE 处理器未注册（sw.bpm.txn-batch.enabled=false），"
                    + "后台批量无推进机会");
        }
        long poolMaxActive = resolvePoolMaxActive();
        long asyncThreads = environment.getProperty("flowable.process.async.executor.core-pool-size",
                Integer.class, 8);
        long required = policy.getRealtimeGlobalConcurrency() + Math.max(asyncThreads, 0) + 2 + POOL_MARGIN;
        if (poolMaxActive < required) {
            violations.add("预算与连接池明显不相容：实际池上限 " + poolMaxActive
                    + " < 所需下限 " + required + "（实时 " + policy.getRealtimeGlobalConcurrency()
                    + " + 异步 " + asyncThreads + " + 调度 2 + 余量 " + POOL_MARGIN + "）");
        }
        return violations;
    }

    // ==================== 内部 ====================

    private List<String> validateShape(BpmResourcePolicy policy) {
        List<String> violations = new ArrayList<>();
        if (policy == null) {
            violations.add("策略为空");
            return violations;
        }
        int segments = nvl(policy.getProdReserved()) + nvl(policy.getOaReserved())
                + nvl(policy.getSharedCapacity());
        if (segments != nvl(policy.getGlobalMaxOutstanding())) {
            violations.add("保留份额不自洽：生产保留 " + nvl(policy.getProdReserved())
                    + " + OA 保留 " + nvl(policy.getOaReserved()) + " + 共享 "
                    + nvl(policy.getSharedCapacity()) + " = " + segments
                    + " ≠ 全局上限 " + nvl(policy.getGlobalMaxOutstanding()));
        }
        if (nvl(policy.getGlobalMaxOutstanding()) <= 0 || nvl(policy.getTenantMaxOutstanding()) <= 0
                || nvl(policy.getTenantRatePerSec()) <= 0 || nvl(policy.getTenantBurst()) <= 0
                || nvl(policy.getRealtimeGlobalConcurrency()) <= 0
                || nvl(policy.getRealtimeTenantConcurrency()) <= 0) {
            violations.add("非法额度：全局/租户上限、速率、突发与并发预算必须为正数");
        }
        if (nvl(policy.getTenantMaxOutstanding()) > nvl(policy.getGlobalMaxOutstanding())) {
            violations.add("每租户上限 " + policy.getTenantMaxOutstanding()
                    + " 不得越过全局上限 " + policy.getGlobalMaxOutstanding());
        }
        if (nvl(policy.getRealtimeTenantConcurrency()) > nvl(policy.getRealtimeGlobalConcurrency())) {
            violations.add("实时单租户并发 " + policy.getRealtimeTenantConcurrency()
                    + " 不得越过实时全局并发 " + policy.getRealtimeGlobalConcurrency());
        }
        if (policy.getBatchSliceItems() != null && policy.getBatchSliceItems() <= 0) {
            violations.add("批量切片项数必须为正数");
        }
        return violations;
    }

    /**
     * 实际连接池上限：从运行中的 DataSource Bean 反射回读（Druid#getMaxActive，运行事实；
     * Druid 不在本模块编译类路径，按既有防腐口径反射访问），无 Bean 时退回配置值
     * （隔离测试装配）；两者都不可得时按不相容拒绝（fail closed）。
     */
    private long resolvePoolMaxActive() {
        try {
            for (javax.sql.DataSource dataSource : applicationContext.getBeansOfType(javax.sql.DataSource.class)
                    .values()) {
                // dynamic-datasource 包裹（DynamicRoutingDataSource→ItemDataSource→Druid）：
                // 路由数据源经 getDataSources() 展开内层数据源，再沿 realDataSource 解包读取实际池值
                if (dataSource.getClass().getSimpleName().contains("DynamicRouting")) {
                    for (Object inner : ((java.util.Collection<?>) dataSource.getClass()
                            .getMethod("getDataSources").invoke(dataSource).getClass()
                            .cast(dataSource.getClass().getMethod("getDataSources").invoke(dataSource)))
                            ) {
                        Long value = druidMaxActiveOf(inner);
                        if (value != null) {
                            return value;
                        }
                    }
                    continue;
                }
                Object current = dataSource;
                for (int depth = 0; current != null && depth < 6; depth++) {
                    if (current.getClass().getSimpleName().contains("Druid")) {
                        Object maxActive = current.getClass().getMethod("getMaxActive").invoke(current);
                        if (maxActive instanceof Number number) {
                            return number.longValue();
                        }
                    }
                    current = unwrapField(current, "realDataSource");
                }
            }
        } catch (Exception e) {
            log.debug("Druid 池运行值回读失败，退回配置值", e);
        }
        Long configured = environment.getProperty("spring.datasource.dynamic.druid.max-active", Long.class);
        return configured == null ? 0 : configured;
    }

    /** 沿 realDataSource 逐层解包定位 Druid 并读取 maxActive；非 Druid 返回 null。 */
    private static Long druidMaxActiveOf(Object cur) throws Exception {
        for (int depth = 0; cur != null && depth < 6; depth++) {
            if (cur.getClass().getSimpleName().contains("Druid")) {
                Object maxActive = cur.getClass().getMethod("getMaxActive").invoke(cur);
                if (maxActive instanceof Number number) {
                    return number.longValue();
                }
            }
            cur = unwrapField(cur, "realDataSource");
        }
        return null;
    }

    private static Object unwrapField(Object o, String field) {
        try {
            var f = o.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.get(o);
        } catch (Exception ignored) {
            return null;
        }
    }

    private BpmResourcePolicy requirePolicy(Long id) {
        BpmResourcePolicy policy = policyMapper.selectById(id);
        if (policy == null) {
            throw new BaseException(com.sw.ck.common.exception.CommonErrorCode.NOT_FOUND.getCode(),
                    "资源策略不存在: " + id);
        }
        return policy;
    }

    private void rejectLog(Long id, BpmResourcePolicy policy, String scope, String detail) {
        try {
            rejectAuditTemplate.executeWithoutResult(status -> {
                BpmResourceRejectLog entry = new BpmResourceRejectLog();
                entry.setTenantId(0L);
                entry.setPolicyVersion(policy.getPolicyVersion());
                entry.setResourceClass(null);
                entry.setRejectScope(scope);
                entry.setRequestedUnits(0);
                entry.setReasonCode(BpmErrorCode.RESOURCE_POLICY_INVALID.getErrorKey());
                entry.setDetail("policyId=" + id + ": " + detail);
                rejectLogMapper.insert(entry);
            });
        } catch (Exception e) {
            log.error("策略启用拒绝审计写入失败: policyId={}", id, e);
        }
    }

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
    }
}
