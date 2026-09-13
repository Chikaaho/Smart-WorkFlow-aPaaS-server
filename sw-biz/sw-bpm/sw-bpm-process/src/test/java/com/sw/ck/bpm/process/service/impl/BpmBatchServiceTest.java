package com.sw.ck.bpm.process.service.impl;

import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.bpm.process.service.TaskActionService;
import com.sw.ck.bpm.process.service.BpmBatchService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.response.R;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * I4 §3.5 批量审批行为：逐项独立结果、单项失败不掩盖其他项、整批汇总不掩盖部分执行。
 */
class BpmBatchServiceTest {

    private final TaskActionService lifecycle = mock(TaskActionService.class);
    private final BpmBatchService service = new BpmBatchServiceImpl(lifecycle);

    @Test
    void shouldReturnPerItemResultsWithoutMaskingFailures() {
        when(lifecycle.execute(eq("t1"), any(ApprovalActionRequest.class)))
                .thenReturn(R.ok());
        when(lifecycle.execute(eq("t2"), any(ApprovalActionRequest.class)))
                .thenThrow(new BaseException(2308, "审批意见不能为空"));
        when(lifecycle.execute(eq("t3"), any(ApprovalActionRequest.class)))
                .thenThrow(new BaseException(403, "无权限"));

        List<Map<String, Object>> results = service.batchAction(List.of(
                new BpmBatchService.BatchItem("t1", ApprovalAction.APPROVE, "ok",
                        null, null, Map.of(), null),
                new BpmBatchService.BatchItem("t2", ApprovalAction.APPROVE, null,
                        null, null, Map.of(), null),
                new BpmBatchService.BatchItem("t3", ApprovalAction.APPROVE, "x",
                        null, null, Map.of(), null)));

        assertThat(results).hasSize(3);
        assertThat(results.get(0)).containsEntry("taskId", "t1").containsEntry("success", true);
        assertThat(results.get(1)).containsEntry("taskId", "t2").containsEntry("success", false)
                .containsEntry("errorCode", 2308);
        assertThat(results.get(2)).containsEntry("taskId", "t3").containsEntry("success", false)
                .containsEntry("errorCode", 403);
        verify(lifecycle, times(3)).execute(any(), any());
    }

    @Test
    void shouldPropagateOpinionFormRequirementsPerItem() {
        when(lifecycle.execute(eq("t9"), any())).thenReturn(R.ok());
        service.batchAction(List.of(new BpmBatchService.BatchItem("t9", ApprovalAction.RETURN,
                "退回原因", "form-1", "2", Map.of("reason", "材料缺失"), "node_start")));
        ArgumentCaptor<ApprovalActionRequest> captor = ArgumentCaptor.forClass(ApprovalActionRequest.class);
        verify(lifecycle).execute(eq("t9"), captor.capture());
        ApprovalActionRequest request = captor.getValue();
        assertThat(request.getAction()).isEqualTo(ApprovalAction.RETURN);
        assertThat(request.getOpinionFormId()).isEqualTo("form-1");
        assertThat(request.getReturnTargetNodeId()).isEqualTo("node_start");
        assertThat(request.getOpinionData()).containsEntry("reason", "材料缺失");
    }
}
