package com.sw.ck.form.txn;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.entity.FormIdGenerator;
import com.sw.ck.form.mapper.FormConfigMapper;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.form.service.FormFieldValidator;
import com.sw.ck.form.txn.mapper.C1PolicyMapper;
import com.sw.ck.form.txn.mapper.TxnActionMapper;
import com.sw.ck.form.txn.mapper.TxnActionVersionMapper;
import com.sw.ck.form.txn.mapper.TxnInvocationMapper;
import com.sw.ck.form.txn.mapper.TxnLedgerMapper;
import com.sw.ck.form.txn.mapper.TxnReservationMapper;
import com.sw.ck.form.txn.model.C1PolicyModel;
import com.sw.ck.form.txn.model.TxnActionConfig;
import com.sw.ck.form.txn.model.TxnActionSaveRequest;
import com.sw.ck.form.txn.model.TxnInvokeRequest;
import com.sw.ck.form.txn.model.TxnInvokeResult;
import com.sw.ck.form.txn.model.TxnPublishError;
import com.sw.ck.form.txn.service.C1PolicyService;
import com.sw.ck.form.txn.service.TxnActionExecutor;
import com.sw.ck.form.txn.service.TxnActionService;
import com.sw.ck.form.txn.service.TxnActionTxOperations;
import com.sw.ck.form.txn.service.TxnFormBinding;
import com.sw.ck.form.txn.service.TxnReservationExpiryJob;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P62 首事务阶段 H2 行为测试：
 * 发布校验 / C1 既有数据闸门与写路径保护 / 预占-确认-释放生命周期 /
 * 幂等重放与同键冲突 / 版本竞争 / 过期释放与确认竞争 / 台账与调用记录查询。
 * <p>真实 H2 + 真实租户拦截器（与 MybatisPlusConfig 同构装配）；动态宽表为真实物理表。</p>
 */
