package com.sw.ck.bpm.engine.participant;

import com.sw.ck.bpm.api.expression.RestrictedExpressionEvaluator;
import com.sw.ck.bpm.api.participant.NodeParticipantContext;
import com.sw.ck.bpm.api.participant.NodeParticipantResolver;
import com.sw.ck.bpm.api.participant.ParticipantStrategy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** 受控表达式策略：只读流程变量/表单快照，不执行任意脚本。 */
@Component
public class ExpressionParticipantResolver implements NodeParticipantResolver {

    @Override
    public Optional<String> strategy() {
        return Optional.of(ParticipantStrategy.EXPRESSION);
    }

    @Override
    public Optional<List<String>> resolve(NodeParticipantContext context) {
        // 表达式求值契约恒 present（求值结果为空/空白时为空列表，属合法零匹配）；
        // 语法非法继续抛 IllegalArgumentException
        return RestrictedExpressionEvaluator.values(
                String.valueOf(context.getStrategyValue()), context.getVariables());
    }
}
