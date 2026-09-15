package com.sw.ck.bpm.process.config;

import java.util.Map;

import com.sw.ck.bpm.api.participant.ConsensusVotePort;
import com.sw.ck.bpm.api.participant.LifecycleTaskEntryPort;
import com.sw.ck.bpm.process.service.ApprovalLifecycleService;
import com.sw.ck.bpm.process.service.NodeFunctionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * I3 引擎 ↔ 业务端口装配（bpm-process）。
 * <p>
 * 代理/时限与订阅计数端口面向 sw-bpm-api 契约暴露；实现全部委托
 * {@link ApprovalLifecycleService}，避免业务服务直接进入引擎监听类。
 * </p>
 */
@Configuration
public class BpmLifecyclePortConfiguration {

    @Bean
    public LifecycleTaskEntryPort lifecycleTaskEntryPort(
            ObjectProvider<ApprovalLifecycleService> lifecycleService,
            ObjectProvider<NodeFunctionService> nodeFunctionService) {
        return new LifecycleTaskEntryPort() {
            @Override
            public String onTaskCreate(Long tenantId, String processInstanceId, String nodeKey,
                                       String taskId, java.util.List<String> resolvedUsers,
                                       String nodeConfig) {
                ApprovalLifecycleService service = lifecycleService.getIfAvailable();
                return service == null ? null
                        : service.lifecycleTaskEntryPort().onTaskCreate(tenantId, processInstanceId,
                        nodeKey, taskId, resolvedUsers, nodeConfig);
            }

            @Override
            public java.util.List<String> resolveParticipantsByFunction(
                    Long tenantId, String processInstanceId, String nodeKey, String taskId,
                    java.util.Map<String, Object> variables, String nodeConfig) {
                NodeFunctionService functions = nodeFunctionService.getIfAvailable();
                if (functions == null) {
                    return null;
                }
                java.util.Map<String, Object> config = parse(configOf(nodeConfig));
                return functions.resolveParticipants(tenantId, processInstanceId, nodeKey,
                        taskId, config, variables);
            }

            private Object configOf(String nodeConfig) {
                if (nodeConfig == null || nodeConfig.isBlank()) {
                    return java.util.Map.of();
                }
                try {
                    return new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(nodeConfig);
                } catch (Exception e) {
                    return java.util.Map.of();
                }
            }

            private java.util.Map<String, Object> parse(Object raw) {
                if (raw instanceof Map<?, ?> map) {
                    java.util.Map<String, Object> copy = new java.util.LinkedHashMap<>();
                    map.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    return copy;
                }
                return java.util.Map.of();
            }
        };
    }

    @Bean
    public ConsensusVotePort consensusVotePort(
            ObjectProvider<ApprovalLifecycleService> lifecycleService) {
        return new ConsensusVotePort() {
            @Override
            public boolean record(String tenantId, String processInstanceId, String nodeKey,
                                  String taskId, String actorId, String outcome) {
                ApprovalLifecycleService service = lifecycleService.getIfAvailable();
                return service != null && service.consensusVotePort().record(tenantId,
                        processInstanceId, nodeKey, taskId, actorId, outcome);
            }

            @Override
            public long count(String tenantId, String processInstanceId, String nodeKey,
                              String outcome) {
                ApprovalLifecycleService service = lifecycleService.getIfAvailable();
                return service == null ? -1L
                        : service.consensusVotePort().count(tenantId, processInstanceId,
                        nodeKey, outcome);
            }
        };
    }

    @Bean
    public com.sw.ck.bpm.api.participant.ConsensusSettlementPort consensusSettlementPort(
            ObjectProvider<ApprovalLifecycleService> lifecycleService) {
        return new com.sw.ck.bpm.api.participant.ConsensusSettlementPort() {
            @Override
            public void onNegativeSettlement(String tenantId, String processInstanceId,
                                             String nodeKey, String reason) {
                ApprovalLifecycleService service = lifecycleService.getIfAvailable();
                if (service == null) {
                    return;
                }
                if (tenantId == null) {
                    // 租户变量在流程启动时已强制非空；缺失属异常路径，fail closed 不落租户 0
                    throw new IllegalArgumentException(
                            "会签负向结算缺少租户上下文: processInstanceId=" + processInstanceId);
                }
                service.settleConsensusNegative(tenantId, processInstanceId, nodeKey, reason);
            }
        };
    }

}
