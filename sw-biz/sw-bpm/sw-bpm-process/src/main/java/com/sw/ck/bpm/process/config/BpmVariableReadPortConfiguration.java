package com.sw.ck.bpm.process.config;

import com.sw.ck.bpm.api.variable.BpmVariableReadPort;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.BpmVariableSnapshotService;
import com.sw.ck.bpm.process.service.NodeFormDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Optional;

/**
 * P64 BPM 变量读取端口装配（bpm-process）。
 * <p>
 * 引擎（动态并行 VARIABLE 来源等）在流程变量缺省时回退读取业务 BPM 变量；
 * 本装配复用 {@link BpmVariableSnapshotService} 的同一解析口径（冻结图 + 当前有效轮次 +
 * 只读本次已最终提交的节点数据），保证"设计器变量"与"流程变量"取值一致、读取时点明确。
 * 解析失败（变量未定义/取值异常/超限）返回 empty，由节点 emptyStrategy 给出确定结果。
 * </p>
 */
@Slf4j
@Configuration
public class BpmVariableReadPortConfiguration {

    @Bean
    public BpmVariableReadPort bpmVariableReadPort(BpmInstanceService bpmInstanceService,
                                                   NodeFormDataService nodeFormDataService,
                                                   BpmVariableSnapshotService variableSnapshotService) {
        return (processInstanceId, varId) -> {
            if (processInstanceId == null || varId == null || varId.isBlank()) {
                return Optional.empty();
            }
            BpmInstance instance = bpmInstanceService.findByProcessInstanceId(processInstanceId)
                    .orElse(null);
            if (instance == null) {
                return Optional.empty();
            }
            ProcessGraph graph = nodeFormDataService.loadGraph(instance.getProcessDefKey(),
                    instance.getDefVersion());
            if (graph == null || graph.getVariables() == null || graph.getVariables().isEmpty()) {
                return Optional.empty();
            }
            long round = nodeFormDataService.currentRound(processInstanceId);
            BpmVariableSnapshotService.SnapshotResult snapshot = variableSnapshotService.buildSnapshot(
                    instance.getTenantId(), instance, graph.getVariables(), List.of(varId), null, round);
            if (snapshot.tooLarge() || !snapshot.errors().isEmpty()) {
                log.warn("BPM 变量回退读取失败: instance={}, varId={}, errors={}", processInstanceId, varId,
                        snapshot.errors());
                return Optional.empty();
            }
            Object value = snapshot.values().get(varId);
            return value == null ? Optional.empty() : Optional.of(value);
        };
    }
}
