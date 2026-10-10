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
import static org.mockito.Mockito.times;
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
    @DisplayName("分组项多行回写：按冻结授权来源行集合逐行回写（各自版本守卫），集合外行忽略不写错行")
    void groupedItemWritesBackAllAuthorizedRowsOnly() {
        BpmChildBatch batch = waitingBatch("ALL", null, null);
        batch.setExpectedCount(1);
        when(batchMapper.selectById(batch.getId())).thenReturn(batch);
        BpmChildItem item = dispatchedItem(batch.getId(), "group-owner2", null, null);
        item.setSourceRowsJson("[{\"rowId\":\"row-a\",\"version\":3},{\"rowId\":\"row-b\",\"version\":4}]");
        when(itemMapper.selectList(any())).thenReturn(List.of(item));
        BpmInstance child = child("rec-group");
        when(nodeFormDataService.listSubmittedByNode(9L, "pi-child", "node_result"))
                .thenReturn(List.of(submitted(1L, """
                        {"result_table":[{"id":"row-a","feedback":"OK-A"},
                                         {"id":"row-b","feedback":"OK-B"},
                                         {"id":"row-other","feedback":"越权尝试"}],
                         "summary_text":"done"}""")));
        when(bpmInstanceService.findByProcessInstanceId("p1")).thenReturn(Optional.of(parent()));
        when(nodeFormDataService.loadGraph("parent_def", 1)).thenReturn(graphWithWaitNode());
        when(writebackFacade.applyWriteback(any())).thenReturn(Optional.of(
                new FormDataWritebackFacade.WritebackResult(
                        FormDataWritebackFacade.WritebackResult.WRITTEN, 5L)));
        when(batchMapper.update(any(), any())).thenReturn(1);
        when(itemMapper.update(any(), any())).thenReturn(1);

        service.onChildProcessTerminal(child, "APPROVED");

        // 两行各一次行级回写 + 一次主记录回写；集合外 row-other 不产生回写
        ArgumentCaptor<FormDataWritebackFacade.WritebackRequest> captor =
                ArgumentCaptor.forClass(FormDataWritebackFacade.WritebackRequest.class);
        verify(writebackFacade, org.mockito.Mockito.times(3)).applyWriteback(captor.capture());
        List<FormDataWritebackFacade.WritebackRequest> rowRequests = captor.getAllValues().stream()
                .filter(request -> request.tableField() != null).toList();
        assertThat(rowRequests).hasSize(2);
        assertThat(rowRequests).extracting(FormDataWritebackFacade.WritebackRequest::rowId)
                .containsExactly("row-a", "row-b");
        assertThat(rowRequests).extracting(FormDataWritebackFacade.WritebackRequest::expectedRowVersion)
                .containsExactly(3L, 4L);
        assertThat(rowRequests.get(0).fields()).containsEntry("feedback", "OK-A");
        assertThat(rowRequests.get(1).fields()).containsEntry("feedback", "OK-B");

        ArgumentCaptor<BpmChildItem> itemCaptor = ArgumentCaptor.forClass(BpmChildItem.class);
        verify(itemMapper).updateById(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getStatus()).isEqualTo(BpmChildItem.STATUS_WRITTEN);
        assertThat(itemCaptor.getValue().getWritebackJson())
                .contains("\"row-a\"").contains("\"row-b\"").doesNotContain("row-other");
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
    @DisplayName("回写冲突受控恢复（多行冻结集合）：重放清空 source_rows_json 内冻结版本，按当前权威版本应用")
    void retryWritebackClearsFrozenVersionsInRowSet() {
        BpmChildBatch batch = waitingBatch("ALL", 1, null);
        batch.setExpectedCount(1);
        BpmChildItem conflict = dispatchedItem(batch.getId(), "row-b", "row-b", 4L);
        conflict.setId(998L);
        conflict.setStatus(BpmChildItem.STATUS_CONFLICT);
        conflict.setErrorText("回写与父记录当前版本冲突");
        conflict.setSourceRowsJson("[{\"rowId\":\"row-b\",\"version\":4}]");
        when(itemMapper.selectById(conflict.getId())).thenReturn(conflict);
        when(itemMapper.selectList(any())).thenReturn(List.of(conflict));
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
        assertThat(captor.getAllValues().get(0).rowId()).isEqualTo("row-b");
        assertThat(captor.getAllValues().get(0).expectedRowVersion()).isNull();
        // 主记录写回同样按当前权威版本应用（恢复重放不设冻结守卫）
        assertThat(captor.getAllValues().get(1).tableField()).isNull();
        assertThat(captor.getAllValues().get(1).expectedRowVersion()).isNull();
        // 冻结集合内版本同样被清理（保留行身份）
        assertThat(conflict.getSourceRowsJson()).contains("row-b").doesNotContain("4");
        // 恢复成功后重新结算（BLOCKED 批次在全项成功后可结算）
        verify(batchMapper).update(any(), any());
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

    // ==================== P64 二级提示02：合法K阈值 / NONE真实结算 / 取消终态失权 / 行身份边界 / 护栏 ====================

    @Test
    @DisplayName("COUNT 合法K（未达阈值）：成功数 < K 不结算、不唤醒等待节点（失败不凑数）")
    void countPolicy_belowKDoesNotSettleOrSignal() {
        BpmChildBatch batch = waitingBatch("COUNT", 2, null);
        when(batchMapper.selectById(batch.getId())).thenReturn(batch);
        when(itemMapper.selectList(any())).thenReturn(List.of(
                writtenItem(batch.getId(), "row-a"),
                dispatchedItem(batch.getId(), "row-b", "row-b", 4L)));

        service.settleForTest(batch.getId());

        verify(batchMapper, never()).update(any(), any());
        verify(bpmRuntimeFacade, never()).signalWaitNode(anyString(), anyString());
    }

    @Test
    @DisplayName("COUNT 合法K（失败不凑数）：FAILED+WRITTEN 成功数1 < K=2 → 不结算不唤醒")
    void countPolicy_failureDoesNotCountTowardK() {
        BpmChildBatch batch = waitingBatch("COUNT", 2, null);
        when(batchMapper.selectById(batch.getId())).thenReturn(batch);
        when(itemMapper.selectList(any())).thenReturn(List.of(
                failedItem(batch.getId(), "row-a", "子流程终态 REJECTED，不计有效完成"),
                writtenItem(batch.getId(), "row-b")));

        service.settleForTest(batch.getId());

        verify(batchMapper, never()).update(any(), any());
        verify(bpmRuntimeFacade, never()).signalWaitNode(anyString(), anyString());
    }

    @Test
    @DisplayName("COUNT 达到K且所需回写成功：只结算推进一次；此后迟到完成记 LATE，不改已用结果、不重复回写/唤醒")
    void countPolicy_reachesKSettlesOnceAndLateNeverRetouchesUsedResults() {
        BpmChildBatch batch = waitingBatch("COUNT", 1, null);
        BpmChildBatch settled = settledBatch("COUNT", 1);
        settled.setId(batch.getId());
        // 时点序列：终态A循环(WAITING) → 结算读批(WAITING) → 迟到B循环(SETTLED)
        when(batchMapper.selectById(batch.getId())).thenReturn(batch, batch, settled);
        BpmChildItem itemA = dispatchedItem(batch.getId(), "row-a", "row-a", 3L);
        BpmChildItem itemB = dispatchedItem(batch.getId(), "row-b", "row-b", 4L);
        // 终态循环(1) + 结算读项(2) + 迟到终态循环(3)：模拟 DB 按 status=DISPATCHED 过滤的时点状态
        when(itemMapper.selectList(any()))
                .thenReturn(List.of(itemA, itemB), List.of(itemA, itemB), List.of(itemB));
        BpmInstance childA = child("rec-a");
        when(nodeFormDataService.listSubmittedByNode(9L, "pi-child", "node_result"))
                .thenReturn(List.of(submitted(1L, "{\"result_table\":[{\"id\":\"row-a\",\"feedback\":\"OK-A\"}],\"summary_text\":\"done-A\"}")));
        when(bpmInstanceService.findByProcessInstanceId("p1")).thenReturn(Optional.of(parent()));
        when(nodeFormDataService.loadGraph("parent_def", 1)).thenReturn(graphWithWaitNode());
        when(writebackFacade.applyWriteback(any())).thenReturn(Optional.of(
                new FormDataWritebackFacade.WritebackResult(
                        FormDataWritebackFacade.WritebackResult.WRITTEN, 5L)));
        when(batchMapper.update(any(), any())).thenReturn(1);
        when(itemMapper.update(any(), any())).thenReturn(1);

        service.onChildProcessTerminal(childA, "APPROVED");

        // 达 K=1：结算一次（状态守卫 UPDATE + 唤醒恰一次），成功数=1
        verify(batchMapper, times(1)).update(any(), any());
        verify(bpmRuntimeFacade, times(1)).signalWaitNode(eq("p1"), eq("wait_1"));
        String usedWritebackJson = itemA.getWritebackJson();
        assertThat(itemA.getStatus()).isEqualTo(BpmChildItem.STATUS_WRITTEN);

        // 迟到完成：LATE 留痕；不再回写、不再结算/唤醒、已用结果不被改写
        service.onChildProcessTerminal(child("rec-b"), "APPROVED");

        assertThat(itemB.getStatus()).isEqualTo(BpmChildItem.STATUS_LATE);
        assertThat(itemB.getWritebackJson()).isNotBlank();
        verify(writebackFacade, times(2)).applyWriteback(any()); // 仅 itemA 行级+主记录
        verify(batchMapper, times(1)).update(any(), any());
        verify(bpmRuntimeFacade, times(1)).signalWaitNode(anyString(), anyString());
        assertThat(itemA.getWritebackJson()).isEqualTo(usedWritebackJson);
        assertThat(itemA.getStatus()).isEqualTo(BpmChildItem.STATUS_WRITTEN);
    }

    @Test
    @DisplayName("NONE：冻结登记为 WAITING（可靠意图提交前不推进）；登记完成后经真实结算通道结算一次")
    void nonePolicy_settleChannelThroughRealServiceAfterItemsRegistered() {
        when(batchMapper.selectCount(any())).thenReturn(0L);
        when(batchMapper.selectOne(any())).thenReturn(null);
        when(writebackFacade.readVersion(eq(9L), eq("parent_form"), eq("rec-1"), isNull(), isNull()))
                .thenReturn(Optional.of(7L));
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.getArgument(0, BpmChildBatch.class).setId(300L);
            return 1;
        }).when(batchMapper).insert(any(BpmChildBatch.class));
        when(batchMapper.updateById(any(BpmChildBatch.class))).thenReturn(1);

        BpmInstance parent = parent();
        BpmChildBatch batch = service.freezeBatch(parent, "trg",
                childAction("act", "NONE", null), "TRG:p1:trg:1:1", 2, 1L, "parent_form", 9L);

        // 派发意图提交（冻结登记）≠ 结算：批次保持 WAITING，结算只经显式 settleIfNone 通道
        assertThat(batch.getStatus()).isEqualTo(BpmChildBatch.STATUS_WAITING);
        verify(batchMapper, never()).update(any(), any());

        when(batchMapper.selectById(300L)).thenReturn(batch);
        when(itemMapper.selectList(any())).thenReturn(List.of(
                dispatchedItem(300L, "row-a", "row-a", 3L),
                dispatchedItem(300L, "row-b", "row-b", 4L)));
        when(bpmInstanceService.findByProcessInstanceId("p1")).thenReturn(Optional.of(parent));
        when(nodeFormDataService.loadGraph("parent_def", 1)).thenReturn(graphWithWaitNode());
        when(batchMapper.update(any(), any())).thenReturn(1);

        service.settleIfNone(batch);

        verify(batchMapper, times(1)).update(any(), any());
        verify(bpmRuntimeFacade, times(1)).signalWaitNode(eq("p1"), eq("wait_1"));
    }

    @Test
    @DisplayName("NONE：结算后迟到反馈版本化留痕（writeback_json/source/time），不应用回写、不覆盖父完成快照")
    void nonePolicy_lateFeedbackVersionedTraceOnly() {
        BpmChildBatch settled = waitingBatch("NONE", null, null);
        settled.setId(301L);
        settled.setStatus(BpmChildBatch.STATUS_SETTLED);
        when(batchMapper.selectById(301L)).thenReturn(settled);
        BpmChildItem late = dispatchedItem(301L, "row-a", "row-a", 3L);
        when(itemMapper.selectList(any())).thenReturn(List.of(late));
        when(nodeFormDataService.listSubmittedByNode(9L, "pi-child", "node_result"))
                .thenReturn(List.of(submitted(1L, "{\"result_table\":[{\"id\":\"row-a\",\"feedback\":\"迟到值\"}],\"summary_text\":\"late\"}")));

        service.onChildProcessTerminal(child("rec-a"), "APPROVED");

        assertThat(late.getStatus()).isEqualTo(BpmChildItem.STATUS_LATE);
        assertThat(late.getWritebackJson()).contains("迟到值");
        assertThat(late.getWritebackSource()).contains("child=").contains("batch=");
        assertThat(late.getWritebackTime()).isNotNull();
        verify(writebackFacade, never()).applyWriteback(any());
        verify(bpmRuntimeFacade, never()).signalWaitNode(anyString(), anyString());
    }

    @Test
    @DisplayName("取消终态实值捕获：批次 CANCELLED、未完成项 REFUSED、失权原因可读（非 verify update(any)）")
    void cancelCapturesRealTerminalStateValues() {
        BpmChildBatch batch = waitingBatch("ALL", 2, null);
        when(batchMapper.selectList(any())).thenReturn(List.of(batch));
        when(batchMapper.update(any(), any())).thenReturn(1);
        when(itemMapper.update(any(), any())).thenReturn(1);

        service.cancelBatchesForParent(parent(), "RETURNED");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<BpmChildBatch>> batchCaptor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(batchMapper).update(isNull(), batchCaptor.capture());
        assertThat(((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>)
                batchCaptor.getValue()).getParamNameValuePairs().values())
                .contains(BpmChildBatch.STATUS_CANCELLED)
                .anySatisfy(value -> String.valueOf(value).contains("失去写回推进权"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<BpmChildItem>> itemCaptor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(itemMapper).update(isNull(), itemCaptor.capture());
        assertThat(((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>)
                itemCaptor.getValue()).getParamNameValuePairs().values())
                .contains(BpmChildItem.STATUS_REFUSED)
                .anySatisfy(value -> String.valueOf(value).contains("失去向当前轮次写回/推进的资格"));
    }

    @Test
    @DisplayName("旧批次回调失权：已取消批次的迟到终态 → REFUSED+留痕，回写0、唤醒0；新轮次批次不受污染照常结算")
    void terminalOnCancelledBatchLosesRightsAndNewRoundUnaffected() {
        BpmChildBatch cancelledOld = waitingBatch("ALL", 2, null);
        cancelledOld.setId(400L);
        cancelledOld.setStatus(BpmChildBatch.STATUS_CANCELLED);
        BpmChildItem oldItem = dispatchedItem(400L, "row-a", "row-a", 3L);
        when(batchMapper.selectById(400L)).thenReturn(cancelledOld);
        when(itemMapper.selectList(any())).thenReturn(List.of(oldItem));
        when(nodeFormDataService.listSubmittedByNode(eq(9L), anyString(), eq("node_result")))
                .thenReturn(List.of(submitted(1L, "{\"result_table\":[{\"id\":\"row-a\",\"feedback\":\"旧轮次结果\"}]}")));

        service.onChildProcessTerminal(child("rec-a"), "APPROVED");

        assertThat(oldItem.getStatus()).isEqualTo(BpmChildItem.STATUS_REFUSED);
        assertThat(oldItem.getWritebackJson()).contains("旧轮次结果");
        verify(writebackFacade, never()).applyWriteback(any());
        verify(bpmRuntimeFacade, never()).signalWaitNode(anyString(), anyString());

        // 新轮次（round=2）WAITING 批次：同父新子完成照常回写+结算推进，旧对象不被触碰
        BpmChildBatch newRound = waitingBatch("ALL", null, null);
        newRound.setId(401L);
        newRound.setRoundNo(2L);
        newRound.setExpectedCount(1);
        BpmChildItem newItem = dispatchedItem(401L, "row-b", "row-b", 4L);
        when(batchMapper.selectById(401L)).thenReturn(newRound);
        when(itemMapper.selectList(any())).thenReturn(List.of(newItem));
        when(nodeFormDataService.listSubmittedByNode(9L, "pi-child", "node_result"))
                .thenReturn(List.of(submitted(2L, "{\"result_table\":[{\"id\":\"row-b\",\"feedback\":\"NEW\"}],\"summary_text\":\"r2\"}")));
        when(bpmInstanceService.findByProcessInstanceId("p1")).thenReturn(Optional.of(parent()));
        when(nodeFormDataService.loadGraph("parent_def", 1)).thenReturn(graphWithWaitNode());
        when(writebackFacade.applyWriteback(any())).thenReturn(Optional.of(
                new FormDataWritebackFacade.WritebackResult(
                        FormDataWritebackFacade.WritebackResult.WRITTEN, 6L)));
        when(batchMapper.update(any(), any())).thenReturn(1);

        service.onChildProcessTerminal(child("rec-b"), "APPROVED");

        assertThat(newItem.getStatus()).isEqualTo(BpmChildItem.STATUS_WRITTEN);
        assertThat(newItem.getWritebackJson()).contains("NEW");
        verify(writebackFacade, times(2)).applyWriteback(any()); // 仅新轮次行级+主记录
        verify(bpmRuntimeFacade, times(1)).signalWaitNode(eq("p1"), eq("wait_1"));
    }

    @Test
    @DisplayName("行身份边界（排序改变）：提交行序倒置仍按冻结行ID+逐行版本回写，不错行不串版本")
    void rowWritebackFollowsStableRowIdentityRegardlessOfSubmissionOrder() {
        BpmChildBatch batch = waitingBatch("ALL", null, null);
        batch.setExpectedCount(1);
        when(batchMapper.selectById(batch.getId())).thenReturn(batch);
        BpmChildItem item = dispatchedItem(batch.getId(), "group-owner", null, null);
        // 冻结授权行集：row-a v3、row-b v4（父表后续按 row-b,row-a 展示/排序）
        item.setSourceRowsJson("[{\"rowId\":\"row-a\",\"version\":3},{\"rowId\":\"row-b\",\"version\":4}]");
        when(itemMapper.selectList(any())).thenReturn(List.of(item));
        when(nodeFormDataService.listSubmittedByNode(9L, "pi-child", "node_result"))
                .thenReturn(List.of(submitted(1L, """
                        {"result_table":[{"id":"row-b","feedback":"OK-B"},
                                         {"id":"row-a","feedback":"OK-A"}],
                         "summary_text":"done"}""")));
        when(bpmInstanceService.findByProcessInstanceId("p1")).thenReturn(Optional.of(parent()));
        when(nodeFormDataService.loadGraph("parent_def", 1)).thenReturn(graphWithWaitNode());
        when(writebackFacade.applyWriteback(any())).thenReturn(Optional.of(
                new FormDataWritebackFacade.WritebackResult(
                        FormDataWritebackFacade.WritebackResult.WRITTEN, 5L)));
        when(batchMapper.update(any(), any())).thenReturn(1);
        when(itemMapper.update(any(), any())).thenReturn(1);

        service.onChildProcessTerminal(child("rec-group"), "APPROVED");

        ArgumentCaptor<FormDataWritebackFacade.WritebackRequest> captor =
                ArgumentCaptor.forClass(FormDataWritebackFacade.WritebackRequest.class);
        verify(writebackFacade, times(3)).applyWriteback(captor.capture());
        List<FormDataWritebackFacade.WritebackRequest> rowRequests = captor.getAllValues().stream()
                .filter(request -> request.tableField() != null).toList();
        assertThat(rowRequests).extracting(FormDataWritebackFacade.WritebackRequest::rowId)
                .containsExactlyInAnyOrder("row-a", "row-b");
        assertThat(rowRequests).filteredOn(request -> "row-a".equals(request.rowId()))
                .singleElement()
                .satisfies(request -> {
                    assertThat(request.expectedRowVersion()).isEqualTo(3L);
                    assertThat(request.fields()).containsEntry("feedback", "OK-A");
                });
        assertThat(rowRequests).filteredOn(request -> "row-b".equals(request.rowId()))
                .singleElement()
                .satisfies(request -> {
                    assertThat(request.expectedRowVersion()).isEqualTo(4L);
                    assertThat(request.fields()).containsEntry("feedback", "OK-B");
                });
    }

    @Test
    @DisplayName("行身份边界（重复提交）：同一行在结果表格重复出现仅回写一次，不产生重复副作用")
    void duplicateRowInResultWritesBackOncePerRow() {
        BpmChildBatch batch = waitingBatch("ALL", 1, null);
        when(batchMapper.selectById(batch.getId())).thenReturn(batch);
        BpmChildItem item = dispatchedItem(batch.getId(), "row-a", "row-a", 3L);
        when(itemMapper.selectList(any())).thenReturn(List.of(item));
        when(nodeFormDataService.listSubmittedByNode(9L, "pi-child", "node_result"))
                .thenReturn(List.of(submitted(1L, """
                        {"result_table":[{"id":"row-a","feedback":"first"},
                                         {"id":"row-a","feedback":"second"}],
                         "summary_text":"done"}""")));
        when(bpmInstanceService.findByProcessInstanceId("p1")).thenReturn(Optional.of(parent()));
        when(nodeFormDataService.loadGraph("parent_def", 1)).thenReturn(graphWithWaitNode());
        when(writebackFacade.applyWriteback(any())).thenReturn(Optional.of(
                new FormDataWritebackFacade.WritebackResult(
                        FormDataWritebackFacade.WritebackResult.WRITTEN, 5L)));
        when(batchMapper.update(any(), any())).thenReturn(1);
        when(itemMapper.update(any(), any())).thenReturn(1);

        service.onChildProcessTerminal(child("rec-a"), "APPROVED");

        ArgumentCaptor<FormDataWritebackFacade.WritebackRequest> captor =
                ArgumentCaptor.forClass(FormDataWritebackFacade.WritebackRequest.class);
        verify(writebackFacade, times(2)).applyWriteback(captor.capture()); // 1行级+1主记录
        assertThat(captor.getAllValues().stream().filter(r -> r.tableField() != null)).hasSize(1);
        assertThat(captor.getAllValues().get(0).fields()).containsEntry("feedback", "first");
    }

    @Test
    @DisplayName("行身份边界（旧轮次）：旧轮次提交不覆盖当前权威结果，回写取当前轮次提交值且只应用一次")
    void staleRoundResultDoesNotOverwriteCurrentRoundWriteback() {
        BpmChildBatch batch = waitingBatch("ALL", 1, null);
        when(batchMapper.selectById(batch.getId())).thenReturn(batch);
        BpmChildItem item = dispatchedItem(batch.getId(), "row-a", "row-a", 3L);
        when(itemMapper.selectList(any())).thenReturn(List.of(item));
        // 同节点两轮提交：round=1 旧值在前、round=2 当前值在后 → latestSubmitted 取当前轮次
        when(nodeFormDataService.listSubmittedByNode(9L, "pi-child", "node_result"))
                .thenReturn(List.of(
                        submitted(1L, "{\"result_table\":[{\"id\":\"row-a\",\"feedback\":\"旧轮次\"}],\"summary_text\":\"old\"}"),
                        submitted(2L, "{\"result_table\":[{\"id\":\"row-a\",\"feedback\":\"当前轮次\"}],\"summary_text\":\"new\"}")));
        when(bpmInstanceService.findByProcessInstanceId("p1")).thenReturn(Optional.of(parent()));
        when(nodeFormDataService.loadGraph("parent_def", 1)).thenReturn(graphWithWaitNode());
        when(writebackFacade.applyWriteback(any())).thenReturn(Optional.of(
                new FormDataWritebackFacade.WritebackResult(
                        FormDataWritebackFacade.WritebackResult.WRITTEN, 5L)));
        when(batchMapper.update(any(), any())).thenReturn(1);
        when(itemMapper.update(any(), any())).thenReturn(1);

        service.onChildProcessTerminal(child("rec-a"), "APPROVED");

        ArgumentCaptor<FormDataWritebackFacade.WritebackRequest> captor =
                ArgumentCaptor.forClass(FormDataWritebackFacade.WritebackRequest.class);
        verify(writebackFacade, times(2)).applyWriteback(captor.capture());
        assertThat(captor.getAllValues().get(0).fields()).containsEntry("feedback", "当前轮次");
        assertThat(captor.getAllValues().get(1).fields()).containsEntry("summary", "new");
    }

    @Test
    @DisplayName("重复终态回调：项已 WRITTEN 后同子实例再次回调 → 零回写零唤醒，不重复已生效结果")
    void duplicateTerminalCallbackDoesNotDoubleWriteback() {
        BpmChildBatch batch = waitingBatch("ALL", 1, null);
        batch.setExpectedCount(1);
        when(batchMapper.selectById(batch.getId())).thenReturn(batch);
        BpmChildItem item = dispatchedItem(batch.getId(), "row-a", "row-a", 3L);
        // 时点序列：首次终态循环 → 结算读项（仍命中该项）→ 二次终态循环（DB 已无 DISPATCHED 项）
        when(itemMapper.selectList(any())).thenReturn(List.of(item), List.of(item), List.of());
        when(nodeFormDataService.listSubmittedByNode(9L, "pi-child", "node_result"))
                .thenReturn(List.of(submitted(1L, "{\"result_table\":[{\"id\":\"row-a\",\"feedback\":\"OK\"}],\"summary_text\":\"done\"}")));
        when(bpmInstanceService.findByProcessInstanceId("p1")).thenReturn(Optional.of(parent()));
        when(nodeFormDataService.loadGraph("parent_def", 1)).thenReturn(graphWithWaitNode());
        when(writebackFacade.applyWriteback(any())).thenReturn(Optional.of(
                new FormDataWritebackFacade.WritebackResult(
                        FormDataWritebackFacade.WritebackResult.WRITTEN, 5L)));
        when(batchMapper.update(any(), any())).thenReturn(1);
        when(itemMapper.update(any(), any())).thenReturn(1);

        service.onChildProcessTerminal(child("rec-a"), "APPROVED");
        service.onChildProcessTerminal(child("rec-a"), "APPROVED");

        verify(writebackFacade, times(2)).applyWriteback(any()); // 仅首次：行级+主记录
        verify(bpmRuntimeFacade, times(1)).signalWaitNode(anyString(), anyString());
    }

    @Test
    @DisplayName("根链护栏：同业务根链自动发起实例数达 1000 → 冻结拒绝（CHILD_CHAIN_OVER_LIMIT），不新增批次/项/意图")
    void rootChainOverLimitRejectsFreezeWithoutBatchOrIntents() {
        BpmInstance parent = parent();
        when(itemMapper.selectList(any())).thenReturn(List.of());
        when(batchMapper.selectCount(any())).thenReturn(1000L);

        assertThatThrownBy(() -> service.freezeBatch(parent, "trg",
                childAction("act_all", "ALL", null), "TRG:p1:trg:1:1", 3, 1L, "parent_form", 9L))
                .isInstanceOfSatisfying(BaseException.class, e ->
                        assertThat(e.getCode()).isEqualTo(BpmErrorCode.CHILD_CHAIN_OVER_LIMIT.getCode()));

        verify(batchMapper, never()).insert(any(BpmChildBatch.class));
        verify(itemMapper, never()).insert(any(BpmChildItem.class));
        verify(bpmRuntimeFacade, never()).signalWaitNode(anyString(), anyString());
    }

    @Test
    @DisplayName("嵌套硬上限8：配置抬高到10仍按硬钳8拒绝（父批次深度7→本次深度8），不新增批次/项")
    void hardNestingCapEightBlocksEvenWithRaisedConfig() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "configuredMaxNesting", 10);
        BpmChildItem parentItem = itemWithBatch(7);
        when(itemMapper.selectList(any())).thenReturn(List.of(parentItem));
        when(batchMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.freezeBatch(parent(), "trg",
                childAction("act_all", "ALL", null), "TRG:x:1", 2, 1L, "parent_form", 9L))
                .isInstanceOfSatisfying(BaseException.class, e ->
                        assertThat(e.getCode()).isEqualTo(BpmErrorCode.CHILD_NESTING_OVER_LIMIT.getCode()));

        verify(batchMapper, never()).insert(any(BpmChildBatch.class));
        verify(itemMapper, never()).insert(any(BpmChildItem.class));
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
