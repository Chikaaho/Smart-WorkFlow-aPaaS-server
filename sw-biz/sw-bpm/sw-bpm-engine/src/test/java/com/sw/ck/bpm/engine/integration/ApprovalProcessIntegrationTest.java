package com.sw.ck.bpm.engine.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.spi.assignee.NodeApproverResolver;
import com.sw.ck.bpm.api.spi.assignee.NodeApproverType;
import com.sw.ck.bpm.engine.facade.BpmDeployFacadeImpl;
import com.sw.ck.bpm.engine.listener.ApprovalTaskListener;
import com.sw.ck.bpm.engine.resolver.DesignatedApproverResolver;
import com.sw.ck.bpm.engine.translator.GraphToBpmnTranslator;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.common.engine.impl.el.DefaultExpressionManager;
import org.flowable.common.engine.impl.el.ExpressionManager;
import org.flowable.engine.*;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Flowable 内存集成测试 —— 验证发布最小图 → 发起实例 → 审批人注入全链路。
 * <p>
 * 使用 Flowable 独立内存 H2 引擎。
 * 策略：先在 config 上用 {@code addExpressionManagerConfigurer} 注册一个<strong>空 beans
 * map</strong>，引擎初始化时 {@link DefaultExpressionManager} 持有该 map 的引用。
 * 引擎就绪后，创建 {@link ApprovalTaskListener} 并塞入同一 map，
 * 运行时 delegation expression {@code ${approvalTaskListener}} 即可从该 map 找到 listener。
 * </p>
 */
class ApprovalProcessIntegrationTest {

    private static RepositoryService repositoryService;
    private static RuntimeService runtimeService;
    private static TaskService taskService;
    
    /** 与引擎表达式管理器共享的空 beans map（引擎初始化后填入 listener） */
    private static final Map<Object, Object> expressionBeans = new HashMap<>();

    private Deployment deployment;

    @BeforeAll
    static void initFlowableEngine() {
        // 1. 建引擎 config
        ProcessEngineConfigurationImpl config =
                (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
                        .createStandaloneInMemProcessEngineConfiguration();
        config.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        config.setJdbcUrl("jdbc:h2:mem:flowable-integration;DB_CLOSE_DELAY=-1");
        config.setJdbcDriver("org.h2.Driver");
        config.setJdbcUsername("sa");
        config.setJdbcPassword("");

        // 2. 在引擎初始化时（buildEngine 之前），将空 beans map 注册到表达式管理器
        //    DefaultExpressionManager 持有该 map 引用，后续 put 的条目运行时可见
        config.addExpressionManagerConfigurer(em -> {
            if (em instanceof DefaultExpressionManager dem) {
                dem.setBeans(expressionBeans);
            }
        });

        // 3. 建引擎
        ProcessEngine processEngine = config.buildEngine();

        repositoryService = processEngine.getRepositoryService();
        runtimeService = processEngine.getRuntimeService();
        taskService = processEngine.getTaskService();

        // 4. 创建 resolver map
        DesignatedApproverResolver designatedResolver = new DesignatedApproverResolver();
        Map<String, NodeApproverResolver> resolverMap = new HashMap<>();
        resolverMap.put(NodeApproverType.DESIGNATED, designatedResolver);

        // 5. 创建 listener（现在 repositoryService 已可用）
        ApprovalTaskListener listener = new ApprovalTaskListener(
                repositoryService, resolverMap, new ObjectMapper());

        // 6. 将 listener 注册到与表达式管理器共享的 beans map
        //    引擎初始化时已注入空 map，此处 put 后表达式管理器运行时可见
        expressionBeans.put("approvalTaskListener", listener);
    }

    @AfterEach
    void cleanUp() {
        if (deployment != null) {
            repositoryService.deleteDeployment(deployment.getId(), true);
            deployment = null;
        }
    }

    @Test
    void shouldSetAssigneeFromApproverConfig() {
        // ========== 1. 构建 ProcessGraph：START → approval → END ==========
        ProcessGraph graph = ProcessGraph.builder()
                .processKey("integration_test")
                .name("Integration Test")
                .elements(List.of(
                        node("start", "START"),
                        node("approval", "APPROVAL", approverConfig("DESIGNATED", List.of("approver1"))),
                        node("end", "END"),
                        edge("e1", "start", "approval"),
                        edge("e2", "approval", "end")
                ))
                .build();

        // ========== 2. 翻译为 BPMN 并部署 ==========
        GraphToBpmnTranslator translator = new GraphToBpmnTranslator();
        BpmnModel model = translator.translate(graph);

        BpmnXMLConverter converter = new BpmnXMLConverter();
        byte[] xmlBytes = converter.convertToXML(model);
        assertThat(xmlBytes).isNotEmpty();

        deployment = repositoryService.createDeployment()
                .addBytes("integration_test.bpmn20.xml", xmlBytes)
                .name("integration_test_deployment")
                .deploy();

        ProcessDefinition def = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId())
                .singleResult();
        assertThat(def).isNotNull();
        assertThat(def.getKey()).isEqualTo("integration_test");

        // ========== 3. 发起流程实例（发起人 = initiator1） ==========
        Map<String, Object> vars = new HashMap<>();
        vars.put("submitter", "initiator1");
        vars.put("recordId", "rec-001");
        vars.put("formKey", "test_form");
        vars.put("tenantId", 1L);
        runtimeService.startProcessInstanceById(def.getId(), "rec-001", vars);

        // ========== 4. 查询 Task ==========
        Task task = taskService.createTaskQuery()
                .processDefinitionId(def.getId())
                .singleResult();
        assertThat(task).isNotNull();

        // ========== 5. 核心断言：assignee = 配置指定人 ≠ 发起人 ==========
        assertThat(task.getAssignee())
                .as("审批人应为 DESIGNATED 配置的 approver1")
                .isEqualTo("approver1");
        assertThat(task.getAssignee())
                .as("审批人不应等于发起人 initiator1")
                .isNotEqualTo("initiator1");

        // ========== 6. 租户透传断言：流程变量中 tenantId 存在且类型正确 ==========
        Object tenantVar = runtimeService.getVariable(task.getProcessInstanceId(), "tenantId");
        assertThat(tenantVar)
                .as("流程变量中应保留 tenantId")
                .isNotNull();
        assertThat(tenantVar)
                .as("process → listener → NodeApproverContext 租户链路打通，ctx.tenantId 不再为 null")
                .isInstanceOfSatisfying(Long.class,
                        v -> assertThat(v).isEqualTo(1L));
    }

