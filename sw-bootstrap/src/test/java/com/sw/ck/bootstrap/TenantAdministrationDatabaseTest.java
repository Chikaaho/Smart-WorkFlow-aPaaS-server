// package com.sw.ck.bootstrap;

// import com.baomidou.mybatisplus.annotation.DbType;
// import com.baomidou.mybatisplus.core.MybatisConfiguration;
// import com.baomidou.mybatisplus.core.config.GlobalConfig;
// import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
// import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
// import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
// import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
// import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;

// import com.sw.ck.bpm.process.entity.CommandChannelEnum;
// import com.sw.ck.bpm.process.entity.CommandTypeEnum;
// import com.sw.ck.bpm.process.mapper.BpmCommandMapper;
// import com.sw.ck.bpm.process.queue.BpmCommandHandler;
// import com.sw.ck.bpm.process.queue.CommandDispatcher;
// import com.sw.ck.bpm.process.queue.CommandEnvelope;
// import com.sw.ck.bpm.process.queue.PersistentBpmCommandQueue;
// import com.sw.ck.bpm.process.service.ResourceAdmissionService;
// import com.sw.ck.bpm.process.service.ResourceReleaseService;
// import com.sw.ck.bpm.process.service.impl.BpmCommandServiceImpl;
// import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
// import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
// import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
// import com.sw.ck.common.exception.BaseException;
// import com.sw.ck.notify.api.NotifyBizType;
// import com.sw.ck.notify.api.NotifyChannel;
// import com.sw.ck.notify.api.NotifyChannelAdapter;
// import com.sw.ck.notify.api.NotifySendRequest;
// import com.sw.ck.notify.api.NotifySendResult;
// import com.sw.ck.notify.impl.NotifyFacadeImpl;
// import com.sw.ck.notify.mapper.NotifyMessageMapper;
// import com.sw.ck.notify.mapper.NotifySendAttemptMapper;
// import com.sw.ck.notify.service.impl.NotifyDeliveryRecoveryServiceImpl;
// import com.sw.ck.notify.service.impl.NotifyMessageServiceImpl;
// import com.sw.ck.security.holder.LoginUser;
// import com.sw.ck.security.holder.LoginUserHolder;
// import com.sw.ck.security.support.SecurityLoginContextProvider;
// import com.sw.ck.system.controller.DeptController;
// import com.sw.ck.system.controller.UserController;
// import com.sw.ck.system.controller.UserController.PostAssignment;
// import com.sw.ck.system.controller.UserController.UserFormRequest;
// import com.sw.ck.system.entity.SysDept;
// import com.sw.ck.system.mapper.SysDeptMapper;
// import com.sw.ck.system.mapper.SysMenuMapper;
// import com.sw.ck.system.mapper.SysPostMapper;
// import com.sw.ck.system.mapper.SysRoleDeptMapper;
// import com.sw.ck.system.mapper.SysRoleMapper;
// import com.sw.ck.system.mapper.SysRoleMenuMapper;
// import com.sw.ck.system.mapper.SysTenantMapper;
// import com.sw.ck.system.mapper.SysTenantStatusAuditMapper;
// import com.sw.ck.system.mapper.SysUserMapper;
// import com.sw.ck.system.mapper.SysUserPostMapper;
// import com.sw.ck.system.mapper.SysUserRoleMapper;
// import com.sw.ck.system.security.UserDetailsProviderImpl;
// import com.sw.ck.system.service.ManagementTenantContext;
// import com.sw.ck.system.service.SysDeptService;
// import com.sw.ck.system.service.SysUserService;
// import com.sw.ck.system.service.TenantAdminService;
// import com.sw.ck.system.service.TenantValidityService;
// import com.sw.ck.system.service.TenantWorkAdmissionService;
// import com.sw.ck.system.service.UserImportExportService;
// import com.sw.ck.system.service.UserPageQuery;
// import com.sw.ck.system.service.impl.SysDeptServiceImpl;
// import com.sw.ck.system.service.impl.SysUserServiceImpl;

// import com.sun.net.httpserver.HttpServer;
// import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
// import org.flywaydb.core.Flyway;
// import org.junit.jupiter.api.AfterEach;
// import org.junit.jupiter.params.ParameterizedTest;
// import org.junit.jupiter.params.provider.EnumSource;
// import org.mockito.ArgumentMatchers;
// import org.mockito.Mockito;
// import org.mybatis.spring.SqlSessionTemplate;
// import org.springframework.aop.framework.ProxyFactory;
// import org.springframework.jdbc.core.JdbcTemplate;
// import org.springframework.jdbc.datasource.DataSourceTransactionManager;
// import org.springframework.jdbc.datasource.DriverManagerDataSource;
// import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
// import org.springframework.test.util.ReflectionTestUtils;
// import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
// import org.springframework.transaction.interceptor.TransactionInterceptor;
// import org.springframework.transaction.support.TransactionTemplate;

// import java.net.InetSocketAddress;
// import java.net.URI;
// import java.net.http.HttpClient;
// import java.net.http.HttpRequest;
// import java.net.http.HttpRequest.BodyPublishers;
// import java.net.http.HttpResponse.BodyHandlers;
// import java.time.Duration;
// import java.time.LocalDateTime;
// import java.util.Arrays;
// import java.util.List;
// import java.util.Optional;
// import java.util.Properties;
// import java.util.Set;
// import java.util.UUID;
// import java.util.concurrent.Callable;
// import java.util.concurrent.CountDownLatch;
// import java.util.concurrent.CyclicBarrier;
// import java.util.concurrent.ExecutorService;
// import java.util.concurrent.Executors;
// import java.util.concurrent.TimeUnit;
// import java.util.concurrent.TimeoutException;
// import java.util.concurrent.atomic.AtomicInteger;

// import static org.assertj.core.api.Assertions.assertThat;
// import static org.assertj.core.api.Assertions.assertThatThrownBy;

// /** T020：正式迁移、真实 Mapper/租户拦截器与 Spring 事务代理的 H2/PG 行为。 */
// class TenantAdministrationDatabaseTest {
//     enum Database { H2, POSTGRES }

//     @AfterEach
//     void clearIdentity() { LoginUserHolder.clear(); }

//     @ParameterizedTest
//     @EnumSource(Database.class)
//     void tenantStatusChangeWritesActorReasonAndEffectiveAdmissionResult(Database database) throws Exception {
//         try (Fixture f = new Fixture(database, false)) {
//             rootIdentity();
//             Long tenantId = f.service.create(command());
//             f.service.updateStatus(tenantId, 1, "季度暂停");

//             assertThat(f.jdbc.queryForObject(
//                     "SELECT action FROM sys_tenant_status_audit WHERE tenant_id = ? ORDER BY id DESC LIMIT 1",
//                     String.class, tenantId)).isEqualTo("DISABLE");
//             assertThat(f.jdbc.queryForObject(
//                     "SELECT reason FROM sys_tenant_status_audit WHERE tenant_id = ? ORDER BY id DESC LIMIT 1",
//                     String.class, tenantId)).isEqualTo("季度暂停");
//             assertThat(f.jdbc.queryForObject(
//                     "SELECT operator_id FROM sys_tenant_status_audit WHERE tenant_id = ? ORDER BY id DESC LIMIT 1",
//                     Long.class, tenantId)).isEqualTo(1L);
//             assertThat(f.workAdmission.claim(tenantId, () -> 1)).isZero();

