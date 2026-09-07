package com.sw.ck.bpm.engine.participant;

import com.sw.ck.bpm.api.expression.RestrictedExpressionEvaluator;
import com.sw.ck.bpm.api.participant.NodeParticipantContext;
import com.sw.ck.bpm.api.participant.NodeParticipantResolver;
import com.sw.ck.bpm.api.participant.ParticipantStrategy;
import org.springframework.stereotype.Component;

import java.util.List;

/** 受控表达式策略：只读流程变量/表单快照，不执行任意脚本。 */
@Component
public class ExpressionParticipantResolver implements NodeParticipantResolver {

    @Override
    public String strategy() {
        return ParticipantStrategy.EXPRESSION;
    }

    @Override
    public List<String> resolve(NodeParticipantContext context) {
        return RestrictedExpressionEvaluator.values(
                String.valueOf(context.getStrategyValue()), context.getVariables());
    }
}
