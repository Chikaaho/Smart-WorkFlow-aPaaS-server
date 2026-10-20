package com.sw.ck.bpm.engine.participant;

import com.sw.ck.bpm.api.expression.RestrictedExpressionEvaluator;
import com.sw.ck.bpm.api.participant.NodeParticipantContext;
import com.sw.ck.bpm.api.participant.NodeParticipantResolver;
import com.sw.ck.bpm.api.participant.ParticipantScriptResolvePort;
import com.sw.ck.bpm.api.participant.ParticipantStrategy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 审批人表达式策略（EXPRESSION）。
 * <p>
 * 流程表达式是**计算值**的适配器：脚本以 {@code return} 返回参与人。运行期优先经
 * {@link ParticipantScriptResolvePort} 交给业务侧脚本引擎执行（与触发器同一 GraalJS +
 * {@code #} 函数语法糖，可读流程变量与组织目录）；业务侧未装配该接缝时（单元测试/纯引擎场景）
 * 退回既有受控路径表达式，保持兼容。
 * </p>
 */
@Component
public class ExpressionParticipantResolver implements NodeParticipantResolver {

    private final ObjectProvider<ParticipantScriptResolvePort> scriptPort;

    @Autowired
    public ExpressionParticipantResolver(ObjectProvider<ParticipantScriptResolvePort> scriptPort) {
        this.scriptPort = scriptPort;
    }

    /** 测试接缝：无业务侧脚本服务时退回受控路径表达式。 */
    public ExpressionParticipantResolver() {
        this.scriptPort = null;
    }

    @Override
    public Optional<String> strategy() {
        return Optional.of(ParticipantStrategy.EXPRESSION);
    }

    @Override
    public Optional<List<String>> resolve(NodeParticipantContext context) {
        String expression = String.valueOf(context.getStrategyValue());
        ParticipantScriptResolvePort port = scriptPort == null ? null : scriptPort.getIfAvailable();
        if (port != null) {
            // 表达式=脚本：由业务侧脚本引擎求值（契约恒 present，空列表属合法零匹配）
            return port.resolve(context.getTenantId(), context.getProcessInstanceId(),
                    expression, context.getVariables());
        }
        // 兼容路径：受控路径表达式（只读变量，不执行脚本）
        return RestrictedExpressionEvaluator.values(expression, context.getVariables());
    }
}