//             rootIdentity();
//             f.service.updateStatus(tenantId, 0, "季度恢复");
//             assertThat(f.jdbc.queryForObject(
//                     "SELECT COUNT(*) FROM sys_tenant_status_audit WHERE tenant_id = ?", Long.class, tenantId))
//                     .isEqualTo(2L);
//             assertThat(f.workAdmission.claim(tenantId, () -> 1)).isEqualTo(1);
//         }
//     }

//     @ParameterizedTest
//     @EnumSource(Database.class)
//     void notificationPausePreservesIntentAndResumesOneHttpDelivery(Database database) throws Exception {
//         try (Fixture f = new Fixture(database, false); var peer = new ControlledNotificationPeer()) {
//             rootIdentity();
//             Long tenantId = f.service.create(command());
//             Long adminId = f.service.detail(tenantId).adminUserId();
//             LoginUser actor = new LoginUser(); actor.setUserId(adminId); actor.setTenantId(tenantId); LoginUserHolder.set(actor);
//             var facade = f.notifications(peer.adapter());
//             var recovery = new NotifyDeliveryRecoveryServiceImpl(f.notificationMapper, f.notificationAttempts, facade, f.workAdmission);
//             var request = notificationRequest(tenantId, adminId, "paused-notification");
//             assertThat(facade.recordIntent(request).orElseThrow().getStatus()).isEqualTo("PENDING");
//             Long id = f.jdbc.queryForObject("SELECT id FROM sw_notify_message WHERE tenant_id = ? AND idempotency_key = ?", Long.class, tenantId, request.getIdempotencyKey());
//             f.jdbc.update("UPDATE sys_tenant SET status = 1 WHERE id = ?", tenantId);
//             assertThat(facade.send(request).orElseThrow().getStatus()).isEqualTo("PENDING");
//             assertThat(recovery.recoverDue()).isZero();
//             assertThat(peer.requests).hasValue(0);
//             assertThat(f.jdbc.queryForObject("SELECT retry_count FROM sw_notify_message WHERE id = ?", Integer.class, id)).isZero();
//             assertThat(f.jdbc.queryForObject("SELECT delivery_status FROM sw_notify_message WHERE id = ?", String.class, id)).isEqualTo("PENDING");
//             f.jdbc.update("UPDATE sys_tenant SET status = 0 WHERE id = ?", tenantId);
//             assertThat(recovery.recoverDue()).isEqualTo(1);
//             LoginUserHolder.set(actor);
//             assertThat(facade.send(request).orElseThrow().getStatus()).isEqualTo("SUCCESS");
//             assertThat(recovery.recoverDue()).isZero();
//             assertThat(peer.requests).hasValue(1);
//             assertThat(f.count("SELECT COUNT(*) FROM sw_notify_message WHERE tenant_id = ? AND idempotency_key = ?", tenantId, request.getIdempotencyKey())).isEqualTo(1);
//             assertThat(f.count("SELECT COUNT(*) FROM sw_notify_send_attempt WHERE message_id = ?", id)).isEqualTo(1);
//             assertThat(f.jdbc.queryForObject("SELECT delivery_status FROM sw_notify_message WHERE id = ?", String.class, id)).isEqualTo("SUCCESS");
//             System.out.println("[T020-" + database + "] paused notification=" + id + " resumed same intent, HTTP=1, attempts=1");
//         }
//     }

//     @ParameterizedTest
//     @EnumSource(Database.class)
//     void externalNotificationInsideBusinessTransactionWaitsForCommit(Database database) throws Exception {
//         try (Fixture f = new Fixture(database, false); var peer = new ControlledNotificationPeer()) {
//             rootIdentity();
//             Long tenantId = f.service.create(command());
//             Long adminId = f.service.detail(tenantId).adminUserId();
//             LoginUser actor = new LoginUser(); actor.setUserId(adminId); actor.setTenantId(tenantId); LoginUserHolder.set(actor);
//             var facade = f.notifications(peer.adapter());
//             var request = notificationRequest(tenantId, adminId, "transactional-notification");
//             var tx = new TransactionTemplate(new DataSourceTransactionManager(f.dataSource));
//             tx.executeWithoutResult(status -> {
//                 assertThat(facade.send(request).orElseThrow().getStatus()).isEqualTo("PENDING");
//                 assertThat(peer.requests).hasValue(0);
//                 status.setRollbackOnly();
//             });
//             assertThat(f.count("SELECT COUNT(*) FROM sw_notify_message WHERE tenant_id = ? AND idempotency_key = ?", tenantId, request.getIdempotencyKey())).isZero();
//             assertThat(peer.requests).hasValue(0);
//             tx.executeWithoutResult(status -> assertThat(facade.send(request).orElseThrow().getStatus()).isEqualTo("PENDING"));
//             assertThat(peer.requests).hasValue(0);
//             var recovery = new NotifyDeliveryRecoveryServiceImpl(f.notificationMapper, f.notificationAttempts, facade, f.workAdmission);
//             assertThat(recovery.recoverDue()).isEqualTo(1);
//             assertThat(peer.requests).hasValue(1);
//         }
//     }

//     @ParameterizedTest
//     @EnumSource(Database.class)
//     void claimedNotificationRecordsRealResultAfterDisableDuringHttp(Database database) throws Exception {
//         try (Fixture f = new Fixture(database, false); var peer = new ControlledNotificationPeer()) {
//             rootIdentity();
//             Long tenantId = f.service.create(command());
//             Long adminId = f.service.detail(tenantId).adminUserId();
//             LoginUser actor = new LoginUser(); actor.setUserId(adminId); actor.setTenantId(tenantId); LoginUserHolder.set(actor);
//             var facade = f.notifications(peer.adapter());
//             var request = notificationRequest(tenantId, adminId, "inflight-notification");
//             facade.recordIntent(request);
//             peer.onSend = () -> {
//                 assertThat(f.jdbc.queryForObject("SELECT delivery_status FROM sw_notify_message WHERE tenant_id = ? AND idempotency_key = ?", String.class, tenantId, request.getIdempotencyKey())).isEqualTo("RESENDING");
//                 f.jdbc.update("UPDATE sys_tenant SET status = 1 WHERE id = ?", tenantId);
//             };
//             assertThat(facade.send(request).orElseThrow().getStatus()).isEqualTo("SUCCESS");
//             assertThat(peer.requests).hasValue(1);
//             assertThat(f.jdbc.queryForObject("SELECT status FROM sys_tenant WHERE id = ?", Integer.class, tenantId)).isEqualTo(1);
//             assertThat(f.jdbc.queryForObject("SELECT delivery_status FROM sw_notify_message WHERE tenant_id = ? AND idempotency_key = ?", String.class, tenantId, request.getIdempotencyKey())).isEqualTo("SUCCESS");
//             assertThat(facade.send(request).orElseThrow().getStatus()).isEqualTo("SUCCESS");
//             assertThat(peer.requests).hasValue(1);
//         }
//     }

