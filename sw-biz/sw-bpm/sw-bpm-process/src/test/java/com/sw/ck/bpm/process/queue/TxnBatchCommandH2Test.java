package com.sw.ck.bpm.process.queue;

import com.sw.ck.bpm.process.dto.TxnBatchSubmitRequest;
import com.sw.ck.bpm.process.dto.TxnBatchView;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.queue.support.BatchH2TestConfig;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.api.port.FormTxnActionPort;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P62 分级执行 S3：后台批量命令行为验证（真实 H2 + 真实持久化队列 + 真实迁移 V103）。
 * <p>
 * 覆盖：受理边界（项数 1—500、项键唯一、动作已发布）、受理持久化与统一命令语义
 * （BATCH_INVOKE / logical_command_id / tier / completion_point）、批次重放返回原批次、
 * 逐项独立事务与持久结果（部分失败可定位）、重入恢复只处理 PENDING 项不重做已成功项、
 * 同键异载荷冲突落 REJECTED 终态。
 * </p>
 */
@SpringBootTest(classes = BatchH2TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "sw.bpm.txn-batch.enabled=true")
@DisplayName("P62 S3 后台批量命令（H2 真实持久化）")
class TxnBatchCommandH2Test {

    private static final Long TENANT = 1L;
    private static final Long USER = 7L;
    private static final String ACTION_ID = "act-batch-1";

    @Autowired
    private com.sw.ck.bpm.process.service.TxnBatchService batchService;

    @Autowired
    private BatchInvokeCommandHandler handler;

    @Autowired
    private PersistentBpmCommandQueue queue;

    @Autowired
    private FormTxnActionPort txnActionPort;

    @Autowired
    private TransactionTemplate txTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        LoginUser user = new LoginUser();
        user.setUserId(USER);
        user.setTenantId(TENANT);
        user.setPermissions(new ArrayList<>(List.of("form:action:invoke")));
        LoginUserHolder.set(user);
        jdbcTemplate.update("DELETE FROM sw_bpm_command_batch_item");
        jdbcTemplate.update("DELETE FROM sw_bpm_command_batch");
        jdbcTemplate.update("DELETE FROM sw_bpm_command_effect");
        jdbcTemplate.update("DELETE FROM sw_bpm_command");
        // 共享内存库（QueueH2TestConfig DB_CLOSE_DELAY=-1）跨测试类留存资源策略/占用：
        // 资源保障类测试启用的策略与未释放占用会把后续批量受理判为额度已满（既有测试间
        // 污染，P64 阶段Ⅰ全模块回归暴露），与命令表一并复位。
        jdbcTemplate.update("DELETE FROM sw_bpm_resource_usage");
        jdbcTemplate.update("DELETE FROM sw_bpm_resource_reject_log");
        jdbcTemplate.update("UPDATE sw_bpm_resource_policy SET enabled = FALSE, stop_acceptance = FALSE");
        when(txnActionPort.describe(ACTION_ID)).thenReturn(Optional.of(
                new FormTxnActionPort.TxnActionDescriptor(ACTION_ID, "form-1", "stock_reserve",
                        "RESERVE", "PUBLISHED", 3)));
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    // ==================== 受理边界 ====================

