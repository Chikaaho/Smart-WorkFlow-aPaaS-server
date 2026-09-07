package com.sw.ck.notify.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.notify.dto.NotifyTemplateDTO;
import com.sw.ck.notify.entity.NotifyMessage;
import com.sw.ck.notify.mapper.NotifyTemplateMapper;
import com.sw.ck.notify.render.TemplateRenderService;
import com.sw.ck.notify.service.NotifyMessageService;
import com.sw.ck.notify.service.NotifyTemplateService;
import com.sw.ck.notify.service.impl.NotifyMessageServiceImpl;
import com.sw.ck.notify.service.impl.NotifyTemplateServiceImpl;
import com.sw.ck.security.cache.LoginUserCacheService;
import com.sw.ck.security.cache.LoginUserLoader;
import com.sw.ck.security.config.SecurityProperties;
import com.sw.ck.security.filter.JwtAuthenticationFilter;
import com.sw.ck.security.handler.RestAccessDeniedHandler;
import com.sw.ck.security.handler.RestAuthenticationEntryPoint;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.security.jwt.JwtProperties;
import com.sw.ck.security.jwt.JwtTokenProvider;
import com.sw.ck.security.jwt.JwtTokenProviderImpl;
import com.sw.ck.security.spi.UserDetailsProvider;
import com.sw.ck.security.support.PermissionService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P36 / M05-F02-01 补证缺口 G2：NotifyTemplateController 管理端点真实身份请求链证据。
 * <p>
 * 请求经真实 Spring Security 过滤链（{@link JwtAuthenticationFilter} → SecurityContext）+
 * 方法级安全（{@code @EnableMethodSecurity} + {@code @PreAuthorize("@ss.hasPermi(...)")} +
 * 真实 {@link PermissionService} Bean "ss"），再进入真实
 * {@link NotifyTemplateController}/{@link NotifyTemplateServiceImpl} + H2（MODE=PostgreSQL，
 * 真实 TenantLine/OptimisticLocker/逻辑删除拦截器链）。非 mock 授权、非直接 new Controller 调用。</p>
 *
 * <p>四类身份 × 管理端点矩阵：</p>
 * <ol>
 *   <li>未认证（无 Authorization 头）→ 401 {@code RestAuthenticationEntryPoint}，响应体
 *       {@code {"code":401,"msg":"未认证"}}，且零落库；</li>
 *   <li>已认证但仅持 notify:view（收件箱普通用户，userId=5）→ 写端点 403
 *       {@code RestAccessDeniedHandler}，响应体 {@code {"code":403,"msg":"无权限"}}，且零落库；</li>
 *   <li>持 notify:template:manage 的非超管用户（userId=7）→ 创建/toggle/删除/发送全部 200 且真实落库；</li>
 *   <li>读端点 GET 列表：仅 notify:view 用户 403；持 notify:template:view 用户（userId=8）200。</li>
 * </ol>
 *
 * <p>本类仅新增测试文件，不修改任何生产源码与 pom（测试类路径经 sw-security →
 * spring-boot-starter-security 已具备完整 Spring Security 过滤链与方法安全能力，
 * 无需 spring-security-test——与 sw-basic-agent 先例同构）。</p>
 */
@SpringBootTest(
        classes = NotifyTemplateSecurityIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                // 排除会引入 Redis/PgVector/生产级 MybatisPlus 装配的自动配置（对齐 agent 模块先例）
                "spring.autoconfigure.exclude=com.sw.ck.common.config.mybatis.MybatisPlusConfig,"
                        + "com.sw.ck.common.config.redis.RedisConfig,"
                        + "com.sw.ck.security.config.SecurityAutoConfiguration,"
                        + "com.sw.ck.security.config.WebSecurityAutoConfiguration",
                "sw.tenant.enabled=true"
        }
)
@DisplayName("P36 G2 补证：消息模板管理端点真实身份请求链（401/403/放行 + 数据前后值）")
class NotifyTemplateSecurityIntegrationTest {