//     private static NotifySendRequest notificationRequest(Long tenantId, Long recipient, String key) {
//         return NotifySendRequest.builder().tenantId(tenantId).recipientId(recipient)
//                 .channel(NotifyChannel.SMS).title("T020 controlled notification").content("fixture")
//                 .bizType(NotifyBizType.SYSTEM).bizId(key).eventType("T020").idempotencyKey(key).build();
//     }

//     private static final class ControlledNotificationPeer implements AutoCloseable {
//         final AtomicInteger requests = new AtomicInteger();
//         final HttpServer server;
//         final ExecutorService executor = Executors.newSingleThreadExecutor();
//         final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
//         volatile Runnable onSend = () -> {};

//         ControlledNotificationPeer() throws Exception {
//             server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
//             server.setExecutor(executor);
//             server.createContext("/delivery", exchange -> {
//                 requests.incrementAndGet();
//                 int status = 200;
//                 try { onSend.run(); } catch (Throwable failed) { status = 500; }
//                 exchange.sendResponseHeaders(status, -1); exchange.close();
//             });
//             server.start();
//         }

//         NotifyChannelAdapter adapter() {
//             return new NotifyChannelAdapter() {
//                 public Optional<NotifyChannel> channel() {
//                     return Optional.of(NotifyChannel.SMS);
//                 }
//                 public Optional<NotifySendResult> send(NotifySendRequest request) {
//                     try {
//                         var response = client.send(HttpRequest.newBuilder(
//                                 URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/delivery"))
//                                 .timeout(Duration.ofSeconds(3)).POST(BodyPublishers.noBody()).build(),
//                                 BodyHandlers.discarding());
//                         return Optional.of(NotifySendResult.builder()
//                                 .channel(NotifyChannel.SMS).status(response.statusCode() == 200 ? "SUCCESS" : "FAILED").build());
//                     } catch (Exception failed) { throw new IllegalStateException(failed); }
//                 }
//             };
//         }

//         public void close() throws Exception {
//             server.stop(0); client.close(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS);
//         }
//     }

//     @ParameterizedTest
//     @EnumSource(Database.class)
//     void claimedDispatcherFinishesAfterTenantDisableWithoutRestoringNewSession(Database database) throws Exception {
//         try (Fixture f = new Fixture(database, false)) {
//             rootIdentity();
//             Long tenantId = f.service.create(command());
//             Long adminId = f.service.detail(tenantId).adminUserId();
//             f.jdbc.update("INSERT INTO sw_bpm_command (id, tenant_id, command_key, command_type, channel, status, payload, retry_count, initiator_id, deleted, create_time) VALUES (882211, ?, 't020-claimed-dispatch', 'TASK_APPROVE', 'NORMAL', 'PENDING', '{}', 0, ?, 0, ?)",
//                     tenantId, adminId, LocalDateTime.now());
//             var queue = Mockito.spy(f.commandQueue);
//             Mockito.doAnswer(call -> {
//                 Object claimed = call.callRealMethod();
//                 f.jdbc.update("UPDATE sys_tenant SET status = 1 WHERE id = ?", tenantId);
//                 return claimed;
//             }).when(queue).claimDue(ArgumentMatchers.anyList(), ArgumentMatchers.anyInt());
//             var executions = new AtomicInteger();
//             var handler = new BpmCommandHandler() {
//                 public Set<CommandTypeEnum> types() {
//                     return Set.of(CommandTypeEnum.TASK_APPROVE);
//                 }
//                 public String handle(CommandEnvelope envelope) {
//                     assertThat(LoginUserHolder.get().getUserId()).isEqualTo(adminId);
//                     assertThat(LoginUserHolder.get().getTenantId()).isEqualTo(tenantId);
//                     assertThat(LoginUserHolder.get().isSuperAdmin()).isFalse();
//                     executions.incrementAndGet();
//                     return "{\"controlled\":true}";
//                 }
//             };
//             var dispatcher = new CommandDispatcher(queue, List.of(handler), f.identities);
//             ReflectionTestUtils.setField(dispatcher, "batchSize", 1);
//             ReflectionTestUtils.setField(dispatcher, "staleSeconds", 60L);
//             ReflectionTestUtils.invokeMethod(dispatcher, "pollNormal");
//             assertThat(executions).hasValue(1);
//             assertThat(f.jdbc.queryForObject("SELECT status FROM sw_bpm_command WHERE id = 882211", String.class)).isEqualTo("COMPLETED");
//             assertThat(LoginUserHolder.get()).isNull();
//             assertThat(f.identities.loadByUserId(adminId)).isNull();
//             assertThat(f.identities.loadClaimedWorkIdentity(adminId, tenantId + 1)).isNull();
//             f.jdbc.update("UPDATE sys_user SET status = 1 WHERE id = ?", adminId);
//             assertThat(f.identities.loadClaimedWorkIdentity(adminId, tenantId)).isNull();
//             assertThat(f.identities.isCachedIdentityValid(null)).isFalse();
//         }
//     }

//     @ParameterizedTest
//     @EnumSource(Database.class)
//     void realBpmQueuePausesPendingTenantAndResumesOriginalCommand(Database database) throws Exception {
//         try (Fixture f = new Fixture(database, false)) {
//             rootIdentity();
//             Long tenantId = f.service.create(command());
//             Long adminId = f.service.detail(tenantId).adminUserId();
//             long commandId = 882210L;
//             f.jdbc.update("INSERT INTO sw_bpm_command (id, tenant_id, command_key, command_type, channel, status, payload, retry_count, initiator_id, deleted, create_time) VALUES (?, ?, ?, 'TASK_APPROVE', 'NORMAL', 'PENDING', '{}', 0, ?, 0, ?)",
//                     commandId, tenantId, "t020-controlled-claim", adminId, LocalDateTime.now());
//             f.jdbc.update("UPDATE sys_tenant SET status = 1 WHERE id = ?", tenantId);
//             assertThat(f.commandQueue.claimDue(List.of(CommandChannelEnum.NORMAL), 10)).isEmpty();
//             assertThat(f.jdbc.queryForObject("SELECT status FROM sw_bpm_command WHERE id = ?", String.class, commandId)).isEqualTo("PENDING");
//             assertThat(f.jdbc.queryForObject("SELECT claim_token FROM sw_bpm_command WHERE id = ?", String.class, commandId)).isNull();
//             f.jdbc.update("UPDATE sys_tenant SET status = 0 WHERE id = ?", tenantId);
//             var claimed = f.commandQueue.claimDue(List.of(CommandChannelEnum.NORMAL), 10);
//             assertThat(claimed).hasSize(1);
//             assertThat(claimed.getFirst().getCommandId()).isEqualTo(commandId);
//             assertThat(claimed.getFirst().getTenantId()).isEqualTo(tenantId);
//             assertThat(claimed.getFirst().getClaimToken()).isNotBlank();
//             f.jdbc.update("UPDATE sys_tenant SET status = 1 WHERE id = ?", tenantId);
//             // 已持久认领可沿原租约记录真实结果；其他待执行工作仍不能进入新认领。
//             f.commandQueue.complete(commandId, claimed.getFirst().getClaimToken(), "{\"controlled\":true}");
//             assertThat(f.jdbc.queryForObject("SELECT status FROM sw_bpm_command WHERE id = ?", String.class, commandId)).isEqualTo("COMPLETED");
//             f.jdbc.update("UPDATE sys_tenant SET status = 0 WHERE id = ?", tenantId);
//             assertThat(f.commandQueue.claimDue(List.of(CommandChannelEnum.NORMAL), 10)).isEmpty();
//             assertThat(f.count("SELECT COUNT(*) FROM sw_bpm_command WHERE command_key = ?", "t020-controlled-claim")).isEqualTo(1);
//             assertThat(LoginUserHolder.get().getTenantId()).isEqualTo(0L);
//         }
//     }

