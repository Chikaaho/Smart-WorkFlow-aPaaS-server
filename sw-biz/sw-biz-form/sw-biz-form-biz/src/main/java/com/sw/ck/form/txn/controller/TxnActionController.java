package com.sw.ck.form.txn.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sw.ck.common.response.R;
import com.sw.ck.form.txn.model.C1PolicySaveRequest;
import com.sw.ck.form.txn.model.C1PolicyView;
import com.sw.ck.form.txn.model.TxnActionSaveRequest;
import com.sw.ck.form.txn.model.TxnActionView;
import com.sw.ck.form.txn.model.TxnInvocationView;
import com.sw.ck.form.txn.model.TxnInvokeRequest;
import com.sw.ck.form.txn.model.TxnInvokeResult;
import com.sw.ck.form.txn.model.TxnLedgerView;
import com.sw.ck.form.txn.model.TxnPublishError;
import com.sw.ck.form.txn.model.TxnReservationView;
import com.sw.ck.form.txn.service.C1PolicyService;
import com.sw.ck.form.txn.service.TxnActionExecutor;
import com.sw.ck.form.txn.service.TxnActionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 低代码本地事务动作：管理（配置/发布校验/启停）、C1 策略、调用与结果查询。
 */
@RestController
@RequestMapping("/form/action")
public class TxnActionController {

    private final TxnActionService actionService;
    private final TxnActionExecutor executor;
    private final C1PolicyService c1PolicyService;
    private final com.sw.ck.form.txn.guard.TxnActionRealtimeGuard realtimeGuard;

    public TxnActionController(TxnActionService actionService,
                               TxnActionExecutor executor,
                               C1PolicyService c1PolicyService,
                               com.sw.ck.form.txn.guard.TxnActionRealtimeGuard realtimeGuard) {
        this.actionService = actionService;
        this.executor = executor;
        this.c1PolicyService = c1PolicyService;
        this.realtimeGuard = realtimeGuard;
    }

    // ==================== 管理 ====================

    @GetMapping("/list")
    @PreAuthorize("@ss.hasPermi('form:action:view')")
    public R<List<TxnActionView>> list(@RequestParam String formId) {
        return R.ok(actionService.listByForm(formId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('form:action:view')")
    public R<TxnActionView> get(@PathVariable String id) {
        return R.ok(actionService.get(id));
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermi('form:action:manage')")
    public R<TxnActionView> create(@RequestParam String formId, @RequestBody TxnActionSaveRequest req) {
        return R.ok(actionService.create(formId, req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('form:action:manage')")
    public R<TxnActionView> update(@PathVariable String id, @RequestBody TxnActionSaveRequest req) {
        return R.ok(actionService.update(id, req));
    }

    @PostMapping("/{id}/validate")
    @PreAuthorize("@ss.hasPermi('form:action:manage')")
    public R<List<TxnPublishError>> validate(@PathVariable String id) {
        return R.ok(actionService.validate(id));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("@ss.hasPermi('form:action:publish')")
    public R<TxnActionView> publish(@PathVariable String id) {
        return R.ok(actionService.publish(id));
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("@ss.hasPermi('form:action:manage')")
    public R<TxnActionView> disable(@PathVariable String id) {
        return R.ok(actionService.disable(id));
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("@ss.hasPermi('form:action:manage')")
    public R<TxnActionView> enable(@PathVariable String id) {
        return R.ok(actionService.enable(id));
    }

    // ==================== C1 策略 ====================

    @GetMapping("/c1-policy")
    @PreAuthorize("@ss.hasPermi('form:action:view')")
    public R<C1PolicyView> getC1Policy(@RequestParam String formId) {
        return R.ok(c1PolicyService.get(formId));
    }

    @PutMapping("/c1-policy")
    @PreAuthorize("@ss.hasPermi('form:action:publish')")
    public R<C1PolicyView> saveC1Policy(@RequestParam String formId, @RequestBody C1PolicySaveRequest req) {
        return R.ok(c1PolicyService.save(formId, req == null ? null : req.policy()));
    }

    // ==================== 调用与结果 ====================

    @PostMapping("/{id}/invoke")
    @PreAuthorize("@ss.hasPermi('form:action:invoke')")
    public R<TxnInvokeResult> invoke(@PathVariable String id, @RequestBody TxnInvokeRequest req) {
        // 实时入口并发预算（P62 资源保障）：仅约束本 HTTP 实时入口，超限明确拒绝不排队；
        // 内部异步调用方（轻流程节点/批量项）不占实时预算
        com.sw.ck.security.holder.LoginUser operator = com.sw.ck.security.holder.LoginUserHolder.get();
        Long tenantId = operator == null ? null : operator.getTenantId();
        return R.ok(realtimeGuard.callWithBudget(tenantId, () -> executor.invoke(id, req)));
    }

    @GetMapping("/invocations/{invocationId}")
    @PreAuthorize("@ss.hasPermi('form:action:invoke')")
    public R<TxnInvocationView> getInvocation(@PathVariable String invocationId) {
        return R.ok(executor.getInvocation(invocationId));
    }

    @GetMapping("/{id}/invocations")
    @PreAuthorize("@ss.hasPermi('form:action:invoke')")
    public R<Page<TxnInvocationView>> pageInvocations(@PathVariable String id,
                                                      @RequestParam(required = false) String status,
                                                      @RequestParam(required = false) Integer actionVersion,
                                                      @RequestParam(required = false) Long callerId,
                                                      @RequestParam(defaultValue = "1") long page,
                                                      @RequestParam(defaultValue = "20") long size) {
        return R.ok(executor.pageInvocations(id, status, actionVersion, callerId, page, size));
    }

    @GetMapping("/reservations/{reservationId}")
    @PreAuthorize("@ss.hasPermi('form:action:invoke')")
    public R<TxnReservationView> getReservation(@PathVariable String reservationId) {
        return R.ok(executor.getReservation(reservationId));
    }

    @GetMapping("/{id}/reservations")
    @PreAuthorize("@ss.hasPermi('form:action:invoke')")
    public R<Page<TxnReservationView>> pageReservations(@PathVariable String id,
                                                        @RequestParam(required = false) String status,
                                                        @RequestParam(defaultValue = "1") long page,
                                                        @RequestParam(defaultValue = "20") long size) {
        return R.ok(executor.pageReservations(id, status, page, size));
    }

    @GetMapping("/{id}/ledger")
    @PreAuthorize("@ss.hasPermi('form:action:invoke')")
    public R<Page<TxnLedgerView>> pageLedger(@PathVariable String id,
                                             @RequestParam(required = false) Integer actionVersion,
                                             @RequestParam(required = false) String reservationId,
                                             @RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "20") long size) {
        return R.ok(executor.pageLedger(id, actionVersion, reservationId, page, size));
    }
}
