package com.sw.ck.bpm.process.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.api.script.BpmScriptEvaluatePort;
import com.sw.ck.bpm.process.entity.BpmActionRef;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.mapper.BpmActionRefMapper;
import com.sw.ck.bpm.process.mapper.BpmTriggerExecMapper;
import com.sw.ck.bpm.process.queue.BpmCommandQueue;
import com.sw.ck.bpm.process.queue.CommandEnvelope;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.BpmVariableSnapshotService;
import com.sw.ck.bpm.process.service.CommandRetryService;
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
 * {@link BpmTriggerController} 回查与重试单测（A04/A11）。
 * <p>
 * 覆盖：动作意图回查按 target_record_id 动态解析关联实例（FLOW_START 异步二段创建，
 * ref 行不回填 target_instance_id）；失败意图重试复用同键命令且不依赖冻结图配置
 * （关入口收敛：触发器配置删除后存量 FAILED 意图仍可恢复）。
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
    private final BpmCommandQueue commandQueue = mock(BpmCommandQueue.class);
    private final CommandRetryService commandRetryService = mock(CommandRetryService.class);
    private final BpmTriggerController controller = new BpmTriggerController(bpmTaskFacade,
            bpmInstanceService, nodeFormDataService, variableSnapshotService, scriptRunner,
            triggerExecMapper, actionRefMapper, commandQueue, commandRetryService);

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
    @DisplayName("失败意图重试：复用同键命令且不读冻结图配置（关入口后仍可恢复）")
    void shouldRetryFailedRefWithoutGraphDependency() {
        BpmActionRef failed = ref();
        failed.setStatus("FAILED");
        failed.setErrorText("目标表单暂不可用");
        when(actionRefMapper.selectById(1L)).thenReturn(failed);
        CommandEnvelope envelope = new CommandEnvelope();
        envelope.setCommandId(77L);
        envelope.setCommandKey(failed.getCommandKey());
        when(commandQueue.findByKey(9L, failed.getCommandKey())).thenReturn(Optional.of(envelope));
        when(commandRetryService.requeueFailed(envelope)).thenReturn(77L);

        R<Map<String, Object>> result = controller.retryActionRef(1L);

        assertThat(result.getData())
                .containsEntry("commandId", 77L)
                .containsEntry("status", "INTENT_SUBMITTED");
        assertThat(failed.getStatus()).isEqualTo("INTENT_SUBMITTED");
        assertThat(failed.getErrorText()).isNull();
        // 重试路径零图配置依赖：触发器在当前/冻结版本中是否仍存在不影响恢复
        verify(nodeFormDataService, never()).loadGraph(anyString(), any());
    }
}
