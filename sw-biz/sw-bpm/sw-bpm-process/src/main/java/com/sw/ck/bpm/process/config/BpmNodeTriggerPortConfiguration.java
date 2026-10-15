package com.sw.ck.bpm.process.config;

import com.sw.ck.bpm.api.participant.NodeTriggerEventPort;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.TriggerExecutionService;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * BF20261013-022：节点/流程生命周期触发事件端口装配（engine 经 ObjectProvider 可选回调）。
 * <p>
 * 实例不存在（理论不可能：同事务先落库）时静默跳过；触发器评估异常由
 * {@code TriggerExecutionService#evaluate} 内部落可诊断行，不阻断任务创建/流程发起。
 * </p>
 */
@Configuration
public class BpmNodeTriggerPortConfiguration {

    @Bean
    public NodeTriggerEventPort nodeTriggerEventPort(BpmInstanceService bpmInstanceService,
                                                     TriggerExecutionService triggerService) {
        return new NodeTriggerEventPort() {
            @Override
            public Optional<Long> onNodeEntering(String processInstanceId, String nodeKey) {
                BpmInstance instance = bpmInstanceService.findByProcessInstanceId(processInstanceId)
                        .orElse(null);
                if (instance == null) {
                    return Optional.empty();
                }
                return Optional.of(triggerService.onNodeEntering(instance, nodeKey));
            }

            @Override
            public Optional<Long> onProcessStarted(String processInstanceId) {
                BpmInstance instance = bpmInstanceService.findByProcessInstanceId(processInstanceId)
                        .orElse(null);
                if (instance == null) {
                    return Optional.empty();
                }
                return Optional.of(triggerService.onProcessStarted(instance));
            }
        };
    }
}
