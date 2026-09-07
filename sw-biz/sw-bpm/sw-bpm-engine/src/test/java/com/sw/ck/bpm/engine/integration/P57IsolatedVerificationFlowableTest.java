package com.sw.ck.bpm.engine.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.node.BpmNodeDefinition;
import com.sw.ck.bpm.api.node.BpmNodeCapability;
import com.sw.ck.bpm.api.node.BpmNodeMetadata;
import com.sw.ck.bpm.api.node.BpmNodeTopology;
import com.sw.ck.bpm.api.node.BpmNodeRegistry;
import com.sw.ck.bpm.engine.integration.fixture.P57IsolatedVerificationNode;
import com.sw.ck.bpm.engine.registry.BpmNodeRegistryImpl;
import com.sw.ck.bpm.engine.translator.GraphToBpmnTranslator;
import com.sw.ck.bpm.engine.translator.NodeTypeTranslator;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.ProcessEngines;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * P57 审查 G1/G3 行为证据：隔离节点发现、重建注册、真实 Flowable 发布/发起/执行和失败零写入。
 */
class P57IsolatedVerificationFlowableTest {

    private static final AtomicBoolean VERIFICATION_DELEGATE_RAN = new AtomicBoolean();
    private static ProcessEngine processEngine;
    private static RepositoryService repositoryService;
    private static RuntimeService runtimeService;
    private static Deployment deployment;

    @BeforeAll
    static void startEngine() {
        ProcessEngineConfigurationImpl config =
                (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
                        .createStandaloneInMemProcessEngineConfiguration();
        config.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        config.setJdbcUrl("jdbc:h2:mem:p57-isolated-verification;DB_CLOSE_DELAY=-1");
        config.setJdbcDriver("org.h2.Driver");
        config.setJdbcUsername("sa");
        config.setJdbcPassword("");

        Map<Object, Object> beans = new HashMap<>();
        beans.put("p57VerificationNodeDelegate", (org.flowable.engine.delegate.JavaDelegate) execution -> {
            VERIFICATION_DELEGATE_RAN.set(true);
            execution.setVariable("p57VerificationResult", "OBSERVED");
        });
        config.addExpressionManagerConfigurer(expressionManager -> {
            if (expressionManager instanceof org.flowable.common.engine.impl.el.DefaultExpressionManager dem) {
                dem.setBeans(beans);
            }
        });

        processEngine = config.buildProcessEngine();
        repositoryService = processEngine.getRepositoryService();
        runtimeService = processEngine.getRuntimeService();
    }

    @AfterAll
    static void stopEngine() {
        if (deployment != null) {
            repositoryService.deleteDeployment(deployment.getId(), true);
        }
        if (processEngine != null) {
            processEngine.close();
            ProcessEngines.unregister(processEngine);
        }
    }

    @Test
    void shouldDiscoverAfterContextRecreationAndRunIsolatedNode() {
        BpmNodeRegistry firstRegistry = discoverRegistry();
        BpmNodeRegistry restartedRegistry = discoverRegistry();

        List<String> firstTypes = firstRegistry.definitions().stream()
                .map(BpmNodeDefinition::type).toList();
        List<String> restartedTypes = restartedRegistry.definitions().stream()
                .map(BpmNodeDefinition::type).toList();
        assertThat(firstTypes).contains("P57_VERIFY");
        assertThat(restartedTypes).isEqualTo(firstTypes);

        GraphToBpmnTranslator translator = new GraphToBpmnTranslator(
                new ObjectMapper(), restartedRegistry);
        BpmnModel model = translator.translate(isolatedGraph());
        assertThat(model.getProcesses().get(0).getFlowElement("verify"))
                .isInstanceOf(org.flowable.bpmn.model.ServiceTask.class);

        long deploymentsBefore = repositoryService.createDeploymentQuery().count();
        long definitionsBefore = repositoryService.createProcessDefinitionQuery().count();
        VERIFICATION_DELEGATE_RAN.set(false);

        deployment = repositoryService.createDeployment()
                .addBytes("p57-isolated.bpmn20.xml", new BpmnXMLConverter().convertToXML(model))
                .name("p57-isolated-verification")
                .deploy();
        ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult();
        assertThat(processDefinition).isNotNull();

        String processInstanceId = runtimeService
                .startProcessInstanceById(processDefinition.getId()).getId();

        assertThat(VERIFICATION_DELEGATE_RAN).as("隔离节点 delegate 应真实执行").isTrue();
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).count()).isZero();
        assertThat(repositoryService.createDeploymentQuery().count())
                .isEqualTo(deploymentsBefore + 1);
        assertThat(repositoryService.createProcessDefinitionQuery().count())
                .isEqualTo(definitionsBefore + 1);

