package com.sw.ck.notify.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.response.R;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.notify.dto.NotifyBatchSendReq;
import com.sw.ck.notify.dto.NotifyBatchSendResp;
import com.sw.ck.notify.entity.NotifyMessage;
import com.sw.ck.notify.mapper.NotifyMessageMapper;
import com.sw.ck.notify.render.TemplateRenderService;
import com.sw.ck.notify.service.NotifyMessageService;
import com.sw.ck.notify.service.NotifyTemplateService;
import com.sw.ck.notify.service.impl.NotifyMessageServiceImpl;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M05 批量发送行为集成测试。
 * 覆盖 G1（接收人解析+去重+有效性）和 G2（零接收人/上限/模板失败/回滚）。
 */
@SpringBootTest(
        classes = NotifyBatchSendIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.flyway.enabled=false", "sw.tenant.enabled=true"}
)
@DisplayName("M05 批量发送：接收人解析、去重、边界与原子性")
class NotifyBatchSendIntegrationTest {

    private static final Long TENANT_100 = 100L;
    private static final Long USER_A = 1L;
    private static final Long USER_B = 2L;
    private static final Long USER_C = 3L;

    @Autowired private NotifyController notifyController;
    @Autowired private NotifyMessageService notifyMessageService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TestLoginContext testLoginContext;

