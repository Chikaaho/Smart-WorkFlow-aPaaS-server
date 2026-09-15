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
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.notify.api.NotifyBizType;
import com.sw.ck.notify.api.NotifyFacade;
import com.sw.ck.notify.api.SendNotifyCommand;
import com.sw.ck.notify.entity.NotifyMessage;
import com.sw.ck.notify.impl.NotifyFacadeImpl;
import com.sw.ck.notify.mapper.NotifyMessageMapper;
import com.sw.ck.notify.render.TemplateRenderService;
import com.sw.ck.notify.service.NotifyMessageService;
import com.sw.ck.notify.service.NotifyTemplateService;
import com.sw.ck.notify.service.impl.NotifyMessageServiceImpl;
import com.sw.ck.notify.dto.NotifyTemplateDTO;
import com.sw.ck.notify.dto.NotifyTemplateQuery;
import com.sw.ck.notify.dto.TemplatePreviewRequest;
import com.sw.ck.notify.dto.TemplatePreviewResult;
import com.sw.ck.notify.entity.NotifyTemplate;
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

/**
 * M05 Step 3：通知接收侧控制器集成测试。
 * <p>
 * 测试范围：
 * <ol>
 *   <li>端到端闭合：GET 列表（read=false）→ POST 已读 → 再查 read=true</li>
 *   <li>越权拒绝：不同用户调 read → 抛 BaseException(FORBIDDEN) + 消息仍未读</li>
 *   <li>租户隔离：跨租户 GET 空列表 / POST read 不到（NOT_FOUND）</li>
 *   <li>用户隔离：同租户不同用户 GET 列表不含对方消息</li>
 * </ol>
 * </p>
 */
@SpringBootTest(
        classes = NotifyControllerIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=false",
                "sw.tenant.enabled=true"
        }
)
@DisplayName("M05 Step 3：通知控制器 - 列表 + 已读 + 越权 + 隔离")
class NotifyControllerIntegrationTest {

    private static final Long TENANT_100 = 100L;
    private static final Long TENANT_200 = 200L;
    private static final Long USER_A = 1L;
    private static final Long USER_B = 2L;
    private static final String TITLE = "测试通知";
    private static final String CONTENT = "测试内容";

    @Autowired
    private NotifyController notifyController;

    @Autowired
    private NotifyFacade notifyFacade;

    @Autowired
    private NotifyMessageService notifyMessageService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestLoginContext testLoginContext;

    /** 自增 ID 生成（H2 无 SEQUENCE，测试手填避免冲突） */
    private long nextId = 1000L;

    // ==================== 表创建 ====================

