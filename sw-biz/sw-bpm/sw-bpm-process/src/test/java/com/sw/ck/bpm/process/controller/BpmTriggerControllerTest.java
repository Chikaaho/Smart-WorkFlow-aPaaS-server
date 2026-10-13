package com.sw.ck.bpm.process.controller;

import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.api.script.BpmScriptEvaluatePort;
import com.sw.ck.bpm.process.entity.BpmActionRef;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.mapper.BpmActionRefMapper;
import com.sw.ck.bpm.process.mapper.BpmTriggerExecMapper;
import com.sw.ck.bpm.process.service.ActionRefRecoveryService;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.BpmVariableSnapshotService;
import com.sw.ck.bpm.process.service.NodeFormDataService;
import com.sw.ck.common.response.R;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BpmTriggerController} 回查与受控恢复单测（A04/A11；复审05 P1-06b 更新）。
 * <p>
 * 覆盖：动作意图回查按 target_record_id 动态解析关联实例（FLOW_START 异步二段创建，
 * ref 行不回填 target_instance_id）；已成功启动拒绝恢复；未建实例窗口交由
 * {@link ActionRefRecoveryService} 按持久状态给出可诊断结果或受控恢复（不再 500），
 * 恢复不读冻结图配置（关入口收敛：触发器配置删除后存量失败意图仍可恢复）。
 * </p>
 */
@DisplayName("P64 触发器回查控制器测试")
class BpmTriggerControllerTest {

    private final BpmTaskFacade bpmTaskFacade = mock(BpmTaskFacade.class);
    private final BpmInstanceService bpmInstanceService = mock(BpmInstanceService.class);
    private final NodeFormDataService nodeFormDataService = mock(NodeFormDataService.class);
    private final BpmVariableSnapshotService variableSnapshotService = mock(BpmVariableSnapshotService.class);
    private final BpmScriptEvaluatePort scriptRunner = mock(BpmScriptEvaluatePort.class);
    private final BpmTriggerExecMapper triggerExecMapper = mock(BpmTriggerExecMapper.class);
    private final BpmActionRefMapper actionRefMapper = mock(BpmActionRefMapper.class);
    private final ActionRefRecoveryService actionRefRecoveryService = mock(ActionRefRecoveryService.class);
    private final BpmTriggerController controller = new BpmTriggerController(bpmTaskFacade,
            bpmInstanceService, nodeFormDataService, variableSnapshotService, scriptRunner,
            triggerExecMapper, actionRefMapper, actionRefRecoveryService);

    @BeforeEach
    void setUp() {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(2L);
        loginUser.setTenantId(9L);
        LoginUserHolder.set(loginUser);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    private BpmActionRef ref() {
        BpmActionRef ref = new BpmActionRef();
        ref.setId(1L);
        ref.setTenantId(9L);
        ref.setProcessInstanceId("pi-1");
        ref.setTriggerId("trg-1");
        ref.setActionId("act-1");
        ref.setCommandKey("P64ACT:TRG:pi-1:trg-1:NODE_ROUND_COMPLETED:1:act-1:11");
        ref.setTargetDefKey("def_target");
        ref.setTargetFormKey("target_form");
        ref.setTargetRecordId("rec-target-1");
        ref.setStatus("STARTED");
        return ref;
    }

    @Test
    @DisplayName("动作意图回查：按 target_record_id 动态解析关联目标实例（异步创建可见）")
    void shouldResolveTargetInstanceByRecordId() {
        when(bpmInstanceService.findByProcessInstanceId("pi-1"))
                .thenReturn(Optional.of(new BpmInstance()));
        when(actionRefMapper.selectList(any())).thenReturn(List.of(ref()));
        BpmInstance target = new BpmInstance();
        target.setProcessInstanceId("pi-target-9");
        when(bpmInstanceService.findByBusinessKey("rec-target-1")).thenReturn(Optional.of(target));

        R<List<Map<String, Object>>> result = controller.listActionRefs("pi-1");

        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData().get(0))
                .containsEntry("targetRecordId", "rec-target-1")
                .containsEntry("targetInstanceId", "pi-target-9");
    }

