package com.sw.ck.form.txn.guard;

import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.api.port.TxnActionRuntimePort;
import com.sw.ck.common.exception.BaseException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * 实时受控动作并发闸（P62 资源保障；form 侧实时入口的唯一运行强制点）。
 * <p>
 * 合同：实时动作全局最多 {@code sw.form.txn-action.realtime-global-concurrency}（默认 16）、
 * 单租户最多 {@code ...realtime-tenant-concurrency}（默认 8）个同时在途。仅约束 HTTP 实时
 * 入口（{@code TxnActionController#invoke}）——内部异步调用方（轻流程节点/批量项）不占实时
 * 并发预算，由引擎线程池/调度切片约束。超限拒绝（明确错误 key、适用额度与可重试提示），
 * 不排队、不静默等待。进程内信号量即单应用进程画像下的准入事实；重启后计数归零（在途
 * 请求随进程终止，无跨进程遗留）。
 * </p>
 */
@Component
public class TxnActionRealtimeGuard implements TxnActionRuntimePort {

    private static final Logger log = LoggerFactory.getLogger(TxnActionRealtimeGuard.class);

    @Value("${sw.form.txn-action.realtime-global-concurrency:16}")
    private int globalMaxConcurrent;

    @Value("${sw.form.txn-action.realtime-tenant-concurrency:8}")
    private int tenantMaxConcurrent;

    private Semaphore globalPermits;
    private ConcurrentHashMap<Long, Semaphore> tenantPermits;

    @PostConstruct
    void init() {
        this.globalPermits = new Semaphore(Math.max(1, globalMaxConcurrent));
        this.tenantPermits = new ConcurrentHashMap<>();
        log.info("实时动作并发闸已装配: globalMax={}, tenantMax={}",
                globalMaxConcurrent, tenantMaxConcurrent);
    }

    /**
     * 在实时并发预算内执行调用；全局或租户预算耗尽时立即拒绝（不排队）。
     */
    public <T> T callWithBudget(Long tenantId, Supplier<T> action) {
        if (!globalPermits.tryAcquire()) {
            throw new BaseException(FormErrorCode.ACTION_REALTIME_CONCURRENCY_EXCEEDED,
                    "实时动作并发已达全局上限 " + globalMaxConcurrent + "，请稍后重试");
        }
        try {
            Semaphore tenantPermit = tenantPermits.computeIfAbsent(
                    tenantId == null ? 0L : tenantId,
                    key -> new Semaphore(Math.max(1, tenantMaxConcurrent)));
            if (!tenantPermit.tryAcquire()) {
                throw new BaseException(FormErrorCode.ACTION_REALTIME_CONCURRENCY_EXCEEDED,
                        "实时动作并发已达本租户上限 " + tenantMaxConcurrent + "，请稍后重试");
            }
            try {
                return action.get();
            } finally {
                tenantPermit.release();
            }
        } finally {
            globalPermits.release();
        }
    }

    @Override
    public java.util.Optional<RealtimeGuardProfile> realtimeGuardProfile() {
        Map<Long, Integer> tenantInFlight = new HashMap<>();
        tenantPermits.forEach((tenant, permit) ->
                tenantInFlight.put(tenant, Math.max(1, tenantMaxConcurrent) - permit.availablePermits()));
        return java.util.Optional.of(new RealtimeGuardProfile(globalMaxConcurrent, tenantMaxConcurrent,
                Math.max(1, globalMaxConcurrent) - globalPermits.availablePermits(), tenantInFlight));
    }
}