//     @ParameterizedTest
//     @EnumSource(Database.class)
//     void tenantDisableAndPersistentClaimHaveAnOrderedBoundary(Database database) throws Exception {
//         try (Fixture f = new Fixture(database, false); var executor = Executors.newFixedThreadPool(2)) {
//             rootIdentity();
//             Long tenantId = f.service.create(command());
//             f.jdbc.execute("CREATE TABLE t020_admission_work (id INT PRIMARY KEY, state VARCHAR(32))");
//             f.jdbc.update("INSERT INTO t020_admission_work VALUES (1, 'PENDING'), (2, 'PENDING')");
//             var entered = new CountDownLatch(1);
//             var finishClaim = new CountDownLatch(1);
//             var disableStarted = new CountDownLatch(1);
//             var claimed = executor.submit(() -> f.workAdmission.claim(tenantId, () -> {
//                 entered.countDown();
//                 try {
//                     if (!finishClaim.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("claim release missing");
//                 } catch (InterruptedException interrupted) {
//                     Thread.currentThread().interrupt();
//                     throw new IllegalStateException(interrupted);
//                 }
//                 return f.jdbc.update("UPDATE t020_admission_work SET state = 'PROCESSING' WHERE id = 1 AND state = 'PENDING'");
//             }));
//             assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
//             var disabled = executor.submit(() -> {
//                 disableStarted.countDown();
//                 return f.jdbc.update("UPDATE sys_tenant SET status = 1 WHERE id = ?", tenantId);
//             });
//             try {
//                 assertThat(disableStarted.await(5, TimeUnit.SECONDS)).isTrue();
//                 assertThatThrownBy(() -> disabled.get(200, TimeUnit.MILLISECONDS))
//                         .isInstanceOf(TimeoutException.class);
//             } finally { finishClaim.countDown(); }
//             assertThat(claimed.get(10, TimeUnit.SECONDS)).isEqualTo(1);
//             assertThat(disabled.get(10, TimeUnit.SECONDS)).isEqualTo(1);
//             var attempts = new AtomicInteger();
//             assertThat(f.workAdmission.claim(tenantId, () -> {
//                 attempts.incrementAndGet();
//                 return f.jdbc.update("UPDATE t020_admission_work SET state = 'PROCESSING' WHERE id = 2");
//             })).isZero();
//             assertThat(attempts).hasValue(0);
//             assertThat(f.jdbc.queryForObject("SELECT state FROM t020_admission_work WHERE id = 2", String.class)).isEqualTo("PENDING");
//             // 已认领动作可真实结束；停用不伪造其他对象状态。
//             assertThat(f.jdbc.update("UPDATE t020_admission_work SET state = 'DONE' WHERE id = 1 AND state = 'PROCESSING'")).isEqualTo(1);
//             f.jdbc.update("UPDATE sys_tenant SET status = 0, expire_time = ? WHERE id = ?",
//                     LocalDateTime.now().minusMinutes(1), tenantId);
//             assertThat(f.workAdmission.claim(tenantId, () -> { attempts.incrementAndGet(); return 1; })).isZero();
//             f.jdbc.update("UPDATE sys_tenant SET expire_time = NULL WHERE id = ?", tenantId);
//             assertThatThrownBy(() -> f.workAdmission.claim(tenantId, () -> {
//                 f.jdbc.update("UPDATE t020_admission_work SET state = 'PROCESSING' WHERE id = 2");
//                 throw new IllegalStateException("controlled failure after write");
//             })).isInstanceOf(IllegalStateException.class);
//             assertThat(f.jdbc.queryForObject("SELECT state FROM t020_admission_work WHERE id = 2", String.class)).isEqualTo("PENDING");
//             assertThat(f.workAdmission.claim(tenantId, () -> f.jdbc.update(
//                     "UPDATE t020_admission_work SET state = 'PROCESSING' WHERE id = 2 AND state = 'PENDING'"))).isEqualTo(1);
//             assertThat(f.workAdmission.claim(null, () -> { attempts.incrementAndGet(); return 1; })).isZero();
//             assertThat(attempts).hasValue(0);
//             assertThat(LoginUserHolder.get().getTenantId()).isEqualTo(0L);
//         }
//     }

//     @ParameterizedTest
//     @EnumSource(Database.class)
//     void cachedSessionAdmissionReadsCurrentTenantStatusAndExpiry(Database database) throws Exception {
//         try (Fixture f = new Fixture(database, false)) {
//             rootIdentity();
//             Long tenantId = f.service.create(command());
//             Long adminId = f.service.detail(tenantId).adminUserId();
//             LoginUser cached = new LoginUser();
//             cached.setUserId(adminId);
//             cached.setTenantId(tenantId);
//             var provider = new UserDetailsProviderImpl(
//                     f.users, null, null, null, null, null, f.validity);
//             // 身份缓存未变；每次准入读取真实租户行，不依赖清理 Redis 的时机。
//             assertThat(provider.isCachedIdentityValid(cached)).isTrue();
//             f.jdbc.update("UPDATE sys_tenant SET status = 1 WHERE id = ?", tenantId);
//             assertThat(provider.isCachedIdentityValid(cached)).isFalse();
//             f.jdbc.update("UPDATE sys_tenant SET status = 0, expire_time = ? WHERE id = ?",
//                     LocalDateTime.now().minusMinutes(1), tenantId);
//             assertThat(provider.isCachedIdentityValid(cached)).isFalse();
//             f.jdbc.update("UPDATE sys_tenant SET expire_time = ? WHERE id = ?",
//                     LocalDateTime.now().plusDays(1), tenantId);
//             assertThat(provider.isCachedIdentityValid(cached)).isTrue();
//             f.jdbc.update("UPDATE sys_tenant SET deleted = 1 WHERE id = ?", tenantId);
//             assertThat(provider.isCachedIdentityValid(cached)).isFalse();
//             cached.setTenantId(null);
//             assertThat(provider.isCachedIdentityValid(cached)).isFalse();
//             assertThat(LoginUserHolder.get().getTenantId()).isEqualTo(0L);
//             assertThat(f.count("SELECT COUNT(*) FROM sys_user WHERE tenant_id = ?", tenantId)).isEqualTo(1);
//         }
//     }

