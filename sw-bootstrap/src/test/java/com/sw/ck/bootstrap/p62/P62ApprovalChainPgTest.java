package com.sw.ck.bootstrap.p62;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.bpm.process.dto.CommandAcceptRespDTO;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.entity.BpmCommand;
import com.sw.ck.bpm.process.mapper.BpmCommandMapper;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.bpm.process.service.CommandAcceptService;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.service.FormDefService;
import com.sw.ck.form.service.FormSubmitService;
import com.sw.ck.form.txn.model.TxnActionConfig;
import com.sw.ck.form.txn.model.TxnActionSaveRequest;
import com.sw.ck.form.txn.model.TxnInvokeRequest;
import com.sw.ck.form.txn.model.TxnInvokeResult;
import com.sw.ck.form.txn.service.TxnActionExecutor;
import com.sw.ck.form.txn.service.TxnActionService;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P62 最终交付（direction-p62-final-delivery A01）：标准人工审批代表业务链
 * 同对象贯穿真实 PostgreSQL 端到端证据。
 * <p>
 * 场景一（成功链）：表单申请持久受理（FLOW_START 同事务）→ 库存预占受控动作
 * （ACTIVE 凭据 + RESERVE 台账）→ 标准流程人工审批任务 → 审批人经受控命令通道真实受理
 * 并通过（TASK_APPROVE 命令消费、审批动作关联 command_id）→ 批准后凭据受控确认
 * （CONFIRM 结算：余额扣减、预占归零、凭据 CONFIRMED）→ 用户按对象回查最终业务结果
 * （命令终态、实例终态、调用记录、台账勾稽一致）。
 * </p>
 * <p>
 * 场景二（拒绝/取消路径）：预占后审批被拒（REJECT 命令消费、实例 REJECTED）→
 * 已产生效果与待释放凭据可定位（ACTIVE 残留可查询）→ 受控释放（RELEASE 结算：
 * 预占归零、余额不变、凭据 RELEASED、台账追加），释放后无待处理凭据。
 * </p>
 * 人工等待保持在标准流程（非轻流程），不为凑链把人工审批塞入轻流程。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P62 最终交付：预占→人工审批→受控确认/拒绝→结果回查 同对象贯穿链（真实 PG）")
class P62ApprovalChainPgTest {

    private static final Long TENANT = 0L;
    private static final Long INITIATOR = 91201L;
    private static final Long APPROVER = 91202L;
    private static final String FORM_KEY = "p62_ac_stock";

    private static EmbeddedPostgres pg;
    private static ConfigurableApplicationContext app;
    private static JdbcTemplate jdbc;
    private static String stockTable;

    private FormDefService formDefService;
    private FormSubmitService submitService;
    private TxnActionService actionService;
    private TxnActionExecutor executor;
    private BpmProcessDefService processDefService;
    private CommandAcceptService acceptService;
    private BpmCommandMapper commandMapper;

