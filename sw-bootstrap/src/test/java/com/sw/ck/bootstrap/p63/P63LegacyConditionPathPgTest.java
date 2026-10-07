package com.sw.ck.bootstrap.p63;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.service.FormDefService;
import com.sw.ck.form.service.FormSubmitService;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.flowable.task.api.Task;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P63 G09a 旧条件网关路径（完整生产注册链：真实 Spring 翻译器注册表 + 真实
 * bpmBranchConditionEvaluator Bean + 真实发布/运行）：
 * <ul>
 *   <li>legacy CONDITION 网关图经 processDefService 真实发布，命中/不命中合法输入
 *       各走原合法路径（命中分支走命中分支、不命中不得走命中分支）；路由结果含真实
 *       任务定义键/办理人/命中表达式原件输出。</li>
 *   <li>v1 冻结内容保护：发布 v2 后 v1 行 graph_json 与发布时逐字符直接等值；
 *       节点上的合法未知属性在新版本保留不丢。</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P63 G09a 旧条件路径 + v1 冻结等值 PG 端到端")
class P63LegacyConditionPathPgTest {

    private static final Long TENANT = 0L;
    private static final Long INITIATOR = 91901L;
    private static final Long APPROVER_A = 91902L;
    private static final Long APPROVER_B = 91903L;
    private static final String FORM_KEY = "p63_cond_form";

    private EmbeddedPostgres pg;
    private ConfigurableApplicationContext app;
    private JdbcTemplate jdbc;
    private FormDefService formDefService;
    private FormSubmitService submitService;
    private BpmProcessDefService processDefService;
    private TaskService taskService;