//     @ParameterizedTest
//     @EnumSource(Database.class)
//     void creationIsStableAcrossRetryRenameAndAnotherAdministrator(Database database) throws Exception {
//         try (Fixture f = new Fixture(database, false)) {
//             rootIdentity();
//             var command = command();
//             Long tenantId = f.service.create(command);
//             Long adminId = f.service.detail(tenantId).adminUserId();
//             assertThat(adminId).isNotNull();
//             assertThat(f.encoder.matches("test-only-password", f.jdbc.queryForObject(
//                     "SELECT password FROM sys_user WHERE id = ?", String.class, adminId))).isTrue();
//             f.jdbc.update("UPDATE sys_user SET username = 'renamed_admin' WHERE id = ?", adminId);
//             addAdmin(f, tenantId, 88101L, 88102L, "second_admin");
//             assertThat(f.service.detail(tenantId).adminUsername()).isEqualTo("renamed_admin");
//             assertThat(f.service.create(command)).isEqualTo(tenantId);
//             assertThat(f.count("SELECT COUNT(*) FROM sys_tenant WHERE creation_request_id = ?", command.requestId())).isEqualTo(1);
//             assertThat(f.count("SELECT COUNT(*) FROM sys_user WHERE tenant_id = ?", tenantId)).isEqualTo(2);
//             assertThat(f.jdbc.queryForObject("SELECT leader_id FROM sys_dept WHERE tenant_id = ?", Long.class, tenantId)).isEqualTo(adminId);
//             assertThat(f.service.adminCandidates(tenantId)).extracting(TenantAdminService.AdminCandidate::id)
//                     .containsExactlyInAnyOrder(adminId, 88101L);
//             assertThat(f.jdbc.queryForObject("SELECT component FROM sys_menu WHERE id = 9600", String.class))
//                     .isEqualTo("system/views/TenantList");
//             assertThat(f.jdbc.queryForObject("SELECT path FROM sys_menu WHERE id = 9600", String.class))
//                     .isEqualTo("system/tenant");
//             assertThat(f.count("SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id WHERE rm.menu_id = 9600 AND r.code = 'superadmin' AND r.tenant_id = 0"))
//                     .isEqualTo(1);
//             assertThat(f.count("SELECT COUNT(*) FROM sys_role_menu WHERE menu_id = 9600 AND tenant_id = ?", tenantId)).isZero();
//             System.out.println("[T020-" + database + "] stable tenant=" + tenantId + ", admin=" + adminId + ", retry returned original");
//         }
//     }

//     @ParameterizedTest
//     @EnumSource(Database.class)
//     void concurrentRetryCreatesOneCompleteTenant(Database database) throws Exception {
//         try (Fixture f = new Fixture(database, false); var executor = Executors.newFixedThreadPool(2)) {
//             var command = command();
//             var barrier = new CyclicBarrier(2);
//             Callable<Long> create = () -> {
//                 rootIdentity();
//                 try {
//                     barrier.await(5, TimeUnit.SECONDS);
//                     return f.service.create(command);
//                 } finally { LoginUserHolder.clear(); }
//             };
//             var first = executor.submit(create);
//             var second = executor.submit(create);
//             Long tenantId = first.get(20, TimeUnit.SECONDS);
//             assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(tenantId);
//             assertThat(f.count("SELECT COUNT(*) FROM sys_tenant WHERE creation_request_id = ?", command.requestId())).isEqualTo(1);
//             assertThat(f.count("SELECT COUNT(*) FROM sys_user WHERE tenant_id = ?", tenantId)).isEqualTo(1);
//             assertThat(f.count("SELECT COUNT(*) FROM sys_dept WHERE tenant_id = ?", tenantId)).isEqualTo(1);
//             assertThat(f.count("SELECT COUNT(*) FROM sys_role WHERE tenant_id = ?", tenantId)).isEqualTo(1);
//             assertThat(f.count("SELECT COUNT(*) FROM sys_user_role WHERE tenant_id = ?", tenantId)).isEqualTo(1);
//             System.out.println("[T020-" + database + "] concurrent retry: one tenant, user, department, role and binding");
//         }
//     }

//     @ParameterizedTest
//     @EnumSource(Database.class)
//     void initializationFailureRollsBackAndSameRequestCanRetry(Database database) throws Exception {
//         try (Fixture f = new Fixture(database, false)) {
//             rootIdentity();
//             var command = command();
//             long tenants = f.count("SELECT COUNT(*) FROM sys_tenant");
//             long users = f.count("SELECT COUNT(*) FROM sys_user");
//             long departments = f.count("SELECT COUNT(*) FROM sys_dept");
//             f.jdbc.update("UPDATE sys_role SET status = 0 WHERE tenant_id = 0 AND code = 'admin'");
//             assertThatThrownBy(() -> f.service.create(command)).isInstanceOf(BaseException.class).hasMessageContaining("模板已停用");
//             assertThat(f.count("SELECT COUNT(*) FROM sys_tenant")).isEqualTo(tenants);
//             assertThat(f.count("SELECT COUNT(*) FROM sys_user")).isEqualTo(users);
//             assertThat(f.count("SELECT COUNT(*) FROM sys_dept")).isEqualTo(departments);
//             f.jdbc.update("UPDATE sys_role SET status = 1 WHERE tenant_id = 0 AND code = 'admin'");
//             Long id = f.service.create(command);
//             assertThat(f.service.detail(id).adminUserId()).isNotNull();
//             System.out.println("[T020-" + database + "] late initialization failure: zero partial rows, same key retry succeeded");
//         }
//     }

//     @ParameterizedTest
//     @EnumSource(Database.class)
//     void legacyMaintenanceValidatesTargetAndDoesNotCreateAnotherFirstAdmin(Database database) throws Exception {
//         try (Fixture f = new Fixture(database, false)) {
//             rootIdentity();
//             addTenant(f, 88L);
//             Long adminId = f.service.createAdministrator(88L, "legacy_admin", "test-only-password");
//             assertThat(f.service.detail(88L).adminUserId()).isEqualTo(adminId);
//             assertThatThrownBy(() -> f.service.createAdministrator(88L, "another_admin", "test-only-password"))
//                     .isInstanceOf(BaseException.class).hasMessageContaining("已配置管理员");
//             Long otherTenant = f.service.create(command());
//             Long foreignAdmin = f.service.detail(otherTenant).adminUserId();
//             assertThatThrownBy(() -> f.service.assignAdministrator(88L, foreignAdmin))
//                     .isInstanceOf(BaseException.class).hasMessageContaining("该租户内");
//             assertThat(f.service.detail(88L).adminUserId()).isEqualTo(adminId);
//             addAdmin(f, 88L, 88101L, 88102L, "replacement_admin");
//             f.service.assignAdministrator(88L, 88101L);
//             assertThat(f.service.detail(88L).adminUserId()).isEqualTo(88101L);
//             assertThatThrownBy(() -> f.service.assignAdministrator(0L, adminId))
//                     .isInstanceOf(BaseException.class).hasMessageContaining("保留对象");
//             LoginUserHolder.get().setSuperAdmin(false);
//             LoginUserHolder.get().setTenantId(88L);
//             assertThatThrownBy(() -> f.service.adminCandidates(88L)).isInstanceOf(BaseException.class).hasMessageContaining("超级管理员");
//             assertThat(f.count("SELECT COUNT(*) FROM sys_user WHERE tenant_id = 88")).isEqualTo(2);
//             System.out.println("[T020-" + database + "] legacy maintenance: stable assignment, cross-tenant/root/ordinary denial, no duplicate first admin");
//         }
//     }

