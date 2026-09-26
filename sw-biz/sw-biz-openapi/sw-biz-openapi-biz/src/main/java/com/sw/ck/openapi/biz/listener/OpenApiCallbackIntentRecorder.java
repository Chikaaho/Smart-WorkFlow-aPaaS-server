package com.sw.ck.openapi.biz.listener;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sw.ck.bpm.api.event.BpmNotifyEvent;
import com.sw.ck.bpm.api.event.BpmNotifyTrigger;
import com.sw.ck.openapi.biz.entity.OpenApiApp;
import com.sw.ck.openapi.biz.entity.OpenApiCallbackTask;
import com.sw.ck.openapi.biz.mapper.OpenApiAppMapper;
import com.sw.ck.openapi.biz.mapper.OpenApiCallbackTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 出站回调意图的事务内登记器（Phase 4 可靠业务事件 —— OpenAPI 回调 MUST_DELIVER）。
 *
 * <p>在 {@link BpmNotifyEvent} 发布处<b>同步</b>执行：为每个启用回调的应用写一条
 * {@code sw_openapi_callback_task}（PENDING，不执行任何 HTTP），因此回调意图随业务事务
 * 提交或回滚；提交后由提交后加速或恢复调度完成签名投递。
 * 原先“只在进程内重试 3 次、失败仅留日志”的路径不再作为唯一事实源。</p>
 */
@Component
public class OpenApiCallbackIntentRecorder {

    private static final Logger log = LoggerFactory.getLogger(OpenApiCallbackIntentRecorder.class);

    /** 只登记实例终态事件（与提交后投递口径一致）。 */
    static final List<BpmNotifyTrigger> TERMINAL = List.of(
            BpmNotifyTrigger.PROCESS_APPROVED, BpmNotifyTrigger.PROCESS_REJECTED,
            BpmNotifyTrigger.PROCESS_DISAPPROVED, BpmNotifyTrigger.PROCESS_WITHDRAWN,
            BpmNotifyTrigger.PROCESS_DISCARDED);

    private final OpenApiAppMapper appMapper;
    private final OpenApiCallbackTaskMapper taskMapper;

    public OpenApiCallbackIntentRecorder(OpenApiAppMapper appMapper,
                                         OpenApiCallbackTaskMapper taskMapper) {
        this.appMapper = appMapper;
        this.taskMapper = taskMapper;
    }

    @EventListener
    public void onBpmNotify(BpmNotifyEvent event) {
        record(event);
    }

    /**
     * 登记该事件的回调任务（每个启用且配置 callbackUrl 的应用一条）。
     *
     * @return 已登记任务数
     */
    public int record(BpmNotifyEvent event) {
        if (event == null || event.getBizId() == null || !TERMINAL.contains(event.getTrigger())) {
            return 0;
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("回调意图登记发生在无事务上下文（任务将独立提交，业务回滚不回滚该任务）:"
                            + " trigger={}, bizId={}",
                    event.getTrigger(), event.getBizId());
        }
        List<OpenApiApp> apps = appMapper.selectList(new LambdaQueryWrapper<OpenApiApp>()
                .eq(OpenApiApp::getTenantId, event.getTenantId())
                .eq(OpenApiApp::getStatus, "ENABLED")
                .isNotNull(OpenApiApp::getCallbackUrl)
                .eq(OpenApiApp::getDeleted, 0));
        int recorded = 0;
        for (OpenApiApp app : apps) {
            OpenApiCallbackTask task = new OpenApiCallbackTask();
            task.setAppId(app.getAppId());
            task.setEvent(event.getTrigger().name());
            task.setBizRef(event.getBizId());
            task.setStatus("PENDING");
            task.setAttempts(0);
            task.setNextRetryTime(LocalDateTime.now());
            task.setTenantId(event.getTenantId());
            try {
                taskMapper.insert(task);
                recorded++;
            } catch (DuplicateKeyException duplicate) {
                // 幂等：同 (租户,应用,事件,业务对象) 已登记，重复发布不新建任务
                log.debug("回调任务幂等命中: appId={}, event={}, bizRef={}",
                        app.getAppId(), event.getTrigger(), event.getBizId());
            }
        }
        log.debug("回调意图已登记: trigger={}, bizId={}, apps={}, recorded={}, inTx={}",
                event.getTrigger(), event.getBizId(), apps.size(), recorded,
                TransactionSynchronizationManager.isActualTransactionActive());
        return recorded;
    }
}