    @BeforeAll
    static void createTables(@Autowired JdbcTemplate jt) {
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_notify_message (
                    id                bigint          not null primary key,
                    create_time       timestamp       not null default current_timestamp,
                    create_by         bigint,
                    update_time       timestamp       not null default current_timestamp,
                    update_by         bigint,
                    deleted           smallint        not null default 0,
                    tenant_id         bigint          not null default 0,
                    version           bigint          not null default 0,
                    recipient_id      bigint          not null,
                    title             varchar(200)    not null,
                    content           text            not null,
                    biz_type          varchar(30)     not null,
                    biz_id            varchar(64),
                    is_read           boolean         not null default false,
                    channel           varchar(40)     not null default 'IN_APP',
                    delivery_status   varchar(20)     not null default 'SUCCESS',
                    external_message_id varchar(200),
                    failure_reason    varchar(500),
                    idempotency_key   varchar(200)
                )
                """);

        // I6 收口新增列（IF NOT EXISTS 双方言兼容；存量 schema 与生产迁移同语义）
        try { jt.execute("ALTER TABLE sw_notify_message ADD COLUMN IF NOT EXISTS event_type varchar(40) not null default 'SYSTEM'"); } catch (Exception ignored) { /* schema already migrated */ }
        try { jt.execute("ALTER TABLE sw_notify_message ADD COLUMN IF NOT EXISTS occurrence_no bigint not null default 1"); } catch (Exception ignored) { /* schema already migrated */ }
        try { jt.execute("ALTER TABLE sw_notify_message ADD COLUMN IF NOT EXISTS template_id bigint"); } catch (Exception ignored) { /* schema already migrated */ }
        try { jt.execute("ALTER TABLE sw_notify_message ADD COLUMN IF NOT EXISTS template_version int"); } catch (Exception ignored) { /* schema already migrated */ }
        try { jt.execute("ALTER TABLE sw_notify_message ADD COLUMN IF NOT EXISTS link_type varchar(32)"); } catch (Exception ignored) { /* schema already migrated */ }
        try { jt.execute("ALTER TABLE sw_notify_message ADD COLUMN IF NOT EXISTS link_id varchar(64)"); } catch (Exception ignored) { /* schema already migrated */ }
        try { jt.execute("ALTER TABLE sw_notify_message ADD COLUMN IF NOT EXISTS retry_count int not null default 0"); } catch (Exception ignored) { /* schema already migrated */ }
        try { jt.execute("ALTER TABLE sw_notify_message ADD COLUMN IF NOT EXISTS next_retry_time timestamp"); } catch (Exception ignored) { /* schema already migrated */ }
        try { jt.execute("ALTER TABLE sw_notify_message ADD COLUMN IF NOT EXISTS failure_class varchar(40)"); } catch (Exception ignored) { /* schema already migrated */ }
        try { jt.execute("ALTER TABLE sw_notify_message ADD COLUMN IF NOT EXISTS receipt_digest varchar(200)"); } catch (Exception ignored) { /* schema already migrated */ }
    }

    // ==================== 前置/后置 ====================

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM sw_notify_message");
        testLoginContext.set(TENANT_100, USER_A);
        LoginUser userA = new LoginUser();
        userA.setUserId(USER_A);
        userA.setTenantId(TENANT_100);
        userA.setUsername("user_a");
        LoginUserHolder.set(userA);
    }

    @AfterEach
    void tearDown() {
        testLoginContext.set(null, null);
        LoginUserHolder.clear();
    }

    // ==================== 辅助方法 ====================

    /**
     * 在当前测试上下文中发送一条通知，
     * 返回刚插入行的 ID（通过 recipient + biz_type 回查）。
     */
    private long sendNotify(Long recipientId) {
        notifyFacade.send(new SendNotifyCommand(
                recipientId, TITLE, CONTENT, NotifyBizType.WF_TODO, "biz_001", TENANT_100));
        List<NotifyMessage> all = notifyMessageService.findByRecipient(recipientId);
        assertThat(all).as("通知应发送成功").isNotEmpty();
        return all.get(0).getId();
    }

    /**
     * 在指定租户/发起人上下文中发送一条通知（用于跨租户场景）。
     * 注意：会改变 testLoginContext 和 LoginUserHolder。
     */
    private long sendNotifyAs(Long tenantId, Long senderId, Long recipientId) {
        testLoginContext.set(tenantId, senderId);
        LoginUser sender = new LoginUser();
        sender.setUserId(senderId);
        sender.setTenantId(tenantId);
        LoginUserHolder.set(sender);

        notifyFacade.send(new SendNotifyCommand(
                recipientId, TITLE, CONTENT, NotifyBizType.WF_TODO, "biz_001", tenantId));

        // 按 recipient + tenant 查询刚插入的行
        List<NotifyMessage> all = notifyMessageService.findByRecipient(recipientId);
        assertThat(all).as("通知应发送成功（tenant=%s）", tenantId).isNotEmpty();
        long id = all.get(0).getId();
        assertThat(all.get(0).getTenantId()).as("tenant_id 应与传入一致").isEqualTo(tenantId);
        return id;
    }

    // ==================== 测试 1：端到端闭合 ====================

    @Test
    @DisplayName("GET 列表(read=false) → POST 已读 → 再查 read=true")
    void e2e_closedLoop() {
        // Arrange：发送一条给 USER_A 的通知
        long msgId = sendNotify(USER_A);

        // Act 1：GET 列表
        R<List<NotifyMessage>> listResp = notifyController.messages(null, null);
        List<NotifyMessage> msgs = listResp.getData();
        assertThat(msgs)
                .as("当前用户应看到 1 条通知")
                .hasSize(1);
        NotifyMessage msg = msgs.get(0);
        assertThat(msg.getId()).isEqualTo(msgId);
        assertThat(msg.getTitle()).isEqualTo(TITLE);
        assertThat(msg.getContent()).isEqualTo(CONTENT);
        assertThat(msg.getRead())
                .as("新通知 read 应为 false")
                .isFalse();

        // Act 2：标记已读
        R<Void> readResp = notifyController.read(msgId);
        assertThat(readResp.getCode())
                .as("标记已读应返回成功码")
                .isEqualTo(R.SUCCESS_CODE);

        // Assert：查库确认已读
        NotifyMessage updated = notifyMessageService.getById(msgId);
        assertThat(updated.getRead())
                .as("标记已读后 read 应为 true")
                .isTrue();

        System.out.println("=== 端到端闭合验证 ===");
        System.out.println("  msgId=" + msgId + ", beforeRead=" + msg.getRead()
                + ", afterRead=" + updated.getRead() + " ✓");
    }

    // ==================== 测试 2：越权拒绝 ====================

    @Test
    @DisplayName("USER_B 调 read(USER_A 的消息) → BaseException + 消息仍未读")
    void read_withWrongRecipient_shouldThrow() {
        // Arrange：USER_A 发一条通知给自己
        long msgId = sendNotify(USER_A);

        // Act：切换为 USER_B（同租户 TENANT_100）
        // testLoginContext 保持 TENANT_100/USER_A（让读操作能查到消息行）
        LoginUser userB = new LoginUser();
        userB.setUserId(USER_B);
        userB.setTenantId(TENANT_100);
        userB.setUsername("user_b");
        LoginUserHolder.set(userB);

        // Assert：抛 FORBIDDEN
        assertThatThrownBy(() -> notifyController.read(msgId))
                .as("USER_B 调 read(USER_A 的消息) 应抛 BaseException")
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("无权");

        // Assert：消息仍为未读
        testLoginContext.set(TENANT_100, USER_A);
        LoginUser userA = new LoginUser();
        userA.setUserId(USER_A);
        userA.setTenantId(TENANT_100);
        LoginUserHolder.set(userA);

        NotifyMessage msg = notifyMessageService.getById(msgId);
        assertThat(msg.getRead())
                .as("越权调用不应改变已读状态")
                .isFalse();

        System.out.println("=== 越权拒绝验证 ===");
        System.out.println("  msgId=" + msgId + ", recipientId=" + msg.getRecipientId()
                + ", read=" + msg.getRead() + " ✓");
    }

    // ==================== 测试 3：租户隔离 ====================

    @Test
    @DisplayName("跨租户 GET 空列表 / POST read 不到(NOT_FOUND)")
    void tenantIsolation_getAndRead() {
        // Arrange：在 TENANT_100 下发一条给 USER_A 的通知
        long msgId = sendNotifyAs(TENANT_100, USER_A, USER_A);

        // Act：切换到 TENANT_200 上下文
        testLoginContext.set(TENANT_200, USER_B);
        LoginUser tenant200User = new LoginUser();
        tenant200User.setUserId(USER_B);
        tenant200User.setTenantId(TENANT_200);
        LoginUserHolder.set(tenant200User);

        // Assert 1：GET 列表 → 空（租户隔离：查不到 TENANT_100 的行）
        R<List<NotifyMessage>> listResp = notifyController.messages(null, null);
        assertThat(listResp.getData())
                .as("TENANT_200 不应看到 TENANT_100 的通知")
                .isEmpty();

        // Assert 2：POST read → NOT_FOUND（租户隔离：getById 也被拦截器过滤）
        assertThatThrownBy(() -> notifyController.read(msgId))
                .as("跨租户 read 应抛 BaseException")
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("不存在");

        // Assert 3：原消息仍为未读（确认未被误操作）
        testLoginContext.set(TENANT_100, USER_A);
        LoginUser origUser = new LoginUser();
        origUser.setUserId(USER_A);
        origUser.setTenantId(TENANT_100);
        LoginUserHolder.set(origUser);

        NotifyMessage msg = notifyMessageService.getById(msgId);
        assertThat(msg.getRead())
                .as("跨租户调用不应改变已读状态")
                .isFalse();

        System.out.println("=== 租户隔离验证 ===");
        System.out.println("  msgId=" + msgId + ", tenantId=" + msg.getTenantId()
                + ", listByT200=0, read→NOT_FOUND, origRead=" + msg.getRead() + " ✓");
    }

    // ==================== 测试 4：用户隔离 ====================

    @Test
    @DisplayName("同租户不同用户 GET 列表 → 不含对方消息")
    void userIsolation_getList() {
        // Arrange：USER_A 发一条通知给自己
        sendNotify(USER_A);

        // Act：切换为 USER_B（同租户 TENANT_100）
        testLoginContext.set(TENANT_100, USER_B);
        LoginUser userB = new LoginUser();
        userB.setUserId(USER_B);
        userB.setTenantId(TENANT_100);
        userB.setUsername("user_b");
        LoginUserHolder.set(userB);

        // Assert：USER_B 的 GET 列表为空（recipient_id != USER_B）
        R<List<NotifyMessage>> listResp = notifyController.messages(null, null);
        assertThat(listResp.getData())
                .as("USER_B 不应看到 USER_A 的通知")
                .isEmpty();

        System.out.println("=== 用户隔离验证 ===");
        System.out.println("  USER_B list count=" + listResp.getData().size() + " ✓");
    }

    // ==================== 测试 5：删除通知 ====================

    @Test
    @DisplayName("DELETE 通知 → 逻辑删除 → GET 列表不再出现")
    void delete_e2e() {
        // Arrange：发送一条通知给 USER_A
        long msgId = sendNotify(USER_A);

        // Act：删除
        R<Void> deleteResp = notifyController.delete(msgId);
        assertThat(deleteResp.getCode())
                .as("删除应返回成功码")
                .isEqualTo(R.SUCCESS_CODE);

        // Assert：GET 列表不再包含该通知
        R<List<NotifyMessage>> listResp = notifyController.messages(null, null);
        assertThat(listResp.getData())
                .as("删除后列表不应包含该通知")
                .isEmpty();

        // Assert：getById 返回 null（逻辑删除生效）
        NotifyMessage deleted = notifyMessageService.getById(msgId);
        assertThat(deleted)
                .as("逻辑删除后 getById 应返回 null")
                .isNull();

        System.out.println("=== 删除通知验证 ===");
        System.out.println("  msgId=" + msgId + ", delete=OK, listCount=0 ✓");
    }

    // ==================== 测试 6：越权删除 ====================

    @Test
    @DisplayName("USER_B 调 delete(USER_A 的消息) → BaseException + 消息仍存在")
    void delete_withWrongRecipient_shouldThrow() {
        // Arrange：USER_A 发一条通知给自己
        long msgId = sendNotify(USER_A);

        // Act：切换为 USER_B（同租户 TENANT_100）
        LoginUser userB = new LoginUser();
        userB.setUserId(USER_B);
        userB.setTenantId(TENANT_100);
        userB.setUsername("user_b");
        LoginUserHolder.set(userB);

        // Assert：抛 FORBIDDEN
        assertThatThrownBy(() -> notifyController.delete(msgId))
                .as("USER_B 调 delete(USER_A 的消息) 应抛 BaseException")
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("无权");

        // Assert：消息仍存在且未读
        testLoginContext.set(TENANT_100, USER_A);
        LoginUser userA = new LoginUser();
        userA.setUserId(USER_A);
        userA.setTenantId(TENANT_100);
        LoginUserHolder.set(userA);

        NotifyMessage msg = notifyMessageService.getById(msgId);
        assertThat(msg)
                .as("越权调用不应删除消息")
                .isNotNull();
        assertThat(msg.getRead())
                .as("越权调用不应改变已读状态")
                .isFalse();

        System.out.println("=== 越权删除拒绝验证 ===");
        System.out.println("  msgId=" + msgId + ", recipientId=" + msg.getRecipientId()
                + ", read=" + msg.getRead() + " ✓");
    }

    // ==================== 测试 7：跨租户删除 ====================

    @Test
    @DisplayName("跨租户 DELETE → NOT_FOUND（租户隔离）")
    void delete_tenantIsolation() {
        // Arrange：在 TENANT_100 下发一条给 USER_A 的通知
        long msgId = sendNotifyAs(TENANT_100, USER_A, USER_A);

        // Act：切换到 TENANT_200 上下文
        testLoginContext.set(TENANT_200, USER_B);
        LoginUser tenant200User = new LoginUser();
        tenant200User.setUserId(USER_B);
        tenant200User.setTenantId(TENANT_200);
        LoginUserHolder.set(tenant200User);

        // Assert：DELETE → NOT_FOUND（租户隔离：getById 被拦截器过滤）
        assertThatThrownBy(() -> notifyController.delete(msgId))
                .as("跨租户 delete 应抛 BaseException")
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("不存在");

        // Assert：原消息仍存在
        testLoginContext.set(TENANT_100, USER_A);
        LoginUser origUser = new LoginUser();
        origUser.setUserId(USER_A);
        origUser.setTenantId(TENANT_100);
        LoginUserHolder.set(origUser);

        NotifyMessage msg = notifyMessageService.getById(msgId);
        assertThat(msg)
                .as("跨租户调用不应删除消息")
                .isNotNull();

        System.out.println("=== 跨租户删除隔离验证 ===");
        System.out.println("  msgId=" + msgId + ", tenantId=" + msg.getTenantId() + " ✓");
    }

    // ==================== 测试 8：查询过滤 - 已读状态 ====================

    @Test
    @DisplayName("GET ?read=false → 仅未读；GET ?read=true → 仅已读")
    void filterByReadStatus() {
        // Arrange：发两条通知给 USER_A
        long msgId1 = sendNotify(USER_A);
        // 第二条用不同 bizId 避免 findByRecipient 返回顺序问题
        notifyFacade.send(new SendNotifyCommand(
                USER_A, "第二条", "内容2", NotifyBizType.WF_APPROVED, "biz_002", TENANT_100));
        List<NotifyMessage> all = notifyMessageService.findByRecipient(USER_A);
        assertThat(all).as("应有 2 条通知").hasSize(2);
        long msgId2 = all.stream()
                .filter(m -> "biz_002".equals(m.getBizId()))
                .findFirst().orElseThrow().getId();

        // Act：标记第二条为已读
        notifyController.read(msgId2);

        // Assert 1：read=false → 仅 1 条未读
        R<List<NotifyMessage>> unreadResp = notifyController.messages(false, null);
        assertThat(unreadResp.getData())
                .as("read=false 应返回 1 条未读")
                .hasSize(1);
        assertThat(unreadResp.getData().get(0).getId()).isEqualTo(msgId1);

        // Assert 2：read=true → 仅 1 条已读
        R<List<NotifyMessage>> readResp = notifyController.messages(true, null);
        assertThat(readResp.getData())
                .as("read=true 应返回 1 条已读")
                .hasSize(1);
        assertThat(readResp.getData().get(0).getId()).isEqualTo(msgId2);

        // Assert 3：read=null → 2 条全部
        R<List<NotifyMessage>> allResp = notifyController.messages(null, null);
        assertThat(allResp.getData())
                .as("read=null 应返回全部 2 条")
                .hasSize(2);

        System.out.println("=== 已读状态过滤验证 ===");
        System.out.println("  unread=" + unreadResp.getData().size()
                + ", read=" + readResp.getData().size()
                + ", all=" + allResp.getData().size() + " ✓");
    }

    // ==================== 测试 9：查询过滤 - 关键词 ====================

    @Test
    @DisplayName("GET ?keyword=审批 → 仅匹配标题或内容包含关键词的通知")
    void filterByKeyword() {
        // Arrange：发三条通知
        notifyFacade.send(new SendNotifyCommand(
                USER_A, "请假审批通知", "张三提交了请假申请", NotifyBizType.WF_TODO, "biz_k1", TENANT_100));
        notifyFacade.send(new SendNotifyCommand(
                USER_A, "报销通知", "李四提交了报销单", NotifyBizType.WF_TODO, "biz_k2", TENANT_100));
        notifyFacade.send(new SendNotifyCommand(
                USER_A, "系统公告", "审批流程已更新", NotifyBizType.WF_APPROVED, "biz_k3", TENANT_100));

        // Act：keyword=审批 → 应匹配标题含"审批"的 1 条 + 内容含"审批"的 1 条 = 2 条
        R<List<NotifyMessage>> resp = notifyController.messages(null, "审批");
        assertThat(resp.getData())
                .as("keyword=审批 应匹配 2 条（标题1 + 内容1）")
                .hasSize(2);

        // Act：keyword=报销 → 应匹配 1 条
        R<List<NotifyMessage>> resp2 = notifyController.messages(null, "报销");
        assertThat(resp2.getData())
                .as("keyword=报销 应匹配 1 条")
                .hasSize(1);

        // Act：keyword=不存在 → 空
        R<List<NotifyMessage>> resp3 = notifyController.messages(null, "不存在的内容");
        assertThat(resp3.getData())
                .as("keyword=不存在 应返回空")
                .isEmpty();

        System.out.println("=== 关键词过滤验证 ===");
        System.out.println("  审批=" + resp.getData().size()
                + ", 报销=" + resp2.getData().size()
                + ", 不存在=" + resp3.getData().size() + " ✓");
    }

    // ==================== 测试 10：组合过滤 ====================

    @Test
    @DisplayName("GET ?read=false&keyword=请假 → 未读且含关键词")
    void filterCombined() {
        // Arrange：发两条通知
        notifyFacade.send(new SendNotifyCommand(
                USER_A, "请假审批通知", "张三提交了请假申请", NotifyBizType.WF_TODO, "biz_c1", TENANT_100));
        notifyFacade.send(new SendNotifyCommand(
                USER_A, "请假审批通知2", "另一条请假", NotifyBizType.WF_TODO, "biz_c2", TENANT_100));

        // 标记第二条为已读
        List<NotifyMessage> all = notifyMessageService.findByRecipient(USER_A);
        long readId = all.stream()
                .filter(m -> "biz_c2".equals(m.getBizId()))
                .findFirst().orElseThrow().getId();
        notifyController.read(readId);

        // Act：read=false + keyword=请假 → 1 条（仅未读且含请假）
        R<List<NotifyMessage>> resp = notifyController.messages(false, "请假");
        assertThat(resp.getData())
                .as("read=false&keyword=请假 应返回 1 条")
                .hasSize(1);

        // Act：read=true + keyword=请假 → 1 条（仅已读且含请假）
        R<List<NotifyMessage>> resp2 = notifyController.messages(true, "请假");
        assertThat(resp2.getData())
                .as("read=true&keyword=请假 应返回 1 条")
                .hasSize(1);

        System.out.println("=== 组合过滤验证 ===");
        System.out.println("  unread+请假=" + resp.getData().size()
                + ", read+请假=" + resp2.getData().size() + " ✓");
    }

    // ==================== LoginContextProvider 测试实现 ====================

    static class TestLoginContext implements LoginContextProvider {

        private volatile Long currentUserId;
        private volatile Long currentTenantId;

        void set(Long tenantId, Long userId) {
            this.currentTenantId = tenantId;
            this.currentUserId = userId;
        }

        @Override
        public Long getUserId() { return currentUserId; }

        @Override
        public Long getTenantId() { return currentTenantId; }

        @Override
        public Long getDeptId() { return null; }

        @Override
        public DataScopeType getDataScopeType() { return DataScopeType.ALL; }

        @Override
        public Set<Long> getCustomDeptIds() { return Set.of(); }

        @Override
        public boolean isSuperAdmin() { return false; }
    }

    // ==================== 组合测试配置 ====================

    @Configuration
    @MapperScan("com.sw.ck.notify.mapper")
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:notifyctrl;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
                    .driverClassName("org.h2.Driver")
                    .username("sa")
                    .password("")
                    .build();
        }

        @Bean
        public JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        public PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        public TestLoginContext testLoginContext() {
            return new TestLoginContext();
        }

        @Bean
        public CommonMetaObjectHandler commonMetaObjectHandler(
                LoginContextProvider loginContextProvider) {
            return new CommonMetaObjectHandler(loginContextProvider);
        }

        @Bean
        public TenantProperties tenantProperties() {
            return new TenantProperties();
        }

        @Bean
        public TenantLineInnerInterceptor tenantLineInnerInterceptor(
                TenantProperties tenantProperties,
                LoginContextProvider loginContextProvider) {
            return new TenantLineInnerInterceptor(
                    new CommonTenantLineHandler(tenantProperties, loginContextProvider));
        }

        @Bean
        public MybatisPlusInterceptor mybatisPlusInterceptor(
                TenantLineInnerInterceptor tenantLineInnerInterceptor) {
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(tenantLineInnerInterceptor);
            interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
            return interceptor;
        }

        @Bean
        public org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                CommonMetaObjectHandler metaObjectHandler,
                MybatisPlusInterceptor interceptor) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setTypeAliasesPackage("com.sw.ck.notify.entity");
            MybatisConfiguration ibatisConfig = new MybatisConfiguration();
            ibatisConfig.setMapUnderscoreToCamelCase(true);
            ibatisConfig.setUseGeneratedKeys(true);
            factory.setConfiguration(ibatisConfig);
            GlobalConfig globalConfig = new GlobalConfig();
            GlobalConfig.DbConfig dbConfig = new GlobalConfig.DbConfig();
            dbConfig.setLogicDeleteField("deleted");
            dbConfig.setLogicDeleteValue("1");
            dbConfig.setLogicNotDeleteValue("0");
            globalConfig.setDbConfig(dbConfig);
            globalConfig.setMetaObjectHandler(metaObjectHandler);
            factory.setGlobalConfig(globalConfig);
            factory.setPlugins(interceptor);
            return factory.getObject();
        }

        @Bean
        public TemplateRenderService templateRenderService() {
            return new TemplateRenderService();
        }

        @Bean
        public NotifyTemplateService notifyTemplateService() {
            return new NotifyTemplateService() {
                @Override
                public PageResult<NotifyTemplateDTO> pageTemplates(NotifyTemplateQuery query) { return null; }
                @Override
                public NotifyTemplateDTO getTemplate(Long id) { return null; }
                @Override
                public Long createTemplate(NotifyTemplateDTO dto) { return null; }
                @Override
                public void updateTemplate(Long id, NotifyTemplateDTO dto) {}
                @Override
                public void deleteTemplate(Long id) {}
                @Override
                public void toggleTemplate(Long id, boolean enabled) {}
                @Override
                public TemplatePreviewResult renderPreview(TemplatePreviewRequest request) { return null; }
                @Override
                public TemplatePreviewResult previewByCode(String templateCode, java.util.Map<String, String> variables) { return null; }
                @Override
                public java.util.Set<String> extractVariables(String titleTemplate, String contentTemplate) { return java.util.Set.of(); }
                @Override
                public NotifyTemplate getEnabledByCode(String code) { return null; }
            };
        }

        @Bean
        public NotifyMessageService notifyMessageService(NotifyTemplateService notifyTemplateService,
                                                         TemplateRenderService templateRenderService,
                                                         LoginContextProvider loginContextProvider) {
            return new NotifyMessageServiceImpl(notifyTemplateService, templateRenderService, loginContextProvider);
        }

        @Bean
        public NotifyFacade notifyFacade(NotifyMessageService notifyMessageService) {
            return new NotifyFacadeImpl(notifyMessageService);
        }

        @Bean
        public NotifyController notifyController(
                NotifyMessageService notifyMessageService) {
            return new NotifyController(notifyMessageService, org.mockito.Mockito.mock(com.sw.ck.notify.api.NotifyFacade.class));
        }
    }
}