//     @ParameterizedTest
//     @EnumSource(Database.class)
//     void migrationBackfillsOnlyAnUnambiguousAuthorizedAdministrator(Database database) throws Exception {
//         try (Fixture f = new Fixture(database, true)) {
//             addTenant(f, 88L);
//             addTenant(f, 89L);
//             addAdmin(f, 88L, 88101L, 88102L, "unique_admin");
//             addAdmin(f, 89L, 89101L, 89102L, "ambiguous_admin_a");
//             addAdmin(f, 89L, 89103L, 89104L, "ambiguous_admin_b");
//             // A corrupt cross-tenant role binding must not count as an authorized same-tenant administrator.
//             f.jdbc.update("INSERT INTO sys_user_role (id, user_id, role_id, tenant_id) VALUES (88999, 89101, 88102, 88)");
//             f.flyway(null).migrate();
//             assertThat(f.jdbc.queryForObject("SELECT admin_user_id FROM sys_tenant WHERE id = 88", Long.class)).isEqualTo(88101L);
//             assertThat(f.jdbc.queryForObject("SELECT admin_user_id FROM sys_tenant WHERE id = 89", Long.class)).isNull();
//             assertThat(f.flyway(null).migrate().migrationsExecuted).isZero();
//             System.out.println("[T020-" + database + "] migration: unique association backfilled; ambiguous and foreign bindings not promoted");
//         }
//     }

//     private static TenantAdminService.CreateTenantCommand command() {
//         return new TenantAdminService.CreateTenantCommand("数据库验证租户", "initial_admin", "test-only-password", UUID.randomUUID().toString());
//     }

//     private static void rootIdentity() {
//         LoginUser login = new LoginUser();
//         login.setUserId(1L); login.setUsername("admin"); login.setTenantId(0L); login.setSuperAdmin(true);
//         LoginUserHolder.set(login);
//     }

//     @ParameterizedTest
//     @EnumSource(Database.class)
//     void managementSeparatesActorAndTargetAndRejectsForeignAssociations(Database database) throws Exception {
//         try (Fixture f = new Fixture(database, false)) {
//             rootIdentity();
//             assertThat(f.users.getById(1L).isProtectedAccount()).isTrue();
//             var reserved = managementUser(0L, null, "reserved_edit"); reserved.setId(1L);
//             assertThatThrownBy(() -> f.userController.update(reserved)).isInstanceOf(BaseException.class);
//             assertThatThrownBy(() -> f.userController.delete(1L)).isInstanceOf(BaseException.class);
//             assertThatThrownBy(() -> f.users.updateRoleIds(1L, List.of())).isInstanceOf(BaseException.class);
//             assertThat(f.jdbc.queryForObject("SELECT username FROM sys_user WHERE id = 1", String.class)).isEqualTo("admin");
//             Long first = f.service.create(command());
//             Long second = f.service.create(command());
//             Long firstRoot = f.jdbc.queryForObject("SELECT id FROM sys_dept WHERE tenant_id = ?", Long.class, first);
//             Long secondRoot = f.jdbc.queryForObject("SELECT id FROM sys_dept WHERE tenant_id = ?", Long.class, second);
//             Long firstRole = f.jdbc.queryForObject("SELECT id FROM sys_role WHERE tenant_id = ?", Long.class, first);
//             Long secondRole = f.jdbc.queryForObject("SELECT id FROM sys_role WHERE tenant_id = ?", Long.class, second);
//             f.jdbc.update("INSERT INTO sys_post (id, tenant_id, code, name, status) VALUES (99101, ?, 'target_post', '本租户岗位', 1)", first);
//             f.jdbc.update("INSERT INTO sys_post (id, tenant_id, code, name, status) VALUES (99102, ?, 'target_post', '另一租户岗位', 1)", second);

//             var department = new SysDept();
//             department.setTenantId(first); department.setParentId(firstRoot);
//             department.setName("目标部门"); department.setCode("target_dept");
//             Long departmentId = f.deptController.create(department).getData();
//             assertThat(f.jdbc.queryForObject("SELECT tenant_id FROM sys_dept WHERE id = ?", Long.class, departmentId)).isEqualTo(first);
//             assertThat(f.jdbc.queryForObject("SELECT create_by FROM sys_dept WHERE id = ?", Long.class, departmentId)).isEqualTo(1L);
//             assertThat(LoginUserHolder.get().getTenantId()).isZero();

//             var request = managementUser(first, departmentId, "shared_username");
//             request.setRoleIds(List.of(firstRole));
//             request.setPosts(List.of(new PostAssignment(99101L, departmentId)));
//             Long userId = f.userController.create(request).getData();
//             assertThat(f.jdbc.queryForObject("SELECT tenant_id FROM sys_user WHERE id = ?", Long.class, userId)).isEqualTo(first);
//             assertThat(f.jdbc.queryForObject("SELECT tenant_id FROM sys_user_role WHERE user_id = ? AND deleted = 0", Long.class, userId)).isEqualTo(first);
//             assertThat(f.jdbc.queryForObject("SELECT tenant_id FROM sys_user_post WHERE user_id = ? AND deleted = 0", Long.class, userId)).isEqualTo(first);
//             f.users.updateRoleIds(userId, List.of());
//             f.users.updateRoleIds(userId, List.of(firstRole));
//             f.users.updateRoleIds(userId, List.of(firstRole));
//             assertThat(f.count("SELECT COUNT(*) FROM sys_user_role WHERE user_id = ? AND tenant_id = ? AND deleted = 0", userId, first)).isEqualTo(1);
//             f.userController.create(managementUser(second, secondRoot, "shared_username"));
//             assertThat(f.count("SELECT COUNT(*) FROM sys_user WHERE username = 'shared_username' AND deleted = 0")).isEqualTo(2);

//             var missing = managementUser(null, null, "missing_target");
//             assertThatThrownBy(() -> f.userController.create(missing)).isInstanceOf(BaseException.class);
//             assertThat(f.count("SELECT COUNT(*) FROM sys_user WHERE username = 'missing_target'")).isZero();
//             var foreignDept = managementUser(first, secondRoot, "foreign_department");
//             assertThatThrownBy(() -> f.userController.create(foreignDept)).isInstanceOf(BaseException.class);
//             var foreignRole = managementUser(first, departmentId, "foreign_role");
//             foreignRole.setRoleIds(List.of(secondRole));
//             assertThatThrownBy(() -> f.userController.create(foreignRole)).isInstanceOf(IllegalArgumentException.class);
//             var foreignPost = managementUser(first, departmentId, "foreign_post");
//             foreignPost.setPosts(List.of(new PostAssignment(99102L, departmentId)));
//             assertThatThrownBy(() -> f.userController.create(foreignPost)).isInstanceOf(IllegalArgumentException.class);
//             assertThat(f.count("SELECT COUNT(*) FROM sys_user WHERE username IN ('foreign_department','foreign_role','foreign_post')")).isZero();
//             request.setId(userId); request.setTenantId(second);
//             assertThatThrownBy(() -> f.userController.update(request)).isInstanceOf(BaseException.class);
//             assertThat(f.jdbc.queryForObject("SELECT tenant_id FROM sys_user WHERE id = ?", Long.class, userId)).isEqualTo(first);
//             var badParent = new SysDept();
//             badParent.setTenantId(first); badParent.setParentId(secondRoot); badParent.setName("跨租户部门"); badParent.setCode("foreign_parent");
//             assertThatThrownBy(() -> f.deptController.create(badParent)).isInstanceOf(BaseException.class);
//             var badLeader = new SysDept();
//             badLeader.setTenantId(first); badLeader.setParentId(firstRoot); badLeader.setName("错误负责人"); badLeader.setCode("foreign_leader");
//             badLeader.setLeaderId(f.service.detail(second).adminUserId());
//             assertThatThrownBy(() -> f.deptController.create(badLeader)).isInstanceOf(BaseException.class);
//             department.setTenantId(second);
//             assertThatThrownBy(() -> f.deptController.update(department)).isInstanceOf(BaseException.class);
//             assertThat(f.jdbc.queryForObject("SELECT tenant_id FROM sys_dept WHERE id = ?", Long.class, departmentId)).isEqualTo(first);