    /** 未认证：无 Authorization 头 */
    private static final Long TENANT_100 = 100L;

    /** userId=5：仅 notify:view 的普通通知用户（收件箱可见，模板管理不可入） */
    private static final Long USER_NOTIFY_VIEW_ONLY = 5L;
    /** userId=7：非超管，持 notify:template:manage + notify:template:view */
    private static final Long USER_TEMPLATE_MANAGER = 7L;
    /** userId=8：非超管，仅持 notify:template:view（可读不可写） */
    private static final Long USER_TEMPLATE_VIEW_ONLY = 8L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    // ==================== 建表 ====================

    @BeforeAll
    static void createTables(@Autowired JdbcTemplate jt) {
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_notify_template (
                    id                bigint not null primary key,
                    create_time       timestamp,
                    create_by         bigint,
                    update_time       timestamp,
                    update_by         bigint,
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

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM sw_notify_template");
        jdbcTemplate.update("DELETE FROM sw_notify_message");
    }

    private String bearerToken(Long userId) {
        return "Bearer " + jwtTokenProvider.generateToken(userId);
    }

    private String templateJson(String code) {
        return """
                {"templateCode":"%s","name":"G2补证模板","titleTemplate":"你好 ${userName}","contentTemplate":"亲爱的 ${userName}，欢迎使用","enabled":true}
                """.formatted(code);
    }

    private String sendJson(String code, Long recipientId) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("userName", "张三");
        Map<String, Object> body = new HashMap<>();
        body.put("templateCode", code);
        body.put("recipientId", recipientId);
        body.put("variables", variables);
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int countTemplates() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_notify_template WHERE deleted=0", Integer.class);
        return n == null ? 0 : n;
    }

