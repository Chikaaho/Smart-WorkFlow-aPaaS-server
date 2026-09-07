package com.sw.ck.notify.controller;

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
import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.notify.api.NotifyBizType;
import com.sw.ck.notify.api.SendNotifyCommand;
import com.sw.ck.notify.dto.NotifyTemplateDTO;
import com.sw.ck.notify.dto.NotifyTemplateQuery;
import com.sw.ck.notify.dto.TemplatePreviewRequest;
import com.sw.ck.notify.dto.TemplatePreviewResult;
import com.sw.ck.notify.entity.NotifyMessage;
import com.sw.ck.notify.entity.NotifyTemplate;
import com.sw.ck.notify.impl.NotifyFacadeImpl;
import com.sw.ck.notify.mapper.NotifyMessageMapper;
import com.sw.ck.notify.mapper.NotifyTemplateMapper;
import com.sw.ck.notify.render.TemplateRenderException;
import com.sw.ck.notify.render.TemplateRenderService;
import com.sw.ck.notify.service.NotifyMessageService;
import com.sw.ck.notify.service.NotifyTemplateService;
import com.sw.ck.notify.service.impl.NotifyMessageServiceImpl;
import com.sw.ck.notify.service.impl.NotifyTemplateServiceImpl;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.apache.ibatis.session.SqlSessionFactory;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P36 / M05-F02-01 消息模板集成测试。
 * <p>
 * 测试装配与 {@link NotifyControllerIntegrationTest} 同构（H2 MODE=PostgreSQL
 * 内存库 + 真实 TenantLine/OptimisticLocker/逻辑删除拦截器链 + TestLoginContext），
 * 服务组合经 {@link NotifyTemplateServiceImpl#requireEnabledByCode} +
 * {@link TemplateRenderService} + {@link NotifyFacadeImpl} 复现生产发送链。
 * </p>
 *
 * <p>覆盖方向 §8 后端行为面：</p>
 * <ol>
 *   <li>CRUD 闭环（创建/查询/更新/启停/幂等逻辑删除）；</li>
 *   <li>同租户模板代码唯一（应用层拒绝 + DB 唯一索引兜底），软删后可重建；</li>
 *   <li>租户隔离：跨租户同代码互不可见、发送按本租户解析；</li>
 *   <li>${var} 渲染正确，预览与真实落库内容逐字一致；</li>
 *   <li>缺变量/非法占位符/停用/删除模板在落库前拒绝，无半成品通知；</li>
 *   <li>额外变量不改变结果；变量值含 ${...} 与反斜杠不被二次解释；</li>
 *   <li>模板编辑/停用/删除后历史通知标题正文保持不变。</li>
 * </ol>
 */
@SpringBootTest(
        classes = NotifyTemplateIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=false",
                "sw.tenant.enabled=true"
        }
)
@DisplayName("P36 M05-F02-01：消息模板 - CRUD/唯一性/租户/渲染/落库前拒绝/历史稳定")
class NotifyTemplateIntegrationTest {

    private static final Long TENANT_100 = 100L;
    private static final Long TENANT_200 = 200L;
    private static final Long USER_A = 1L;
    private static final Long RECIPIENT = 9L;

    @Autowired
    private NotifyTemplateService templateService;

    @Autowired
    private NotifyTemplateServiceImpl templateServiceImpl;

    @Autowired
    private TemplateRenderService renderService;

    @Autowired
    private NotifyFacadeImpl notifyFacade;

    @Autowired
    private NotifyMessageService notifyMessageService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestLoginContext testLoginContext;

    /** 自增 ID（H2 无 SEQUENCE，手填避免冲突——既有测试惯例） */
    private long nextId = 5000L;

    // ==================== 表创建 ====================