//             var query = new UserPageQuery(); query.setTenantId(first);
//             var page = f.userController.page(1, 200, query).getData();
//             assertThat(page.getRecords()).allMatch(row -> first.equals(row.getTenantId()));
//             assertThat(page.getRecords()).allMatch(row -> row.getTenantName() != null);
//             assertThat(f.deptController.tree(null, null, first).getData()).allMatch(row -> first.equals(row.getTenantId()));
//             assertThat(LoginUserHolder.get().getTenantId()).isZero();

//             LoginUser actor = new LoginUser(); actor.setTenantId(first); actor.setUserId(userId); LoginUserHolder.set(actor);
//             Long ordinaryId = f.userController.create(managementUser(null, departmentId, "ordinary_own")).getData();
//             assertThat(f.jdbc.queryForObject("SELECT tenant_id FROM sys_user WHERE id = ?", Long.class, ordinaryId)).isEqualTo(first);
//             assertThatThrownBy(() -> f.userController.create(managementUser(second, secondRoot, "ordinary_foreign"))).isInstanceOf(BaseException.class);
//             assertThatThrownBy(() -> f.deptController.tree(null, null, second)).isInstanceOf(BaseException.class);
//             query.setTenantId(second);
//             assertThatThrownBy(() -> f.userController.page(1, 200, query)).isInstanceOf(BaseException.class);
//             assertThatThrownBy(() -> f.management.options(null, false)).isInstanceOf(BaseException.class);
//             assertThat(LoginUserHolder.get().getTenantId()).isEqualTo(first);
//             System.out.println("[T020-" + database + "] management actor unchanged, explicit target, same-tenant associations, rollback and ordinary isolation");
//         }
//     }

//     @ParameterizedTest
//     @EnumSource(Database.class)
//     void managementCreationOptionsExcludeDisabledExpiredAndMissingTargets(Database database) throws Exception {
//         try (Fixture f = new Fixture(database, false)) {
//             rootIdentity();
//             Long tenantId = f.service.create(command());
//             f.jdbc.update("UPDATE sys_tenant SET status = 1 WHERE id = ?", tenantId);
//             assertThat(f.management.options(null, true)).noneMatch(row -> row.id().equals(tenantId));
//             assertThat(f.management.options(null, false)).anyMatch(row -> row.id().equals(tenantId) && !row.writable());
//             assertThatThrownBy(() -> f.management.createTarget(tenantId)).isInstanceOf(BaseException.class);
//             f.jdbc.update("UPDATE sys_tenant SET status = 0, expire_time = ? WHERE id = ?", LocalDateTime.now().minusSeconds(5), tenantId);
//             assertThat(f.management.options(null, true)).noneMatch(row -> row.id().equals(tenantId));
//             assertThatThrownBy(() -> f.management.createTarget(tenantId)).isInstanceOf(BaseException.class);
//             assertThatThrownBy(() -> f.management.createTarget(999999L)).isInstanceOf(BaseException.class);
//             assertThatThrownBy(() -> f.management.createTarget(null)).isInstanceOf(BaseException.class);
//             f.jdbc.update("UPDATE sys_tenant SET expire_time = null WHERE id = ?", tenantId);
//             assertThat(f.management.createTarget(tenantId)).isEqualTo(tenantId);
//             assertThat(f.management.options(null, true)).anyMatch(row -> row.id().equals(tenantId) && row.writable());
//             System.out.println("[T020-" + database + "] management target validity: disabled, expired, missing rejected; restored tenant selectable");
//         }
//     }

//     private static UserFormRequest managementUser(Long tenantId, Long deptId, String username) {
//         var request = new UserFormRequest();
//         request.setTenantId(tenantId); request.setDeptId(deptId); request.setUsername(username);
//         request.setPlainPassword("test-only-password"); request.setStatus(0);
//         return request;
//     }

//     private static void addTenant(Fixture f, long id) {
//         f.jdbc.update("INSERT INTO sys_tenant (id, name, code, status) VALUES (?, ?, ?, 0)", id, "Legacy " + id, "legacy-" + id);
//     }

//     private static void addAdmin(Fixture f, long tenantId, long userId, long roleId, String username) {
//         f.jdbc.update("INSERT INTO sys_user (id, username, password, status, tenant_id) VALUES (?, ?, ?, 0, ?)", userId, username, f.encoder.encode("test-only-password"), tenantId);
//         var existingRoles = f.jdbc.queryForList("SELECT id FROM sys_role WHERE tenant_id = ? AND code = 'admin' AND deleted = 0", Long.class, tenantId);
//         long targetRoleId = existingRoles.isEmpty() ? roleId : existingRoles.get(0);
//         if (existingRoles.isEmpty()) {
//             f.jdbc.update("INSERT INTO sys_role (id, name, code, status, tenant_id) VALUES (?, '管理员', 'admin', 1, ?)", roleId, tenantId);
//         }
//         f.jdbc.update("INSERT INTO sys_user_role (id, user_id, role_id, tenant_id) VALUES (?, ?, ?, ?)", userId, userId, targetRoleId, tenantId);
//     }

//     private static final class Fixture implements AutoCloseable {
//         final EmbeddedPostgres postgres;
//         final DriverManagerDataSource dataSource;
//         final JdbcTemplate jdbc;
//         final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
//         final TenantAdminService service;
//         final String vendor;
//         final boolean oldVersion;
//         final SysUserService users;
//         final SysDeptService departments;
//         final ManagementTenantContext management;
//         final TenantValidityService validity;
//         final TenantWorkAdmissionService workAdmission;
//         final PersistentBpmCommandQueue commandQueue;
//         final UserDetailsProviderImpl identities;
//         final NotifyMessageMapper notificationMapper;
//         final NotifySendAttemptMapper notificationAttempts;
//         final NotifyMessageServiceImpl notificationMessages;
//         final UserController userController;
//         final DeptController deptController;