    private int countMessages() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_notify_message WHERE deleted=0", Integer.class);
        return n == null ? 0 : n;
    }

    // ==================== a. 未认证 → 401 ====================

    @Test
    @DisplayName("a1: POST /notify/templates 未认证（无 token）→ 401 {code:401,msg:未认证}，且不写入")
    void unauth_create_shouldReturn401_andNotWrite() throws Exception {
        int before = countTemplates();

        MvcResult result = mockMvc.perform(post("/notify/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(templateJson("G2_UNAUTH_CREATE")))
                .andExpect(status().isUnauthorized())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(401);
        String msg = body.get("msg").asText();
        assertThat(msg).isEqualTo("未认证");

        int after = countTemplates();
        assertThat(after).as("拒绝发生在任何写入之前").isEqualTo(before);

        System.out.println("[G2-a1] 未认证 POST 创建: HTTP 401, body={\"code\":401,\"msg\":\"" + msg
                + "\"}, 行数 " + before + "->" + after);
    }

    @Test
    @DisplayName("a2: PUT toggle / DELETE / POST send 未认证 → 全部 401，且不写入")
    void unauth_toggleDeleteSend_shouldReturn401_andNotWrite() throws Exception {
        int beforeTpl = countTemplates();
        int beforeMsg = countMessages();

        MvcResult toggleRes = mockMvc.perform(put("/notify/templates/{id}/toggle", 99901L)
                        .param("enabled", "false"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        JsonNode toggleBody = objectMapper.readTree(toggleRes.getResponse().getContentAsString());
        assertThat(toggleBody.get("code").asInt()).isEqualTo(401);
        assertThat(toggleBody.get("msg").asText()).isEqualTo("未认证");

        MvcResult deleteRes = mockMvc.perform(delete("/notify/templates/{id}", 99902L))
                .andExpect(status().isUnauthorized())
                .andReturn();
        JsonNode deleteBody = objectMapper.readTree(deleteRes.getResponse().getContentAsString());
        assertThat(deleteBody.get("code").asInt()).isEqualTo(401);

        MvcResult sendRes = mockMvc.perform(post("/notify/templates/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendJson("G2_UNAUTH_SEND", 9L)))
                .andExpect(status().isUnauthorized())
                .andReturn();
        JsonNode sendBody = objectMapper.readTree(sendRes.getResponse().getContentAsString());
        assertThat(sendBody.get("code").asInt()).isEqualTo(401);

        assertThat(countTemplates()).isEqualTo(beforeTpl);
        assertThat(countMessages()).isEqualTo(beforeMsg);

        System.out.println("[G2-a2] 未认证 toggle/delete/send: 全部 HTTP 401 msg=未认证, 模板行数 "
                + beforeTpl + "->" + countTemplates() + ", 通知行数 " + beforeMsg + "->" + countMessages());
    }

    @Test
    @DisplayName("a3: GET /notify/templates 列表未认证 → 401")
    void unauth_list_shouldReturn401() throws Exception {
        MvcResult result = mockMvc.perform(get("/notify/templates")
                        .param("pageNum", "1").param("pageSize", "10"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(401);
        System.out.println("[G2-a3] 未认证 GET 列表: HTTP 401, msg=" + body.get("msg").asText());
    }

    // ==================== b. 仅 notify:view → 管理写端点 403 ====================

    @Test
    @DisplayName("b1: 仅 notify:view 用户（userId=5）POST 创建 → 403 {code:403,msg:无权限}，且不写入")
    void notifyViewOnly_create_shouldReturn403_andNotWrite() throws Exception {
        int before = countTemplates();

        MvcResult result = mockMvc.perform(post("/notify/templates")
                        .header("Authorization", bearerToken(USER_NOTIFY_VIEW_ONLY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(templateJson("G2_VIEWONLY_CREATE")))
                .andExpect(status().isForbidden())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(403);
        String msg = body.get("msg").asText();
        assertThat(msg).isEqualTo("无权限");

        int after = countTemplates();
        assertThat(after).as("缺 manage 权限不得产生任何数据行").isEqualTo(before);

        System.out.println("[G2-b1] notify:view-only POST 创建: HTTP 403, body={\"code\":403,\"msg\":\""
                + msg + "\"}, 行数 " + before + "->" + after);
    }

    @Test
    @DisplayName("b2: 仅 notify:view 用户 PUT toggle / DELETE / POST send → 全部 403 且零落库")
    void notifyViewOnly_mutations_shouldReturn403_andNotWrite() throws Exception {
        // 先用 manager 身份造一条启用模板，供 toggle/delete/send 目标使用
        MvcResult createRes = mockMvc.perform(post("/notify/templates")
                        .header("Authorization", bearerToken(USER_TEMPLATE_MANAGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(templateJson("G2_TARGET_TPL")))
                .andExpect(status().isOk())
                .andReturn();
        long targetId = objectMapper.readTree(createRes.getResponse().getContentAsString())
                .get("data").asLong();

        int beforeMsg = countMessages();

        MvcResult toggleRes = mockMvc.perform(put("/notify/templates/{id}/toggle", targetId)
                        .header("Authorization", bearerToken(USER_NOTIFY_VIEW_ONLY))
                        .param("enabled", "false"))
                .andExpect(status().isForbidden())
                .andReturn();
        JsonNode toggleBody = objectMapper.readTree(toggleRes.getResponse().getContentAsString());
        assertThat(toggleBody.get("code").asInt()).isEqualTo(403);
        assertThat(toggleBody.get("msg").asText()).isEqualTo("无权限");

        MvcResult deleteRes = mockMvc.perform(delete("/notify/templates/{id}", targetId)
                        .header("Authorization", bearerToken(USER_NOTIFY_VIEW_ONLY)))
                .andExpect(status().isForbidden())
                .andReturn();
        JsonNode deleteBody = objectMapper.readTree(deleteRes.getResponse().getContentAsString());
        assertThat(deleteBody.get("code").asInt()).isEqualTo(403);

        MvcResult sendRes = mockMvc.perform(post("/notify/templates/send")
                        .header("Authorization", bearerToken(USER_NOTIFY_VIEW_ONLY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendJson("G2_TARGET_TPL", 9L)))
                .andExpect(status().isForbidden())
                .andReturn();
        JsonNode sendBody = objectMapper.readTree(sendRes.getResponse().getContentAsString());
        assertThat(sendBody.get("code").asInt()).isEqualTo(403);

        // 目标模板未被改动（enabled 仍为 1、未被逻辑删除）、零通知落库
        Boolean enabledAfter = jdbcTemplate.queryForObject(
                "SELECT enabled FROM sw_notify_template WHERE id=?", Boolean.class, targetId);
        assertThat(enabledAfter).as("toggle 被 403 拒绝后目标行不变").isTrue();
        Integer deletedAfter = jdbcTemplate.queryForObject(
                "SELECT deleted FROM sw_notify_template WHERE id=?", Integer.class, targetId);
        assertThat(deletedAfter).as("delete 被 403 拒绝后目标行不被逻辑删除").isZero();
        assertThat(countMessages()).as("send 被 403 拒绝则零通知落库").isEqualTo(beforeMsg);

        System.out.println("[G2-b2] notify:view-only toggle/delete/send: 全部 HTTP 403 msg=无权限, 目标行 enabled="
                + enabledAfter + " deleted=" + deletedAfter + ", 通知行数 " + beforeMsg + "->" + countMessages());
    }

    // ==================== c. 持 notify:template:manage 的非超管 → 全部操作成功 ====================

    @Test
    @DisplayName("c1: 非超管 manager（userId=7，持 notify:template:manage）POST 创建 → 200 并真实落库")
    void manager_create_shouldSucceed_andPersist() throws Exception {
        int before = countTemplates();

        MvcResult result = mockMvc.perform(post("/notify/templates")
                        .header("Authorization", bearerToken(USER_TEMPLATE_MANAGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(templateJson("G2_MGR_CREATE")))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isZero();
        long id = body.get("data").asLong();
        assertThat(id).isPositive();

        int after = countTemplates();
        assertThat(after).isEqualTo(before + 1);

        // 租户注入正确：tenant_id 来自 JWT 解析出的登录用户租户（100），非超管租户
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT template_code, name, title_template, enabled, tenant_id, create_by "
                        + "FROM sw_notify_template WHERE id=?", id);
        assertThat(row.get("TEMPLATE_CODE")).isEqualTo("G2_MGR_CREATE");
        assertThat(row.get("TENANT_ID")).isEqualTo(TENANT_100);
        assertThat(row.get("CREATE_BY")).isEqualTo(USER_TEMPLATE_MANAGER);

        System.out.println("[G2-c1] manager POST 创建: HTTP 200 code=0 id=" + id
                + ", 落库 tenant_id=" + row.get("TENANT_ID") + " create_by=" + row.get("CREATE_BY")
                + ", 行数 " + before + "->" + after);
    }

    @Test
    @DisplayName("c2: manager PUT toggle → 200 且 enabled 翻转持久化")
    void manager_toggle_shouldFlipEnabled() throws Exception {
        MvcResult createRes = mockMvc.perform(post("/notify/templates")
                        .header("Authorization", bearerToken(USER_TEMPLATE_MANAGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(templateJson("G2_MGR_TOGGLE")))
                .andExpect(status().isOk())
                .andReturn();
        long id = objectMapper.readTree(createRes.getResponse().getContentAsString())
                .get("data").asLong();

        MvcResult toggleRes = mockMvc.perform(put("/notify/templates/{id}/toggle", id)
                        .header("Authorization", bearerToken(USER_TEMPLATE_MANAGER))
                        .param("enabled", "false"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode toggleBody = objectMapper.readTree(toggleRes.getResponse().getContentAsString());
        assertThat(toggleBody.get("code").asInt()).isZero();
        assertThat(toggleBody.get("msg").asText()).isEqualTo("success");

        Boolean enabled = jdbcTemplate.queryForObject(
                "SELECT enabled FROM sw_notify_template WHERE id=? AND deleted=0", Boolean.class, id);
        assertThat(enabled).isFalse();

        System.out.println("[G2-c2] manager PUT toggle(enabled=false): HTTP 200 code=0, 落库 enabled=" + enabled);
    }

    @Test
    @DisplayName("c3: manager DELETE → 200 幂等逻辑删除，deleted=1")
    void manager_delete_shouldSoftDelete() throws Exception {
        MvcResult createRes = mockMvc.perform(post("/notify/templates")
                        .header("Authorization", bearerToken(USER_TEMPLATE_MANAGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(templateJson("G2_MGR_DELETE")))
                .andExpect(status().isOk())
                .andReturn();
        long id = objectMapper.readTree(createRes.getResponse().getContentAsString())
                .get("data").asLong();

        mockMvc.perform(delete("/notify/templates/{id}", id)
                        .header("Authorization", bearerToken(USER_TEMPLATE_MANAGER)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/notify/templates/{id}", id)
                        .header("Authorization", bearerToken(USER_TEMPLATE_MANAGER)))
                .andExpect(status().isOk()); // 幂等第二次

        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT deleted FROM sw_notify_template WHERE id=?", Integer.class, id);
        assertThat(deleted).isEqualTo(1);
        assertThat(countTemplates()).isZero();

        System.out.println("[G2-c3] manager DELETE ×2（幂等）: HTTP 200 ×2, 落库 deleted=" + deleted
                + ", 有效行数=" + countTemplates());
    }

    @Test
    @DisplayName("c4: manager POST send → 200 渲染落库通知，标题正文为渲染结果")
    void manager_send_shouldRenderAndPersist() throws Exception {
        MvcResult createRes = mockMvc.perform(post("/notify/templates")
                        .header("Authorization", bearerToken(USER_TEMPLATE_MANAGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(templateJson("G2_MGR_SEND")))
                .andExpect(status().isOk())
                .andReturn();
        long tplId = objectMapper.readTree(createRes.getResponse().getContentAsString())
                .get("data").asLong();

        int beforeMsg = countMessages();
        MvcResult sendRes = mockMvc.perform(post("/notify/templates/send")
                        .header("Authorization", bearerToken(USER_TEMPLATE_MANAGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendJson("G2_MGR_SEND", 9L)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode sendBody = objectMapper.readTree(sendRes.getResponse().getContentAsString());
        assertThat(sendBody.get("code").asInt()).isZero();
        long msgId = sendBody.get("data").asLong();
        assertThat(msgId).isPositive();

        int afterMsg = countMessages();
        assertThat(afterMsg).isEqualTo(beforeMsg + 1);
        Map<String, Object> msgRow = jdbcTemplate.queryForMap(
                "SELECT title, content, biz_type, biz_id, recipient_id FROM sw_notify_message WHERE id=?", msgId);
        assertThat(msgRow.get("TITLE")).isEqualTo("你好 张三");
        assertThat(msgRow.get("CONTENT")).isEqualTo("亲爱的 张三，欢迎使用");
        assertThat(msgRow.get("BIZ_TYPE")).isEqualTo("SYSTEM");
        assertThat(msgRow.get("BIZ_ID")).isEqualTo("G2_MGR_SEND");
        assertThat(((Number) msgRow.get("RECIPIENT_ID")).longValue()).isEqualTo(9L);
        assertThat(((Number) jdbcTemplate.queryForObject(
                "SELECT id FROM sw_notify_template WHERE id=?", Long.class, tplId)).longValue()).isEqualTo(tplId);

        System.out.println("[G2-c4] manager POST send: HTTP 200 code=0 msgId=" + msgId
                + ", title=" + msgRow.get("TITLE") + ", bizType=" + msgRow.get("BIZ_TYPE")
                + ", 通知行数 " + beforeMsg + "->" + afterMsg);
    }

    // ==================== d. 读端点 GET 列表：view 权限映射 ====================

    @Test
    @DisplayName("d1: GET 列表 仅 notify:view 用户（userId=5）→ 403（无 notify:template:view）")
    void list_notifyViewOnly_shouldReturn403() throws Exception {
        MvcResult result = mockMvc.perform(get("/notify/templates")
                        .header("Authorization", bearerToken(USER_NOTIFY_VIEW_ONLY))
                        .param("pageNum", "1").param("pageSize", "10"))
                .andExpect(status().isForbidden())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(403);
        assertThat(body.get("msg").asText()).isEqualTo("无权限");
        System.out.println("[G2-d1] notify:view-only GET 列表: HTTP 403 msg=无权限");
    }

    @Test
    @DisplayName("d2: GET 列表 持 notify:template:view 非超管（userId=8）→ 200 返回本租户分页数据")
    void list_templateViewOnly_shouldReturn200_withTenantData() throws Exception {
        mockMvc.perform(post("/notify/templates")
                        .header("Authorization", bearerToken(USER_TEMPLATE_MANAGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(templateJson("G2_LIST_A")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/notify/templates")
                        .header("Authorization", bearerToken(USER_TEMPLATE_MANAGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(templateJson("G2_LIST_B")))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/notify/templates")
                        .header("Authorization", bearerToken(USER_TEMPLATE_VIEW_ONLY))
                        .param("keyword", "G2_LIST").param("pageNum", "1").param("pageSize", "10"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isZero();
        JsonNode page = body.get("data");
        assertThat(page.get("total").asLong()).isEqualTo(2);
        assertThat(page.get("records")).hasSize(2);
        assertThat(page.get("records").get(0).get("templateCode").asText()).startsWith("G2_LIST_");

        System.out.println("[G2-d2] template:view GET 列表: HTTP 200 code=0 total="
                + page.get("total").asLong() + " records=" + page.get("records").size()
                + " firstCode=" + page.get("records").get(0).get("templateCode").asText());
    }

    // ==================== e. view-only 用户对写端点仍 403（更严格证明） ====================

    @Test
    @DisplayName("e: 持 notify:template:view 但缺 manage 的用户（userId=8）POST 创建 → 403")
    void templateViewOnly_create_shouldReturn403() throws Exception {
        int before = countTemplates();
        MvcResult result = mockMvc.perform(post("/notify/templates")
                        .header("Authorization", bearerToken(USER_TEMPLATE_VIEW_ONLY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(templateJson("G2_TVONLY_CREATE")))
                .andExpect(status().isForbidden())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(403);
        assertThat(countTemplates()).isEqualTo(before);
        System.out.println("[G2-e] template:view-only POST 创建: HTTP 403 msg=" + body.get("msg").asText()
                + ", 行数 " + before + "->" + countTemplates());
    }

    // ==================== 组合测试配置（真实 Security 链 + 真实 Service + H2） ====================

    @Configuration
    @EnableAutoConfiguration(exclude = org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class)
    @EnableMethodSecurity
    @EnableWebSecurity
    @EnableTransactionManagement
    @MapperScan("com.sw.ck.notify.mapper")
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .driverClassName("org.h2.Driver")
                    .url("jdbc:h2:mem:notifytpl_sec;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
                    .username("sa")
                    .password("")
                    .build();
        }

        @Bean
        public PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        public TenantLineInnerInterceptor tenantLineInnerInterceptor(LoginContextProvider loginContextProvider) {
            return new TenantLineInnerInterceptor(new CommonTenantLineHandler(new TenantProperties(), loginContextProvider));
        }

        @Bean
        public MybatisPlusInterceptor mybatisPlusInterceptor(TenantLineInnerInterceptor tenantLineInnerInterceptor) {
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(tenantLineInnerInterceptor);
            interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
            interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
            return interceptor;
        }

        @Bean
        public org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(
                DataSource dataSource, CommonMetaObjectHandler metaObjectHandler,
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
        public CommonMetaObjectHandler commonMetaObjectHandler(LoginContextProvider loginContextProvider) {
            return new CommonMetaObjectHandler(loginContextProvider);
        }

        @Bean
        public JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        public ObjectMapper objectMapper() {
            ObjectMapper om = new ObjectMapper();
            om.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            om.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            return om;
        }

        @Bean
        public TemplateRenderService templateRenderService() {
            return new TemplateRenderService();
        }

        /**
         * 单一 Bean 同时满足 {@link NotifyTemplateServiceImpl}（发送链 requireEnabledByCode）
         * 与 {@link NotifyTemplateService}（接口）两个注入点，避免同实例双 Bean 注册歧义。
         */
        @Bean
        public NotifyTemplateServiceImpl notifyTemplateServiceImpl(NotifyTemplateMapper mapper,
                                                                   TemplateRenderService renderService) {
            return new NotifyTemplateServiceImpl(mapper, renderService);
        }

        @Bean
        public NotifyMessageService notifyMessageService(NotifyTemplateServiceImpl notifyTemplateServiceImpl,
                                                         TemplateRenderService templateRenderService,
                                                         LoginContextProvider loginContextProvider) {
            return new NotifyMessageServiceImpl(notifyTemplateServiceImpl, templateRenderService, loginContextProvider);
        }

        @Bean
        public NotifyTemplateController notifyTemplateController(
                NotifyTemplateService templateService,
                NotifyTemplateServiceImpl templateServiceImpl,
                TemplateRenderService renderService,
                NotifyMessageService messageService) {
            return new NotifyTemplateController(templateService, templateServiceImpl,
                    renderService, messageService);
        }

        /**
         * LoginContextProvider：从 {@link LoginUserHolder} 读当前登录用户（与生产
         * SecurityLoginContextProvider 同语义）；租户注入/审计列填充据此生效。
         */
        @Bean
        public LoginContextProvider loginContextProvider() {
            return new LoginContextProvider() {
                @Override
                public Long getUserId() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getUserId() : null;
                }

                @Override
                public Long getTenantId() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getTenantId() : null;
                }

                @Override
                public Long getDeptId() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getDeptId() : null;
                }

                @Override
                public DataScopeType getDataScopeType() {
                    return DataScopeType.ALL;
                }

                @Override
                public Set<Long> getCustomDeptIds() {
                    return Set.of();
                }

                @Override
                public boolean isSuperAdmin() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null && user.isSuperAdmin();
                }
            };
        }

        @Bean
        public JwtProperties jwtProperties() {
            JwtProperties props = new JwtProperties();
            props.setSecret("test-jwt-secret-at-least-256-bits-long-for-hs256-algorithm");
            props.setExpireSeconds(7200);
            props.setAccessExpireSeconds(900);
            props.setRefreshExpireSeconds(604800);
            return props;
        }

        @Bean
        public JwtTokenProvider jwtTokenProvider(JwtProperties jwtProperties) {
            return new JwtTokenProviderImpl(jwtProperties);
        }

        @Bean
        public SecurityProperties securityProperties() {
            SecurityProperties props = new SecurityProperties();
            props.setTokenHeader("Authorization");
            props.setTokenPrefix("Bearer ");
            props.setPermitUrls(List.of("/auth/login"));
            return props;
        }

        /** Redis mock：缓存层 no-op，强制每次回查 UserDetailsProvider（内存身份源） */
        @Bean
        @SuppressWarnings({"unchecked", "rawtypes"})
        public LoginUserCacheService loginUserCacheService(JwtProperties jwtProperties) {
            RedisTemplate<String, Object> mockRedis = mock(RedisTemplate.class);
            ValueOperations<String, Object> mockOps = mock(ValueOperations.class);
            when(mockRedis.opsForValue()).thenReturn(mockOps);
            when(mockOps.get(anyString())).thenReturn(null);
            return new LoginUserCacheService(mockRedis, jwtProperties) {
                @Override
                public void cache(LoginUser loginUser) {
                    // no-op
                }

                @Override
                public void evict(Long userId) {
                    // no-op
                }
            };
        }

        @Bean
        @SuppressWarnings("unchecked")
        public LoginUserLoader loginUserLoader(UserDetailsProvider userDetailsProvider,
                                               LoginUserCacheService loginUserCacheService) {
            org.springframework.beans.factory.ObjectProvider<UserDetailsProvider> provider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(userDetailsProvider);
            return new LoginUserLoader(provider, loginUserCacheService);
        }

        /** 内存身份源：5=notify:view-only；7=模板管理非超管；8=template:view-only。全部 tenant=100 */
        @Bean
        public UserDetailsProvider userDetailsProvider() {
            Map<Long, LoginUser> users = new HashMap<>();
            LoginUser u5 = new LoginUser();
            u5.setUserId(5L);
            u5.setTenantId(TENANT_100);
            u5.setUsername("inbox_user");
            u5.setPermissions(List.of("notify:view"));
            u5.setRoles(List.of("normal"));
            u5.setSuperAdmin(false);
            users.put(5L, u5);
            LoginUser u7 = new LoginUser();
            u7.setUserId(7L);
            u7.setTenantId(TENANT_100);
            u7.setUsername("tpl_manager");
            u7.setPermissions(List.of("notify:view", "notify:template:view", "notify:template:manage"));
            u7.setRoles(List.of("normal"));
            u7.setSuperAdmin(false);
            users.put(7L, u7);
            LoginUser u8 = new LoginUser();
            u8.setUserId(8L);
            u8.setTenantId(TENANT_100);
            u8.setUsername("tpl_viewer");
            u8.setPermissions(List.of("notify:template:view"));
            u8.setRoles(List.of("normal"));
            u8.setSuperAdmin(false);
            users.put(8L, u8);
            return new UserDetailsProvider() {
                @Override
                public LoginUser loadByUsername(String username) {
                    return null;
                }

                @Override
                public LoginUser loadByUserId(Long userId) {
                    return users.get(userId);
                }
            };
        }

        /** 与生产同名的 "ss" Bean：@PreAuthorize("@ss.hasPermi(...)") 的真实求值入口 */
        @Bean("ss")
        public PermissionService permissionService() {
            return new PermissionService();
        }

        @Bean
        public SecurityFilterChain filterChain(HttpSecurity http,
                                               JwtAuthenticationFilter jwtAuthenticationFilter,
                                               RestAccessDeniedHandler accessDeniedHandler,
                                               RestAuthenticationEntryPoint authenticationEntryPoint) throws Exception {
            http.csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                    .exceptionHandling(e -> e
                            .accessDeniedHandler(accessDeniedHandler)
                            .authenticationEntryPoint(authenticationEntryPoint));
            return http.build();
        }

        @Bean
        public MockMvc mockMvc(WebApplicationContext context,
                               @Qualifier("springSecurityFilterChain") Filter springSecurityFilterChain) {
            return MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(springSecurityFilterChain)
                    .build();
        }

        @Bean
        public JwtAuthenticationFilter jwtAuthenticationFilter(
                JwtTokenProvider jwtTokenProvider,
                LoginUserLoader loginUserLoader,
                SecurityProperties securityProperties) {
            return new JwtAuthenticationFilter(jwtTokenProvider, loginUserLoader, securityProperties);
        }

        @Bean
        public RestAccessDeniedHandler restAccessDeniedHandler(ObjectMapper objectMapper) {
            return new RestAccessDeniedHandler(objectMapper);
        }

        @Bean
        public RestAuthenticationEntryPoint restAuthenticationEntryPoint(ObjectMapper objectMapper) {
            return new RestAuthenticationEntryPoint(objectMapper);
        }
    }
}
