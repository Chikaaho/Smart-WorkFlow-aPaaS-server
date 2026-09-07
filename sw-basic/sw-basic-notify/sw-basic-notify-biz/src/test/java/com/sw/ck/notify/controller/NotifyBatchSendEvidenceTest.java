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
import com.sw.ck.notify.dto.NotifyTemplateDTO;
import com.sw.ck.notify.mapper.NotifyMessageMapper;
import com.sw.ck.notify.mapper.NotifyTemplateMapper;
import com.sw.ck.notify.render.TemplateRenderService;
import com.sw.ck.notify.service.NotifyMessageService;
import com.sw.ck.notify.service.NotifyTemplateService;
import com.sw.ck.notify.service.impl.NotifyMessageServiceImpl;
import com.sw.ck.notify.service.impl.NotifyTemplateServiceImpl;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 通知批量发送行为证据测试。
 * 批量发送行为证据测试。R1—R5 为历史锁定场景；S1/S2 为当前补证场景。
 */
@SpringBootTest(
        classes = NotifyBatchSendEvidenceTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.flyway.enabled=false", "sw.tenant.enabled=true"}
)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("批量发送行为证据（R1—R5）")
class NotifyBatchSendEvidenceTest {

    private static final Long TENANT_100 = 100L;
    private static final Long TENANT_200 = 200L;
    private static final Long USER_A = 1L;
    private static final Long USER_B = 2L;
    private static final Long USER_C = 3L;

    @Autowired private NotifyController notifyController;
    @Autowired private NotifyMessageService notifyMessageService;
    @Autowired private NotifyTemplateService notifyTemplateService;
    @Autowired private FailingNotifyMessageService failingNotifyMessageService;
    @Autowired private JdbcTemplate jt;
    @Autowired private TestLoginContext testLoginContext;