    @Test
    @DisplayName("状态语义：STARTING 且实例未建显示 STARTING（不冒称已启动）；实例已建解析 STARTED")
    void shouldResolveStartingDisplayByInstanceFact() {
        when(bpmInstanceService.findByProcessInstanceId("pi-1"))
                .thenReturn(Optional.of(new BpmInstance()));
        BpmActionRef starting = ref();
        starting.setStatus("STARTING");
        BpmActionRef started = ref();
        started.setStatus("STARTING");
        started.setTargetRecordId("rec-done-2");
        when(actionRefMapper.selectList(any())).thenReturn(List.of(starting, started));
        BpmInstance target = new BpmInstance();
        target.setProcessInstanceId("pi-target-9");
        when(bpmInstanceService.findByBusinessKey("rec-target-1")).thenReturn(Optional.empty());
        when(bpmInstanceService.findByBusinessKey("rec-done-2")).thenReturn(Optional.of(target));

        R<List<Map<String, Object>>> result = controller.listActionRefs("pi-1");

        assertThat(result.getData()).hasSize(2);
        assertThat(result.getData().get(0)).containsEntry("status", "STARTING");
        assertThat(result.getData().get(1)).containsEntry("status", "STARTED");
    }

    @Test
    @DisplayName("重试门槛按持久事实：目标实例已建（已成功启动）拒绝恢复")
    void shouldRejectRetryWhenInstanceAlreadyExists() {
        BpmActionRef starting = ref();
        starting.setStatus("STARTING");
        when(actionRefMapper.selectById(1L)).thenReturn(starting);
        BpmInstance target = new BpmInstance();
        target.setProcessInstanceId("pi-target-9");
        when(bpmInstanceService.findByBusinessKey("rec-target-1")).thenReturn(Optional.of(target));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.retryActionRef(1L))
                .isInstanceOf(com.sw.ck.common.exception.BaseException.class)
                .hasMessageContaining("已成功启动");
        verify(actionRefRecoveryService, never()).retry(any());
    }

    @Test
    @DisplayName("FLOW_START 失败窗口：未建实例交由恢复服务，返回受控恢复结果（不再 500）")
    void shouldDelegateRecoveryWhenInstanceNotYetCreated() {
        BpmActionRef starting = ref();
        starting.setStatus("STARTING");
        when(actionRefMapper.selectById(1L)).thenReturn(starting);
        when(bpmInstanceService.findByBusinessKey("rec-target-1")).thenReturn(Optional.empty());
        when(actionRefRecoveryService.retry(starting)).thenReturn(
                new ActionRefRecoveryService.RetryOutcome(
                        "RECOVERY_ENQUEUED", "已按当前绑定受理恢复发起（目标实例由既有消费链创建，原失败行保留）", 78L));

        R<Map<String, Object>> result = controller.retryActionRef(1L);

        assertThat(result.getData())
                .containsEntry("status", "RECOVERY_ENQUEUED")
                .containsEntry("commandId", 78L);
    }

    @Test
    @DisplayName("零目标安全处置：无有效绑定时返回可诊断结果而非异常（明确处置路径）")
    void shouldReturnDiagnosableOutcomeInsteadOfError() {
        BpmActionRef starting = ref();
        starting.setStatus("STARTING");
        when(actionRefMapper.selectById(1L)).thenReturn(starting);
        when(bpmInstanceService.findByBusinessKey("rec-target-1")).thenReturn(Optional.empty());
        when(actionRefRecoveryService.retry(starting)).thenReturn(
                new ActionRefRecoveryService.RetryOutcome(
                        "DISPOSED_NO_BINDING", "表单当前无启用流程绑定：按明确安全处置零目标启动；如需发起请先恢复绑定后再次恢复", null));

        R<Map<String, Object>> result = controller.retryActionRef(1L);

        assertThat(result.getData())
                .containsEntry("status", "DISPOSED_NO_BINDING")
                .containsEntry("commandId", null);
        assertThat((String) result.getData().get("message")).contains("零目标");
    }

    @Test
    @DisplayName("失败意图恢复：不读冻结图配置（关入口后仍可恢复）")
    void shouldRetryFailedRefWithoutGraphDependency() {
        BpmActionRef failed = ref();
        failed.setStatus("FAILED");
        failed.setErrorText("目标表单暂不可用");
        when(actionRefMapper.selectById(1L)).thenReturn(failed);
        when(actionRefRecoveryService.retry(failed)).thenReturn(
                new ActionRefRecoveryService.RetryOutcome(
                        "INTENT_SUBMITTED", "失败意图已重新入队（复用同键命令，原失败记录保留）", 77L));

        R<Map<String, Object>> result = controller.retryActionRef(1L);

        assertThat(result.getData())
                .containsEntry("commandId", 77L)
                .containsEntry("status", "INTENT_SUBMITTED");
        // 恢复路径零图配置依赖：触发器在当前/冻结版本中是否仍存在不影响恢复
        verify(nodeFormDataService, never()).loadGraph(anyString(), any());
    }
}