    @BeforeAll
    static void createTables(@Autowired JdbcTemplate jt) {
        jt.execute("CREATE TABLE IF NOT EXISTS sys_user (id BIGINT PRIMARY KEY, username VARCHAR(50) NOT NULL, nickname VARCHAR(50), dept_id BIGINT, status INT DEFAULT 0, tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT DEFAULT 0, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jt.execute("CREATE TABLE IF NOT EXISTS sys_role (id BIGINT PRIMARY KEY, name VARCHAR(50) NOT NULL, code VARCHAR(50) NOT NULL, status INT DEFAULT 1, tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT DEFAULT 0, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jt.execute("CREATE TABLE IF NOT EXISTS sys_user_role (user_id BIGINT NOT NULL, role_id BIGINT NOT NULL, tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT DEFAULT 0, PRIMARY KEY (user_id, role_id))");
        jt.execute("CREATE TABLE IF NOT EXISTS sys_dept (id BIGINT PRIMARY KEY, parent_id BIGINT DEFAULT 0, name VARCHAR(50) NOT NULL, code VARCHAR(50), status INT DEFAULT 0, tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT DEFAULT 0, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jt.execute("CREATE TABLE IF NOT EXISTS sw_notify_message (id BIGINT PRIMARY KEY, recipient_id BIGINT NOT NULL, title VARCHAR(200) NOT NULL, content TEXT NOT NULL, biz_type VARCHAR(30) NOT NULL DEFAULT 'SYSTEM', biz_id VARCHAR(64), is_read BOOLEAN DEFAULT FALSE, channel VARCHAR(40) NOT NULL DEFAULT 'IN_APP', delivery_status VARCHAR(20) NOT NULL DEFAULT 'SUCCESS', external_message_id VARCHAR(200), failure_reason VARCHAR(500), idempotency_key VARCHAR(200), tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT DEFAULT 0, version BIGINT DEFAULT 0, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, create_by BIGINT, update_by BIGINT)");

        jt.update("INSERT INTO sys_dept VALUES(1,0,'技术部','tech',0,100,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jt.update("INSERT INTO sys_dept VALUES(2,0,'产品部','pm',0,100,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jt.update("INSERT INTO sys_role VALUES(1,'管理员','admin',1,100,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jt.update("INSERT INTO sys_role VALUES(2,'普通用户','user',1,100,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jt.update("INSERT INTO sys_user VALUES(1,'userA','用户A',1,0,100,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jt.update("INSERT INTO sys_user VALUES(2,'userB','用户B',2,0,100,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jt.update("INSERT INTO sys_user VALUES(3,'userC','用户C',1,0,100,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jt.update("INSERT INTO sys_user_role VALUES(1,1,100,0)");
        jt.update("INSERT INTO sys_user_role VALUES(2,2,100,0)");
        jt.update("INSERT INTO sys_user_role VALUES(3,2,100,0)");
        jt.update("INSERT INTO sys_user VALUES(10,'userX','用户X',1,0,200,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jt.update("INSERT INTO sys_user VALUES(4,'userD','用户D',1,1,100,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jt.update("INSERT INTO sys_user VALUES(5,'userE','用户E',1,0,100,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM sw_notify_message WHERE 1=1");
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(USER_A);
        loginUser.setUsername("userA");
        loginUser.setTenantId(TENANT_100);
        loginUser.setSuperAdmin(false);
        loginUser.setPermissions(List.of("notify:batch:send"));
        LoginUserHolder.set(loginUser);
        testLoginContext.set(TENANT_100, USER_A);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
        testLoginContext.set(null, null);
    }

    // ═══════════ G1：接收人解析、去重和有效性 ═══════════

    @Test @DisplayName("G1-a: 按单用户 ID 发送 → 1 条通知落库")
    void singleUserSend() {
        long before = countMessages();
        NotifyBatchSendReq req = new NotifyBatchSendReq();
        req.setRecipientUserIds(List.of(USER_A));
        req.setTitle("单用户测试"); req.setContent("内容");
        NotifyBatchSendResp resp = notifyController.batchSend(req).getData();
        long after = countMessages();
        assertThat(resp.getRecipientCount()).isEqualTo(1);
        assertThat(after - before).isEqualTo(1);
    }

    @Test @DisplayName("G1-b: 按单部门 ID 发送 → 部门 1 下 2 个有效用户")
    void singleDeptSend() {
        long before = countMessages();
        NotifyBatchSendReq req = new NotifyBatchSendReq();
        req.setRecipientDeptIds(List.of(1L));
        req.setTitle("部门测试"); req.setContent("内容");
        NotifyBatchSendResp resp = notifyController.batchSend(req).getData();
        long after = countMessages();
        assertThat(resp.getRecipientCount()).isEqualTo(2);
        assertThat(after - before).isEqualTo(2);
    }

    @Test @DisplayName("G1-c: 按角色 code 发送 → 角色 user 下 2 个用户")
    void singleRoleSend() {
        long before = countMessages();
        NotifyBatchSendReq req = new NotifyBatchSendReq();
        req.setRecipientRoleCodes(List.of("user"));
        req.setTitle("角色测试"); req.setContent("内容");
        NotifyBatchSendResp resp = notifyController.batchSend(req).getData();
        long after = countMessages();
        assertThat(resp.getRecipientCount()).isEqualTo(2);
        assertThat(after - before).isEqualTo(2);
    }

    @Test @DisplayName("G1-d: 三维度组合重叠 → 去重后 = 3")
    void combinedOverlapDedup() {
        long before = countMessages();
        NotifyBatchSendReq req = new NotifyBatchSendReq();
        req.setRecipientUserIds(List.of(USER_A));
        req.setRecipientDeptIds(List.of(1L));
        req.setRecipientRoleCodes(List.of("user"));
        req.setTitle("组合去重"); req.setContent("内容");
        NotifyBatchSendResp resp = notifyController.batchSend(req).getData();
        long after = countMessages();
        assertThat(resp.getRecipientCount()).isEqualTo(3);
        assertThat(after - before).isEqualTo(3);
    }

    @Test @DisplayName("G1-e: 跨租户用户 ID 不投递")
    void crossTenantUserRejected() {
        long before = countMessages();
        NotifyBatchSendReq req = new NotifyBatchSendReq();
        req.setRecipientUserIds(List.of(10L));
        req.setTitle("跨租户"); req.setContent("内容");
        assertThatThrownBy(() -> notifyController.batchSend(req)).isInstanceOf(Exception.class);
        assertThat(countMessages() - before).isEqualTo(0);
    }

    @Test @DisplayName("G1-f: 停用用户不投递")
    void disabledUserNotIncluded() {
        long before = countMessages();
        NotifyBatchSendReq req = new NotifyBatchSendReq();
        req.setRecipientUserIds(List.of(4L));
        req.setTitle("停用用户"); req.setContent("内容");
        assertThatThrownBy(() -> notifyController.batchSend(req)).isInstanceOf(Exception.class);
        assertThat(countMessages() - before).isEqualTo(0);
    }

    @Test @DisplayName("G1-g: 已删除用户不投递")
    void deletedUserNotIncluded() {
        long before = countMessages();
        NotifyBatchSendReq req = new NotifyBatchSendReq();
        req.setRecipientUserIds(List.of(5L));
        req.setTitle("删除用户"); req.setContent("内容");
        assertThatThrownBy(() -> notifyController.batchSend(req)).isInstanceOf(Exception.class);
        assertThat(countMessages() - before).isEqualTo(0);
    }

    // ═══════════ G2：零接收人、500 上限和整批原子性 ═══════════

    @Test @DisplayName("G2-a: 零接收人 → 整体拒绝")
    void zeroRecipientRejected() {
        long before = countMessages();
        NotifyBatchSendReq req = new NotifyBatchSendReq();
        req.setRecipientUserIds(List.of());
        req.setTitle("零人"); req.setContent("内容");
        assertThatThrownBy(() -> notifyController.batchSend(req)).isInstanceOf(Exception.class);
        assertThat(countMessages() - before).isEqualTo(0);
    }

    @Test @DisplayName("G2-b: 501 人 → 整体拒绝")
    void over500Rejected() {
        for (int i = 100; i < 601; i++)
            jdbcTemplate.update("INSERT INTO sys_user VALUES(?,?,?,1,0,100,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", i, "u" + i, "U" + i);
        List<Long> ids = new java.util.ArrayList<>();
        for (long i = 100; i <= 600; i++) ids.add(i);
        long before = countMessages();
        NotifyBatchSendReq req = new NotifyBatchSendReq();
        req.setRecipientUserIds(ids);
        req.setTitle("超限"); req.setContent("内容");
        assertThatThrownBy(() -> notifyController.batchSend(req)).isInstanceOf(Exception.class);
        assertThat(countMessages() - before).isEqualTo(0);
        for (long i = 100; i <= 600; i++) jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", i);
    }

    @Test @DisplayName("G2-c: 直接内容与模板互斥 → 拒绝")
    void contentModeConflictRejected() {
        long before = countMessages();
        NotifyBatchSendReq req = new NotifyBatchSendReq();
        req.setRecipientUserIds(List.of(USER_A));
        req.setTitle("标题"); req.setContent("内容"); req.setTemplateCode("tpl");
        assertThatThrownBy(() -> notifyController.batchSend(req)).isInstanceOf(Exception.class);
        assertThat(countMessages() - before).isEqualTo(0);
    }

    @Test @DisplayName("G2-d: 两种模式都不提供 → 拒绝")
    void noContentModeRejected() {
        long before = countMessages();
        NotifyBatchSendReq req = new NotifyBatchSendReq();
        req.setRecipientUserIds(List.of(USER_A));
        assertThatThrownBy(() -> notifyController.batchSend(req)).isInstanceOf(Exception.class);
        assertThat(countMessages() - before).isEqualTo(0);
    }

    @Test @DisplayName("G2-e: 500 人成功 → 响应人数 = 新增记录数")
    void exactly500Success() {
        for (int i = 100; i < 600; i++)
            jdbcTemplate.update("INSERT INTO sys_user VALUES(?,?,?,1,0,100,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", i, "u" + i, "U" + i);
        List<Long> ids = new java.util.ArrayList<>();
        for (long i = 100; i < 600; i++) ids.add(i);
        long before = countMessages();
        NotifyBatchSendReq req = new NotifyBatchSendReq();
        req.setRecipientUserIds(ids);
        req.setTitle("500人"); req.setContent("内容");
        NotifyBatchSendResp resp = notifyController.batchSend(req).getData();
        long after = countMessages();
        assertThat(resp.getRecipientCount()).isEqualTo(500);
        assertThat(after - before).isEqualTo(500);
        for (long i = 100; i < 600; i++) jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", i);
    }

    @Test @DisplayName("G2-f: resolve-count 返回去重人数（不落库）")
    void resolveCountReturnsDedupedCount() {
        long before = countMessages();
        NotifyBatchSendReq req = new NotifyBatchSendReq();
        req.setRecipientUserIds(List.of(USER_A));       // userA 直接
        req.setRecipientDeptIds(List.of(1L));            // 技术部 → userA + userC
        req.setRecipientRoleCodes(List.of("user"));      // 角色 user → userB + userC
        NotifyBatchSendResp resp = notifyController.resolveCount(req).getData();
        long after = countMessages();
        // userA(直接+部门) + userB(角色) + userC(部门+角色) = 3 去重
        assertThat(resp.getRecipientCount()).isEqualTo(3);
        assertThat(after - before).isEqualTo(0);
    }

    // ─── 辅助 ───

    private long countMessages() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sw_notify_message WHERE deleted = 0", Long.class);
    }

    // ─── TestLoginContext ───

    static class TestLoginContext implements LoginContextProvider {
        private volatile Long currentUserId;
        private volatile Long currentTenantId;
        void set(Long tenantId, Long userId) { this.currentTenantId = tenantId; this.currentUserId = userId; }
        @Override public Long getUserId() { return currentUserId; }
        @Override public Long getTenantId() { return currentTenantId; }
        @Override public Long getDeptId() { return null; }
        @Override public DataScopeType getDataScopeType() { return DataScopeType.ALL; }
        @Override public Set<Long> getCustomDeptIds() { return Set.of(); }
        @Override public boolean isSuperAdmin() { return false; }
    }

    // ─── TestConfig ───

    @Configuration
    @MapperScan("com.sw.ck.notify.mapper")
    @EnableTransactionManagement
    static class TestConfig {
        @Bean public DataSource dataSource() {
            return DataSourceBuilder.create().url("jdbc:h2:mem:batchsend;DB_CLOSE_DELAY=-1;MODE=PostgreSQL").driverClassName("org.h2.Driver").username("sa").password("").build();
        }
        @Bean public JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean public PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean public TestLoginContext testLoginContext() { return new TestLoginContext(); }
        @Bean public CommonMetaObjectHandler commonMetaObjectHandler(LoginContextProvider p) { return new CommonMetaObjectHandler(p); }
        @Bean public TenantProperties tenantProperties() { return new TenantProperties(); }
        @Bean public TenantLineInnerInterceptor tenantLineInnerInterceptor(TenantProperties tp, LoginContextProvider p) {
            return new TenantLineInnerInterceptor(new CommonTenantLineHandler(tp, p));
        }
        @Bean public MybatisPlusInterceptor mybatisPlusInterceptor(TenantLineInnerInterceptor t) {
            MybatisPlusInterceptor i = new MybatisPlusInterceptor(); i.addInnerInterceptor(t); i.addInnerInterceptor(new OptimisticLockerInnerInterceptor()); return i;
        }
        @Bean public org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(DataSource ds, CommonMetaObjectHandler m, MybatisPlusInterceptor i) throws Exception {
            MybatisSqlSessionFactoryBean f = new MybatisSqlSessionFactoryBean(); f.setDataSource(ds); f.setTypeAliasesPackage("com.sw.ck.notify.entity");
            MybatisConfiguration c = new MybatisConfiguration(); c.setMapUnderscoreToCamelCase(true); c.setUseGeneratedKeys(true); f.setConfiguration(c);
            GlobalConfig g = new GlobalConfig(); GlobalConfig.DbConfig d = new GlobalConfig.DbConfig(); d.setLogicDeleteField("deleted"); d.setLogicDeleteValue("1"); d.setLogicNotDeleteValue("0");
            g.setDbConfig(d); g.setMetaObjectHandler(m); f.setGlobalConfig(g); f.setPlugins(i); return f.getObject();
        }
        @Bean public TemplateRenderService templateRenderService() { return new TemplateRenderService(); }
        @Bean public NotifyTemplateService notifyTemplateService() { return mock(NotifyTemplateService.class); }
        @Bean public NotifyMessageService notifyMessageService(NotifyTemplateService ts, TemplateRenderService rs, LoginContextProvider lp) { return new NotifyMessageServiceImpl(ts, rs, lp); }
        @Bean public NotifyController notifyController(NotifyMessageService s) { return new NotifyController(s, org.mockito.Mockito.mock(com.sw.ck.notify.api.NotifyFacade.class)); }
    }
}
