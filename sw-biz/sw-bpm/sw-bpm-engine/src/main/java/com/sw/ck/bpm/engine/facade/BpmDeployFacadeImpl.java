package com.sw.ck.bpm.engine.facade;

import com.sw.ck.bpm.api.dto.BpmDeployResult;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.facade.BpmDeployFacade;
import com.sw.ck.bpm.api.result.MutationOutcome;
import com.sw.ck.bpm.engine.translator.GraphToBpmnTranslator;
import com.sw.ck.common.exception.BaseException;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * BPM 部署门面实现 —— 封装 Flowable {@link RepositoryService}。
 * <p>
 * {@code Optional.empty()} 只表达"目标定义不存在"；部署失败、参数非法与引擎异常继续抛明确异常。
 * </p>
 */
@Service
public class BpmDeployFacadeImpl implements BpmDeployFacade {

    private static final Logger log = LoggerFactory.getLogger(BpmDeployFacadeImpl.class);

    private final RepositoryService repositoryService;
    private final GraphToBpmnTranslator graphToBpmnTranslator;

    public BpmDeployFacadeImpl(RepositoryService repositoryService) {
        this(repositoryService, new GraphToBpmnTranslator());
    }

    @Autowired
    public BpmDeployFacadeImpl(RepositoryService repositoryService,
                               GraphToBpmnTranslator graphToBpmnTranslator) {
        this.repositoryService = repositoryService;
        this.graphToBpmnTranslator = graphToBpmnTranslator;
    }

    @Override
    public Optional<String> deployClasspathBpmn(String resourcePath, String deploymentName) {
        Deployment deployment = repositoryService.createDeployment()
                .addClasspathResource(resourcePath)
                .name(deploymentName)
                .deploy();
        log.info("BPMN deployed: deploymentId={}, name={}, resource={}",
                deployment.getId(), deploymentName, resourcePath);
        return Optional.of(deployment.getId());
    }

    @Override
    public Optional<byte[]> translateToBpmn(ProcessGraph graph) {
        BpmnModel bpmnModel = graphToBpmnTranslator.translate(graph);
        BpmnXMLConverter converter = new BpmnXMLConverter();
        byte[] xmlBytes = converter.convertToXML(bpmnModel);
        log.info("BPMN translation completed: processKey={}, name={}, xmlBytes={}",
                graph.getProcessKey(), graph.getName(), xmlBytes.length);
        return Optional.of(xmlBytes);
    }

    @Override
    public Optional<BpmDeployResult> deployModel(byte[] bpmnXml, String deploymentName) {
        try {
            var deploymentBuilder = repositoryService.createDeployment()
                    .addBytes("process.bpmn20.xml", bpmnXml)
                    .name(deploymentName);
            // 部署跟随发布者租户：发起/待办查询均按 tenantId 过滤，定义无租户会导致实例发起失败
            com.sw.ck.security.holder.LoginUser loginUser = com.sw.ck.security.holder.LoginUserHolder.get();
            if (loginUser != null && loginUser.getTenantId() != null) {
                deploymentBuilder.tenantId(String.valueOf(loginUser.getTenantId()));
            }
            Deployment deployment = deploymentBuilder.deploy();

            // 查询部署中的流程定义（首个）
            ProcessDefinition processDef = repositoryService.createProcessDefinitionQuery()
                    .deploymentId(deployment.getId())
                    .singleResult();

            if (processDef == null) {
                log.warn("No process definition found in deployment: deploymentId={}", deployment.getId());
                // 部署成功但无定义——不太可能，但防御性处理
                return Optional.of(BpmDeployResult.builder()
                        .deploymentId(deployment.getId())
                        .build());
            }

            log.info("BPMN model deployed: deploymentId={}, processDefinitionId={}, processDefKey={}",
                    deployment.getId(), processDef.getId(), processDef.getKey());

            return Optional.of(BpmDeployResult.builder()
                    .deploymentId(deployment.getId())
                    .processDefinitionId(processDef.getId())
                    .build());
        } catch (Exception e) {
            log.error("BPMN deployment failed: {}", e.getMessage(), e);
            throw new BaseException(BpmErrorCode.DEPLOYMENT_FAILED);
        }
    }

    @Override
    public Optional<String> getBpmnXml(String processDefinitionId) {
        ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(processDefinitionId)
                .singleResult();
        if (processDefinition == null) {
            // 该流程定义不存在：目标缺失，不产生 empty 之外的业务副作用
            return Optional.empty();
        }
        try (InputStream is = repositoryService.getResourceAsStream(
                processDefinition.getDeploymentId(), processDefinition.getResourceName())) {
            return Optional.of(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<MutationOutcome> suspendProcessDefinition(String processDefinitionId) {
        if (processDefinitionId == null || processDefinitionId.isBlank()) {
            throw new BaseException(BpmErrorCode.PROCESS_NOT_PUBLISHED);
        }
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(processDefinitionId)
                .singleResult();
        if (definition == null) {
            // 定义不存在：保持原路径由 Flowable 抛明确异常
            repositoryService.suspendProcessDefinitionById(processDefinitionId, true, null);
            return Optional.of(MutationOutcome.APPLIED);
        }
        if (definition.isSuspended()) {
            // 已处于挂起态：合法幂等，不产生第二次效果
            return Optional.of(MutationOutcome.ALREADY_APPLIED);
        }
        repositoryService.suspendProcessDefinitionById(processDefinitionId, true, null);
        log.info("BPM process definition suspended: processDefinitionId={}", processDefinitionId);
        return Optional.of(MutationOutcome.APPLIED);
    }

    @Override
    public Optional<MutationOutcome> activateProcessDefinition(String processDefinitionId) {
        if (processDefinitionId == null || processDefinitionId.isBlank()) {
            throw new BaseException(BpmErrorCode.PROCESS_NOT_PUBLISHED);
        }
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(processDefinitionId)
                .singleResult();
        if (definition == null) {
            // 定义不存在：保持原路径由 Flowable 抛明确异常
            repositoryService.activateProcessDefinitionById(processDefinitionId, true, null);
            return Optional.of(MutationOutcome.APPLIED);
        }
        if (!definition.isSuspended()) {
            // 已处于激活态：合法幂等，不产生第二次效果
            return Optional.of(MutationOutcome.ALREADY_APPLIED);
        }
        repositoryService.activateProcessDefinitionById(processDefinitionId, true, null);
        log.info("BPM process definition activated: processDefinitionId={}", processDefinitionId);
        return Optional.of(MutationOutcome.APPLIED);
    }
}