    @Test
    @DisplayName("受理边界：0/501 项拒绝、项键重复拒绝、动作未发布拒绝、无权限拒绝")
    void submitBoundaries() {
        assertThatThrownBy(() -> batchService.submit(request("b-bounds", items(0))))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> batchService.submit(request("b-bounds", items(501))))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> batchService.submit(requestWithDupKeys()))
                .isInstanceOf(BaseException.class).hasMessageContaining("重复");

        when(txnActionPort.describe(ACTION_ID)).thenReturn(Optional.of(
                new FormTxnActionPort.TxnActionDescriptor(ACTION_ID, "form-1", "stock_reserve",
                        "RESERVE", "DRAFT", 1)));
        assertThatThrownBy(() -> batchService.submit(request("b-draft", items(1))))
                .isInstanceOf(BaseException.class).hasMessageContaining("未发布");

        LoginUser noPermission = new LoginUser();
        noPermission.setUserId(USER);
        noPermission.setTenantId(TENANT);
        LoginUserHolder.set(noPermission);
        assertThatThrownBy(() -> batchService.submit(request("b-noperm", items(1))))
                .isInstanceOf(BaseException.class).hasMessageContaining("form:action:invoke");
        assertThat(batchCount()).as("被拒受理不得落批次").isZero();
    }

    // ==================== 受理持久化 + 统一命令语义 ====================

    @Test
    @DisplayName("受理持久化：批次/项行与 BATCH_INVOKE 命令同事务落库，携带统一逻辑身份与形态冻结")
    void submitPersistsBatchAndCommand() {
        TxnBatchView view = batchService.submit(request("b-persist", items(3)));
        assertThat(view.isReplay()).isFalse();
        assertThat(view.getStatus()).isEqualTo("PENDING");
        assertThat(view.getActionVersion()).isEqualTo(3);
        assertThat(view.getTotalCount()).isEqualTo(3);
        assertThat(view.getItems()).allMatch(item -> "PENDING".equals(item.getStatus()));

        Long batchId = jdbcTemplate.queryForObject(
                "SELECT id FROM sw_bpm_command_batch WHERE batch_key = 'b-persist'", Long.class);
        assertThat(itemRowCount(batchId)).isEqualTo(3);

        Map<String, Object> command = jdbcTemplate.queryForMap(
                "SELECT command_type, logical_command_id, tier, completion_point, status FROM sw_bpm_command"
                        + " WHERE command_key = 'BATCH:b-persist'");
        assertThat(command.get("command_type")).isEqualTo("BATCH_INVOKE");
        assertThat(command.get("logical_command_id")).isEqualTo("BATCH:b-persist");
        assertThat(command.get("tier")).isEqualTo("BULK");
        assertThat(command.get("completion_point")).isEqualTo("BATCH_SETTLED");
        assertThat(command.get("status")).isEqualTo("PENDING");
        System.out.println("[P62-EV] s3.accept batch=3-items tier=BULK logical=BATCH:b-persist");
    }

    // ==================== 批次重放 ====================

    @Test
    @DisplayName("批次重放：同批次键返回原批次（replay=true），不新增命令或项")
    void batchReplayReturnsOriginal() {
        TxnBatchView first = batchService.submit(request("b-replay", items(2)));
        TxnBatchView second = batchService.submit(request("b-replay", items(2)));
        assertThat(second.isReplay()).isTrue();
        assertThat(second.getCommandId()).isEqualTo(first.getCommandId());
        assertThat(second.getItems()).hasSize(2);
        assertThat(batchCount()).as("重放不新建批次").isEqualTo(1);
        assertThat(commandCount()).as("重放不新建命令").isEqualTo(1);
        System.out.println("[P62-EV] s3.replay original-batch=true commands=1 batches=1");
    }

    // ==================== 逐项执行：部分失败可定位 ====================

    @Test
    @DisplayName("逐项执行：混合成功/拒绝落逐项持久结果，批次 PARTIALLY_FAILED，效果权威账本写入")
    void partialFailureSettledPerItem() {
        batchService.submit(request("b-partial", items(3)));
        CommandEnvelope claimed = claimOne();
        stubInvoke("item-1", succeeded());
        stubInvoke("item-2", rejected(1604, "可用量不足"));
        stubInvoke("item-3", succeeded());

        String result = handler.handle(claimed);

        assertThat(result).contains("\"status\":\"PARTIALLY_FAILED\"").contains("\"succeeded\":2")
                .contains("\"failed\":1");
        TxnBatchView view = batchService.get("b-partial");
        assertThat(view.getStatus()).isEqualTo("PARTIALLY_FAILED");
        assertThat(view.getSucceededCount()).isEqualTo(2);
        assertThat(view.getFailedCount()).isEqualTo(1);
        TxnBatchView.Item rejected = view.getItems().stream()
                .filter(i -> "item-2".equals(i.getItemKey())).findFirst().orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo("REJECTED");
        assertThat(rejected.getErrorCode()).isEqualTo(1604);
        assertThat(rejected.getErrorMsg()).contains("可用量不足");

        Long effects = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_command_effect WHERE command_id = ? AND biz_ref = 'BATCH:b-partial'",
                Long.class, claimed.getCommandId());
        assertThat(effects).as("批次结算效果权威账本恰一条").isEqualTo(1L);
        queue.complete(claimed.getCommandId(), claimed.getClaimToken(), result);
        System.out.println("[P62-EV] s3.partial succeeded=2 failed=1 effect-authority=1");
    }

    // ==================== 中断恢复：重入只处理 PENDING 项 ====================

    @Test
    @DisplayName("中断恢复：基础设施异常中断后重入，只处理剩余 PENDING 项，已成功项不重做")
    void resumeProcessesOnlyPendingItems() {
        batchService.submit(request("b-resume", items(2)));
        CommandEnvelope claimed = claimOne();
        stubInvoke("item-1", succeeded());
        when(txnActionPort.invoke(ArgumentMatchers.argThat(cmd ->
                        cmd != null && cmd.invocationKey().endsWith(":item-2"))))
                .thenThrow(new IllegalStateException("db-connection-lost"));

        assertThatThrownBy(() -> handler.handle(claimed))
                .as("基础设施异常向上传播触发命令重试").isInstanceOf(IllegalStateException.class);
        TxnBatchView afterCrash = batchService.get("b-resume");
        assertThat(statusOf(afterCrash, "item-1")).isEqualTo("SUCCEEDED");
        assertThat(statusOf(afterCrash, "item-2")).isEqualTo("PENDING");

        // 修复后重试：只处理 item-2
        when(txnActionPort.invoke(ArgumentMatchers.argThat(cmd ->
                        cmd != null && cmd.invocationKey().endsWith(":item-2"))))
                .thenReturn(java.util.Optional.of(succeeded()),
                        java.util.Optional.of(succeeded()));
        handler.handle(claimed);

        TxnBatchView recovered = batchService.get("b-resume");
        assertThat(recovered.getStatus()).isEqualTo("COMPLETED");
        assertThat(recovered.getSucceededCount()).isEqualTo(2);
        // item-1 的调用只发生一次（重入不重做已成功项）
        verify(txnActionPort, times(1)).invoke(ArgumentMatchers.argThat(cmd ->
                cmd != null && cmd.invocationKey().endsWith(":item-1")));
        System.out.println("[P62-EV] s3.resume pending-only=true no-redo=true final=COMPLETED");
    }

    // ==================== 同键异载荷冲突落终态 ====================

    @Test
    @DisplayName("同键异载荷冲突：动作内核明确拒绝（1606），项落 REJECTED 终态且批次可定位")
    void sameKeyDifferentPayloadRejected() {
        batchService.submit(request("b-conflict", items(1)));
        CommandEnvelope claimed = claimOne();
        when(txnActionPort.invoke(any())).thenThrow(new BaseException(
                FormErrorCode.ACTION_IDEMPOTENCY_CONFLICT));

        String result = handler.handle(claimed);

        assertThat(result).contains("\"status\":\"PARTIALLY_FAILED\"").contains("\"failed\":1");
        TxnBatchView view = batchService.get("b-conflict");
        assertThat(statusOf(view, "item-1")).isEqualTo("REJECTED");
        assertThat(view.getItems().get(0).getErrorCode())
                .isEqualTo(FormErrorCode.ACTION_IDEMPOTENCY_CONFLICT.getCode());
        System.out.println("[P62-EV] s3.conflict rejected=1606 item-terminal=true");
    }

    // ==================== helpers ====================

    private TxnBatchSubmitRequest request(String batchKey, List<TxnBatchSubmitRequest.Item> items) {
        TxnBatchSubmitRequest request = new TxnBatchSubmitRequest();
        request.setBatchKey(batchKey);
        request.setActionId(ACTION_ID);
        request.setItems(items);
        return request;
    }

    private TxnBatchSubmitRequest requestWithDupKeys() {
        TxnBatchSubmitRequest request = request("b-dup", new ArrayList<>());
        request.setItems(List.of(item("same"), item("same")));
        return request;
    }

    private List<TxnBatchSubmitRequest.Item> items(int count) {
        List<TxnBatchSubmitRequest.Item> list = new ArrayList<>(Math.max(count, 0));
        for (int i = 1; i <= count; i++) {
            list.add(item("item-" + i));
        }
        return list;
    }

    private TxnBatchSubmitRequest.Item item(String key) {
        TxnBatchSubmitRequest.Item item = new TxnBatchSubmitRequest.Item();
        item.setItemKey(key);
        item.setRecordId("record-" + key);
        item.setQuantity("1");
        return item;
    }

    private void stubInvoke(String itemKey, FormTxnActionPort.TxnActionResult result) {
        when(txnActionPort.invoke(ArgumentMatchers.argThat(cmd ->
                        cmd != null && cmd.invocationKey().endsWith(":" + itemKey))))
                .thenReturn(java.util.Optional.of(result));
    }

    private FormTxnActionPort.TxnActionResult succeeded() {
        return new FormTxnActionPort.TxnActionResult("inv-" + java.util.UUID.randomUUID(), "SUCCEEDED",
                3, "res-1", null, null, null, null, null, 5L, false);
    }

    private FormTxnActionPort.TxnActionResult rejected(int errorCode, String message) {
        return new FormTxnActionPort.TxnActionResult("inv-" + java.util.UUID.randomUUID(), "REJECTED",
                3, null, null, null, null, errorCode, message, 3L, false);
    }

    private CommandEnvelope claimOne() {
        List<CommandEnvelope> claimed = queue.claimDue(List.of(CommandChannelEnum.NORMAL), 10);
        assertThat(claimed).as("BATCH_INVOKE 命令应可领取").hasSize(1);
        assertThat(claimed.get(0).getCommandType()).isEqualTo(CommandTypeEnum.BATCH_INVOKE);
        return claimed.get(0);
    }

    private String statusOf(TxnBatchView view, String itemKey) {
        return view.getItems().stream().filter(i -> itemKey.equals(i.getItemKey()))
                .findFirst().orElseThrow().getStatus();
    }

    private long batchCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sw_bpm_command_batch", Long.class);
    }

    private long commandCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_command WHERE command_type = 'BATCH_INVOKE'", Long.class);
    }

    private long itemRowCount(Long batchId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_command_batch_item WHERE batch_id = ?", Long.class, batchId);
    }
}
