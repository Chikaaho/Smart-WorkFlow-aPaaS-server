package com.sw.ck.bpm.process.config;

import java.util.Map;
import java.util.Optional;

import com.sw.ck.bpm.api.participant.ConsensusVotePort;
import com.sw.ck.bpm.api.participant.LifecycleTaskEntryPort;
import com.sw.ck.bpm.api.result.MutationOutcome;
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
 * 实现 Bean 不可用（装配缺失）时以 {@code Optional.empty()} 表达"无法裁决/未配置"，
 * 不得伪造业务结论；租户上下文缺失等非法调用继续抛原有异常。
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
            public Optional<String> onTaskCreate(Long tenantId, String processInstanceId, String nodeKey,
                                                 String taskId, java.util.List<String> resolvedUsers,
                                                 String nodeConfig) {
                ApprovalLifecycleService service = lifecycleService.getIfAvailable();
                if (service == null) {
                    // 服务未装配：本次不改写 assignee（走默认派发），不得伪装成空改写结论
                    return Optional.empty();
                }
                return service.lifecycleTaskEntryPort().onTaskCreate(tenantId, processInstanceId,
                        nodeKey, taskId, resolvedUsers, nodeConfig);
            }

            @Override
            public Optional<java.util.List<String>> resolveParticipantsByFunction(
                    Long tenantId, String processInstanceId, String nodeKey, String taskId,
                    java.util.Map<String, Object> variables, String nodeConfig) {
                NodeFunctionService functions = nodeFunctionService.getIfAvailable();
                if (functions == null) {
                    // 函数服务不可用：走默认策略解析（原 null 返回路径）
                    return Optional.empty();
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
            public Optional<Boolean> record(String tenantId, String processInstanceId, String nodeKey,
                                            String taskId, String actorId, String outcome) {
                ApprovalLifecycleService service = lifecycleService.getIfAvailable();
                if (service == null) {
                    // 投票服务不可用：调用方不得据此认为重复（原 false 哨兵已移除）
                    return Optional.empty();
                }
                return service.consensusVotePort().record(tenantId,
                        processInstanceId, nodeKey, taskId, actorId, outcome);
            }

            @Override
            public Optional<Long> count(String tenantId, String processInstanceId, String nodeKey,
                                        String outcome) {
                ApprovalLifecycleService service = lifecycleService.getIfAvailable();
                if (service == null) {
                    // 端口不可用：调用方按既有回退口径处理（原 -1 哨兵已移除）
                    return Optional.empty();
                }
                return service.consensusVotePort().count(tenantId, processInstanceId,
                        nodeKey, outcome);
            }
        };
    }

    @Bean
    public com.sw.ck.bpm.api.participant.ConsensusSettlementPort consensusSettlementPort(
            ObjectProvider<ApprovalLifecycleService> lifecycleService) {
        return new com.sw.ck.bpm.api.participant.ConsensusSettlementPort() {
            @Override
            public Optional<MutationOutcome> onNegativeSettlement(String tenantId, String processInstanceId,
                                                                  String nodeKey, String reason) {
                ApprovalLifecycleService service = lifecycleService.getIfAvailable();
                if (service == null) {
                    // 结算服务未装配：无可变更对象、未产生任何效果（不是失败）
                    return Optional.of(MutationOutcome.ALREADY_APPLIED);
                }
                if (tenantId == null) {
                    // 租户变量在流程启动时已强制非空；缺失属异常路径，fail closed 不落租户 0
                    throw new IllegalArgumentException(
                            "会签负向结算缺少租户上下文: processInstanceId=" + processInstanceId);
                }
                service.settleConsensusNegative(tenantId, processInstanceId, nodeKey, reason);
                return Optional.of(MutationOutcome.APPLIED);
            }
        };
    }

}