    @Test
    void getBpmnXml_shouldReturnOriginalDeployedXml() {
        // ========== 1. 构建简单 BPMN 模型 ==========
        ProcessGraph graph = ProcessGraph.builder()
                .processKey("bpmn_xml_test")
                .name("BPMN XML Test")
                .elements(List.of(
                        node("StartEvent_1", "START"),
                        node("EndEvent_1", "END"),
                        edge("e1", "StartEvent_1", "EndEvent_1")
                ))
                .build();

        GraphToBpmnTranslator translator = new GraphToBpmnTranslator();
        BpmnModel model = translator.translate(graph);
        BpmnXMLConverter converter = new BpmnXMLConverter();
        byte[] xmlBytes = converter.convertToXML(model);
        assertThat(xmlBytes).isNotEmpty();

        // ========== 2. 部署 ==========
        deployment = repositoryService.createDeployment()
                .addBytes("bpmn_xml_test.bpmn20.xml", xmlBytes)
                .name("bpmn_xml_test_deployment")
                .deploy();

        ProcessDefinition def = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId())
                .singleResult();
        assertThat(def).isNotNull();
        assertThat(def.getKey()).isEqualTo("bpmn_xml_test");

        // ========== 3. 调用 getBpmnXml ==========
        BpmDeployFacadeImpl facadeImpl = new BpmDeployFacadeImpl(repositoryService);
        String resultXml = facadeImpl.getBpmnXml(def.getId()).orElseThrow();

        // ========== 4. 断言 ==========
        assertThat(resultXml).isNotEmpty();
        assertThat(resultXml).contains("StartEvent_1");
        assertThat(resultXml).contains("EndEvent_1");
        // 验证可被解析为合法 XML
        assertThatCode(() -> {
            javax.xml.parsers.DocumentBuilderFactory factory =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.newDocumentBuilder().parse(
                    new java.io.ByteArrayInputStream(resultXml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }).doesNotThrowAnyException();
    }

    // ==================== helpers ====================

    private static GraphElement node(String id, String type) {
        return GraphElement.builder()
                .id(id)
                .kind("node")
                .type(type)
                .config(Collections.emptyMap())
                .style(Collections.emptyMap())
                .build();
    }

    private static GraphElement node(String id, String type, Map<String, Object> config) {
        return GraphElement.builder()
                .id(id)
                .kind("node")
                .type(type)
                .config(config)
                .style(Collections.emptyMap())
                .build();
    }

    private static GraphElement edge(String id, String source, String target) {
        return GraphElement.builder()
                .id(id)
                .kind("edge")
                .source(source)
                .target(target)
                .config(Collections.emptyMap())
                .style(Collections.emptyMap())
                .build();
    }

    private static Map<String, Object> approverConfig(String type, List<String> userIds) {
        return Map.of("approver", Map.of("type", type, "value", userIds));
    }


}
