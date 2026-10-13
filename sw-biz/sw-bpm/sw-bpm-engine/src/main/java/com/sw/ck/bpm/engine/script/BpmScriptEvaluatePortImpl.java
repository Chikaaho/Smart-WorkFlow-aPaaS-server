package com.sw.ck.bpm.engine.script;

import com.sw.ck.bpm.api.script.BpmScriptEvaluatePort;
import com.sw.ck.security.holder.LoginUserHolder;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * {@link BpmScriptEvaluatePort} 引擎侧实现：判断经 {@link ScriptWorkerPool} 在专职
 * worker JVM（-Xmx128m）内隔离执行（全局/租户并发上限 + 有限排队 + 满额繁忙可恢复），
 * 供 sw-bpm-process（发布校验/触发执行/预览）消费；重型依赖不进入 process 类路径。
 * <p>
 * 语法校验（validate，仅编译不执行）无资源风险，仍在进程内完成。
 * </p>
 */
@Component
public class BpmScriptEvaluatePortImpl implements BpmScriptEvaluatePort {

    private final BpmScriptRunner runner;
    private final ScriptWorkerPool workerPool;

    public BpmScriptEvaluatePortImpl(BpmScriptRunner runner, ScriptWorkerPool workerPool) {
        this.runner = runner;
        this.workerPool = workerPool;
    }

    @Override
    public Optional<ScriptOutcome> run(String script, Map<String, Object> variables, long timeoutMs) {
        Long tenantId = LoginUserHolder.get() == null ? null : LoginUserHolder.get().getTenantId();
        BpmScriptRunner.Result result = workerPool.evaluate(script, variables, timeoutMs, tenantId);
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