    @BeforeAll
    void boot() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        String pgUrl = "jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified";
        Map<String, Object> props = new HashMap<>();
        props.put("server.port", "0");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "org.postgresql.Driver");
        props.put("spring.datasource.dynamic.datasource.master.url", pgUrl);
        props.put("spring.datasource.dynamic.datasource.master.username", "postgres");
        props.put("spring.datasource.dynamic.datasource.master.password", "postgres");
        props.put("sw.security.jwt.secret", "p62-ac-chain-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p62-ac-chain-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p62-ac-chain", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p62AcChainLoginContextProvider", provider);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);
        formDefService = app.getBean(FormDefService.class);
        submitService = app.getBean(FormSubmitService.class);
        actionService = app.getBean(TxnActionService.class);
        executor = app.getBean(TxnActionExecutor.class);
        processDefService = app.getBean(BpmProcessDefService.class);
        acceptService = app.getBean(CommandAcceptService.class);
        commandMapper = app.getBean(BpmCommandMapper.class);

        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'ac-chain-initiator', 'seed-not-a-login-secret', '贯穿链发起人', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", INITIATOR, TENANT);
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'ac-chain-approver', 'seed-not-a-login-secret', '贯穿链审批人', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", APPROVER, TENANT);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (91201, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, INITIATOR);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (91202, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, APPROVER);

        seedStockForm();
        publishApprovalProcess();
        System.out.println("[P62-EV] fd.chain boot ok pgPort=" + pg.getPort() + " table=" + stockTable
                + " form=" + FORM_KEY + " process=p62_ac_approval_flow(published)");
    }

    @AfterAll
    void tearDown() {
        if (app != null) {
            app.close();
        }
        try {
            if (pg != null) {
                pg.close();
            }
        } catch (Exception ignored) {
            // 关闭容错
        }
        LoginUserHolder.clear();
    }

    // ==================== 场景一：预占→审批通过→受控确认→结果回查 ====================

    @Test
    @DisplayName("成功链：表单申请→库存预占→人工审批通过（真实命令通道）→批准后受控确认→同对象最终结果回查")
    void approvalChainConfirmAndQuery() {
        // 1. 表单申请：提交持久化 + 同事务受理标准审批流程 FLOW_START
        String recordId = asUser(INITIATOR, () -> submitService.submitForm(FORM_KEY,
                data("material", "AC-A-001", "qty_available", "100", "qty_reserved", "0"),
                null, null, null));
        assertThat(recordId).isNotBlank();
        BpmCommand flowStart = findCommand("FLOW_START:" + recordId);
        assertThat(flowStart).as("表单提交事务内已受理 FLOW_START").isNotNull();

        // 2. 库存预占：受控动作产生 ACTIVE 凭据（同对象 recordId），余额不动、预占量增加
        String reserveId = publishAction("ac_a_reserve", "贯穿链A预占", "RESERVE", cfg(600L));
        TxnInvokeResult reserved = asUser(INITIATOR, () -> {
            TxnInvokeRequest req = new TxnInvokeRequest();
            req.setRecordId(recordId);
            req.setQuantity("5");
            req.setInvocationKey("FD-CHAIN-A-RESERVE");
            return executor.invoke(reserveId, req);
        });
        assertThat(reserved.status()).isEqualTo("SUCCEEDED");
        String reservationId = reserved.reservationId();
        assertThat(reservationId).isNotBlank();
        assertThat(balanceOf(recordId)).as("预占不动余额").isEqualByComparingTo("100");
        assertThat(reservedOf(recordId)).as("预占量+5").isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("SELECT status FROM sw_form_txn_reservation WHERE id = ?",
                String.class, reservationId)).as("凭据 ACTIVE 可定位").isEqualTo("ACTIVE");

        // 3. 人工审批任务出现（标准流程实例挂起等待审批人）
        String processInstanceId = awaitInstanceStarted(recordId);
        String taskId = awaitTask(processInstanceId);
        assertThat(jdbc.queryForObject("SELECT status FROM sw_bpm_instance WHERE business_key = ?",
                String.class, recordId)).as("审批挂起时实例仍 RUNNING").isEqualTo("RUNNING");

        // 4. 审批人经受控命令通道真实受理通过
        CommandAcceptRespDTO accepted = asUser(APPROVER, () -> {
            ApprovalActionRequest req = new ApprovalActionRequest();
            req.setComment("库存申请核准");
            return acceptService.acceptTaskAction(taskId, ApprovalAction.APPROVE, req,
                    CommandChannelEnum.NORMAL);
        });
        assertThat(accepted.getCommandId()).isNotNull();
        assertThat(accepted.isDuplicated()).as("duplicated=true=本次新建受理（CommandAcceptRespDTO 合同）").isTrue();

        // 5. 命令消费完成：命令 COMPLETED、审批动作与命令关联、实例 APPROVED
        awaitCommandCompleted(accepted.getCommandId());
        Map<String, Object> actionRow = jdbc.queryForMap(
                "SELECT actor_id, action, command_id FROM sw_bpm_approval_action WHERE task_id = ?", taskId);
        assertThat(((Number) actionRow.get("actor_id")).longValue()).isEqualTo(APPROVER);
        assertThat(actionRow.get("action")).isEqualTo("APPROVE");
        assertThat(((Number) actionRow.get("command_id")).longValue()).isEqualTo(accepted.getCommandId());
        assertThat(awaitInstanceTerminal(recordId, 120_000L)).as("流程经人工审批后到达通过终态")
                .isEqualTo("APPROVED");

        // 6. 批准后的受控确认：凭据结算，余额扣减、预占归零、凭据 CONFIRMED
        String confirmId = publishAction("ac_a_confirm", "贯穿链A确认", "CONFIRM", cfg(null));
        TxnInvokeResult confirmed = asUser(INITIATOR, () -> {
            TxnInvokeRequest req = new TxnInvokeRequest();
            req.setReservationId(reservationId);
            req.setInvocationKey("FD-CHAIN-A-CONFIRM");
            return executor.invoke(confirmId, req);
        });
        assertThat(confirmed.status()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT status FROM sw_form_txn_reservation WHERE id = ?",
                String.class, reservationId)).isEqualTo("CONFIRMED");
        assertThat(balanceOf(recordId)).as("确认扣减余额 100-5").isEqualByComparingTo("95");
        assertThat(reservedOf(recordId)).as("确认后预占归零").isEqualByComparingTo("0");

        // 7. 用户回查最终业务结果：调用记录/凭据/台账/实例/命令同对象一致
        Map<String, Object> confirmInvocation = jdbc.queryForMap(
                "SELECT status, action_version FROM sw_form_txn_invocation WHERE action_id = ?"
                        + " AND invocation_key = ?", confirmId, "FD-CHAIN-A-CONFIRM");
        assertThat(confirmInvocation.get("status")).isEqualTo("SUCCEEDED");
        long reserveLedger = countLedger(reservationId, "RESERVE");
        long confirmLedger = countLedger(reservationId, "CONFIRM");
        assertThat(reserveLedger).as("RESERVE 台账恰一条").isEqualTo(1L);
        assertThat(confirmLedger).as("CONFIRM 台账恰一条").isEqualTo(1L);
        Map<String, Object> lastEntry = jdbc.queryForMap(
                "SELECT balance_after, reserved_after FROM sw_form_txn_ledger WHERE reservation_id = ?"
                        + " AND entry_type = 'CONFIRM'", reservationId);
        assertThat((BigDecimal) lastEntry.get("balance_after")).as("台账终值=余额实际值")
                .isEqualByComparingTo(balanceOf(recordId));
        assertThat((BigDecimal) lastEntry.get("reserved_after")).isEqualByComparingTo(reservedOf(recordId));

        System.out.println("[P62-EV] fd.chain success form=" + FORM_KEY + " record=" + recordId
                + " reservation=" + reservationId + " instance=" + processInstanceId + " task=" + taskId
                + " approveCommand=" + accepted.getCommandId()
                + " flowStart=COMPLETED approve=COMPLETED instance=APPROVED"
                + " reserve(SUCCEEDED) confirm(SUCCEEDED) balance 100->95 reserved 5->0"
                + " ledger RESERVE=1 CONFIRM=1 terminal-entry=balance/reserved-consistent");
    }

    // ==================== 场景二：预占→审批拒绝→凭据定位→受控释放→回查 ====================

    @Test
    @DisplayName("异常链：预占后审批被拒→已产生效果与待释放凭据可定位→受控释放→台账与余额回查一致")
    void approvalChainRejectReleasesReservation() {
        // 1. 第二笔申请 + 预占
        String recordId = asUser(INITIATOR, () -> submitService.submitForm(FORM_KEY,
                data("material", "AC-B-001", "qty_available", "80", "qty_reserved", "0"),
                null, null, null));
        String reserveId = publishAction("ac_b_reserve", "贯穿链B预占", "RESERVE", cfg(600L));
        TxnInvokeResult reserved = asUser(INITIATOR, () -> {
            TxnInvokeRequest req = new TxnInvokeRequest();
            req.setRecordId(recordId);
            req.setQuantity("4");
            req.setInvocationKey("FD-CHAIN-B-RESERVE");
            return executor.invoke(reserveId, req);
        });
        String reservationId = reserved.reservationId();
        assertThat(reserved.status()).isEqualTo("SUCCEEDED");
        assertThat(reservedOf(recordId)).isEqualByComparingTo("4");

        // 2. 审批人拒绝（真实命令通道）
        String processInstanceId = awaitInstanceStarted(recordId);
        String taskId = awaitTask(processInstanceId);
        CommandAcceptRespDTO accepted = asUser(APPROVER, () -> {
            ApprovalActionRequest req = new ApprovalActionRequest();
            req.setComment("申请不满足出库条件，驳回");
            return acceptService.acceptTaskAction(taskId, ApprovalAction.REJECT, req,
                    CommandChannelEnum.NORMAL);
        });
        awaitCommandCompleted(accepted.getCommandId());
        assertThat(awaitInstanceTerminal(recordId, 120_000L)).as("拒绝后实例 REJECTED 终态")
                .isEqualTo("REJECTED");

        // 3. 已产生效果与待释放凭据可定位：余额未被改动、预占仍在、凭据仍 ACTIVE 可查询
        assertThat(balanceOf(recordId)).as("拒绝不改余额（预占效果保留待处置）").isEqualByComparingTo("80");
        assertThat(reservedOf(recordId)).as("待释放预占可定位（仍占 4）").isEqualByComparingTo("4");
        assertThat(jdbc.queryForObject("SELECT status FROM sw_form_txn_reservation WHERE id = ?",
                String.class, reservationId)).as("凭据 ACTIVE 待处置").isEqualTo("ACTIVE");
        long activeForRecord = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_form_txn_reservation WHERE record_id = ? AND status = 'ACTIVE'"
                        + " AND tenant_id = ?", Long.class, recordId, TENANT);
        assertThat(activeForRecord).as("按对象查询到待释放凭据恰一条").isEqualTo(1L);

        // 4. 受控释放：追加事实与审计（台账 RELEASE 条目），原 RESERVE 台账不删除
        String releaseId = publishAction("ac_b_release", "贯穿链B释放", "RELEASE", cfg(null));
        TxnInvokeResult released = asUser(INITIATOR, () -> {
            TxnInvokeRequest req = new TxnInvokeRequest();
            req.setReservationId(reservationId);
            req.setInvocationKey("FD-CHAIN-B-RELEASE");
            return executor.invoke(releaseId, req);
        });
        assertThat(released.status()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT status FROM sw_form_txn_reservation WHERE id = ?",
                String.class, reservationId)).isEqualTo("RELEASED");
        assertThat(balanceOf(recordId)).as("释放只回预占不动余额").isEqualByComparingTo("80");
        assertThat(reservedOf(recordId)).as("释放后预占归零").isEqualByComparingTo("0");

        // 5. 回查：RESERVE/RELEASE 台账各恰一条（追加式、原条目保留），无待处理凭据残留
        assertThat(countLedger(reservationId, "RESERVE")).as("原预占台账保留").isEqualTo(1L);
        assertThat(countLedger(reservationId, "RELEASE")).as("释放为追加台账条目").isEqualTo(1L);
        long remainingActive = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_form_txn_reservation WHERE record_id = ? AND status = 'ACTIVE'"
                        + " AND tenant_id = ?", Long.class, recordId, TENANT);
        assertThat(remainingActive).as("受控处置后无 ACTIVE 残留").isZero();
        Map<String, Object> releaseEntry = jdbc.queryForMap(
                "SELECT balance_after, reserved_after FROM sw_form_txn_ledger WHERE reservation_id = ?"
                        + " AND entry_type = 'RELEASE'", reservationId);
        assertThat((BigDecimal) releaseEntry.get("balance_after")).isEqualByComparingTo("80");
        assertThat((BigDecimal) releaseEntry.get("reserved_after")).isEqualByComparingTo("0");

        System.out.println("[P62-EV] fd.chain reject form=" + FORM_KEY + " record=" + recordId
                + " reservation=" + reservationId + " instance=" + processInstanceId + " task=" + taskId
                + " rejectCommand=" + accepted.getCommandId()
                + " reject=COMPLETED instance=REJECTED"
                + " pending-reservation=locatable(ACTIVE=1) release(SUCCEEDED)"
                + " balance 80 unchanged reserved 4->0 ledger RESERVE=1 RELEASE=1 active-left=0");
    }

    // ==================== 流程定义 ====================

    private void publishApprovalProcess() {
        BpmProcessDef def = asUser(INITIATOR, () -> processDefService.createDef("贯穿链标准审批流程", FORM_KEY));
        List<GraphElement> elements = List.of(
                node("start", "START"),
                node("approver", "APPROVAL", Map.of("name", "人工审批",
                        "approver", Map.of("type", "DESIGNATED", "value", String.valueOf(APPROVER)))),
                node("end", "END"),
                edge("e1", "start", "approver"),
                edge("e2", "approver", "end"));
        ProcessGraph graph = ProcessGraph.builder()
                .processKey(def.getProcessKey())
                .name("贯穿链标准审批流程")
                .formKey(FORM_KEY)
                .version(1)
                .elements(elements)
                .build();
        asUser(INITIATOR, () -> {
            processDefService.saveDraftGraph(def.getId(), toJson(graph));
            processDefService.publish(def.getId());
            return null;
        });
        BpmProcessDef published = asUser(INITIATOR, () -> processDefService.findById(def.getId()));
        assertThat(published.getStatus()).isEqualTo("PUBLISHED");
        assertThat(published.getProcessDefinitionId()).as("标准审批流程已部署").isNotBlank();
    }

    // ==================== 有界等待 ====================

    /** 有界等待 FLOW_START 命令消费并创建流程实例（真实调度，不空等）。 */
    private String awaitInstanceStarted(String recordId) {
        long deadline = System.currentTimeMillis() + 180_000L;
        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT process_instance_id FROM sw_bpm_instance WHERE business_key = ?", recordId);
            if (!rows.isEmpty()) {
                return String.valueOf(rows.get(0).get("process_instance_id"));
            }
            sleepBriefly();
        }
        throw new AssertionError("180s 内流程实例未创建: record=" + recordId
                + " command=" + findCommand("FLOW_START:" + recordId));
    }

    /** 有界等待引擎在当前节点产生人工审批任务并返回 taskId。 */
    private String awaitTask(String processInstanceId) {
        org.flowable.engine.TaskService taskService = app.getBean(org.flowable.engine.TaskService.class);
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            var task = taskService.createTaskQuery().processInstanceId(processInstanceId).singleResult();
            if (task != null) {
                return task.getId();
            }
            sleepBriefly();
        }
        throw new AssertionError("60s 内未产生人工审批任务: instance=" + processInstanceId);
    }

    /** 有界等待受理命令被真实调度器消费到 COMPLETED。 */
    private void awaitCommandCompleted(Long commandId) {
        long deadline = System.currentTimeMillis() + 120_000L;
        String status = null;
        while (System.currentTimeMillis() < deadline) {
            status = jdbc.queryForObject("SELECT status FROM sw_bpm_command WHERE id = ?",
                    String.class, commandId);
            if ("COMPLETED".equals(status)) {
                return;
            }
            assertThat(status).as("审批命令不得进入失败/过期终态").isNotIn("FAILED", "EXPIRED");
            sleepBriefly();
        }
        throw new AssertionError("120s 内审批命令未 COMPLETED: command=" + commandId + " status=" + status);
    }

    /** 有界等待实例进入终态（触发一次状态对账收敛，与既有阶段模板一致）。 */
    private String awaitInstanceTerminal(String recordId, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            List<String> statuses = jdbc.queryForList(
                    "SELECT status FROM sw_bpm_instance WHERE business_key = ?", String.class, recordId);
            if (!statuses.isEmpty() && ("APPROVED".equals(statuses.get(0)) || "REJECTED".equals(statuses.get(0)))) {
                return statuses.get(0);
            }
            asUser(INITIATOR, () -> app.getBean(com.sw.ck.bpm.process.job.BpmInstanceStateSyncJob.class)
                    .sweepOnce());
            sleepBriefly();
        }
        throw new AssertionError(timeoutMillis + "ms 内实例未进入终态: record=" + recordId);
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ==================== 辅助 ====================

    private BpmCommand findCommand(String commandKey) {
        return asUser(INITIATOR, () -> commandMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BpmCommand>()
                        .eq(BpmCommand::getCommandKey, commandKey)
                        .eq(BpmCommand::getTenantId, TENANT)
                        .orderByDesc(BpmCommand::getId)
                        .last("LIMIT 1"))).stream().findFirst().orElse(null);
    }

    private long countLedger(String reservationId, String entryType) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sw_form_txn_ledger WHERE reservation_id = ?"
                + " AND entry_type = ?", Long.class, reservationId, entryType);
    }

    private void seedStockForm() {
        asUser(INITIATOR, () -> {
            FormDefDTO draft = formDefService.createDraft(FORM_KEY, "P62贯穿链库存", null, null);
            formDefService.saveConfig(draft.getId(), stockDefinition());
            formDefService.publish(draft.getId());
            return null;
        });
        FormDefDTO def = asUser(INITIATOR, () -> formDefService.getFormDefByKey(FORM_KEY));
        stockTable = def.getPhysicalTableName();
        assertThat(stockTable).isNotBlank();
    }

    private String stockDefinition() {
        return "{\"schemaVersion\":1,\"title\":\"P62贯穿链库存\",\"fields\":["
                + "{\"name\":\"material\",\"type\":\"TEXT\",\"label\":\"物料\",\"required\":false},"
                + "{\"name\":\"qty_available\",\"type\":\"NUMBER\",\"label\":\"可用量\",\"required\":false},"
                + "{\"name\":\"qty_reserved\",\"type\":\"NUMBER\",\"label\":\"预占量\",\"required\":false}]}";
    }

    private String publishAction(String key, String name, String type, TxnActionConfig cfg) {
        String formId = asUser(INITIATOR, () -> formDefService.getFormDefByKey(FORM_KEY).getId());
        return asUser(INITIATOR, () -> {
            String id = actionService.create(formId, new TxnActionSaveRequest(key, name, type, null, cfg)).id();
            actionService.publish(id);
            return id;
        });
    }

    private TxnActionConfig cfg(Long ttl) {
        TxnActionConfig c = new TxnActionConfig();
        c.setBalanceField("qty_available");
        c.setReservedField("qty_reserved");
        c.setExpiresInSeconds(ttl);
        return c;
    }

    private BigDecimal balanceOf(String recordId) {
        return jdbc.queryForObject("SELECT " + q("qty_available") + " FROM " + q(stockTable)
                + " WHERE " + q("id") + " = ?", BigDecimal.class, recordId);
    }

    private BigDecimal reservedOf(String recordId) {
        return jdbc.queryForObject("SELECT " + q("qty_reserved") + " FROM " + q(stockTable)
                + " WHERE " + q("id") + " = ?", BigDecimal.class, recordId);
    }

    private String q(String identifier) {
        return "\"" + identifier + "\"";
    }

    private static Map<String, Object> data(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private static GraphElement node(String id, String type) {
        return node(id, type, Map.of());
    }

    private static GraphElement node(String id, String type, Map<String, Object> config) {
        return GraphElement.builder().id(id).kind("node").type(type)
                .config(config == null ? Map.of() : config).style(Map.of()).build();
    }

    private static GraphElement edge(String id, String source, String target) {
        return GraphElement.builder().id(id).kind("edge").source(source).target(target)
                .config(Map.of()).style(Map.of()).build();
    }

    private String toJson(ProcessGraph graph) {
        try {
            return app.getBean(com.fasterxml.jackson.databind.ObjectMapper.class).writeValueAsString(graph);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 以指定业务身份执行（模拟真实会话；租户/用户/权限上下文与消费侧一致）。 */
    private static <T> T asUser(Long userId, Callable<T> action) {
        LoginUser previous = LoginUserHolder.get();
        LoginUser user = new LoginUser();
        user.setUserId(userId);
        user.setTenantId(TENANT);
        user.setPermissions(new ArrayList<>(List.of("form:action:invoke", "workflow:def:publish")));
        try {
            LoginUserHolder.set(user);
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            if (previous == null) {
                LoginUserHolder.clear();
            } else {
                LoginUserHolder.set(previous);
            }
        }
    }

    private static String generatedRsaPkcs8Base64() {
        try {
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return java.util.Base64.getEncoder()
                    .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
