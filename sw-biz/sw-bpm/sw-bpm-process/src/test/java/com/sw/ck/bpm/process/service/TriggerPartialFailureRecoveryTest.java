package com.sw.ck.bpm.process.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.ActionConfig;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.dto.ProcessVariableDef;
import com.sw.ck.bpm.api.dto.TriggerConfig;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.api.script.BpmScriptEvaluatePort;
import com.sw.ck.bpm.process.entity.BpmActionRef;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.entity.CommandStatusEnum;
import com.sw.ck.bpm.process.mapper.BpmActionRefMapper;
import com.sw.ck.bpm.process.mapper.BpmTriggerExecMapper;
import com.sw.ck.bpm.process.queue.BpmCommandQueue;
import com.sw.ck.bpm.process.queue.CommandEnvelope;
import com.sw.ck.bpm.process.queue.support.QueueH2TestConfig;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BE020-G2 余项：同一监听部分失败后的有界恢复（真实持久命令队列 + 真实恢复入口）。
 * <p>
 * 同一合法监听/实例/事件（PROCESS_COMPLETED，APPROVED 终态、同事务阶段）带两个动作
 * （act-1/act-2，各自稳定动作标识进入 commandKey 与 action_ref）：act-1 正常完成；
 * act-2 以既有队列 reject 语义受控失败（零副作用、落终态 FAILED 与原因）。
 * 经既有合法恢复入口 {@link ActionRefRecoveryService#retry} 复用同键命令重新入队后
 * 完成恢复。受控接收端为测试内效果计数器；物理租约/状态沿现有 sw_bpm_command 机制，
 * 不发明新表或重试体系。
 * </p>
 */
@SpringBootTest(classes = QueueH2TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("BE020-G2 余项：同一监听部分失败的有界恢复（H2 真实持久队列）")
class TriggerPartialFailureRecoveryTest {

    private static final long TENANT = 1L;

    @Autowired
    private BpmCommandQueue queue;
    @Autowired
    private TransactionTemplate txTemplate;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired
    private BpmActionRefMapper actionRefMapper;
    @Autowired
    private BpmTriggerExecMapper triggerExecMapper;

    private final Map<String, AtomicInteger> receiverEffects = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        LoginUser user = new LoginUser();
        user.setUserId(7L);
        user.setTenantId(TENANT);
        LoginUserHolder.set(user);
        receiverEffects.clear();
        jdbcTemplate.update("DELETE FROM sw_bpm_command");
        jdbcTemplate.update("DELETE FROM sw_bpm_action_ref");
        jdbcTemplate.update("DELETE FROM sw_bpm_trigger_exec");
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    private TriggerExecutionService triggerService() {
        BpmScriptEvaluatePort okScript = mock(BpmScriptEvaluatePort.class);
        when(okScript.run(anyString(), any(), anyLong(), any())).thenAnswer(inv ->
                java.util.Optional.of(new BpmScriptEvaluatePort.ScriptOutcome(
                        "OK", 1L, "NUMBER", null, 1L)));
        BpmVariableSnapshotService snapshotService = mock(BpmVariableSnapshotService.class);
        when(snapshotService.buildSnapshot(any(), any(), any(), any(), any(), anyLong()))
                .thenReturn(new BpmVariableSnapshotService.SnapshotResult(
                        Map.of("v_set", List.of("11")),
                        "{\"variables\":{\"v_set\":[\"11\"]}}", false, List.of(), List.of()));
        NodeFormDataService nodeFormDataService = mock(NodeFormDataService.class);
        BpmInstance instance = instance();
        ProcessGraph graph = ProcessGraph.builder()
                .processKey("def_main")
                .variables(List.of(ProcessVariableDef.builder()
                        .varId("v_set").type("USER_SET").source("MAIN_FORM")
                        .sourceField("handlers").aggregation("UNION").nullable(false).build()))
                .triggers(List.of(trigger()))
                .build();
        when(nodeFormDataService.loadGraph("def_main", 2)).thenReturn(graph);
        when(nodeFormDataService.currentRound("pi-1")).thenReturn(1L);
        return new TriggerExecutionService(nodeFormDataService, snapshotService, okScript,
                queue, triggerExecMapper, actionRefMapper, mock(BpmTaskFacade.class),
                new ObjectMapper(), null, null, transactionManager);
    }

    private BpmInstance instance() {
        BpmInstance instance = new BpmInstance();
        instance.setProcessInstanceId("pi-1");
        instance.setProcessDefKey("def_main");
        instance.setBusinessKey("rec-1");
        instance.setFormKey("main_form");
        instance.setInitiatorId(7L);
        instance.setTenantId(TENANT);
        instance.setDefVersion(2);
        return instance;
    }

    /** 同一分支的两个动作：稳定动作标识 act-1/act-2 各自派发一项意图命令。 */
    private TriggerConfig trigger() {
        ActionConfig act1 = singleAction("act-1");
        ActionConfig act2 = singleAction("act-2");
        return TriggerConfig.builder()
                .triggerId("trg-1").name("t").event(TriggerExecutionService.EVENT_PROCESS_COMPLETED)
                .script("return 1;").variables(List.of("v_set"))
                .branches(List.of(TriggerConfig.TriggerBranch.builder()
                        .branchId("b1").name("b").matchType("NUMBER").matchValue("1")
                        .actions(List.of(act1, act2)).build()))
                .build();
    }

    private ActionConfig singleAction(String actionId) {
        return ActionConfig.builder()
                .actionId(actionId).name(actionId).type("START_EACH")
                .sourceVariable("v_set").targetProcessDefKey("def_target")
                .targetFormKey("target_form").maxDispatch(50)
                .mapping(List.of(ActionConfig.ActionMapping.builder()
                        .targetField("owner").itemField("id").build()))
                .build();
    }

    private int receiverEffect(String actionId) {
        AtomicInteger effect = receiverEffects.get(actionId);
        return effect == null ? 0 : effect.get();
    }

    private Optional<CommandEnvelope> findCommandByAction(String actionId) {
        // 命令键已收敛为 P64ACT 摘要（不再含动作段），改由动作引用表按 action_id 回查 command_key
        return jdbcTemplate.queryForList(
                "SELECT command_key FROM sw_bpm_action_ref WHERE tenant_id = ? AND action_id = ? ORDER BY id",
                String.class, TENANT, actionId).stream()
                .findFirst()
                .flatMap(key -> queue.findByKey(TENANT, key));
    }

    private String commandState(Long commandId) {
        return jdbcTemplate.queryForObject(
                "SELECT status || '|' || COALESCE(failure_reason, '-') || '|' || retry_count "
                        + "FROM sw_bpm_command WHERE id = ?", String.class, commandId);
    }

    private void recordReceiverEffect(CommandEnvelope envelope, String actionId) {
        // 命令键已收敛为 P64ACT 摘要（不含动作段），动作归属由测试调用方显式传入
        receiverEffects.computeIfAbsent(actionId, k -> new AtomicInteger()).incrementAndGet();
    }

    @Test
    @DisplayName("部分失败恢复：act-1 完成、act-2 受控失败零副作用，恢复后完成且成功计数保持1")
    void partialFailureRecoversViaExistingEntryWithoutDuplicatingSuccess() {
        // 1. 首次执行：同一监听一次评估派发两项意图（真实事务 + 真实持久命令/引用）
        txTemplate.executeWithoutResult(status ->
                triggerService().onProcessCompleted(instance(), "APPROVED"));

        Optional<CommandEnvelope> act1 = findCommandByAction("act-1");
        Optional<CommandEnvelope> act2 = findCommandByAction("act-2");
        assertThat(act1).as("act-1 意图命令已受理").isPresent();
        assertThat(act2).as("act-2 意图命令已受理").isPresent();
        assertThat(act1.get().getCommandId()).isNotEqualTo(act2.get().getCommandId());
        Integer refCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_action_ref WHERE tenant_id = ?", Integer.class, TENANT);
        assertThat(refCount).as("两条动作引用持久化").isEqualTo(2);
        assertThat(receiverEffect("act-1")).as("首次执行零接收端效果（尚未消费）").isZero();
        assertThat(receiverEffect("act-2")).isZero();

        // 2. act-1 正常完成（受控接收端确认，效果计数 1；持久终态 COMPLETED + 结果）
        List<CommandEnvelope> claimed = queue.claimDue(List.of(CommandChannelEnum.NORMAL), 10);
        CommandEnvelope act1Claimed = claimed.stream()
                .filter(env -> env.getCommandId().equals(act1.get().getCommandId()))
                .findFirst().orElseThrow();
        recordReceiverEffect(act1Claimed, "act-1");
        queue.complete(act1Claimed.getCommandId(), act1Claimed.getClaimToken(),
                "{\"status\":\"OK\",\"action\":\"act-1\"}");
        assertThat(commandState(act1Claimed.getCommandId()))
                .as("act-1 持久终态 COMPLETED").startsWith("COMPLETED|");
        assertThat(receiverEffect("act-1")).isEqualTo(1);

        // 3. act-2 受控失败：既有队列拒绝语义（消费前安全门禁，零副作用，落终态 FAILED + 原因）
        CommandEnvelope act2Claimed = claimed.stream()
                .filter(env -> env.getCommandId().equals(act2.get().getCommandId()))
                .findFirst().orElseThrow();
        queue.reject(act2Claimed.getCommandId(), act2Claimed.getClaimToken(),
                "受控失败：act-2 接收端安全门禁拒绝（明确无未知副作用）");
        assertThat(commandState(act2Claimed.getCommandId()))
                .as("act-2 受控失败落 FAILED 与原因")
                .isEqualTo("FAILED|受控失败：act-2 接收端安全门禁拒绝（明确无未知副作用）|0");
        assertThat(receiverEffect("act-2")).as("受控失败零副作用").isZero();

        // 4. 经既有合法恢复入口恢复 act-2（复用同键命令重新入队，不改历史行、不生成第二个实例）
        BpmActionRef act2Ref = actionRefMapper.selectOne(Wrappers.<BpmActionRef>lambdaQuery()
                .eq(BpmActionRef::getTenantId, TENANT)
                .eq(BpmActionRef::getActionId, "act-2")
                .last("LIMIT 1"));
        assertThat(act2Ref).isNotNull();
        ActionRefRecoveryService recoveryService = new ActionRefRecoveryService(queue,
                new CommandRetryService(queue), null, actionRefMapper,
                mock(BpmInstanceService.class), new ObjectMapper());
        ActionRefRecoveryService.RetryOutcome outcome = recoveryService.retry(act2Ref);
        assertThat(outcome.status()).as("FAILED 分支按既有恢复入口重新入队").isEqualTo("INTENT_SUBMITTED");
        assertThat(outcome.commandId()).as("复用同一命令行（同键不新插）")
                .isEqualTo(act2Claimed.getCommandId());
        assertThat(commandState(act2Claimed.getCommandId())).startsWith("PENDING|");
        BpmActionRef act2RefAfter = actionRefMapper.selectById(act2Ref.getId());
        assertThat(act2RefAfter.getStatus()).isEqualTo("INTENT_SUBMITTED");

        // 5. 恢复后的 act-2 正常完成：成功动作效果计数保持 1，act-1 不受影响
        List<CommandEnvelope> reclaimed = queue.claimDue(List.of(CommandChannelEnum.NORMAL), 10);
        CommandEnvelope act2Recovered = reclaimed.stream()
                .filter(env -> env.getCommandId().equals(act2Claimed.getCommandId()))
                .findFirst().orElseThrow();
        recordReceiverEffect(act2Recovered, "act-2");
        queue.complete(act2Recovered.getCommandId(), act2Recovered.getClaimToken(),
                "{\"status\":\"OK\",\"action\":\"act-2\",\"recovered\":true}");
        assertThat(receiverEffect("act-1")).as("已成功动作不重复执行").isEqualTo(1);
        assertThat(receiverEffect("act-2")).as("失败动作恢复后恰好成功一次").isEqualTo(1);

        // 6. 最终持久结果解释三段去向：act-1 一次成功；act-2 同一命令行经失败后恢复完成；
        //    业务对象不变（无新业务/第二实例：仅两条命令行、同键复用、无恢复代新行）
        assertThat(commandState(act1Claimed.getCommandId())).startsWith("COMPLETED|");
        assertThat(commandState(act2Claimed.getCommandId())).startsWith("COMPLETED|");
        Integer commandCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_command WHERE tenant_id = ?", Integer.class, TENANT);
        assertThat(commandCount).as("无新增命令行（同键复用，未生成第二实例或恢复代新行）").isEqualTo(2);
        String act2Result = jdbcTemplate.queryForObject(
                "SELECT result FROM sw_bpm_command WHERE id = ?", String.class,
                act2Claimed.getCommandId());
        assertThat(act2Result).contains("recovered");
    }
}