        long deploymentsBeforeRejectedPublish = repositoryService.createDeploymentQuery().count();
        long definitionsBeforeRejectedPublish = repositoryService.createProcessDefinitionQuery().count();
        assertThat(assertThrows(com.sw.ck.common.exception.BaseException.class,
                () -> translator.translate(graphWithUnknownType())).getCode())
                .isEqualTo(BpmErrorCode.NODE_CAPABILITY_MISSING.getCode());
        assertThat(repositoryService.createDeploymentQuery().count())
                .isEqualTo(deploymentsBeforeRejectedPublish);
        assertThat(repositoryService.createProcessDefinitionQuery().count())
                .isEqualTo(definitionsBeforeRejectedPublish);
    }

    @Test
    void shouldRejectNullTranslationResultInsteadOfWritingPartialModel() {
        NodeTypeTranslator nullTranslator = new NodeTypeTranslator() {
            @Override
            public String type() {
                return "NULL_TRANSLATION";
            }

            @Override
            public BpmNodeMetadata metadata() {
                return new BpmNodeMetadata(
                        "空翻译", "测试空翻译", "OTHER",
                        new BpmNodeTopology(0, 1, 0, 1),
                        List.of(), "test-v1",
                        java.util.EnumSet.allOf(BpmNodeCapability.class),
                        false, false, false, true);
            }

            @Override
            public org.flowable.bpmn.model.FlowElement translate(GraphElement node) {
                return null;
            }
        };

        GraphToBpmnTranslator translator = new GraphToBpmnTranslator(
                new ObjectMapper(), List.of(nullTranslator));
        assertThat(assertThrows(com.sw.ck.common.exception.BaseException.class,
                () -> translator.translate(graphWithNullTranslation()))
                .getCode()).isEqualTo(BpmErrorCode.NODE_CAPABILITY_MISSING.getCode());
    }

    private static BpmNodeRegistry discoverRegistry() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.scan("com.sw.ck.bpm.engine.translator",
                    "com.sw.ck.bpm.engine.integration.fixture");
            context.refresh();
            return new BpmNodeRegistryImpl(List.copyOf(
                    context.getBeansOfType(NodeTypeTranslator.class).values()));
        }
    }

    private static ProcessGraph isolatedGraph() {
        return ProcessGraph.builder()
                .processKey("p57_isolated_runtime")
                .elements(List.of(
                        node("start", "START"),
                        node("verify", "P57_VERIFY", Map.of("message", "observed")),
                        node("end", "END"),
                        edge("e1", "start", "verify"),
                        edge("e2", "verify", "end")))
                .build();
    }

    private static ProcessGraph graphWithUnknownType() {
        return ProcessGraph.builder()
                .processKey("p57_unknown_runtime")
                .elements(List.of(
                        node("start", "START"),
                        node("unknown", "P57_UNKNOWN"),
                        node("end", "END"),
                        edge("e1", "start", "unknown"),
                        edge("e2", "unknown", "end")))
                .build();
    }

    private static ProcessGraph graphWithNullTranslation() {
        return ProcessGraph.builder()
                .processKey("p57_null_translation")
                .elements(List.of(
                        node("start", "START"),
                        node("null-node", "NULL_TRANSLATION"),
                        node("end", "END"),
                        edge("e1", "start", "null-node"),
                        edge("e2", "null-node", "end")))
                .build();
    }

    private static GraphElement node(String id, String type) {
        return node(id, type, Collections.emptyMap());
    }

    private static GraphElement node(String id, String type, Map<String, Object> config) {
        return GraphElement.builder()
                .id(id).kind("node").type(type).config(config).style(Collections.emptyMap()).build();
    }

    private static GraphElement edge(String id, String source, String target) {
        return GraphElement.builder()
                .id(id).kind("edge").source(source).target(target)
                .config(Collections.emptyMap()).style(Collections.emptyMap()).build();
    }
}
