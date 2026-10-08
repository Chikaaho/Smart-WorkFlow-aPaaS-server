package com.sw.ck.bpm.engine.script;

import com.sw.ck.bpm.api.script.BpmScriptEvaluatePort;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * {@link BpmScriptEvaluatePort} 引擎侧实现：GraalJS 运行器装配为公共端口，
 * 供 sw-bpm-process（发布校验/触发执行/预览）消费；重型依赖不进入 process 类路径。
 */
@Component
public class BpmScriptEvaluatePortImpl implements BpmScriptEvaluatePort {

    private final BpmScriptRunner runner;

    public BpmScriptEvaluatePortImpl(BpmScriptRunner runner) {
        this.runner = runner;
    }

    @Override
    public Optional<ScriptOutcome> run(String script, Map<String, Object> variables, long timeoutMs) {
        BpmScriptRunner.Result result = runner.run(script, variables, timeoutMs);
        return Optional.of(new ScriptOutcome(result.kind().name(), result.value(), result.typeName(),
                result.errorMessage(), result.durationMs()));
    }

    @Override
    public Optional<ScriptOutcome> validate(String script) {
        BpmScriptRunner.Result result = runner.validate(script);
        return Optional.of(new ScriptOutcome(result.kind().name(), result.value(), result.typeName(),
                result.errorMessage(), result.durationMs()));
    }
}