    @BeforeAll
    static void createTables(@Autowired JdbcTemplate jt) {
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_notify_template (
                    id                bigint not null primary key,
                    create_time       timestamp,
                    create_by         varchar(64),
                    update_time       timestamp,
                    update_by         varchar(64),
                    deleted           smallint not null default 0,
                    tenant_id         bigint not null default 0,
                    version           bigint not null default 0,
                    template_code     varchar(100) not null,
                    name              varchar(100) not null,
                    title_template    varchar(200) not null,
                    content_template  text not null,
                    enabled           smallint not null default 1,
                    remark            varchar(500)
                )
                """);
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_notify_message (
                    id                bigint not null primary key,
                    create_time       timestamp not null default current_timestamp,
                    create_by         bigint,
                    update_time       timestamp not null default current_timestamp,
                    update_by         bigint,
                    deleted           smallint not null default 0,
                    tenant_id         bigint not null default 0,
                    version           bigint not null default 0,
                    recipient_id      bigint not null,
                    title             varchar(200) not null,
                    content           text not null,
                    biz_type          varchar(30) not null,
                    biz_id            varchar(64),
                    is_read           boolean not null default false,
                    channel           varchar(40) not null default 'IN_APP',
                    delivery_status   varchar(20) not null default 'SUCCESS',
                    external_message_id varchar(200),
                    failure_reason    varchar(500),
                    idempotency_key   varchar(200)
                )
                """);
        jt.execute("DROP INDEX IF EXISTS uk_sw_notify_template_tenant_code");
        jt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_notify_template_tenant_code "
                + "ON sw_notify_template (tenant_id, template_code, deleted)");
    }

    // ==================== 前置/后置 ====================

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM sw_notify_template");
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

    // ==================== 辅助 ====================

    /** 直插模板行（绕过服务校验，构造边界数据） */
    private long insertTemplate(Long tenantId, String code, String titleTpl,
                                String contentTpl, int enabled) {
        long id = nextId++;
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO sw_notify_template (id, create_time, update_time, deleted, tenant_id, version, "
                        + "template_code, name, title_template, content_template, enabled) VALUES (?, ?, ?, 0, ?, 0, ?, ?, ?, ?, ?)",
                id, now, now, tenantId, code, "模板" + code, titleTpl, contentTpl, enabled);
        return id;
    }

    private Map<String, String> vars(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private NotifyTemplateQuery newQuery(String keyword) {
        NotifyTemplateQuery q = new NotifyTemplateQuery();
        q.setKeyword(keyword);
        q.setPageNum(1);
        q.setPageSize(10);
        return q;
    }

    /** 生产同源发送链：取启用模板 → 渲染 → 落库（含 Controller.send 的异常转译）。 */
    private long sendViaTemplate(String code, Map<String, String> variables) {
        NotifyTemplate t = templateServiceImpl.requireEnabledByCode(code);
        String title;
        String content;
        try {
            title = renderService.render(t.getTitleTemplate(), variables);
            content = renderService.render(t.getContentTemplate(), variables);
        } catch (TemplateRenderException e) {
            // 与 NotifyTemplateController.send 相同的落库前转译
            throw new BaseException(400, e.getMessage());
        }
        SendNotifyCommand cmd = new SendNotifyCommand(RECIPIENT, title, content,
                NotifyBizType.SYSTEM, t.getTemplateCode(), TENANT_100);
        notifyFacade.send(cmd);
        List<NotifyMessage> all = notifyMessageService.findByRecipient(RECIPIENT);
        assertThat(all).as("模板通知应落库").isNotEmpty();
        return all.stream().filter(m -> code.equals(m.getBizId()))
                .findFirst().orElseThrow().getId();
    }

    private int countMessages() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_notify_message", Integer.class);
        return n == null ? 0 : n;
    }

    private Long activeTemplateId(String code) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM sw_notify_template WHERE template_code=? AND deleted=0", Long.class, code);
    }

    private NotifyTemplateDTO dtoOf(String code, String name, String title, String content) {
        NotifyTemplateDTO dto = new NotifyTemplateDTO();
        dto.setTemplateCode(code);
        dto.setName(name);
        dto.setTitleTemplate(title);
        dto.setContentTemplate(content);
        dto.setEnabled(true);
        return dto;
    }

    private TemplatePreviewRequest previewReq(String title, String content, Map<String, String> variables) {
        TemplatePreviewRequest req = new TemplatePreviewRequest();
        req.setTitleTemplate(title);
        req.setContentTemplate(content);
        req.setVariables(variables);
        return req;
    }

    // ==================== 1. CRUD 闭环 ====================

    @Test
    @DisplayName("CRUD 闭环：新建→查询→更新→启停→幂等逻辑删除")
    void crudLifecycle() {
        NotifyTemplateDTO dto = dtoOf("WELCOME_MAIL", "欢迎通知",
                "你好 ${userName}", "亲爱的 ${userName}，欢迎使用本系统。");
        Long id = templateService.createTemplate(dto);
        assertThat(id).isNotNull();

        NotifyTemplateDTO got = templateService.getTemplate(id);
        assertThat(got.getTemplateCode()).isEqualTo("WELCOME_MAIL");
        assertThat(got.getTitleTemplate()).isEqualTo("你好 ${userName}");
        assertThat(got.getEnabled()).isTrue();

        // 更新（代码不变）
        NotifyTemplateDTO upd = dtoOf("WELCOME_MAIL", "欢迎通知V2",
                "你好 ${userName}", "亲爱的 ${userName}，欢迎回来。");
        templateService.updateTemplate(id, upd);
        assertThat(templateService.getTemplate(id).getContentTemplate())
                .isEqualTo("亲爱的 ${userName}，欢迎回来。");

        // 启停切换
        templateService.toggleTemplate(id, false);
        assertThat(templateService.getTemplate(id).getEnabled()).isFalse();
        templateService.toggleTemplate(id, true);

        // 幂等删除
        templateService.deleteTemplate(id);
        templateService.deleteTemplate(id);
        assertThatThrownBy(() -> templateService.getTemplate(id))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("消息模板不存在");

        System.out.println("[P36] CRUD 闭环 ✓ id=" + id);
    }

    @Test
    @DisplayName("编辑试图变更 templateCode 被拒绝（稳定标识不可变）")
    void updateCannotChangeCode() {
        insertTemplate(TENANT_100, "CODE_LOCK", "标题", "正文", 1);
        NotifyTemplateDTO upd = dtoOf("NEW_CODE", "x", "t", "c");
        Long id = activeTemplateId("CODE_LOCK");
        assertThatThrownBy(() -> templateService.updateTemplate(id, upd))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("模板代码不可变更");
    }

    @Test
    @DisplayName("分页列表：keyword 匹配代码或名称，enabled 过滤生效")
    void pageList_filtering() {
        insertTemplate(TENANT_100, "LIST_A", "t", "c", 1);
        insertTemplate(TENANT_100, "LIST_B", "t", "c", 0);

        var byKeyword = templateService.pageTemplates(newQuery("LIST"));
        assertThat(byKeyword.getTotal()).isEqualTo(2);

        var onlyEnabled = new NotifyTemplateQuery();
        onlyEnabled.setEnabled(true);
        onlyEnabled.setPageNum(1);
        onlyEnabled.setPageSize(10);
        var enabledPage = templateService.pageTemplates(onlyEnabled);
        assertThat(enabledPage.getTotal()).isEqualTo(1);
        assertThat(enabledPage.getRecords().get(0).getTemplateCode()).isEqualTo("LIST_A");
    }

    // ==================== 2. 唯一性与软删重建 ====================

    @Test
    @DisplayName("同租户重复代码被应用层拒绝 + DB 唯一索引兜底；软删后同代码可重建")
    void duplicateCode_rejected_rebuildAfterSoftDelete_ok() {
        templateService.createTemplate(dtoOf("DUP_CODE", "A", "t", "c"));

        assertThatThrownBy(() -> templateService.createTemplate(dtoOf("DUP_CODE", "B", "t2", "c2")))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("模板代码已存在");

        // DB 层兜底：绕过应用查重直插第二条 deleted=0 → H2 唯一冲突
        Timestamp now = Timestamp.from(Instant.now());
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO sw_notify_template (id, create_time, update_time, deleted, tenant_id, version, "
                        + "template_code, name, title_template, content_template, enabled) VALUES (?, ?, ?, 0, ?, 0, 'DUP_CODE', 'C', 't', 'c', 1)",
                nextId++, now, now, TENANT_100))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        // 软删后重建成功
        jdbcTemplate.update("UPDATE sw_notify_template SET deleted=1 WHERE template_code='DUP_CODE'");
        assertThat(templateService.createTemplate(dtoOf("DUP_CODE", "R", "t3", "c3"))).isNotNull();

        System.out.println("[P36] 唯一性+软删重建 ✓");
    }

    @Test
    @DisplayName("租户隔离：T100/T200 同代码各自存在互不可见；发送按当前租户解析模板")
    void tenantIsolation_sameCodeIndependent() {
        insertTemplate(TENANT_100, "SHARED_CODE", "T100 标题 ${v}", "T100 内容", 1);
        insertTemplate(TENANT_200, "SHARED_CODE", "T200 标题", "T200 内容", 1);

        setTenant(TENANT_100);
        var page100 = templateService.pageTemplates(newQuery("SHARED_CODE"));
        assertThat(page100.getTotal()).isEqualTo(1);
        assertThat(page100.getRecords().get(0).getTitleTemplate()).isEqualTo("T100 标题 ${v}");

        setTenant(TENANT_200);
        var page200 = templateService.pageTemplates(newQuery("SHARED_CODE"));
        assertThat(page200.getTotal()).isEqualTo(1);
        assertThat(page200.getRecords().get(0).getTitleTemplate()).isEqualTo("T200 标题");

        long msgId = sendViaTemplate("SHARED_CODE", vars());
        NotifyMessage msg = notifyMessageService.getById(msgId);
        assertThat(msg.getTitle()).as("发送必须用本租户的模板渲染").isEqualTo("T200 标题");

        System.out.println("[P36] 租户隔离 ✓ msgTitle=" + msg.getTitle());
    }

    private void setTenant(Long tenantId) {
        testLoginContext.set(tenantId, USER_A);
        LoginUser u = new LoginUser();
        u.setUserId(USER_A);
        u.setTenantId(tenantId);
        u.setUsername("user_a");
        LoginUserHolder.set(u);
    }

    // ==================== 3. 渲染语义 ====================

    @Test
    @DisplayName("${var} 标题正文替换正确；预览结果与真实发送落库内容逐字一致")
    void renderMatchesPreviewAndPersistedContent() {
        String titleTpl = "${userName} 的审批提醒";
        String contentTpl = "您好 ${userName}，单据 ${docNo} 待处理。";
        insertTemplate(TENANT_100, "RENDER_MATCH", titleTpl, contentTpl, 1);
        Map<String, String> v = vars("userName", "张三", "docNo", "DOC-001");

        TemplatePreviewResult preview = templateService.renderPreview(previewReq(titleTpl, contentTpl, v));
        long msgId = sendViaTemplate("RENDER_MATCH", v);
        NotifyMessage persisted = notifyMessageService.getById(msgId);

        assertThat(persisted.getTitle()).isEqualTo(preview.getTitle()).isEqualTo("张三 的审批提醒");
        assertThat(persisted.getContent()).isEqualTo(preview.getContent())
                .isEqualTo("您好 张三，单据 DOC-001 待处理。");
        assertThat(persisted.getBizType()).isEqualTo("SYSTEM");
        assertThat(persisted.getBizId()).isEqualTo("RENDER_MATCH");

        System.out.println("[P36] 预览=落库 一致性 ✓ title=" + preview.getTitle());
    }

    @Test
    @DisplayName("额外变量不改变结果；变量值含 ${...} 与 \\ 按字面文本替换不被二次解释")
    void extraVarsAndLiteralInjection_notInterpreted() {
        String r = renderService.render("值：${a}",
                vars("a", "${danger} \\ ${b}", "b", "ignored"));
        assertThat(r).isEqualTo("值：${danger} \\ ${b}");

        assertThat(renderService.render("${x}${x}", vars("x", "Y", "unused", "Z"))).isEqualTo("YY");
    }

    @Test
    @DisplayName("非法占位符在提取阶段即拒绝：${} / ${1abc} / ${a-b}；坏模板不得入库")
    void invalidPlaceholders_rejectedAtExtraction() {
        assertThatThrownBy(() -> renderService.extractVariables("前缀 ${}"))
                .isInstanceOf(TemplateRenderException.class)
                .hasMessageContaining("非法占位符");
        assertThatThrownBy(() -> renderService.extractVariables("${1abc}"))
                .isInstanceOf(TemplateRenderException.class);
        assertThatThrownBy(() -> renderService.extractVariables("${a-b}"))
                .isInstanceOf(TemplateRenderException.class);

        assertThatThrownBy(() -> templateService.createTemplate(
                dtoOf("BAD_TPL", "x", "ok", "bad ${1abc} here")))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("非法占位符");
    }

    // ==================== 4. 落库前拒绝（原子性） ====================

    @Test
    @DisplayName("缺变量发送 → PARAM_ERROR 指出全部缺失项且零落库（无半成品通知）")
    void missingVariable_sendRejected_noRowInserted() {
        insertTemplate(TENANT_100, "MISSING_VAR", "${need1} 提醒", "需要 ${need1} 和 ${need2}", 1);
        int before = countMessages();
        assertThatThrownBy(() -> sendViaTemplate("MISSING_VAR", vars("need1", "有", "other", "x")))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("缺少变量")
                .hasMessageContaining("need2");
        assertThat(countMessages()).as("失败必须发生在落库之前").isEqualTo(before);
    }

    @Test
    @DisplayName("停用/不存在模板发送 → NOT_FOUND 且零落库")
    void disabledOrMissingTemplate_sendRejected_noRowInserted() {
        insertTemplate(TENANT_100, "DISABLED_TPL", "t", "c", 0);
        int before = countMessages();
        assertThatThrownBy(() -> sendViaTemplate("DISABLED_TPL", vars()))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("模板不存在或未启用");
        assertThatThrownBy(() -> sendViaTemplate("NO_SUCH_TPL", vars()))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("模板不存在或未启用");
        assertThat(countMessages()).isEqualTo(before);
    }

    @Test
    @DisplayName("已删除模板发送 → NOT_FOUND（逻辑删除后按代码解析不到）")
    void deletedTemplate_sendRejected() {
        long id = insertTemplate(TENANT_100, "DELETED_TPL", "t", "c", 1);
        templateService.deleteTemplate(id);
        assertThatThrownBy(() -> sendViaTemplate("DELETED_TPL", vars()))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("模板不存在或未启用");
    }

    // ==================== 4b. 按代码预览的可用性检查（补证缺口 G1） ====================

    @Test
    @DisplayName("G1-a 停用模板按代码预览 → NOT_FOUND「模板不存在或未启用」")
    void disabledTemplate_previewByCode_rejected() {
        insertTemplate(TENANT_100, "PV_DISABLED", "停用标题 ${n}", "停用正文 ${n}", 0);
        assertThatThrownBy(() -> templateService.previewByCode("PV_DISABLED", vars("n", "1")))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("模板不存在或未启用");
        System.out.println("[P36][G1] 停用模板按代码预览被拒 ✓");
    }

    @Test
    @DisplayName("G1-b 删除模板按代码预览 → NOT_FOUND；不存在代码同样拒绝")
    void deletedOrMissingTemplate_previewByCode_rejected() {
        long id = insertTemplate(TENANT_100, "PV_DELETED", "t ${n}", "c ${n}", 1);
        templateService.deleteTemplate(id);
        assertThatThrownBy(() -> templateService.previewByCode("PV_DELETED", vars()))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("模板不存在或未启用");
        assertThatThrownBy(() -> templateService.previewByCode("NO_SUCH_PV", vars()))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("模板不存在或未启用");
        System.out.println("[P36][G1] 删除/不存在模板按代码预览被拒 ✓");
    }

    @Test
    @DisplayName("G1-c 启用模板按代码预览渲染正确；预览失败零落库（sw_notify_message 计数不变）")
    void previewByCode_noSideEffects_andEnabledPathRenders() {
        insertTemplate(TENANT_100, "PV_ENABLED", "启用标题 ${n}", "启用正文 ${n}", 1);
        TemplatePreviewResult ok = templateService.previewByCode("PV_ENABLED", vars("n", "7"));
        assertThat(ok.getTitle()).isEqualTo("启用标题 7");
        assertThat(ok.getContent()).isEqualTo("启用正文 7");

        int before = countMessages();
        assertThatThrownBy(() ->
                templateService.previewByCode("PV_DISABLED_MISSING", vars()))
                .isInstanceOf(BaseException.class);
        assertThat(countMessages()).as("预览拒绝必须零落库").isEqualTo(before);
        System.out.println("[P36][G1] 预览无副作用 ✓ msgCount=" + before);
    }

    // ==================== 5. 历史稳定性 ====================

    @Test
    @DisplayName("模板编辑/停用/删除后，既有历史通知标题正文保持渲染时内容不变")
    void historyStable_afterTemplateMutations() {
        insertTemplate(TENANT_100, "HISTORY_STABLE", "旧标题 ${n}", "旧正文 ${n}", 1);
        long msgId = sendViaTemplate("HISTORY_STABLE", vars("n", "1"));

        templateService.updateTemplate(activeTemplateId("HISTORY_STABLE"),
                dtoOf("HISTORY_STABLE", "新名", "全新标题 ${n}", "全新正文 ${n}"));
        templateService.toggleTemplate(activeTemplateId("HISTORY_STABLE"), false);
        templateService.deleteTemplate(activeTemplateId("HISTORY_STABLE"));

        NotifyMessage after = notifyMessageService.getById(msgId);
        assertThat(after.getTitle()).as("编辑/停用/删除不回写历史").isEqualTo("旧标题 1");
        assertThat(after.getContent()).isEqualTo("旧正文 1");
    }

    // ==================== 6. 直接发送兼容回归 ====================

    @Test
    @DisplayName("直接标题/正文发送（NotifyFacade.send）不受影响，bizType 原值保留")
    void directSendStillWorks() {
        notifyFacade.send(new SendNotifyCommand(
                RECIPIENT, "直接标题", "直接正文", NotifyBizType.WF_TODO, "task-1", TENANT_100));
        List<NotifyMessage> all = notifyMessageService.findByRecipient(RECIPIENT);
        assertThat(all).hasSize(1);
        NotifyMessage saved = all.get(0);
        assertThat(saved.getTitle()).isEqualTo("直接标题");
        assertThat(saved.getContent()).isEqualTo("直接正文");
        assertThat(saved.getBizType()).isEqualTo("WF_TODO");
        assertThat(saved.getBizId()).isEqualTo("task-1");
    }

    // ==================== LoginContextProvider 测试实现（对齐既有测试） ====================

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

    // ==================== 组合测试配置（对齐 NotifyControllerIntegrationTest） ====================

    @Configuration
    @MapperScan("com.sw.ck.notify.mapper")
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:notifytpl;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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
        public CommonMetaObjectHandler commonMetaObjectHandler(LoginContextProvider provider) {
            return new CommonMetaObjectHandler(provider);
        }

        @Bean
        public TenantProperties tenantProperties() {
            return new TenantProperties();
        }

        @Bean
        public TenantLineInnerInterceptor tenantLineInnerInterceptor(
                TenantProperties tenantProperties, LoginContextProvider provider) {
            return new TenantLineInnerInterceptor(
                    new CommonTenantLineHandler(tenantProperties, provider));
        }

        @Bean
        public MybatisPlusInterceptor mybatisPlusInterceptor(
                TenantLineInnerInterceptor tenantInterceptor) {
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(tenantInterceptor);
            // 分页插件（与生产 MybatisPlusConfig 对齐；selectPage 无它则不拼 LIMIT）
            interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
            interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
            return interceptor;
        }

        @Bean
        public SqlSessionFactory sqlSessionFactory(DataSource dataSource,
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
        public NotifyTemplateServiceImpl notifyTemplateServiceImpl(NotifyTemplateMapper mapper,
                                                                   TemplateRenderService renderService) {
            // NotifyTemplateMapper 由 @MapperScan 注册，构造注入即可
            return new NotifyTemplateServiceImpl(mapper, renderService);
        }

        @Bean
        public NotifyMessageService notifyMessageService(NotifyTemplateServiceImpl notifyTemplateServiceImpl,
                                                         TemplateRenderService templateRenderService,
                                                         LoginContextProvider loginContextProvider) {
            return new NotifyMessageServiceImpl(notifyTemplateServiceImpl, templateRenderService, loginContextProvider);
        }

        @Bean
        public NotifyFacadeImpl notifyFacade(NotifyMessageService messageService) {
            return new NotifyFacadeImpl(messageService);
        }
    }
}