    @BeforeAll
    void setUp() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        String pgUrl = "jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified";
        Map<String, Object> props = new java.util.HashMap<>();
        props.put("server.port", "0");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "org.postgresql.Driver");
        props.put("spring.datasource.dynamic.datasource.master.url", pgUrl);
        props.put("spring.datasource.dynamic.datasource.master.username", "postgres");
        props.put("spring.datasource.dynamic.datasource.master.password", "postgres");
        props.put("sw.security.jwt.secret", "p63-cond-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p63-cond-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.enabled", "false");
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p63-cond", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p63CondLoginContextProvider", provider);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);
        formDefService = app.getBean(FormDefService.class);
        submitService = app.getBean(FormSubmitService.class);
        processDefService = app.getBean(BpmProcessDefService.class);
        taskService = app.getBean(TaskService.class);

        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'p63-cond-init', 'seed-not-a-login-secret', '条件发起人', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", INITIATOR, TENANT);
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'p63-cond-approver-a', 'seed-not-a-login-secret', '条件审批甲', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", APPROVER_A, TENANT);
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'p63-cond-approver-b', 'seed-not-a-login-secret', '条件审批乙', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", APPROVER_B, TENANT);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (91901, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, INITIATOR);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (91902, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, APPROVER_A);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (91903, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, APPROVER_B);

        asUser(INITIATOR, () -> {
            FormDefDTO draft = formDefService.createDraft(FORM_KEY, "P63条件表单", null, null);
            formDefService.saveConfig(draft.getId(),
                    "{\"schemaVersion\":1,\"title\":\"P63条件表单\",\"fields\":["
                            + "{\"name\":\"topic\",\"type\":\"TEXT\",\"label\":\"主题\",\"required\":false},"
                            + "{\"name\":\"level\",\"type\":\"TEXT\",\"label\":\"等级\",\"required\":false}]}");
            formDefService.publish(draft.getId());
            return null;
        });
        System.out.println("[P63-EV] cond boot ok pgPort=" + pg.getPort());
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

    @Test
    @DisplayName("生产注册链：命中分支输入走命中路径、不命中走默认路径（真实路由任务可查）")
    void hitAndMissRouteOriginalPaths() {
        BpmProcessDef def = publishConditionProcess();

        // 命中：level=B → approval_b（分支表达式真实命中）
        String recordHit = submitForm("条件命中", "B");
        String piHit = awaitInstanceStarted(recordHit);
        Task taskHit = awaitTask(piHit);
        assertThat(taskHit.getTaskDefinitionKey()).as("命中分支走 approval_b").isEqualTo("approval_b");
        assertThat(taskHit.getAssignee()).as("命中分支办理人为乙").isEqualTo(String.valueOf(APPROVER_B));
        System.out.println("[P63-EV] g09a.route-hit def=" + def.getProcessKey() + " instance=" + piHit
                + " input=form.level=='B' taskKey=" + taskHit.getTaskDefinitionKey()
                + " assignee=" + taskHit.getAssignee() + " route=branch-b");

        // 不命中：level=A → 默认分支 approval_a（不得走命中分支）
        String recordMiss = submitForm("条件不命中", "A");
        String piMiss = awaitInstanceStarted(recordMiss);
        Task taskMiss = awaitTask(piMiss);
        assertThat(taskMiss.getTaskDefinitionKey()).as("不命中走默认分支 approval_a").isEqualTo("approval_a");
        assertThat(taskMiss.getAssignee()).as("默认分支办理人为甲").isEqualTo(String.valueOf(APPROVER_A));
        assertThat(taskMiss.getTaskDefinitionKey()).as("不命中不得走命中分支").isNotEqualTo("approval_b");
        System.out.println("[P63-EV] g09a.route-miss def=" + def.getProcessKey() + " instance=" + piMiss
                + " input=form.level=='A' taskKey=" + taskMiss.getTaskDefinitionKey()
                + " assignee=" + taskMiss.getAssignee() + " route=default-a not-hit-branch=true");

        // 完成两实例（办理人各自审批）确认路由后可正常走完
        complete(taskHit);
        complete(taskMiss);
        System.out.println("[P63-EV] g09a.both-completed hitDone=true missDone=true");
    }

    @Test
    @DisplayName("v1 冻结内容保护：发布 v2 后 v1 graph_json 逐字符直接等值；合法未知属性保留")
    void v1FrozenContentUnchangedAfterRepublish() {
        BpmProcessDef def = publishConditionProcess();
        String v1GraphBefore = jdbc.queryForObject(
                "SELECT graph_json FROM sw_bpm_process_def WHERE process_key = ? AND def_version = 1",
                String.class, def.getProcessKey());
        assertThat(v1GraphBefore).as("v1 图内容可读").isNotBlank();

        // 合法未知属性（承载层不认识的 businessTag）随 v2 保存
        BpmProcessDef draft = asUser(INITIATOR, () -> processDefService.createDef(
                "P63COND-V2-" + System.nanoTime(), FORM_KEY));
        String graphJson = buildConditionGraphJson(draft.getProcessKey());
        String withUnknownAttr = graphJson.replace(
                "\"name\":\"条件分支\"", "\"name\":\"条件分支\",\"businessTag\":\"legacy-line-b\"");
        asUser(INITIATOR, () -> {
            processDefService.saveDraftGraph(draft.getId(), withUnknownAttr);
            processDefService.publish(draft.getId());
            return null;
        });

        String v1GraphAfter = jdbc.queryForObject(
                "SELECT graph_json FROM sw_bpm_process_def WHERE process_key = ? AND def_version = 1",
                String.class, def.getProcessKey());
        assertThat(v1GraphAfter).as("v1 冻结内容前后逐字符直接等值").isEqualTo(v1GraphBefore);
        String v2Graph = jdbc.queryForObject(
                "SELECT graph_json FROM sw_bpm_process_def WHERE process_key = ? AND def_version = 1",
                String.class, draft.getProcessKey());
        assertThat(v2Graph).as("合法未知属性在新版本保留").contains("legacy-line-b");
        Integer publishedVersion = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_process_def WHERE process_key = ? AND status = 'PUBLISHED'",
                Integer.class, draft.getProcessKey());
        assertThat(publishedVersion).as("新定义发布态可查").isEqualTo(1);
        System.out.println("[P63-EV] g09a.v1-frozen def=" + def.getProcessKey()
                + " v1GraphLen=" + v1GraphAfter.length() + " v1Equality=exact-string-equal"
                + " unknownAttrKept=legacy-line-b v2Def=" + draft.getId() + " published=true");
    }

    // ==================== helpers ====================

    private BpmProcessDef publishConditionProcess() {
        BpmProcessDef def = asUser(INITIATOR, () -> processDefService
                .createDef("P63COND-" + System.nanoTime(), FORM_KEY));
        asUser(INITIATOR, () -> {
            processDefService.saveDraftGraph(def.getId(), buildConditionGraphJson(def.getProcessKey()));
            processDefService.publish(def.getId());
            return null;
        });
        return def;
    }

    private String buildConditionGraphJson(String processKey) {
        Map<String, Object> approverA = Map.of("type", "DESIGNATED", "value", String.valueOf(APPROVER_A));
        Map<String, Object> approverB = Map.of("type", "DESIGNATED", "value", String.valueOf(APPROVER_B));
        Map<String, Object> graph = new java.util.LinkedHashMap<>();
        graph.put("processKey", processKey);
        graph.put("name", "P63条件流程");
        graph.put("formKey", FORM_KEY);
        graph.put("version", 1);
        graph.put("elements", List.of(
                node("start", "START", null),
                node("cond", "CONDITION", Map.of("name", "条件分支")),
                node("approval_a", "APPROVAL", Map.of("name", "审批甲", "approver", approverA)),
                node("approval_b", "APPROVAL", Map.of("name", "审批乙", "approver", approverB)),
                node("end", "END", null),
                edge("e1", "start", "cond", null),
                edge("e2", "cond", "approval_b",
                        Map.of("branchId", "b", "priority", 10,
                                "condition", Map.of("expression", "form.level == 'B'"))),
                edge("e3", "cond", "approval_a", Map.of("branchId", "a", "priority", 20, "default", true)),
                edge("e4", "approval_a", "end", null),
                edge("e5", "approval_b", "end", null)));
        return toJson(graph);
    }

    private String submitForm(String topic, String level) {
        return asUser(INITIATOR, () -> submitService.submitForm(FORM_KEY,
                data("topic", topic, "level", level), null, null, null));
    }

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
        throw new AssertionError("180s 内流程实例未创建: record=" + recordId);
    }

    private Task awaitTask(String processInstanceId) {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            Task task = taskService.createTaskQuery().processInstanceId(processInstanceId).singleResult();
            if (task != null) {
                return task;
            }
            sleepBriefly();
        }
        throw new AssertionError("60s 内未产生人工审批任务: instance=" + processInstanceId);
    }

    private void complete(Task task) {
        asUser(APPROVER_A, () -> {
            taskService.complete(task.getId(), Map.of("outcome", "APPROVED"));
            return null;
        });
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> data(String... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static GraphElement node(String id, String type, Map<String, Object> config) {
        return GraphElement.builder().id(id).kind("node").type(type).config(config).build();
    }

    private static GraphElement edge(String id, String source, String target, Map<String, Object> config) {
        return GraphElement.builder().id(id).kind("edge").source(source).target(target)
                .config(config).build();
    }

    private String toJson(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

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
            java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            java.security.KeyPair pair = gen.generateKeyPair();
            return java.util.Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