@SpringBootTest(classes = TxnActionFlowH2Test.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("P62 本地事务动作 H2 行为证据")
class TxnActionFlowH2Test {

    private static final String STOCK_TABLE = "sw_form_txnstock01";
    private static final String QSTOCK = "\"" + STOCK_TABLE + "\"";
    private static final long TENANT = 0L;

    @Autowired
    private TxnActionService actionService;
    @Autowired
    private TxnActionExecutor executor;
    @Autowired
    private C1PolicyService c1PolicyService;
    @Autowired
    private TxnActionTxOperations txOps;

    @Autowired
    private com.sw.ck.form.api.port.FormTxnActionPort port;
    @Autowired
    private com.sw.ck.form.txn.mapper.TxnReservationMapper reservationMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String formId;

    @BeforeEach
    void setUp() {
        createTables();
        LoginUser user = new LoginUser();
        user.setTenantId(TENANT);
        user.setUserId(1L);
        user.setUsername("txn-tester");
        LoginUserHolder.set(user);
        formId = seedForm();
        seedStock("stock-1", "10", "0");
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    // ==================== 发布校验 ====================

    @Test
    @DisplayName("发布校验：非法配置（缺绑定/非数字字段/TTL 非法）被拒绝，合法配置可发布且版本冻结")
    void publishValidation() {
        // 缺余额字段
        String a1 = create("stock_reserve", "库存预占", "RESERVE", cfg(null, "qty_reserved", 600L)).id();
        List<TxnPublishError> errors = actionService.validate(a1);
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(e -> e.field().contains("balanceField"));
        assertThatThrownBy(() -> actionService.publish(a1))
                .isInstanceOf(BaseException.class);

        // 余额绑定到 TEXT 字段
        String a2 = create("stock_reserve2", "库存预占", "RESERVE", cfg("material", "qty_reserved", 600L)).id();
        assertThat(actionService.validate(a2)).anyMatch(e -> "config.balanceField".equals(e.field()));

        // TTL 非法
        String a3 = create("stock_reserve3", "库存预占", "RESERVE", cfg("qty_available", "qty_reserved", 0L)).id();
        assertThat(actionService.validate(a3)).anyMatch(e -> e.field().contains("expiresInSeconds"));

        // 业务键不可与余额/预占相同
        TxnActionConfig badKeys = cfg("qty_available", "qty_reserved", 600L);
        badKeys.setKeyFields(List.of("qty_available"));
        String a4 = create("stock_reserve4", "库存预占", "RESERVE", badKeys).id();
        assertThat(actionService.validate(a4)).anyMatch(e -> e.field().contains("keyFields"));

        // 合法配置发布成功并冻结版本
        String ok = create("stock_reserve_ok", "库存预占", "RESERVE", cfg("qty_available", "qty_reserved", 600L)).id();
        assertThat(actionService.validate(ok)).isEmpty();
        var published = actionService.publish(ok);
        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(published.currentVersion()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_form_txn_action_version WHERE action_id = ?", Long.class, ok))
                .isEqualTo(1L);

        // 停用后不可调用；启用恢复
        actionService.disable(ok);
        assertThatThrownBy(() -> executor.invoke(ok, reserveReq("k1", "stock-1", "1")))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("停用");
        actionService.enable(ok);
        assertThat(actionService.get(ok).status()).isEqualTo("PUBLISHED");
    }

    // ==================== 预占 + 幂等 + 版本 ====================

    @Test
    @DisplayName("预占：成功/同键重放/同键不同输入冲突/可用量不足拒绝/版本冲突拒绝")
    void reserveIdempotencyAndConflicts() {
        String actionId = publishReserve("stock_reserve", 600L);

        TxnInvokeResult r1 = executor.invoke(actionId, reserveReq("K1", "stock-1", "5"));
        assertThat(r1.status()).isEqualTo("SUCCEEDED");
        assertThat(r1.reservedAfter()).isEqualByComparingTo("5");
        assertThat(r1.replay()).isFalse();
        assertThat(reservedOf("stock-1")).isEqualByComparingTo("5");

        // 同键同输入 → 重放原结果，无第二副作用
        TxnInvokeResult replay = executor.invoke(actionId, reserveReq("K1", "stock-1", "5"));
        assertThat(replay.replay()).isTrue();
        assertThat(replay.invocationId()).isEqualTo(r1.invocationId());
        assertThat(reservedOf("stock-1")).isEqualByComparingTo("5");
        assertThat(countInvocations(actionId)).isEqualTo(1L);

        // 同键不同输入 → 明确冲突
        assertThatThrownBy(() -> executor.invoke(actionId, reserveReq("K1", "stock-1", "6")))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("不同");

        // 可用量不足 → 业务拒绝（记录 REJECTED，不产生副作用）
        TxnInvokeResult insufficient = executor.invoke(actionId, reserveReq("K2", "stock-1", "100"));
        assertThat(insufficient.status()).isEqualTo("REJECTED");
        assertThat(insufficient.errorCode()).isEqualTo(FormErrorCode.ACTION_INSUFFICIENT_AVAILABLE.getCode());
        assertThat(reservedOf("stock-1")).isEqualByComparingTo("5");
        assertThat(countInvocations(actionId)).isEqualTo(2L);

        // 版本竞争 → 拒绝
        TxnInvokeRequest wrongVersion = reserveReq("K3", "stock-1", "1");
        wrongVersion.setExpectedVersion(999L);
        TxnInvokeResult versionConflict = executor.invoke(actionId, wrongVersion);
        assertThat(versionConflict.status()).isEqualTo("REJECTED");
        assertThat(versionConflict.errorCode()).isEqualTo(FormErrorCode.ACTION_VERSION_CONFLICT.getCode());

        // 正确版本 → 成功
        TxnInvokeRequest rightVersion = reserveReq("K4", "stock-1", "1");
        rightVersion.setExpectedVersion(versionOf("stock-1"));
        assertThat(executor.invoke(actionId, rightVersion).status()).isEqualTo("SUCCEEDED");
        assertThat(reservedOf("stock-1")).isEqualByComparingTo("6");
    }

    // ==================== 确认 / 释放 / 过期 ====================

    @Test
    @DisplayName("确认与释放：状态守卫一次性结算；确认过期拒绝；过期扫描释放并落台账")
    void confirmReleaseExpiry() {
        String reserveId = publishReserve("stock_reserve", 600L);
        String confirmId = publishAction("stock_confirm", "库存确认", "CONFIRM", cfg("qty_available", "qty_reserved", null));
        String releaseId = publishAction("stock_release", "库存释放", "RELEASE", cfg("qty_available", "qty_reserved", null));

        // 预占 → 确认：余额与预占同减
        TxnInvokeResult reserved = executor.invoke(reserveId, reserveReq("C1", "stock-1", "4"));
        String reservationId = reserved.reservationId();
        TxnInvokeRequest confirmReq = new TxnInvokeRequest();
        confirmReq.setReservationId(reservationId);
        confirmReq.setInvocationKey("C2");
        TxnInvokeResult confirmed = executor.invoke(confirmId, confirmReq);
        assertThat(confirmed.status()).isEqualTo("SUCCEEDED");
        assertThat(balanceOf("stock-1")).isEqualByComparingTo("6");
        assertThat(reservedOf("stock-1")).isEqualByComparingTo("0");
        assertThat(reservationStatus(reservationId)).isEqualTo("CONFIRMED");
        assertThat(countLedger(confirmId, reservationId, "CONFIRM")).isEqualTo(1L);

        // 二次确认 → 非 ACTIVE 拒绝
        TxnInvokeRequest again = new TxnInvokeRequest();
        again.setReservationId(reservationId);
        again.setInvocationKey("C3");
        TxnInvokeResult reConfirm = executor.invoke(confirmId, again);
        assertThat(reConfirm.status()).isEqualTo("REJECTED");
        assertThat(reConfirm.errorCode()).isEqualTo(FormErrorCode.ACTION_RESERVATION_NOT_ACTIVE.getCode());

        // 过期：预占 → 手工过期 → 确认拒绝 → 扫描释放一次结算
        TxnInvokeResult expiring = executor.invoke(reserveId, reserveReq("E1", "stock-1", "3"));
        String expiringId = expiring.reservationId();
        jdbcTemplate.update("UPDATE sw_form_txn_reservation SET expires_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(1), expiringId);
        TxnInvokeRequest expConfirm = new TxnInvokeRequest();
        expConfirm.setReservationId(expiringId);
        expConfirm.setInvocationKey("E2");
        TxnInvokeResult expiredReject = executor.invoke(confirmId, expConfirm);
        assertThat(expiredReject.status()).isEqualTo("REJECTED");
        assertThat(expiredReject.errorCode()).isEqualTo(FormErrorCode.ACTION_RESERVATION_EXPIRED.getCode());
        assertThat(reservedOf("stock-1")).isEqualByComparingTo("3");

        TxnReservationExpiryJob job = new TxnReservationExpiryJob(reservationMapper, txOps, new FormIdGenerator());
        job.sweep();
        assertThat(reservationStatus(expiringId)).isEqualTo("EXPIRED");
        assertThat(reservedOf("stock-1")).isEqualByComparingTo("0");
        assertThat(countLedger(reserveId, expiringId, "EXPIRE")).isEqualTo(1L);
        // 仅一次结算：再次扫描不重复副作用
        job.sweep();
        assertThat(countLedger(reserveId, expiringId, "EXPIRE")).isEqualTo(1L);

        // 释放：预占 → 释放 → 预占回退
        TxnInvokeResult toRelease = executor.invoke(reserveId, reserveReq("R1", "stock-1", "2"));
        TxnInvokeRequest releaseReq = new TxnInvokeRequest();
        releaseReq.setReservationId(toRelease.reservationId());
        releaseReq.setInvocationKey("R2");
        assertThat(executor.invoke(releaseId, releaseReq).status()).isEqualTo("SUCCEEDED");
        assertThat(reservedOf("stock-1")).isEqualByComparingTo("0");
        assertThat(reservationStatus(toRelease.reservationId())).isEqualTo("RELEASED");
    }

    // ==================== 调整 ====================

    @Test
    @DisplayName("LT04 冻结语义与停用边界：重发布不改旧预占结算语义；停用只拒新调用且旧凭据可结算；非法声明保存即拒")
    void frozenSemanticsDisableBoundaryAndUnsupportedDeclarations() {
        String formId = jdbcTemplate.queryForObject(
                "SELECT id FROM sw_form_def WHERE form_key = 'stock_form'", String.class);
        jdbcTemplate.update("ALTER TABLE " + QSTOCK + " ADD COLUMN \"qty_reserved2\" NUMERIC(20,6) DEFAULT 0");
        jdbcTemplate.update("UPDATE sw_form_config SET definition = ? WHERE form_id = ?",
                "{\"schemaVersion\":1,\"title\":\"库存表\",\"fields\":["
                        + "{\"name\":\"material\",\"type\":\"TEXT\",\"label\":\"物料\"},"
                        + "{\"name\":\"qty_available\",\"type\":\"NUMBER\",\"label\":\"可用量\"},"
                        + "{\"name\":\"qty_reserved\",\"type\":\"NUMBER\",\"label\":\"预占量\"},"
                        + "{\"name\":\"qty_reserved2\",\"type\":\"NUMBER\",\"label\":\"预占量2\"}]}",
                formId);

        // 非法声明（模型不支持的键）在保存入口被明确拒绝，错误含违规键名
        TxnActionConfig bad = cfg("qty_available", "qty_reserved", 600L);
        bad.getUnsupportedKeys().put("remoteSideEffect", java.util.Map.of("url", "http://example.invalid"));
        assertThatThrownBy(() -> create("stock_reserve_bad", "非法声明", "RESERVE", bad))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("remoteSideEffect");

        // v1 预占 → 重发布 v2 改指 qty_reserved2 → 旧凭据仍按 v1 冻结版本语义结算
        String reserveId = publishReserve("stock_reserve", 600L);
        seedStock("lt04-f1", "100", "0");
        TxnInvokeResult reserved = executor.invoke(reserveId, reserveReq("F1", "lt04-f1", "5"));
        assertThat(reserved.status()).isEqualTo("SUCCEEDED");
        String reservationId = reserved.reservationId();
        actionService.update(reserveId, new TxnActionSaveRequest(null, "库存预占", "RESERVE", null,
                cfg("qty_available", "qty_reserved2", 600L)));
        assertThat(actionService.publish(reserveId).currentVersion()).isEqualTo(2);

        String confirmId = publishAction("stock_confirm", "库存确认", "CONFIRM",
                cfg("qty_available", "qty_reserved2", null));
        TxnInvokeRequest confirmReq = new TxnInvokeRequest();
        confirmReq.setReservationId(reservationId);
        confirmReq.setInvocationKey("F2");
        TxnInvokeResult confirmed = executor.invoke(confirmId, confirmReq);
        assertThat(confirmed.status()).isEqualTo("SUCCEEDED");
        assertThat(confirmed.actionVersion()).as("结算按预占受理冻结版本执行").isEqualTo(1);
        assertThat(reservedOf("lt04-f1")).isEqualByComparingTo("0");
        assertThat(jdbcTemplate.queryForObject("SELECT \"qty_reserved2\" FROM " + QSTOCK
                + " WHERE \"id\" = 'lt04-f1'", BigDecimal.class)).as("新版本字段未被改写").isEqualByComparingTo("0");
        assertThat(balanceOf("lt04-f1")).isEqualByComparingTo("95");

        // 停用边界：停用预占动作 → 新调用被拒；既有凭据仍可结算（未受停用影响）
        seedStock("lt04-f2", "50", "0");
        TxnInvokeResult second = executor.invoke(reserveId, reserveReq("F3", "lt04-f2", "4"));
        assertThat(second.status()).isEqualTo("SUCCEEDED");
        actionService.disable(reserveId);
        assertThatThrownBy(() -> executor.invoke(reserveId, reserveReq("F4", "lt04-f2", "1")))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("停用");
        TxnInvokeRequest settle = new TxnInvokeRequest();
        settle.setReservationId(second.reservationId());
        settle.setInvocationKey("F5");
        assertThat(executor.invoke(confirmId, settle).status())
                .as("停用只拒绝新调用：旧凭据仍可结算").isEqualTo("SUCCEEDED");
        assertThat(reservedOf("lt04-f2")).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("调整：增减余额受可用量非负守卫；减到可用为负被拒绝")
    void adjustGuards() {
        String adjustId = publishAction("stock_adjust", "库存调整", "ADJUST", cfg("qty_available", null, null));

        TxnInvokeResult inc = executor.invoke(adjustId, adjustReq("A1", "stock-1", "5"));
        assertThat(inc.status()).isEqualTo("SUCCEEDED");
        assertThat(balanceOf("stock-1")).isEqualByComparingTo("15");

        TxnInvokeResult dec = executor.invoke(adjustId, adjustReq("A2", "stock-1", "-5"));
        assertThat(dec.status()).isEqualTo("SUCCEEDED");
        assertThat(balanceOf("stock-1")).isEqualByComparingTo("10");

        TxnInvokeResult overdraw = executor.invoke(adjustId, adjustReq("A3", "stock-1", "-20"));
        assertThat(overdraw.status()).isEqualTo("REJECTED");
        assertThat(overdraw.errorCode()).isEqualTo(FormErrorCode.ACTION_INSUFFICIENT_AVAILABLE.getCode());
        assertThat(balanceOf("stock-1")).isEqualByComparingTo("10");
    }

    // ==================== C1 ====================

    @Test
    @DisplayName("C1：既有数据违反约束拒绝启用；启用后受保护字段直接写入与删除被拒；未声明字段不受影响")
    void c1PolicyGate() {
        // 先禁用态保存策略（不校验数据）
        C1PolicyModel model = new C1PolicyModel();
        model.setEnabled(false);
        model.setProtectedFields(List.of("qty_available", "qty_reserved"));
        c1PolicyService.save(formId, model);

        // 制造违反约束的既有数据：可用 = 10 - 20 = -10
        jdbcTemplate.update("UPDATE " + QSTOCK + " SET \"qty_reserved\" = 20 WHERE \"id\" = 'stock-1'");
        C1PolicyModel enable = new C1PolicyModel();
        enable.setEnabled(true);
        enable.setProtectedFields(List.of("qty_available", "qty_reserved"));
        enable.setBalanceField("qty_available");
        enable.setReservedField("qty_reserved");
        enable.setNonNegativeAvailable(true);
        assertThatThrownBy(() -> c1PolicyService.save(formId, enable))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("不满足");

        // 修复数据后可启用
        jdbcTemplate.update("UPDATE " + QSTOCK + " SET \"qty_reserved\" = 0 WHERE \"id\" = 'stock-1'");
        assertThat(c1PolicyService.save(formId, enable).enabled()).isTrue();

        // 直接写入受保护字段 → 拒绝
        assertThatThrownBy(() -> c1PolicyService.assertDirectWriteAllowed(formId, List.of("material", "qty_available")))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("受保护");
        // 未声明字段 → 放行
        c1PolicyService.assertDirectWriteAllowed(formId, List.of("material"));
        // 受保护模型记录删除 → 拒绝
        assertThatThrownBy(() -> c1PolicyService.assertDeleteAllowed(formId))
                .isInstanceOf(BaseException.class);
    }

    // ==================== 查询 ====================

    @Test
    @DisplayName("查询：调用记录/预占凭据/台账可按动作与状态分页回查，结果字段可复算")
    void queries() {
        String actionId = publishReserve("stock_reserve", 600L);
        TxnInvokeResult reserved = executor.invoke(actionId, reserveReq("Q1", "stock-1", "2"));

        var invocation = executor.getInvocation(reserved.invocationId());
        assertThat(invocation.status()).isEqualTo("SUCCEEDED");
        assertThat(invocation.resultJson()).contains("reservationId");
        assertThat(executor.pageInvocations(actionId, "SUCCEEDED", null, null, 1, 20).getTotal())
                .isGreaterThanOrEqualTo(1);
        var reservation = executor.getReservation(reserved.reservationId());
        assertThat(reservation.quantity()).isEqualByComparingTo("2");
        assertThat(executor.pageReservations(actionId, "ACTIVE", 1, 20).getTotal()).isEqualTo(1L);
        assertThat(executor.pageLedger(actionId, 1, null, 1, 20).getRecords())
                .anyMatch(l -> "RESERVE".equals(l.entryType()));
    }

    // ==================== 帮助方法 ====================

    private TxnActionConfig cfg(String balance, String reserved, Long ttl) {
        TxnActionConfig c = new TxnActionConfig();
        c.setBalanceField(balance);
        c.setReservedField(reserved);
        c.setExpiresInSeconds(ttl);
        return c;
    }

    private com.sw.ck.form.txn.model.TxnActionView create(String key, String name, String type, TxnActionConfig cfg) {
        return actionService.create(formId, new TxnActionSaveRequest(key, name, type, null, cfg));
    }

    // ==================== 受控 Port（BPM 节点内部调用口径） ====================

    @Test
    @DisplayName("受控 Port：内部调用沿用同一授权与事务内核；无权限被拒；describe 供发布期绑定校验")
    void formTxnActionPortDelegatesWithServerSideAuthorization() {
        String actionId = publishReserve("stock_port_reserve", 600L);

        // 无权（既非超管也无 form:action:invoke）：拒绝
        assertThatThrownBy(() -> port.invoke(new com.sw.ck.form.api.port.FormTxnActionPort.TxnActionCommand(
                actionId, "stock-1", "2", "NODE:demo:1", null, null)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("form:action:invoke");

        // 授权后：与 HTTP 入口同一事务内核（幂等键沿用调用方稳定键）
        LoginUser user = LoginUserHolder.get();
        user.setPermissions(java.util.List.of("form:action:invoke"));
        var result = port.invoke(new com.sw.ck.form.api.port.FormTxnActionPort.TxnActionCommand(
                actionId, "stock-1", "2", "NODE:demo:1", null, null));
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.reservationId()).isNotBlank();
        assertThat(result.actionVersion()).isEqualTo(1);
        assertThat(reservedOf("stock-1")).isEqualByComparingTo("2");

        // 同键重放：返回原结果、无第二次效果
        var replay = port.invoke(new com.sw.ck.form.api.port.FormTxnActionPort.TxnActionCommand(
                actionId, "stock-1", "2", "NODE:demo:1", null, null));
        assertThat(replay.replay()).isTrue();
        assertThat(replay.reservationId()).isEqualTo(result.reservationId());
        assertThat(reservedOf("stock-1")).isEqualByComparingTo("2");
        assertThat(countInvocations(actionId)).isEqualTo(1L);

        // 发布期绑定校验：已发布动作可描述；缺失/跨租户返回 empty（不泄露）
        var descriptor = port.describe(actionId).orElseThrow();
        assertThat(descriptor.status()).isEqualTo("PUBLISHED");
        assertThat(descriptor.actionType()).isEqualTo("RESERVE");
        assertThat(descriptor.currentVersion()).isEqualTo(1);
        assertThat(port.describe("no-such-action")).isEmpty();
        System.out.println("[P62-EV] s2.port authorized=delegated no-permission=rejected replay=same-reservation"
                + " describe=PUBLISHED missing=empty");
    }

    private String publishAction(String key, String name, String type, TxnActionConfig cfg) {
        String id = create(key, name, type, cfg).id();
        actionService.publish(id);
        return id;
    }

    private String publishReserve(String key, long ttl) {
        return publishAction(key, "库存预占", "RESERVE", cfg("qty_available", "qty_reserved", ttl));
    }

    private TxnInvokeRequest reserveReq(String key, String recordId, String qty) {
        TxnInvokeRequest req = new TxnInvokeRequest();
        req.setInvocationKey(key);
        req.setRecordId(recordId);
        req.setQuantity(qty);
        return req;
    }

    private TxnInvokeRequest adjustReq(String key, String recordId, String qty) {
        return reserveReq(key, recordId, qty);
    }

    private BigDecimal balanceOf(String recordId) {
        return jdbcTemplate.queryForObject("SELECT \"qty_available\" FROM " + QSTOCK + " WHERE \"id\" = ?",
                BigDecimal.class, recordId);
    }

    private BigDecimal reservedOf(String recordId) {
        return jdbcTemplate.queryForObject("SELECT \"qty_reserved\" FROM " + QSTOCK + " WHERE \"id\" = ?",
                BigDecimal.class, recordId);
    }

    private Long versionOf(String recordId) {
        return jdbcTemplate.queryForObject("SELECT \"version\" FROM " + QSTOCK + " WHERE \"id\" = ?",
                Long.class, recordId);
    }

    private String reservationStatus(String reservationId) {
        return jdbcTemplate.queryForObject("SELECT status FROM sw_form_txn_reservation WHERE id = ?",
                String.class, reservationId);
    }

    private long countInvocations(String actionId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_form_txn_invocation WHERE action_id = ?", Long.class, actionId);
    }

    private long countLedger(String actionId, String reservationId, String entryType) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_form_txn_ledger WHERE action_id = ? AND reservation_id = ? AND entry_type = ?",
                Long.class, actionId, reservationId, entryType);
    }

    private void seedStock(String id, String available, String reserved) {
        jdbcTemplate.update("INSERT INTO " + QSTOCK
                        + " (\"id\", \"tenant_id\", \"deleted\", \"version\", \"material\", \"qty_available\", \"qty_reserved\")"
                        + " VALUES (?, 0, 0, 0, 'M-1', ?, ?)",
                id, new BigDecimal(available), new BigDecimal(reserved));
    }

    private String seedForm() {
        String id = new FormIdGenerator().generate();
        jdbcTemplate.update("INSERT INTO sw_form_def (id, form_key, name, status, physical_table_name,"
                        + " form_version, tenant_id, deleted, version) VALUES (?, 'stock_form', '库存表',"
                        + " 'PUBLISHED', ?, 1, 0, 0, 0)",
                id, STOCK_TABLE);
        String definition = "{\"schemaVersion\":1,\"title\":\"库存表\",\"fields\":["
                + "{\"name\":\"material\",\"type\":\"TEXT\",\"label\":\"物料\"},"
                + "{\"name\":\"qty_available\",\"type\":\"NUMBER\",\"label\":\"可用量\"},"
                + "{\"name\":\"qty_reserved\",\"type\":\"NUMBER\",\"label\":\"预占量\"}]}";
        jdbcTemplate.update("INSERT INTO sw_form_config (id, form_id, definition, table_name, parent_table,"
                        + " tenant_id, deleted, version) VALUES (?, ?, ?, ?, NULL, 0, 0, 0)",
                new FormIdGenerator().generate(), id, definition, STOCK_TABLE);
        return id;
    }

    private void createTables() {
        for (String ddl : txnTableDdls()) {
            jdbcTemplate.execute(ddl);
        }
        jdbcTemplate.execute("DROP TABLE IF EXISTS sw_form_def");
        jdbcTemplate.execute("""
                CREATE TABLE sw_form_def (
                    id                   varchar(36)  not null primary key,
                    form_key             varchar(100) not null,
                    name                 varchar(200) not null,
                    logical_table_name   varchar(100),
                    physical_table_name  varchar(100),
                    status               varchar(20)  not null default 'DRAFT',
                    form_version         integer      not null default 1,
                    description          varchar(500),
                    visibility_scope     clob,
                    sub_table_mapping    clob,
                    create_time          timestamp    not null default current_timestamp,
                    create_by            bigint,
                    update_time          timestamp    not null default current_timestamp,
                    update_by            bigint,
                    deleted              smallint     not null default 0,
                    tenant_id            bigint       not null default 0,
                    version              bigint       not null default 0
                )
                """);
        jdbcTemplate.execute("DROP TABLE IF EXISTS sw_form_config");
        jdbcTemplate.execute("""
                CREATE TABLE sw_form_config (
                    id                   varchar(36)  not null primary key,
                    form_id              varchar(36)  not null,
                    definition           clob,
                    table_name           varchar(200),
                    parent_table         varchar(200),
                    create_time          timestamp    not null default current_timestamp,
                    create_by            bigint,
                    update_time          timestamp    not null default current_timestamp,
                    update_by            bigint,
                    deleted              smallint     not null default 0,
                    tenant_id            bigint       not null default 0,
                    version              bigint       not null default 0
                )
                """);
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + QSTOCK);
        // 动态宽表按真实 DynamicTableManager 的方式建表：标识符全部双引号（保留小写）
        jdbcTemplate.execute("CREATE TABLE " + QSTOCK + " ("
                + " \"id\" varchar(36) not null primary key,"
                + " \"tenant_id\" bigint not null default 0,"
                + " \"deleted\" smallint not null default 0,"
                + " \"create_time\" timestamp not null default current_timestamp,"
                + " \"create_by\" bigint,"
                + " \"update_time\" timestamp not null default current_timestamp,"
                + " \"update_by\" bigint,"
                + " \"version\" bigint not null default 0,"
                + " \"material\" varchar(50),"
                + " \"qty_available\" numeric(20,6) not null default 0,"
                + " \"qty_reserved\" numeric(20,6) not null default 0)");
    }

    private List<String> txnTableDdls() {
        return List.of(
                "DROP TABLE IF EXISTS sw_form_txn_action",
                "CREATE TABLE sw_form_txn_action (id varchar(36) not null primary key, form_id varchar(36) not null,"
                        + " action_key varchar(100) not null, name varchar(200) not null, action_type varchar(20) not null,"
                        + " status varchar(20) not null default 'DRAFT', current_version integer, config_json clob,"
                        + " description varchar(500), tenant_id bigint not null default 0, deleted smallint not null default 0,"
                        + " create_time timestamp not null default current_timestamp, create_by bigint,"
                        + " update_time timestamp not null default current_timestamp, update_by bigint,"
                        + " version bigint not null default 0)",
                "CREATE UNIQUE INDEX uk_sw_form_txn_action_key ON sw_form_txn_action (tenant_id, form_id, action_key)",
                "DROP TABLE IF EXISTS sw_form_txn_action_version",
                "CREATE TABLE sw_form_txn_action_version (id varchar(36) not null primary key, action_id varchar(36) not null,"
                        + " version_no integer not null, form_version integer not null, config_json clob not null,"
                        + " published_by bigint, published_at timestamp not null default current_timestamp,"
                        + " tenant_id bigint not null default 0, deleted smallint not null default 0,"
                        + " create_time timestamp not null default current_timestamp, create_by bigint,"
                        + " update_time timestamp not null default current_timestamp, update_by bigint,"
                        + " version bigint not null default 0)",
                "CREATE UNIQUE INDEX uk_sw_form_txn_action_ver ON sw_form_txn_action_version (tenant_id, action_id, version_no)",
                "DROP TABLE IF EXISTS sw_form_txn_invocation",
                "CREATE TABLE sw_form_txn_invocation (id varchar(36) not null primary key, action_id varchar(36) not null,"
                        + " action_version integer not null, invocation_key varchar(200) not null, request_hash varchar(64) not null,"
                        + " biz_record_id varchar(64), status varchar(20) not null, error_code integer, error_msg varchar(500),"
                        + " result_json clob, duration_ms bigint, tenant_id bigint not null default 0,"
                        + " deleted smallint not null default 0, create_time timestamp not null default current_timestamp,"
                        + " create_by bigint, update_time timestamp not null default current_timestamp, update_by bigint,"
                        + " version bigint not null default 0)",
                "CREATE UNIQUE INDEX uk_sw_form_txn_inv_key ON sw_form_txn_invocation (tenant_id, action_id, invocation_key)",
                "DROP TABLE IF EXISTS sw_form_txn_reservation",
                "CREATE TABLE sw_form_txn_reservation (id varchar(36) not null primary key, action_id varchar(36) not null,"
                        + " action_version integer not null, form_id varchar(36) not null, record_id varchar(64) not null,"
                        + " biz_keys_json clob, quantity numeric(20,6) not null, status varchar(20) not null default 'ACTIVE',"
                        + " expires_at timestamp not null, reserve_invocation_id varchar(36), settle_invocation_id varchar(36),"
                        + " settled_at timestamp, tenant_id bigint not null default 0, deleted smallint not null default 0,"
                        + " create_time timestamp not null default current_timestamp, create_by bigint,"
                        + " update_time timestamp not null default current_timestamp, update_by bigint,"
                        + " version bigint not null default 0)",
                "DROP TABLE IF EXISTS sw_form_txn_ledger",
                "CREATE TABLE sw_form_txn_ledger (id varchar(36) not null primary key, action_id varchar(36) not null,"
                        + " action_version integer not null, invocation_id varchar(36) not null, reservation_id varchar(36),"
                        + " entry_type varchar(20) not null, form_id varchar(36) not null, record_id varchar(64) not null,"
                        + " quantity numeric(20,6) not null, balance_after numeric(20,6), reserved_after numeric(20,6),"
                        + " biz_keys_json clob, tenant_id bigint not null default 0, deleted smallint not null default 0,"
                        + " create_time timestamp not null default current_timestamp, create_by bigint,"
                        + " update_time timestamp not null default current_timestamp, update_by bigint,"
                        + " version bigint not null default 0)",
                "DROP TABLE IF EXISTS sw_form_c1_policy",
                "CREATE TABLE sw_form_c1_policy (id varchar(36) not null primary key, form_id varchar(36) not null,"
                        + " enabled smallint not null default 0, policy_json clob, applied_at timestamp,"
                        + " tenant_id bigint not null default 0, deleted smallint not null default 0,"
                        + " create_time timestamp not null default current_timestamp, create_by bigint,"
                        + " update_time timestamp not null default current_timestamp, update_by bigint,"
                        + " version bigint not null default 0)",
                "CREATE UNIQUE INDEX uk_sw_form_c1_policy_form ON sw_form_c1_policy (tenant_id, form_id)");
    }

    @Configuration
    @MapperScan({"com.sw.ck.form.mapper", "com.sw.ck.form.txn.mapper"})
    @EnableTransactionManagement
    static class Config {

        private static LoginContextProvider loginContextProvider() {
            return new LoginContextProvider() {
                @Override public Long getUserId() {
                    LoginUser u = LoginUserHolder.get();
                    return u != null ? u.getUserId() : null;
                }
                @Override public Long getTenantId() {
                    LoginUser u = LoginUserHolder.get();
                    return u != null ? u.getTenantId() : null;
                }
                @Override public Long getDeptId() { return null; }
                @Override public com.sw.ck.common.datascope.DataScopeType getDataScopeType() {
                    return com.sw.ck.common.datascope.DataScopeType.ALL;
                }
                @Override public java.util.Set<Long> getCustomDeptIds() { return java.util.Set.of(); }
                @Override public boolean isSuperAdmin() { return false; }
            };
        }

        @Bean
        public DataSource dataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl("jdbc:h2:mem:p62_txn_flow;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
            ds.setUsername("sa");
            ds.setPassword("");
            return ds;
        }

        @Bean
        public JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        public PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        }

        @Bean
        public org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setTypeAliasesPackage("com.sw.ck.form.entity");

            MybatisConfiguration ibatisConfig = new MybatisConfiguration();
            ibatisConfig.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(ibatisConfig);

            GlobalConfig globalConfig = new GlobalConfig();
            GlobalConfig.DbConfig dbConfig = new GlobalConfig.DbConfig();
            dbConfig.setLogicDeleteField("deleted");
            dbConfig.setLogicDeleteValue("1");
            dbConfig.setLogicNotDeleteValue("0");
            globalConfig.setDbConfig(dbConfig);

            LoginContextProvider provider = loginContextProvider();
            CommonMetaObjectHandler metaObjectHandler = new CommonMetaObjectHandler(provider);
            metaObjectHandler.setFormIdFiller(meta -> {
                Object original = meta.getOriginalObject();
                if (original instanceof com.sw.ck.form.entity.FormBaseEntity f && f.getId() == null) {
                    f.setId(new FormIdGenerator().generate());
                }
            });
            globalConfig.setMetaObjectHandler(metaObjectHandler);
            factory.setGlobalConfig(globalConfig);

            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(
                    new CommonTenantLineHandler(new TenantProperties(), provider)));
            interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
            interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
            factory.setPlugins(interceptor);

            return factory.getObject();
        }

        @Bean
        public TenantProperties tenantProperties() {
            TenantProperties props = new TenantProperties();
            props.setEnabled(true);
            props.setIgnoreTables(List.of("sys_menu"));
            return props;
        }

        @Bean
        public com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }

        @Bean
        public TxnFormBinding txnFormBinding(FormDefMapper formDefMapper, FormConfigMapper formConfigMapper,
                                             com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
            return new TxnFormBinding(formDefMapper, new FormFieldValidator(formConfigMapper, objectMapper));
        }

        @Bean
        public TxnActionService txnActionService(TxnActionMapper actionMapper, TxnActionVersionMapper versionMapper,
                                                 TxnFormBinding binding, com.fasterxml.jackson.databind.ObjectMapper om) {
            return new TxnActionService(actionMapper, versionMapper, binding, new FormIdGenerator(), om);
        }

        @Bean
        public C1PolicyService c1PolicyService(C1PolicyMapper policyMapper, TxnFormBinding binding,
                                               JdbcTemplate jdbcTemplate,
                                               com.fasterxml.jackson.databind.ObjectMapper om) {
            return new C1PolicyService(policyMapper, binding, new FormIdGenerator(), jdbcTemplate, om);
        }

        @Bean
        public TxnActionTxOperations txnActionTxOperations(TxnInvocationMapper invocationMapper,
                                                           TxnReservationMapper reservationMapper,
                                                           TxnLedgerMapper ledgerMapper,
                                                           TxnActionVersionMapper versionMapper,
                                                           TxnFormBinding binding,
                                                           com.fasterxml.jackson.databind.ObjectMapper om,
                                                           JdbcTemplate jdbcTemplate) {
            return new TxnActionTxOperations(invocationMapper, reservationMapper, ledgerMapper, versionMapper,
                    binding, new FormIdGenerator(), om, jdbcTemplate);
        }

        @Bean
        public com.sw.ck.form.api.port.FormTxnActionPort formTxnActionPort(TxnActionService actionService,
                                                                         TxnActionExecutor executor,
                                                                         com.sw.ck.form.txn.mapper.TxnInvocationMapper invocationMapper) {
            return new com.sw.ck.form.txn.port.FormTxnActionPortImpl(actionService, executor, invocationMapper);
        }

        @Bean
        public com.sw.ck.form.txn.guard.TxnActionRealtimeGuard txnActionRealtimeGuard() {
            return new com.sw.ck.form.txn.guard.TxnActionRealtimeGuard();
        }

        @Bean
        public TxnActionExecutor txnActionExecutor(TxnActionService actionService, TxnActionTxOperations txOps,
                                                   TxnInvocationMapper invocationMapper,
                                                   TxnReservationMapper reservationMapper,
                                                   TxnLedgerMapper ledgerMapper,
                                                   com.fasterxml.jackson.databind.ObjectMapper om) {
            return new TxnActionExecutor(actionService, txOps, invocationMapper, reservationMapper, ledgerMapper,
                    new FormIdGenerator(), om);
        }
    }
}
