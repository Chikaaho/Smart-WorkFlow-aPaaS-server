package com.sw.ck.bootstrap.p62;

import com.sw.ck.bpm.process.entity.BpmCommandEffect;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.entity.CommandStatusEnum;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.queue.CommandEffectRecorder;
import com.sw.ck.bpm.process.queue.CommandEnvelope;
import com.sw.ck.bpm.process.queue.CommandFingerprint;
import com.sw.ck.bpm.process.queue.PersistentBpmCommandQueue;
import com.sw.ck.bpm.process.queue.TieredCommandReconcileJob;
import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.common.exception.BaseException;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P62 分级执行与统一命令：命令语义核心真实 PostgreSQL 行为证据。
 * <p>
 * 与 H2 模块级用例同口径，但在真实 PostgreSQL（含 V0.1.2 增量迁移）上验证：
 * 效果权威账本关闭"业务已提交、完成记录未写"窗口；租约回收后旧执行者不得提交效果
 * （效果至多一次，真事务 + 行锁）；准入截止只过期未执行且无效果的命令；统一逻辑身份唯一。
 * 自动轮询车道以 1 小时间隔静默，领取由测试直驱。
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P62 分级命令语义（真实 PostgreSQL）")
class TieredCommandSemanticsPgTest {

    private static final Long TENANT = 0L;
    private static final Long USER = 1L;

