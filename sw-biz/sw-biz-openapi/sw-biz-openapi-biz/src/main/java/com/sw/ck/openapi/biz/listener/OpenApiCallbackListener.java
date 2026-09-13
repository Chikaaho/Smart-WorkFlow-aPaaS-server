package com.sw.ck.openapi.biz.listener;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sw.ck.bpm.api.event.BpmNotifyEvent;
import com.sw.ck.bpm.api.event.BpmNotifyTrigger;
import com.sw.ck.openapi.biz.entity.OpenApiApp;
import com.sw.ck.openapi.biz.mapper.OpenApiAppMapper;
import com.sw.ck.openapi.biz.service.OpenApiCallbackDeliveryService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 流程状态出站回调事件转发（I4 §3.4）。
 * <p>
 * 只转发实例终态事件到 {@link OpenApiCallbackDeliveryService}（签名/重试/去重/
 * 失败可查/恢复重发的唯一实现）；载荷只含白名单字段（实例/事件/租户），敏感表单数据不出站。
 * 回调失败不回滚已合法完成的流程动作（监听 AFTER_COMMIT）。
 * </p>
 */
@Component
public class OpenApiCallbackListener {

    private static final List<BpmNotifyTrigger> TERMINAL = List.of(
            BpmNotifyTrigger.PROCESS_APPROVED, BpmNotifyTrigger.PROCESS_REJECTED,
            BpmNotifyTrigger.PROCESS_DISAPPROVED, BpmNotifyTrigger.PROCESS_WITHDRAWN,
            BpmNotifyTrigger.PROCESS_DISCARDED);

    private final OpenApiAppMapper appMapper;
    private final OpenApiCallbackDeliveryService deliveryService;

    public OpenApiCallbackListener(OpenApiAppMapper appMapper,
                                   OpenApiCallbackDeliveryService deliveryService) {
        this.appMapper = appMapper;
        this.deliveryService = deliveryService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEvent(BpmNotifyEvent event) {
        if (event == null || event.getBizId() == null
                || !TERMINAL.contains(event.getTrigger())) {
            return;
        }
        List<OpenApiApp> apps = appMapper.selectList(new LambdaQueryWrapper<OpenApiApp>()
                .eq(OpenApiApp::getTenantId, event.getTenantId())
                .eq(OpenApiApp::getStatus, "ENABLED")
                .isNotNull(OpenApiApp::getCallbackUrl)
                .eq(OpenApiApp::getDeleted, 0));
        for (OpenApiApp app : apps) {
            deliveryService.dispatch(app, event);
        }
    }
}