//         Fixture(Database database, boolean oldVersion) throws Exception {
//             this.oldVersion = oldVersion;
//             vendor = database == Database.H2 ? "h2" : "postgresql";
//             if (database == Database.POSTGRES) {
//                 postgres = EmbeddedPostgres.builder().start();
//                 dataSource = new DriverManagerDataSource(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "postgres");
//                 var connectionProperties = new Properties();
//                 connectionProperties.setProperty("socketTimeout", "15");
//                 connectionProperties.setProperty("options", "-c lock_timeout=5000 -c statement_timeout=10000");
//                 dataSource.setConnectionProperties(connectionProperties);
//             } else {
//                 postgres = null;
//                 dataSource = new DriverManagerDataSource("jdbc:h2:mem:tenant_identity_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
//             }
//             jdbc = new JdbcTemplate(dataSource);
//             try {
//                 flyway(oldVersion ? "0.1.8" : null).migrate();
//                 var context = new SecurityLoginContextProvider();
//                 var global = new GlobalConfig();
//                 global.setDbConfig(new GlobalConfig.DbConfig().setLogicDeleteField("deleted").setLogicDeleteValue("1").setLogicNotDeleteValue("0"));
//                 global.setMetaObjectHandler(new CommonMetaObjectHandler(context));
//                 var config = new MybatisConfiguration();
//                 config.setMapUnderscoreToCamelCase(true);
//                 var interceptor = new MybatisPlusInterceptor();
//                 interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new CommonTenantLineHandler(new TenantProperties(), context)));
//                 interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
//                 interceptor.addInnerInterceptor(new PaginationInnerInterceptor(
//                         database == Database.H2 ? DbType.H2 : DbType.POSTGRE_SQL));
//                 var factory = new MybatisSqlSessionFactoryBean();
//                 factory.setDataSource(dataSource); factory.setConfiguration(config); factory.setGlobalConfig(global); factory.setPlugins(interceptor);
//                 var sessions = factory.getObject();
//                 for (Class<?> mapper : new Class<?>[]{SysTenantMapper.class, SysDeptMapper.class, SysUserMapper.class, SysRoleMapper.class, SysRoleMenuMapper.class, SysUserRoleMapper.class,
//                         SysPostMapper.class, SysUserPostMapper.class,
//                         SysMenuMapper.class, SysRoleDeptMapper.class,
//                         SysTenantStatusAuditMapper.class,
//                         NotifyMessageMapper.class, NotifySendAttemptMapper.class,
//                         BpmCommandMapper.class}) {
//                     sessions.getConfiguration().addMapper(mapper);
//                 }
//                 var template = new SqlSessionTemplate(sessions);
//                 var transactions = new DataSourceTransactionManager(dataSource);
//                 workAdmission = new TenantWorkAdmissionService(template.getMapper(SysTenantMapper.class), transactions);
//                 notificationMapper = template.getMapper(NotifyMessageMapper.class);
//                 notificationAttempts = template.getMapper(NotifySendAttemptMapper.class);
//                 notificationMessages = new NotifyMessageServiceImpl(null, null, context, null);
//                 ReflectionTestUtils.setField(notificationMessages, "baseMapper", notificationMapper);
//                 var commandService = new BpmCommandServiceImpl();
//                 ReflectionTestUtils.setField(commandService, "baseMapper",
//                         template.getMapper(BpmCommandMapper.class));
//                 var resourceAdmission = Mockito.mock(ResourceAdmissionService.class);
//                 commandQueue = new PersistentBpmCommandQueue(commandService, null,
//                         Mockito.mock(ResourceReleaseService.class), resourceAdmission, workAdmission);
//                 var target = new TenantAdminService(template.getMapper(SysTenantMapper.class), template.getMapper(SysDeptMapper.class),
//                         template.getMapper(SysUserMapper.class), template.getMapper(SysRoleMapper.class), template.getMapper(SysRoleMenuMapper.class),
//                         template.getMapper(SysUserRoleMapper.class),
//                         template.getMapper(SysTenantStatusAuditMapper.class), encoder, transactions);
//                 var proxy = new ProxyFactory(target);
//                 proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
//                 service = (TenantAdminService) proxy.getProxy();
//                 var userTarget = new SysUserServiceImpl(encoder,
//                         template.getMapper(SysUserRoleMapper.class), template.getMapper(SysUserPostMapper.class),
//                         template.getMapper(SysRoleMapper.class), template.getMapper(SysPostMapper.class),
//                         template.getMapper(SysDeptMapper.class), null);
//                 ReflectionTestUtils.setField(userTarget, "baseMapper", template.getMapper(SysUserMapper.class));
//                 var userProxy = new ProxyFactory(userTarget);
//                 userProxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
//                 users = (SysUserService) userProxy.getProxy();
//                 identities = new UserDetailsProviderImpl(users,
//                         template.getMapper(SysUserRoleMapper.class), template.getMapper(SysRoleMapper.class),
//                         template.getMapper(SysRoleMenuMapper.class), template.getMapper(SysMenuMapper.class),
//                         template.getMapper(SysRoleDeptMapper.class),
//                         new TenantValidityService(template.getMapper(SysTenantMapper.class)));
//                 var deptTarget = new SysDeptServiceImpl(users);
//                 ReflectionTestUtils.setField(deptTarget, "baseMapper", template.getMapper(SysDeptMapper.class));
//                 var deptProxy = new ProxyFactory(deptTarget);
//                 deptProxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
//                 departments = (SysDeptService) deptProxy.getProxy();
//                 validity = new TenantValidityService(template.getMapper(SysTenantMapper.class));
//                 management = new ManagementTenantContext(template.getMapper(SysTenantMapper.class), validity);
//                 userController = new UserController(users, null, management,
//                         new UserImportExportService(null, null, null, null, users, null));
//                 deptController = new DeptController(departments, management);
//             } catch (Exception failure) {
//                 if (postgres != null) postgres.close();
//                 throw failure;
//             }
//         }

//         Flyway flyway(String target) {
//             // 旧链形态夹具（0.1.8 终点）使用 0.2.0 升级通道归档的逐字节原件构造 versioned 链，
//             // 避免把收敛后的 V0.2.0 基线重放到旧结构库（0.1.9 增量 DDL 无守卫会重复执行）。
//             String[] locations = oldVersion
//                     ? new String[]{"db/upgrade/0.2.0/legacy-0.1.10/", "db/migration/bpm/", "db/migration/notify/", "db/migration/form/", "db/migration/storage/", "db/migration/job/", "db/migration/agent/", "db/migration/iot/", "db/migration/openapi/", "db/migration/system/"}
//                     : new String[]{"db/migration/", "db/migration/bpm/", "db/migration/notify/", "db/migration/form/", "db/migration/storage/", "db/migration/job/", "db/migration/agent/", "db/migration/iot/", "db/migration/openapi/", "db/migration/system/"};
//             var configuration = Flyway.configure().dataSource(dataSource).locations(Arrays.stream(locations).map(path -> "classpath:" + path + vendor).toArray(String[]::new));
//             if (target != null) configuration.target(target);
//             return configuration.load();
//         }

//         long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
//         NotifyFacadeImpl notifications(NotifyChannelAdapter adapter) {
//             return new NotifyFacadeImpl(notificationMessages, List.of(adapter), notificationAttempts, workAdmission);
//         }
//         public void close() throws Exception { if (postgres != null) postgres.close(); }
//     }
// }