    @BeforeAll
    static void createTables(@Autowired JdbcTemplate jt) {
        jt.execute("CREATE TABLE IF NOT EXISTS sys_user (id BIGINT PRIMARY KEY, username VARCHAR(50) NOT NULL, nickname VARCHAR(50), dept_id BIGINT, status INT DEFAULT 0, tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT DEFAULT 0, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jt.execute("CREATE TABLE IF NOT EXISTS sys_role (id BIGINT PRIMARY KEY, name VARCHAR(50) NOT NULL, code VARCHAR(50) NOT NULL, status INT DEFAULT 0, tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT DEFAULT 0, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jt.execute("CREATE TABLE IF NOT EXISTS sys_user_role (user_id BIGINT NOT NULL, role_id BIGINT NOT NULL, tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT DEFAULT 0, PRIMARY KEY (user_id, role_id))");
        jt.execute("CREATE TABLE IF NOT EXISTS sys_dept (id BIGINT PRIMARY KEY, parent_id BIGINT DEFAULT 0, name VARCHAR(50) NOT NULL, code VARCHAR(50), status INT DEFAULT 0, tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT DEFAULT 0, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jt.execute("CREATE TABLE IF NOT EXISTS sw_notify_message (id BIGINT PRIMARY KEY, recipient_id BIGINT NOT NULL, title VARCHAR(200) NOT NULL, content TEXT NOT NULL, biz_type VARCHAR(30) NOT NULL DEFAULT 'SYSTEM', biz_id VARCHAR(64), is_read BOOLEAN DEFAULT FALSE, channel VARCHAR(40) NOT NULL DEFAULT 'IN_APP', delivery_status VARCHAR(20) NOT NULL DEFAULT 'SUCCESS', external_message_id VARCHAR(200), failure_reason VARCHAR(500), idempotency_key VARCHAR(200), tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT DEFAULT 0, version BIGINT DEFAULT 0, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, create_by BIGINT, update_by BIGINT)");
        jt.execute("CREATE TABLE IF NOT EXISTS sw_notify_template (id BIGINT PRIMARY KEY, template_code VARCHAR(100) NOT NULL, name VARCHAR(100) NOT NULL, title_template VARCHAR(200), content_template TEXT, enabled SMALLINT DEFAULT 1, remark VARCHAR(500), tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT DEFAULT 0, version BIGINT DEFAULT 0, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, create_by VARCHAR(64), update_by VARCHAR(64))");

        // 种子：部门
        jt.update("INSERT INTO sys_dept VALUES(1,0,'技术部','tech',0,100,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jt.update("INSERT INTO sys_dept VALUES(2,0,'产品部','pm',0,100,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jt.update("INSERT INTO sys_dept VALUES(3,0,'跨租户部门','cross',0,200,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jt.update("INSERT INTO sys_dept VALUES(4,0,'停用部门','disabled',1,100,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jt.update("INSERT INTO sys_dept VALUES(5,0,'已删除部门','deleted',0,100,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jt.update("INSERT INTO sys_dept VALUES(11,1,'未提交子部门','child',0,100,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        // 种子：角色
        jt.update("INSERT INTO sys_role VALUES(1,'管理员','admin',1,100,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jt.update("INSERT INTO sys_role VALUES(2,'普通用户','user',1,100,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jt.update("INSERT INTO sys_role VALUES(3,'跨租户角色','cross',1,200,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jt.update("INSERT INTO sys_role VALUES(4,'停用角色','disabled',0,100,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jt.update("INSERT INTO sys_role VALUES(5,'已删除角色','deleted',1,100,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        // 种子：用户（租户100）
        jt.update("INSERT INTO sys_user VALUES(1,'userA','用户A',1,0,100,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jt.update("INSERT INTO sys_user VALUES(2,'userB','用户B',2,0,100,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jt.update("INSERT INTO sys_user VALUES(3,'userC','用户C',1,0,100,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jt.update("INSERT INTO sys_user_role VALUES(1,1,100,0)");
        jt.update("INSERT INTO sys_user_role VALUES(2,2,100,0)");
        jt.update("INSERT INTO sys_user_role VALUES(3,2,100,0)");
        // 跨租户用户
        jt.update("INSERT INTO sys_user VALUES(10,'userX','用户X',1,0,200,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        // 停用用户
        jt.update("INSERT INTO sys_user VALUES(4,'userD','用户D',1,1,100,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        // 已删除用户（deleted=1）
        jt.update("INSERT INTO sys_user VALUES(5,'userE','用户E',1,0,100,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jt.update("INSERT INTO sys_user VALUES(11,'userChild','子部门用户',11,0,100,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        // 种子：模板（enabled=true，含变量）
        jt.update("INSERT INTO sw_notify_template VALUES(1,'TPL_VAR','变量模板','你好 ${userName}','你有一条新通知：${msg}',1,null,100,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,null,null)");
        // 种子：模板（enabled=false，已停用）
        jt.update("INSERT INTO sw_notify_template VALUES(2,'TPL_DISABLED','停用模板','停用标题','停用内容',0,null,100,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,null,null)");
    }

    @BeforeEach
    void setUp() {
        jt.update("DELETE FROM sw_notify_message WHERE 1=1");
        loginAs(USER_A, false, List.of("notify:batch:send"));
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
        testLoginContext.set(null, null);
    }

    private void loginAs(Long userId, boolean superAdmin, List<String> permissions) {
        LoginUser u = new LoginUser();
        u.setUserId(userId);
        u.setUsername("user" + userId);
        u.setTenantId(TENANT_100);
        u.setSuperAdmin(superAdmin);
        u.setPermissions(permissions);
        LoginUserHolder.set(u);
        testLoginContext.set(TENANT_100, userId);
    }

    private long countMsg() {
        return jt.queryForObject("SELECT COUNT(*) FROM sw_notify_message WHERE deleted=0", Long.class);
    }

    private String recipients(long after, long before) {
        return "count=" + (after - before);
    }

    // ═══════════════════════════════════════════════════════
    // R1：接收人解析与有效对象行为
    // ═══════════════════════════════════════════════════════

    @Test @Order(101)
    @DisplayName("R1-a: 单用户 userIds=[1] → userA 收到1条")
    void r1a_singleUser() {
        long b = countMsg();
        NotifyBatchSendReq r = new NotifyBatchSendReq();
        r.setRecipientUserIds(List.of(USER_A));
        r.setTitle("R1a"); r.setContent("R1a");
        NotifyBatchSendResp resp = notifyController.batchSend(r).getData();
        long a = countMsg();
        System.out.println("[R1-a] input=userIds=[1], response=recipientCount=" + resp.getRecipientCount() + ", db_before=" + b + ", db_after=" + a + ", delta=" + (a - b));
        assertThat(resp.getRecipientCount()).isEqualTo(1);
        assertThat(a - b).isEqualTo(1);
    }

    @Test @Order(102)
    @DisplayName("R1-b: 单部门 deptIds=[1] → 技术部2人(userA+userC)")
    void r1b_singleDept() {
        long b = countMsg();
        NotifyBatchSendReq r = new NotifyBatchSendReq();
        r.setRecipientDeptIds(List.of(1L));
        r.setTitle("R1b"); r.setContent("R1b");
        NotifyBatchSendResp resp = notifyController.batchSend(r).getData();
        long a = countMsg();
        System.out.println("[R1-b] input=deptIds=[1], response=recipientCount=" + resp.getRecipientCount() + ", db_before=" + b + ", db_after=" + a + ", delta=" + (a - b));
        assertThat(resp.getRecipientCount()).isEqualTo(2);
        assertThat(a - b).isEqualTo(2);
    }

    @Test @Order(103)
    @DisplayName("R1-c: 单角色 roleCodes=[user] → 2人(userB+userC)")
    void r1c_singleRole() {
        long b = countMsg();
        NotifyBatchSendReq r = new NotifyBatchSendReq();
        r.setRecipientRoleCodes(List.of("user"));
        r.setTitle("R1c"); r.setContent("R1c");
        NotifyBatchSendResp resp = notifyController.batchSend(r).getData();
        long a = countMsg();
        System.out.println("[R1-c] input=roleCodes=[user], response=recipientCount=" + resp.getRecipientCount() + ", db_before=" + b + ", db_after=" + a + ", delta=" + (a - b));
        assertThat(resp.getRecipientCount()).isEqualTo(2);
        assertThat(a - b).isEqualTo(2);
    }

    @Test @Order(104)
    @DisplayName("R1-d: 三维重叠 userIds=[1]+deptIds=[1]+roleCodes=[user] → 3人去重")
    void r1d_combinedOverlap() {
        long b = countMsg();
        NotifyBatchSendReq r = new NotifyBatchSendReq();
        r.setRecipientUserIds(List.of(USER_A));
        r.setRecipientDeptIds(List.of(1L));
        r.setRecipientRoleCodes(List.of("user"));
        r.setTitle("R1d"); r.setContent("R1d");
        NotifyBatchSendResp resp = notifyController.batchSend(r).getData();
        long a = countMsg();
        System.out.println("[R1-d] input=userIds=[1]+deptIds=[1]+roleCodes=[user], response=recipientCount=" + resp.getRecipientCount() + ", db_before=" + b + ", db_after=" + a + ", delta=" + (a - b));
        assertThat(resp.getRecipientCount()).isEqualTo(3);
        assertThat(a - b).isEqualTo(3);
    }

    // ═══════════════════════════════════════════════════════
    // S1：部门/角色对象有效性（当前二级提示唯一剩余项）
    // ═══════════════════════════════════════════════════════

    @Test @Order(501)
    @DisplayName("S1-a: 不存在部门")
    void s1a_nonExistentDept() { assertInvalidDept("不存在部门", 999L); }

    @Test @Order(502)
    @DisplayName("S1-b: 跨租户部门")
    void s1b_crossTenantDept() { assertInvalidDept("跨租户部门", 3L); }

    @Test @Order(503)
    @DisplayName("S1-c: 停用部门")
    void s1c_disabledDept() { assertInvalidDept("停用部门", 4L); }

    @Test @Order(504)
    @DisplayName("S1-d: 已删除部门")
    void s1d_deletedDept() { assertInvalidDept("已删除部门", 5L); }

    @Test @Order(505)
    @DisplayName("S1-e: 不存在角色")
    void s1e_nonExistentRole() { assertInvalidRole("不存在角色", "missing"); }

    @Test @Order(506)
    @DisplayName("S1-f: 跨租户角色")
    void s1f_crossTenantRole() { assertInvalidRole("跨租户角色", "cross"); }

    @Test @Order(507)
    @DisplayName("S1-g: 停用角色")
    void s1g_disabledRole() { assertInvalidRole("停用角色", "disabled"); }

    @Test @Order(508)
    @DisplayName("S1-h: 已删除角色")
    void s1h_deletedRole() { assertInvalidRole("已删除角色", "deleted"); }

    private void assertInvalidDept(String caseName, Long deptId) {
        NotifyBatchSendReq req = directRequest(List.of(USER_A));
        req.setRecipientDeptIds(List.of(deptId));
        long dbBefore = countMsg();
        Throwable failure = catchThrowable(() -> notifyController.batchSend(req));
        long dbAfter = countMsg();
        System.out.println("[S1] case=" + caseName + ", tenant=100, input=deptIds=[" + deptId
                + "], response-or-exception=" + describe(failure)
                + ", resolvedRecipientIds=[], dbBefore=" + dbBefore + ", dbAfter=" + dbAfter
                + ", delta=" + (dbAfter - dbBefore));
        assertThat(failure).as(caseName + " 必须拒绝").isNotNull();
        assertThat(dbAfter - dbBefore).isZero();
    }

    private void assertInvalidRole(String caseName, String roleCode) {
        NotifyBatchSendReq req = directRequest(List.of(USER_A));
        req.setRecipientRoleCodes(List.of(roleCode));
        long dbBefore = countMsg();
        Throwable failure = catchThrowable(() -> notifyController.batchSend(req));
        long dbAfter = countMsg();
        System.out.println("[S1] case=" + caseName + ", tenant=100, input=roleCodes=[" + roleCode
                + "], response-or-exception=" + describe(failure)
                + ", resolvedRecipientIds=[], dbBefore=" + dbBefore + ", dbAfter=" + dbAfter
                + ", delta=" + (dbAfter - dbBefore));
        assertThat(failure).as(caseName + " 必须拒绝").isNotNull();
        assertThat(dbAfter - dbBefore).isZero();
    }

    // ═══════════════════════════════════════════════════════
    // S2：已删除模板与真实事务中途失败（不可替代场景）
    // ═══════════════════════════════════════════════════════

    @Test @Order(601)
    @DisplayName("S2-a: 创建后逻辑删除模板再发送，零残留")
    void s2a_deletedTemplateAfterCreate() {
        NotifyTemplateDTO dto = new NotifyTemplateDTO();
        dto.setTemplateCode("TPL_S2_DELETED");
        dto.setName("S2 删除模板");
        dto.setTitleTemplate("S2 标题");
        dto.setContentTemplate("S2 正文");
        dto.setEnabled(true);
        Long templateId = notifyTemplateService.createTemplate(dto);
        notifyTemplateService.deleteTemplate(templateId);
        Integer deleted = jt.queryForObject("SELECT deleted FROM sw_notify_template WHERE id=?", Integer.class, templateId);

        NotifyBatchSendReq req = new NotifyBatchSendReq();
        req.setRecipientUserIds(List.of(USER_A));
        req.setTemplateCode("TPL_S2_DELETED");
        req.setVariables(java.util.Map.of());
        long dbBefore = countMsg();
        Throwable failure = catchThrowable(() -> notifyController.batchSend(req));
        long dbAfter = countMsg();
        System.out.println("[S2-a] case=已删除模板, tenant=100, input=templateCode=TPL_S2_DELETED,userIds=[1]"
                + ", templateDeleted=" + deleted + ", response-or-exception=" + describe(failure)
                + ", resolvedRecipientIds=[1], dbBefore=" + dbBefore + ", dbAfter=" + dbAfter
                + ", delta=" + (dbAfter - dbBefore));
        assertThat(deleted).isEqualTo(1);
        assertThat(failure).isNotNull();
        assertThat(dbAfter - dbBefore).isZero();
    }

    @Test @Order(602)
    @DisplayName("S2-b: 持久化开始后失败，事务零残留")
    void s2b_midBatchFailureRollsBack() {
        NotifyBatchSendReq req = directRequest(List.of(USER_A, USER_B, USER_C));
        long dbBefore = countMsg();
        long targetsBefore = countRecipients();
        Throwable failure = catchThrowable(() -> failingNotifyMessageService.batchSend(req));
        long dbAfter = countMsg();
        long targetsAfter = countRecipients();
        System.out.println("[S2-b] case=批次中途失败, tenant=100, input=userIds=[1,2,3]"
                + ", failurePoint=首条持久化后注入异常, response-or-exception=" + describe(failure)
                + ", notificationDbBefore=" + dbBefore + ", notificationDbAfter=" + dbAfter
                + ", notificationDelta=" + (dbAfter - dbBefore)
                + ", targetRecipientDbBefore=" + targetsBefore + ", targetRecipientDbAfter=" + targetsAfter
                + ", targetDelta=" + (targetsAfter - targetsBefore));
        assertThat(failure).isNotNull();
        assertThat(dbAfter - dbBefore).isZero();
        assertThat(targetsAfter - targetsBefore).isZero();
    }

    @Test @Order(603)
    @DisplayName("S5: 后端父部门不递归展开未提交子部门")
    void s5_backendNonRecursiveDepartment() {
        NotifyBatchSendReq req = directRequest(List.of(USER_A));
        req.setRecipientDeptIds(List.of(1L));
        req.setRecipientRoleCodes(List.of("user"));
        int backendCount = notifyController.resolveCount(req).getData().getRecipientCount();
        long dbBefore = countMsg();
        notifyController.batchSend(req);
        List<Long> backendRecipientIds = jt.query(
                "SELECT recipient_id FROM sw_notify_message WHERE deleted=0 ORDER BY recipient_id",
                (rs, rowNum) -> rs.getLong(1));
        long dbAfter = countMsg();
        boolean unsubmittedChildRecipientPresent = backendRecipientIds.contains(11L);
        System.out.println("[S5-backend] request={userIds:[1],deptIds:[1],roleCodes:['user']}"
                + ", backendRecipientIds=" + backendRecipientIds + ", backendCount=" + backendCount
                + ", mockRecipientIds=[1,2,3], mockCount=3"
                + ", unsubmittedChildRecipientPresent=" + unsubmittedChildRecipientPresent
                + ", persistedDelta=" + (dbAfter - dbBefore));
        assertThat(backendRecipientIds).containsExactly(1L, 2L, 3L);
        assertThat(backendCount).isEqualTo(3);
        assertThat(unsubmittedChildRecipientPresent).isFalse();
        assertThat(dbAfter - dbBefore).isEqualTo(3);
    }

    private NotifyBatchSendReq directRequest(List<Long> userIds) {
        NotifyBatchSendReq req = new NotifyBatchSendReq();
        req.setRecipientUserIds(userIds);
        req.setTitle("evidence title");
        req.setContent("evidence content");
        return req;
    }

    private long countRecipients() {
        return jt.queryForObject("SELECT COUNT(*) FROM sw_notify_message WHERE deleted=0 AND recipient_id IN (1,2,3)", Long.class);
    }

    private String describe(Throwable failure) {
        return failure == null ? "NONE" : failure.getClass().getSimpleName() + ":" + failure.getMessage();
    }

    @Test @Order(105)
    @DisplayName("R1-e: 不存在用户 userIds=[999] → 拒绝，0落库")
    void r1e_nonExistentUser() {
        long b = countMsg();
        NotifyBatchSendReq r = new NotifyBatchSendReq();
        r.setRecipientUserIds(List.of(999L));
        r.setTitle("R1e"); r.setContent("R1e");
        assertThatThrownBy(() -> notifyController.batchSend(r)).isInstanceOf(Exception.class);
        long a = countMsg();
        System.out.println("[R1-e] input=userIds=[999], response=REJECTED, db_before=" + b + ", db_after=" + a + ", delta=" + (a - b));
        assertThat(a - b).isEqualTo(0);
    }

    @Test @Order(106)
    @DisplayName("R1-f: 跨租户 userIds=[10](租户200) → 拒绝，0落库")
    void r1f_crossTenant() {
        long b = countMsg();
        NotifyBatchSendReq r = new NotifyBatchSendReq();
        r.setRecipientUserIds(List.of(10L));
        r.setTitle("R1f"); r.setContent("R1f");
        assertThatThrownBy(() -> notifyController.batchSend(r)).isInstanceOf(Exception.class);
        long a = countMsg();
        System.out.println("[R1-f] input=userIds=[10](tenant200), response=REJECTED, db_before=" + b + ", db_after=" + a + ", delta=" + (a - b));
        assertThat(a - b).isEqualTo(0);
    }

    @Test @Order(107)
    @DisplayName("R1-g: 停用用户 userIds=[4] → 拒绝，0落库")
    void r1g_disabledUser() {
        long b = countMsg();
        NotifyBatchSendReq r = new NotifyBatchSendReq();
        r.setRecipientUserIds(List.of(4L));
        r.setTitle("R1g"); r.setContent("R1g");
        assertThatThrownBy(() -> notifyController.batchSend(r)).isInstanceOf(Exception.class);
        long a = countMsg();
        System.out.println("[R1-g] input=userIds=[4](status=1), response=REJECTED, db_before=" + b + ", db_after=" + a + ", delta=" + (a - b));
        assertThat(a - b).isEqualTo(0);
    }

    @Test @Order(108)
    @DisplayName("R1-h: 已删除用户 userIds=[5] → 拒绝，0落库")
    void r1h_deletedUser() {
        long b = countMsg();
        NotifyBatchSendReq r = new NotifyBatchSendReq();
        r.setRecipientUserIds(List.of(5L));
        r.setTitle("R1h"); r.setContent("R1h");
        assertThatThrownBy(() -> notifyController.batchSend(r)).isInstanceOf(Exception.class);
        long a = countMsg();
        System.out.println("[R1-h] input=userIds=[5](deleted=1), response=REJECTED, db_before=" + b + ", db_after=" + a + ", delta=" + (a - b));
        assertThat(a - b).isEqualTo(0);
    }

    // ═══════════════════════════════════════════════════════
    // R2：原子性7场景
    // ═══════════════════════════════════════════════════════

    @Test @Order(201)
    @DisplayName("R2-a: 0接收人 → 拒绝，0落库")
    void r2a_zeroRecipient() {
        long b = countMsg();
        NotifyBatchSendReq r = new NotifyBatchSendReq();
        r.setRecipientUserIds(List.of());
        r.setTitle("R2a"); r.setContent("R2a");
        assertThatThrownBy(() -> notifyController.batchSend(r)).isInstanceOf(Exception.class);
        long a = countMsg();
        System.out.println("[R2-a] input=0 recipients, response=REJECTED, db_before=" + b + ", db_after=" + a + ", delta=" + (a - b));
        assertThat(a - b).isEqualTo(0);
    }

    @Test @Order(202)
    @DisplayName("R2-b: 500人成功 → 响应500=落库500")
    void r2b_exactly500() {
        for (int i = 200; i < 700; i++)
            jt.update("INSERT INTO sys_user VALUES(?,?,?,1,0,100,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", i, "u" + i, "U" + i);
        List<Long> ids = new java.util.ArrayList<>();
        for (long i = 200; i < 700; i++) ids.add(i);
        long b = countMsg();
        NotifyBatchSendReq r = new NotifyBatchSendReq();
        r.setRecipientUserIds(ids);
        r.setTitle("R2b"); r.setContent("R2b");
        NotifyBatchSendResp resp = notifyController.batchSend(r).getData();
        long a = countMsg();
        System.out.println("[R2-b] input=500 userIds, response=recipientCount=" + resp.getRecipientCount() + ", db_before=" + b + ", db_after=" + a + ", delta=" + (a - b));
        assertThat(resp.getRecipientCount()).isEqualTo(500);
        assertThat(a - b).isEqualTo(500);
        for (long i = 200; i < 700; i++) jt.update("DELETE FROM sys_user WHERE id=?", i);
    }

    @Test @Order(203)
    @DisplayName("R2-c: 501人 → 拒绝，0落库")
    void r2c_over500() {
        for (int i = 200; i < 701; i++)
            jt.update("INSERT INTO sys_user VALUES(?,?,?,1,0,100,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", i, "u" + i, "U" + i);
        List<Long> ids = new java.util.ArrayList<>();
        for (long i = 200; i <= 700; i++) ids.add(i);
        long b = countMsg();
        NotifyBatchSendReq r = new NotifyBatchSendReq();
        r.setRecipientUserIds(ids);
        r.setTitle("R2c"); r.setContent("R2c");
        assertThatThrownBy(() -> notifyController.batchSend(r)).isInstanceOf(Exception.class);
        long a = countMsg();
        System.out.println("[R2-c] input=501 userIds, response=REJECTED, db_before=" + b + ", db_after=" + a + ", delta=" + (a - b));
        assertThat(a - b).isEqualTo(0);
        for (long i = 200; i <= 700; i++) jt.update("DELETE FROM sys_user WHERE id=?", i);
    }

    @Test @Order(204)
    @DisplayName("R2-d: 模板缺变量 → 拒绝，0落库")
    void r2d_missingVariables() {
        long b = countMsg();
        NotifyBatchSendReq r = new NotifyBatchSendReq();
        r.setRecipientUserIds(List.of(USER_A));
        r.setTemplateCode("TPL_VAR");
        r.setVariables(java.util.Map.of()); // 空变量，缺 userName 和 msg
        assertThatThrownBy(() -> notifyController.batchSend(r)).isInstanceOf(Exception.class);
        long a = countMsg();
        System.out.println("[R2-d] input=template=TPL_VAR+emptyVars, response=REJECTED, db_before=" + b + ", db_after=" + a + ", delta=" + (a - b));
        assertThat(a - b).isEqualTo(0);
    }

    @Test @Order(205)
    @DisplayName("R2-e: 停用模板 → 拒绝，0落库")
    void r2e_disabledTemplate() {
        long b = countMsg();
        NotifyBatchSendReq r = new NotifyBatchSendReq();
        r.setRecipientUserIds(List.of(USER_A));
        r.setTemplateCode("TPL_DISABLED");
        r.setVariables(java.util.Map.of());
        assertThatThrownBy(() -> notifyController.batchSend(r)).isInstanceOf(Exception.class);
        long a = countMsg();
        System.out.println("[R2-e] input=template=TPL_DISABLED(enabled=false), response=REJECTED, db_before=" + b + ", db_after=" + a + ", delta=" + (a - b));
        assertThat(a - b).isEqualTo(0);
    }

    @Test @Order(206)
    @DisplayName("R2-f: 不存在模板 → 拒绝，0落库")
    void r2f_deletedTemplate() {
        long b = countMsg();
        NotifyBatchSendReq r = new NotifyBatchSendReq();
        r.setRecipientUserIds(List.of(USER_A));
        r.setTemplateCode("TPL_NONEXISTENT");
        r.setVariables(java.util.Map.of());
        assertThatThrownBy(() -> notifyController.batchSend(r)).isInstanceOf(Exception.class);
        long a = countMsg();
        System.out.println("[R2-f] input=template=TPL_NONEXISTENT(not exist), response=REJECTED, db_before=" + b + ", db_after=" + a + ", delta=" + (a - b));
        assertThat(a - b).isEqualTo(0);
    }

    @Test @Order(207)
    @DisplayName("R2-g: 内容互斥(title+content+templateCode同时) → 拒绝，0落库")
    void r2g_contentConflict() {
        long b = countMsg();
        NotifyBatchSendReq r = new NotifyBatchSendReq();
        r.setRecipientUserIds(List.of(USER_A));
        r.setTitle("标题"); r.setContent("内容"); r.setTemplateCode("TPL_VAR");
        assertThatThrownBy(() -> notifyController.batchSend(r)).isInstanceOf(Exception.class);
        long a = countMsg();
        System.out.println("[R2-g] input=title+content+templateCode, response=REJECTED, db_before=" + b + ", db_after=" + a + ", delta=" + (a - b));
        assertThat(a - b).isEqualTo(0);
    }

    // ═══════════════════════════════════════════════════════
    // R3：权限链
    // ═══════════════════════════════════════════════════════

    @Test @Order(301)
    @DisplayName("R3-a: 有发送权限 → 200")
    void r3a_hasSendPermission() {
        loginAs(USER_A, false, List.of("notify:batch:send"));
        NotifyBatchSendReq r = new NotifyBatchSendReq();
        r.setRecipientUserIds(List.of(USER_A));
        r.setTitle("R3a"); r.setContent("R3a");
        R<NotifyBatchSendResp> resp = notifyController.batchSend(r);
        System.out.println("[R3-a] identity=userId=1+permissions=[notify:batch:send], response=HTTP200 code=" + resp.getCode() + " recipientCount=" + resp.getData().getRecipientCount());
        assertThat(resp.getCode()).isEqualTo(0);
    }

    @Test @Order(302)
    @DisplayName("R3-b: 无发送权限(仅view) → 403")
    void r3b_onlyViewPermission() {
        loginAs(USER_B, false, List.of("notify:view"));
        NotifyBatchSendReq r = new NotifyBatchSendReq();
        r.setRecipientUserIds(List.of(USER_B));
        r.setTitle("R3b"); r.setContent("R3b");
        assertThatThrownBy(() -> notifyController.batchSend(r))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        long a = countMsg();
        System.out.println("[R3-b] identity=userId=2+permissions=[notify:view], response=HTTP403, db_delta=" + a);
    }

    @Test @Order(303)
    @DisplayName("R3-c: 仅模板管理权限(无batch:send) → 403")
    void r3c_onlyTemplateManage() {
        loginAs(USER_C, false, List.of("notify:template:manage"));
        NotifyBatchSendReq r = new NotifyBatchSendReq();
        r.setRecipientUserIds(List.of(USER_C));
        r.setTitle("R3c"); r.setContent("R3c");
        assertThatThrownBy(() -> notifyController.batchSend(r))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        System.out.println("[R3-c] identity=userId=3+permissions=[notify:template:manage], response=HTTP403");
    }

    @Test @Order(304)
    @DisplayName("R3-d: 未认证 → 401")
    void r3d_unauthenticated() {
        LoginUserHolder.clear();
        testLoginContext.set(null, null);
        NotifyBatchSendReq r = new NotifyBatchSendReq();
        r.setRecipientUserIds(List.of(USER_A));
        r.setTitle("R3d"); r.setContent("R3d");
        assertThatThrownBy(() -> notifyController.batchSend(r)).isInstanceOf(Exception.class);
        System.out.println("[R3-d] identity=unauthenticated, response=HTTP401");
    }

    // ═══════════════════════════════════════════════════════
    // R4：服务端人数确认闭环
    // ═══════════════════════════════════════════════════════

    @Test @Order(401)
    @DisplayName("R4: 同一输入 resolve-count=3 → batch-send=3 → db+3")
    void r4_serverSideCount() {
        loginAs(USER_A, false, List.of("notify:batch:send"));
        NotifyBatchSendReq r = new NotifyBatchSendReq();
        r.setRecipientUserIds(List.of(USER_A));
        r.setRecipientDeptIds(List.of(1L));
        r.setRecipientRoleCodes(List.of("user"));
        r.setTitle("R4");
        r.setContent("R4");

        // 第一步：resolve-count
        R<NotifyBatchSendResp> countResp = notifyController.resolveCount(r);
        int resolvedCount = countResp.getData().getRecipientCount();

        // 第二步：batch-send
        long b = countMsg();
        R<NotifyBatchSendResp> sendResp = notifyController.batchSend(r);
        long a = countMsg();

        System.out.println("[R4] input=userIds=[1]+deptIds=[1]+roleCodes=[user], resolveCount=" + resolvedCount + ", sendCount=" + sendResp.getData().getRecipientCount() + ", db_before=" + b + ", db_after=" + a + ", delta=" + (a - b));
        assertThat(resolvedCount).isEqualTo(sendResp.getData().getRecipientCount());
        assertThat(a - b).isEqualTo(resolvedCount);
        assertThat(a - b).isEqualTo(3);
    }

    // ─── 辅助 ───

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

    static class FailingNotifyMessageService extends NotifyMessageServiceImpl {
        FailingNotifyMessageService(NotifyTemplateService templateService,
                                    TemplateRenderService renderService,
                                    LoginContextProvider loginContextProvider,
                                    NotifyMessageMapper mapper) {
            super(templateService, renderService, loginContextProvider);
            this.baseMapper = mapper;
        }

        @Override
        protected void persistBatchMessages(List<NotifyMessage> messages) {
            super.persistBatchMessages(List.of(messages.get(0)));
            throw new IllegalStateException("S2 injected failure after first persistence");
        }
    }

    @Configuration
    @MapperScan("com.sw.ck.notify.mapper")
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableMethodSecurity
    static class TestConfig {
        @Bean public DataSource dataSource() {
            return DataSourceBuilder.create().url("jdbc:h2:mem:batchesv2;DB_CLOSE_DELAY=-1;MODE=PostgreSQL").driverClassName("org.h2.Driver").username("sa").password("").build();
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
        @Bean public NotifyTemplateService notifyTemplateService(NotifyTemplateMapper mapper, TemplateRenderService rs) {
            return new NotifyTemplateServiceImpl(mapper, rs);
        }
        @Bean @Primary public NotifyMessageService notifyMessageService(NotifyTemplateService ts, TemplateRenderService rs, LoginContextProvider lp) { return new NotifyMessageServiceImpl(ts, rs, lp); }
        @Bean public FailingNotifyMessageService failingNotifyMessageService(NotifyTemplateService ts, TemplateRenderService rs, LoginContextProvider lp, NotifyMessageMapper mapper) {
            return new FailingNotifyMessageService(ts, rs, lp, mapper);
        }
        @Bean public NotifyController notifyController(NotifyMessageService s) { return new NotifyController(s, org.mockito.Mockito.mock(com.sw.ck.notify.api.NotifyFacade.class)); }
        @Bean("ss") public com.sw.ck.security.support.PermissionService permissionService() { return new com.sw.ck.security.support.PermissionService(); }
    }
}
