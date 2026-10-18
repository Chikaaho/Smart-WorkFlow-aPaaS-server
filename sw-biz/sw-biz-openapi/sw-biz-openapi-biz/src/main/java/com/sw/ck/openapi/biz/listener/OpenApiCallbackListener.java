package com.sw.ck.openapi.biz.listener;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sw.ck.bpm.api.event.BpmNotifyEvent;
import com.sw.ck.bpm.api.event.BpmNotifyTrigger;
import com.sw.ck.common.constant.CommonConstants;
import com.sw.ck.openapi.biz.entity.OpenApiApp;
import com.sw.ck.openapi.biz.mapper.OpenApiAppMapper;
import com.sw.ck.openapi.biz.service.OpenApiCallbackDeliveryService;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>
 * 异步线程无请求线程身份（租户行拦截器无身份即 fail-closed）：入口按事件携带的权威
 * 租户还原系统执行身份（与 {@code BpmNotifyListener} 同口径），用毕清理；事件缺失
 * 租户身份时拒绝处理，不回退为全局查询。
 * </p>
 */
@Component
public class OpenApiCallbackListener {

    private static final Logger log = LoggerFactory.getLogger(OpenApiCallbackListener.class);

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
        Long tenantId = event.getTenantId();
        if (tenantId == null) {
            // 后台投递以事件携带的权威租户为唯一身份来源；缺失即拒绝，不得退化为全局查询
            log.warn("回调事件缺少权威租户身份，拒绝处理: trigger={}, bizId={}",
                    event.getTrigger(), event.getBizId());
            return;
        }
        // @Async 线程无请求线程身份：按事件租户还原执行身份后投递，租户行过滤保持生效
        LoginUser identity = new LoginUser();
        identity.setUserId(event.getActorUserId() != null
                ? event.getActorUserId() : CommonConstants.SYSTEM_OPERATOR_ID);
        identity.setTenantId(tenantId);
        LoginUserHolder.set(identity);
        try {
            List<OpenApiApp> apps = appMapper.selectList(new LambdaQueryWrapper<OpenApiApp>()
                    .eq(OpenApiApp::getTenantId, tenantId)
                    .eq(OpenApiApp::getStatus, "ENABLED")
                    .isNotNull(OpenApiApp::getCallbackUrl)
                    .eq(OpenApiApp::getDeleted, 0));
            for (OpenApiApp app : apps) {
                deliveryService.dispatch(app, event);
            }
        } finally {
            LoginUserHolder.clear();
        }
    }
}