    private EmbeddedPostgres pg;
    private ConfigurableApplicationContext app;
    private JdbcTemplate jdbc;
    private PersistentBpmCommandQueue queue;
    private CommandEffectRecorder effectRecorder;
    private TieredCommandReconcileJob reconcileJob;
    private TransactionTemplate txTemplate;

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
        props.put("sw.security.jwt.secret", "p62-tiered-pg-jwt-secret-0123456789abcdef0123456789");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p62-tiered-pg-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.bpm.command.poll-interval-millis", "3600000");
        props.put("sw.bpm.command.p0-poll-interval-millis", "3600000");
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p62-tiered-pg", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p62TieredPgLoginContextProvider", provider);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);
        queue = app.getBean(PersistentBpmCommandQueue.class);
        effectRecorder = app.getBean(CommandEffectRecorder.class);
        reconcileJob = app.getBean(TieredCommandReconcileJob.class);
        txTemplate = new TransactionTemplate(app.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        seedIdentity();
        System.out.println("[P62-EV] tiered.boot pg=" + pg.getPort() + " migration=V0.1.2-applied");
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

    private void seedIdentity() {
        LoginUser user = new LoginUser();
        user.setUserId(USER);
        user.setTenantId(TENANT);
        LoginUserHolder.set(user);
    }

    private CommandEnvelope envelope(String key, String logicalId, int deadlineSeconds) {
        CommandEnvelope envelope = new CommandEnvelope();
        envelope.setCommandType(CommandTypeEnum.TASK_APPROVE);
        envelope.setChannel(CommandChannelEnum.NORMAL);
        envelope.setCommandKey(key);
        envelope.setTenantId(TENANT);
        envelope.setInitiatorId(USER);
        envelope.setPayload("{\"payload\":\"" + key + "\"}");
        envelope.setLogicalCommandId(logicalId);
        envelope.setPayloadFingerprint(CommandFingerprint.of(key));
        envelope.setTier("LIGHT_FLOW");
        envelope.setCompletionPoint("ACTION_COMMITTED");
        envelope.setDeadlineSeconds(deadlineSeconds);
        return envelope;
    }

    private Long accept(String key, String logicalId, int deadlineSeconds) {
        return txTemplate.execute(status -> queue.enqueue(envelope(key, logicalId, deadlineSeconds)));
    }

    private String statusOf(Long commandId) {
        return jdbc.queryForObject("SELECT status FROM sw_bpm_command WHERE id = ?", String.class, commandId);
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("真实 PG：效果权威+对账收敛关闭完成记录窗口（业务提交后未写完成记录仍确定恢复）")
    void effectLedgerAndReconcileOnRealPostgres() {
        Long commandId = accept("PG-EFFECT", "PG-LOG-1", 30);
        CommandEnvelope claimed = queue.claimDue(List.of(CommandChannelEnum.NORMAL), 1).stream()
                .filter(e -> e.getCommandId().equals(commandId)).findFirst().orElseThrow();

        // 业务效果事务：效果 + 权威行同事务提交；随后"崩溃"（不写完成记录）
        txTemplate.executeWithoutResult(status -> effectRecorder.record(
                commandId, claimed.getClaimToken(), "PG-LOG-1",
                "{\"status\":\"ACTION_COMMITTED\",\"bizRef\":\"PG-BIZ-1\"}", "PG-BIZ-1"));
        assertThat(statusOf(commandId)).isEqualTo(CommandStatusEnum.PROCESSING.getCode());
        assertThat(effectRecorder.findEffect(commandId).getResultJson()).contains("PG-BIZ-1");

        TieredCommandReconcileJob.ReconcileResult r = reconcileJob.reconcileOnce(LocalDateTime.now());
        assertThat(r.completedFromEffect()).isEqualTo(1);
        assertThat(statusOf(commandId)).isEqualTo(CommandStatusEnum.COMPLETED.getCode());
        assertThat(jdbc.queryForObject("SELECT result FROM sw_bpm_command WHERE id = ?", String.class, commandId))
                .contains("PG-BIZ-1");
        System.out.println("[P62-EV] tiered.effect command=" + commandId
                + " window-closed-by=authoritative-effect reconcile=COMPLETED effectRows=1");
    }

    @Test
    @DisplayName("真实 PG：租约回收后旧执行者提交效果被行锁+令牌拒绝，新领取者效果恰一条")
    void staleExecutorBlockedOnRealPostgres() {
        Long commandId = accept("PG-STALE", "PG-LOG-2", 30);
        CommandEnvelope first = queue.claimDue(List.of(CommandChannelEnum.NORMAL), 10).stream()
                .filter(e -> e.getCommandId().equals(commandId)).findFirst().orElseThrow();
        String staleToken = first.getClaimToken();

        jdbc.update("UPDATE sw_bpm_command SET claimed_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(10), commandId);
        assertThat(queue.reclaimStale(LocalDateTime.now().minusMinutes(1))).isGreaterThanOrEqualTo(1);
        CommandEnvelope second = queue.claimDue(List.of(CommandChannelEnum.NORMAL), 10).stream()
                .filter(e -> e.getCommandId().equals(commandId)).findFirst().orElseThrow();
        assertThat(second.getClaimToken()).isNotEqualTo(staleToken);

        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status -> effectRecorder.record(
                commandId, staleToken, "PG-LOG-2", "{\"status\":\"STALE\"}", "STALE-BIZ")))
                .isInstanceOf(BaseException.class);
        assertThat(effectRecorder.findEffect(commandId)).isNull();

        txTemplate.executeWithoutResult(status -> effectRecorder.record(
                commandId, second.getClaimToken(), "PG-LOG-2",
                "{\"status\":\"ACTION_COMMITTED\",\"bizRef\":\"PG-BIZ-2\"}", "PG-BIZ-2"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sw_bpm_command_effect WHERE command_id = ?",
                Long.class, commandId)).isEqualTo(1L);
        System.out.println("[P62-EV] tiered.stale command=" + commandId
                + " old-executor=rejected-by-lease new-effect=1");
    }

    @Test
    @DisplayName("真实 PG：准入截止仅过期未执行且无效果的命令；执行中只记超期不改状态")
    void deadlineSemanticsOnRealPostgres() {
        // 同一 Spring 上下文内其它用例可能遗留 PENDING 命令：先按"截止到期"排空，
        // 保证本用例的领取选择只针对本用例命令（确定性）
        queue.expireDue(LocalDateTime.now().plusYears(1));
        Long pendingId = accept("PG-DL-PENDING", "PG-DL-1", 1);
        Long processingId = accept("PG-DL-PROC", "PG-DL-2", 1);
        CommandEnvelope claimed = queue.claimDue(List.of(CommandChannelEnum.NORMAL), 1).stream()
                .filter(e -> e.getCommandId().equals(pendingId) || e.getCommandId().equals(processingId))
                .findFirst().orElseThrow();
        Long claimedId = claimed.getCommandId();
        Long otherId = claimedId.equals(pendingId) ? processingId : pendingId;
        LocalDateTime future = LocalDateTime.now().plusSeconds(5);

        assertThat(queue.markOverdue(future)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT overdue_at FROM sw_bpm_command WHERE id = ?",
                LocalDateTime.class, claimedId)).isNotNull();
        assertThat(statusOf(claimedId)).isEqualTo(CommandStatusEnum.PROCESSING.getCode());

        assertThat(queue.expireDue(future)).isEqualTo(1);
        assertThat(statusOf(otherId)).isEqualTo(CommandStatusEnum.EXPIRED.getCode());
        assertThat(statusOf(claimedId)).isEqualTo(CommandStatusEnum.PROCESSING.getCode());
        System.out.println("[P62-EV] tiered.deadline pending=" + otherId + "->EXPIRED processing=" + claimedId
                + "->overdue-only status-stable");
    }

    @Test
    @DisplayName("真实 PG：统一逻辑身份唯一（同身份重复受理被唯一索引拒绝），可按身份回查指纹")
    void logicalIdentityUniqueOnRealPostgres() {
        String fingerprint = CommandFingerprint.of("PG-LOGIC-1");
        Long first = accept("PG-LOGIC-1", "PG-ID-1", 30);
        assertThat(first).isNotNull();
        assertThatThrownBy(() -> accept("PG-LOGIC-2", "PG-ID-1", 30))
                .isInstanceOf(DuplicateKeyException.class);
        CommandEnvelope found = queue.findByLogicalId(TENANT, "PG-ID-1").orElseThrow();
        assertThat(found.getCommandKey()).isEqualTo("PG-LOGIC-1");
        assertThat(found.getPayloadFingerprint()).isEqualTo(fingerprint);
        System.out.println("[P62-EV] tiered.identity logical=PG-ID-1 duplicate=rejected queryable=true");
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
