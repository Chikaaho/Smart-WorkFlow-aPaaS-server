package com.sw.ck.bpm.process.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.ActionConfig;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.facade.BpmRuntimeFacade;
import com.sw.ck.bpm.process.entity.BpmChildBatch;
import com.sw.ck.bpm.process.entity.BpmChildItem;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.BpmTaskFormData;
import com.sw.ck.bpm.process.mapper.BpmChildBatchMapper;
import com.sw.ck.bpm.process.mapper.BpmChildItemMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.facade.FormDataWritebackFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P64 阶段Ⅱ主子流程编排单测（A05/A06）。
 * <p>
 * 覆盖：批次冻结（策略/K/嵌套与根链护栏/父记录版本快照）、子完成回写（稳定行身份 +
 * 版本守卫 + 允许字段 + 来源可追溯）、四等待策略结算一次、失败/冲突不凑成功数、
 * 迟到结果留痕不覆盖、父取消/退回终止写回推进权、回写冲突受控恢复。
 * </p>
 */
@DisplayName("P64 阶段Ⅱ主子流程编排测试")
class ChildOrchestrationServiceTest {

    @org.junit.jupiter.api.BeforeAll
    static void initTableInfo() {
        // LambdaWrapper 解析 SFunction 需要实体 lambda 缓存；纯单测无 MyBatis 上下文，手动初始化
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                BpmChildBatch.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                BpmChildItem.class);
    }

    private com.sw.ck.bpm.process.mapper.BpmActionRefMapper actionRefMapper;
    private BpmChildBatchMapper batchMapper;
    private BpmChildItemMapper itemMapper;
    private NodeFormDataService nodeFormDataService;
    private BpmInstanceService bpmInstanceService;
    private BpmRuntimeFacade bpmRuntimeFacade;
    private FormDataWritebackFacade writebackFacade;
    private ChildOrchestrationService service;

    @BeforeEach
    void setUp() {
        actionRefMapper = mock(com.sw.ck.bpm.process.mapper.BpmActionRefMapper.class);
        batchMapper = mock(BpmChildBatchMapper.class);
        itemMapper = mock(BpmChildItemMapper.class);
        nodeFormDataService = mock(NodeFormDataService.class);
        bpmInstanceService = mock(BpmInstanceService.class);
        bpmRuntimeFacade = mock(BpmRuntimeFacade.class);
        writebackFacade = mock(FormDataWritebackFacade.class);
        service = new ChildOrchestrationService(actionRefMapper, batchMapper, itemMapper, nodeFormDataService,
                bpmInstanceService, bpmRuntimeFacade, writebackFacade, new ObjectMapper());
        // parseData 为实例方法：以真实 Jackson 语义应答（与实现口径一致）
        com.fasterxml.jackson.databind.ObjectMapper realMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        when(nodeFormDataService.parseData(anyString())).thenAnswer(invocation -> {
            String json = invocation.getArgument(0);
            if (json == null || json.isBlank()) {
                return new java.util.LinkedHashMap<String, Object>();
            }
            return realMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() { });
        });
        // 唤醒门面契约恒 present
        when(bpmRuntimeFacade.signalWaitNode(anyString(), anyString()))
                .thenReturn(Optional.of(com.sw.ck.bpm.api.result.MutationOutcome.APPLIED));
    }

    // ==================== 派发冻结 ====================

    @Test
    @DisplayName("freezeBatch：冻结批次身份/策略/K/预期数与父记录版本快照；COUNT K 超过预期整体拒绝")
    void freezeBatch_freezesIdentityAndRejectsInvalidCount() {
        BpmInstance parent = parent();
        when(batchMapper.selectCount(any())).thenReturn(0L);
        when(itemMapper.selectList(any())).thenReturn(List.of());
        when(writebackFacade.readVersion(eq(9L), eq("parent_form"), eq("rec-1"),
                isNull(), isNull())).thenReturn(Optional.of(7L));

        ActionConfig action = childAction("act_all", "ALL", null);
        BpmChildBatch batch = service.freezeBatch(parent, "trg", action, "TRG:p1:trg:1:1",
                3, 1L, "parent_form", 9L);

        assertThat(batch.getBatchKey()).isEqualTo("CHILD:TRG:p1:trg:1:1:act_all");
        assertThat(batch.getWaitPolicy()).isEqualTo("ALL");
        assertThat(batch.getExpectedCount()).isEqualTo(3);
        assertThat(batch.getParentDepth()).isZero();
        assertThat(batch.getRootInstanceId()).isEqualTo("p1");
        assertThat(batch.getSourceRecordId()).isEqualTo("rec-1");
        assertThat(batch.getSourceRecordVersion()).isEqualTo(7L);

        assertThatThrownBy(() -> service.freezeBatch(parent, "trg",
                childAction("act_c", "COUNT", 5), "TRG:p1:trg:1:2", 3, 1L, "parent_form", 9L))
                .isInstanceOfSatisfying(BaseException.class, e ->
                        assertThat(e.getCode()).isEqualTo(BpmErrorCode.CHILD_ACTION_INVALID.getCode()));
    }

    @Test
    @DisplayName("freezeBatch：嵌套超限（子层深度达上限）阻止派发")
    void freezeBatch_nestingOverLimitRejected() {
        // 先完成子桩（doReturn 完整链），再作为参数进入外层打桩
        BpmChildItem parentItem = itemWithBatch(3);
        when(itemMapper.selectList(any())).thenReturn(List.of(parentItem));
        when(batchMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.freezeBatch(parent(), "trg",
                childAction("act_all", "ALL", null), "TRG:x:1", 2, 1L, "parent_form", 9L))
                .isInstanceOfSatisfying(BaseException.class, e ->
                        assertThat(e.getCode()).isEqualTo(BpmErrorCode.CHILD_NESTING_OVER_LIMIT.getCode()));
    }

    // ==================== 子完成回写与结算 ====================

    @Test
    @DisplayName("子完成回写：按冻结行身份+版本调用受控回写，允许字段受控、来源可追溯；ALL 全部 WRITTEN 后结算一次并唤醒等待节点")
    void childApprovedAppliesRowWritebackAndSettlesAllOnce() {
        BpmChildBatch batch = waitingBatch("ALL", 2, null);
        when(batchMapper.selectById(batch.getId())).thenReturn(batch);
        when(itemMapper.selectList(any())).thenReturn(List.of(
                dispatchedItem(batch.getId(), "row-a", "row-a", 3L),
                dispatchedItem(batch.getId(), "row-b", "row-b", 4L)));
        BpmInstance child = child("rec-b");
        when(nodeFormDataService.listSubmittedByNode(9L, "pi-child", "node_result"))
                .thenReturn(List.of(submitted(2L, """
                        {"result_table":[{"id":"row-a","feedback":"OK-A","extra":"x"},
                                         {"id":"row-b","feedback":"OK","extra":"y"}],
                         "summary_text":"done"}""")));
        BpmInstance parent = parent();
        when(bpmInstanceService.findByProcessInstanceId("p1")).thenReturn(Optional.of(parent));
        when(nodeFormDataService.loadGraph("parent_def", 1)).thenReturn(graphWithWaitNode());
        when(writebackFacade.applyWriteback(any())).thenReturn(Optional.of(
                new FormDataWritebackFacade.WritebackResult(
                        FormDataWritebackFacade.WritebackResult.WRITTEN, 5L)));
        when(batchMapper.update(any(), any())).thenReturn(1);
        when(itemMapper.update(any(), any())).thenReturn(1);

        service.onChildProcessTerminal(child, "APPROVED");

        // 行级 + 主记录两段回写 × 两个批次项：稳定行身份 + 派发冻结版本 + 允许字段（extra 不回写）
        ArgumentCaptor<FormDataWritebackFacade.WritebackRequest> captor =
                ArgumentCaptor.forClass(FormDataWritebackFacade.WritebackRequest.class);
        verify(writebackFacade, org.mockito.Mockito.times(4)).applyWriteback(captor.capture());
        List<FormDataWritebackFacade.WritebackRequest> requests = captor.getAllValues();
        FormDataWritebackFacade.WritebackRequest rowRequest = requests.get(0);
        assertThat(rowRequest.tenantId()).isEqualTo(9L);
        assertThat(rowRequest.formKey()).isEqualTo("parent_form");
        assertThat(rowRequest.recordId()).isEqualTo("rec-1");
        assertThat(rowRequest.tableField()).isEqualTo("parent_table");
        assertThat(rowRequest.rowId()).isIn("row-a", "row-b");
        assertThat(rowRequest.expectedRowVersion()).isIn(3L, 4L);
        assertThat(rowRequest.fields()).containsOnlyKeys("feedback");
        FormDataWritebackFacade.WritebackRequest mainRequest = requests.get(1);
        assertThat(mainRequest.tableField()).isNull();
        assertThat(mainRequest.expectedRowVersion()).isEqualTo(7L);
        assertThat(mainRequest.fields()).containsEntry("summary", "done");
        // ALL 达预期：批次结算一次（状态守卫 UPDATE）并唤醒父图等待节点
        verify(batchMapper).update(any(), any());
        verify(bpmRuntimeFacade).signalWaitNode(eq("p1"), eq("wait_1"));
    }

    @Test
    @DisplayName("等待策略 ANY：首个有效完成即结算一次；此后迟到完成记 LATE 且不应用回写")
    void anyPolicySettlesOnceAndLateCompletionRecordedOnly() {
        BpmChildBatch batch = waitingBatch("ANY", 2, null);
        BpmChildItem first = dispatchedItem(batch.getId(), "row-a", "row-a", 1L);
        when(batchMapper.selectById(batch.getId())).thenReturn(batch);
        when(itemMapper.selectList(any())).thenReturn(List.of(first));
        BpmInstance child = child("rec-a");
        when(nodeFormDataService.listSubmittedByNode(eq(9L), anyString(), eq("node_result")))
                .thenReturn(List.of(submitted(1L, "{\"result_table\":[{\"id\":\"row-a\",\"feedback\":\"early\"}]}")));
        when(bpmInstanceService.findByProcessInstanceId("p1")).thenReturn(Optional.of(parent()));
        when(nodeFormDataService.loadGraph("parent_def", 1)).thenReturn(graphWithWaitNode());
        when(writebackFacade.applyWriteback(any())).thenReturn(Optional.of(
                new FormDataWritebackFacade.WritebackResult(
                        FormDataWritebackFacade.WritebackResult.WRITTEN, 2L)));
        when(batchMapper.update(any(), any())).thenReturn(1);

        service.onChildProcessTerminal(child, "APPROVED");
        // 行级 + 主记录两段回写
        verify(writebackFacade, org.mockito.Mockito.times(2)).applyWriteback(any());
        verify(batchMapper).update(any(), any());
        verify(bpmRuntimeFacade).signalWaitNode(eq("p1"), eq("wait_1"));

        // 迟到完成：批次已 SETTLED → LATE 留痕，不再调用回写、不再结算/唤醒
        BpmChildBatch settled = settledBatch("ANY", 2);
        when(batchMapper.selectById(settled.getId())).thenReturn(settled);
        BpmChildItem late = dispatchedItem(settled.getId(), "row-b", "row-b", 1L);
        when(itemMapper.selectList(any())).thenReturn(List.of(late));
        BpmInstance lateChild = child("rec-b");
        org.mockito.Mockito.clearInvocations(writebackFacade, bpmRuntimeFacade);

        service.onChildProcessTerminal(lateChild, "APPROVED");

        assertThat(late.getStatus()).isEqualTo(BpmChildItem.STATUS_LATE);
        assertThat(late.getWritebackJson()).isNotBlank();
        assertThat(late.getWritebackSource()).contains("child=");
        verify(writebackFacade, never()).applyWriteback(any());
        verify(bpmRuntimeFacade, never()).signalWaitNode(anyString(), anyString());
    }

    @Test
    @DisplayName("子流程失败/拒绝不凑成功数：ALL 全部终态含失败 → 批次 BLOCKED 可诊断、不唤醒等待节点")
    void allPolicyWithFailureBlocksBatchInsteadOfSettling() {
        BpmChildBatch batch = waitingBatch("ALL", 2, null);
        when(batchMapper.selectById(batch.getId())).thenReturn(batch);
        when(itemMapper.selectList(any())).thenReturn(List.of(
                writtenItem(batch.getId(), "row-a"),
                failedItem(batch.getId(), "row-b", "子流程终态 REJECTED，不计有效完成")));
        when(batchMapper.update(any(), any())).thenReturn(1);

        // 第二项也已终态（FAILED）：open=0、含失败 → BLOCKED
        service.settleForTest(batch.getId());

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<BpmChildBatch>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(batchMapper).update(any(), captor.capture());
        verify(bpmRuntimeFacade, never()).signalWaitNode(anyString(), anyString());
    }

    @Test
    @DisplayName("回写版本冲突挂起：目标行版本与派发冻结版本不一致 → CONFLICT 可诊断，不覆盖现有值")
    void writebackVersionConflictSuspendsItem() {
        BpmChildBatch batch = waitingBatch("ALL", 1, null);
        when(batchMapper.selectById(batch.getId())).thenReturn(batch);
        when(itemMapper.selectList(any())).thenReturn(List.of(
                dispatchedItem(batch.getId(), "row-b", "row-b", 4L)));
        BpmInstance child = child("rec-b");
        when(nodeFormDataService.listSubmittedByNode(eq(9L), anyString(), eq("node_result")))
                .thenReturn(List.of(submitted(2L,
                        "{\"result_table\":[{\"id\":\"row-b\",\"feedback\":\"OK\"}]}")));
        when(bpmInstanceService.findByProcessInstanceId("p1")).thenReturn(Optional.of(parent()));
        when(writebackFacade.applyWriteback(any())).thenReturn(Optional.of(
                new FormDataWritebackFacade.WritebackResult(
                        FormDataWritebackFacade.WritebackResult.VERSION_CONFLICT, 9L)));

        service.onChildProcessTerminal(child, "APPROVED");

        BpmChildItem item = capturedItem();
        assertThat(item.getStatus()).isEqualTo(BpmChildItem.STATUS_CONFLICT);
        assertThat(item.getErrorText()).contains("版本冲突").contains("9");
        // 冲突项不计成功：ALL 全部终态含失败 → 批次 BLOCKED，不唤醒等待节点
        verify(bpmRuntimeFacade, never()).signalWaitNode(anyString(), anyString());
    }

    @Test
    @DisplayName("父取消/退回：WAITING 批次 CANCELLED、未完成项 REFUSED，失去写回推进权")
    void cancelBatchesForParentCancelsWaitingBatchesAndRefusesItems() {
        BpmChildBatch batch = waitingBatch("ALL", 2, null);
        when(batchMapper.selectList(any())).thenReturn(List.of(batch));
        when(batchMapper.update(any(), any())).thenReturn(1);
        when(itemMapper.update(any(), any())).thenReturn(1);

        service.cancelBatchesForParent(parent(), "RETURNED");

        verify(batchMapper).update(any(), any());
        verify(itemMapper).update(any(), any());
    }

    @Test
    @DisplayName("回写冲突受控恢复：仅 CONFLICT 项可按当前版本重放；重放后重新结算")
    void retryWritebackReplaysConflictItemWithCurrentVersion() {
        BpmChildBatch batch = waitingBatch("ALL", 1, null);
        BpmChildItem conflict = conflictItem(batch.getId());
        when(itemMapper.selectById(conflict.getId())).thenReturn(conflict);
        when(batchMapper.selectById(batch.getId())).thenReturn(batch);
        when(bpmInstanceService.findByBusinessKey("rec-row-b")).thenReturn(Optional.of(child("rec-row-b")));
        when(nodeFormDataService.listSubmittedByNode(eq(9L), anyString(), eq("node_result")))
                .thenReturn(List.of(submitted(2L,
                        "{\"result_table\":[{\"id\":\"row-b\",\"feedback\":\"OK\"}]}")));
        when(bpmInstanceService.findByProcessInstanceId("p1")).thenReturn(Optional.of(parent()));
        when(writebackFacade.applyWriteback(any())).thenReturn(Optional.of(
                new FormDataWritebackFacade.WritebackResult(
                        FormDataWritebackFacade.WritebackResult.WRITTEN, 9L)));
        when(itemMapper.update(any(), any())).thenReturn(1);
        when(batchMapper.update(any(), any())).thenReturn(1);

        ChildOrchestrationService.WritebackRetryOutcome outcome =
                service.retryWriteback(9L, conflict.getId(), 7L);

        assertThat(outcome.status()).isEqualTo(BpmChildItem.STATUS_WRITTEN);
        ArgumentCaptor<FormDataWritebackFacade.WritebackRequest> captor =
                ArgumentCaptor.forClass(FormDataWritebackFacade.WritebackRequest.class);
        verify(writebackFacade, org.mockito.Mockito.times(2)).applyWriteback(captor.capture());
        // 恢复重放：清空冻结版本守卫（按当前权威版本应用）；行级请求先于主记录请求
        assertThat(captor.getAllValues().get(0).expectedRowVersion()).isNull();
        assertThat(captor.getAllValues().get(0).rowId()).isEqualTo("row-b");

        // 非 CONFLICT 项拒绝恢复（不重复已生效结果）
        BpmChildItem written = writtenItem(batch.getId(), "row-a");
        when(itemMapper.selectById(written.getId())).thenReturn(written);
        ChildOrchestrationService.WritebackRetryOutcome skipped =
                service.retryWriteback(9L, written.getId(), 7L);
        assertThat(skipped.status()).isEqualTo("SKIP");
    }

    // ==================== 工具 ====================

    private com.sw.ck.bpm.api.dto.ProcessGraph graphWithWaitNode() {
        com.sw.ck.bpm.api.dto.GraphElement waitNode = com.sw.ck.bpm.api.dto.GraphElement.builder()
                .id("wait_1").kind("node").type("SUBFLOW_WAIT")
                .config(java.util.Map.of("waitActionIds", java.util.List.of("act")))
                .build();
        com.sw.ck.bpm.api.dto.ProcessGraph graph = new com.sw.ck.bpm.api.dto.ProcessGraph();
        graph.setElements(new java.util.ArrayList<>(java.util.List.of(waitNode)));
        return graph;
    }


    private BpmChildItem capturedItem() {
        ArgumentCaptor<BpmChildItem> captor = ArgumentCaptor.forClass(BpmChildItem.class);
        verify(itemMapper, atLeastOnce()).updateById(captor.capture());
        return captor.getValue();
    }

    private BpmInstance parent() {
        BpmInstance instance = new BpmInstance();
        instance.setTenantId(9L);
        instance.setProcessInstanceId("p1");
        instance.setProcessDefKey("parent_def");
        instance.setBusinessKey("rec-1");
        instance.setFormKey("parent_form");
        instance.setDefVersion(1);
        return instance;
    }

    private BpmInstance child(String businessKey) {
        BpmInstance instance = new BpmInstance();
        instance.setTenantId(9L);
        instance.setProcessInstanceId("pi-child");
        instance.setProcessDefKey("child_def");
        instance.setBusinessKey(businessKey);
        instance.setFormKey("child_form");
        instance.setInitiatorId(7L);
        return instance;
    }

    private BpmChildBatch waitingBatch(String policy, Integer waitCount, Integer settled) {
        BpmChildBatch batch = new BpmChildBatch();
        batch.setTenantId(9L);
        batch.setId(100L);
        batch.setBatchKey("CHILD:TRG:1:act");
        batch.setParentInstanceId("p1");
        batch.setParentDefKey("parent_def");
        batch.setRootInstanceId("p1");
        batch.setParentDepth(0);
        batch.setTriggerId("trg");
        batch.setActionId("act");
        batch.setRoundNo(1L);
        batch.setWaitPolicy(policy);
        batch.setWaitCount(waitCount);
        batch.setExpectedCount(2);
        batch.setSourceRecordId("rec-1");
        batch.setSourceRecordVersion(7L);
        batch.setStatus(BpmChildBatch.STATUS_WAITING);
        ActionConfig action = childAction("act", policy, waitCount);
        batch.setConfigJson(new ObjectMapper().valueToTree(action).toString());
        return batch;
    }

    private BpmChildBatch settledBatch(String policy, Integer settledCount) {
        BpmChildBatch batch = waitingBatch(policy, null, settledCount);
        batch.setStatus(BpmChildBatch.STATUS_SETTLED);
        return batch;
    }

    private ActionConfig childAction(String actionId, String policy, Integer waitCount) {
        return ActionConfig.builder()
                .actionId(actionId)
                .type("START_GROUPED")
                .orchestration("CHILD")
                .waitPolicy(policy)
                .waitCount(waitCount)
                .targetProcessDefKey("child_def")
                .targetFormKey("child_form")
                .writeBack(ActionConfig.WriteBackConfig.builder()
                        .resultNodeKey("node_result")
                        .tableField("result_table")
                        .rowKeyField("id")
                        .parentTableField("parent_table")
                        .fields(List.of(ActionConfig.FieldMapping.builder()
                                .fromField("feedback").toField("feedback").build()))
                        .mainFields(List.of(ActionConfig.FieldMapping.builder()
                                .fromField("summary_text").toField("summary").build()))
                        .build())
                .build();
    }

    private BpmChildItem dispatchedItem(Long batchId, String itemKey, String rowId, Long rowVersion) {
        BpmChildItem item = new BpmChildItem();
        item.setTenantId(9L);
        item.setId(batchId * 10 + 1);
        item.setBatchId(batchId);
        item.setItemKey(itemKey);
        item.setSourceRowId(rowId);
        item.setSourceRowVersion(rowVersion);
        item.setTargetRecordId("rec-" + itemKey);
        item.setStatus(BpmChildItem.STATUS_DISPATCHED);
        return item;
    }

    private BpmChildItem writtenItem(Long batchId, String itemKey) {
        BpmChildItem item = dispatchedItem(batchId, itemKey, null, null);
        item.setStatus(BpmChildItem.STATUS_WRITTEN);
        return item;
    }

    private BpmChildItem failedItem(Long batchId, String itemKey, String error) {
        BpmChildItem item = dispatchedItem(batchId, itemKey, null, null);
        item.setStatus(BpmChildItem.STATUS_FAILED);
        item.setErrorText(error);
        return item;
    }

    private BpmChildItem conflictItem(Long batchId) {
        BpmChildItem item = dispatchedItem(batchId, "row-b", "row-b", null);
        item.setId(999L);
        item.setStatus(BpmChildItem.STATUS_CONFLICT);
        item.setErrorText("回写与来源行当前版本冲突");
        return item;
    }

    private BpmChildItem itemWithBatch(int parentDepth) {
        BpmChildItem item = new BpmChildItem();
        item.setTenantId(9L);
        item.setTargetRecordId("rec-1");
        item.setBatchId(55L);
        BpmChildBatch batch = new BpmChildBatch();
        batch.setId(55L);
        batch.setParentDepth(parentDepth);
        org.mockito.Mockito.doReturn(batch).when(batchMapper).selectById(55L);
        return item;
    }

    private BpmTaskFormData submitted(long round, String dataJson) {
        BpmTaskFormData row = new BpmTaskFormData();
        row.setTenantId(9L);
        row.setProcessInstanceId("pi-child");
        row.setNodeKey("node_result");
        row.setTaskId("task-" + round);
        row.setRoundNo(round);
        row.setStatus("SUBMITTED");
        row.setDataText(dataJson);
        return row;
    }
}
