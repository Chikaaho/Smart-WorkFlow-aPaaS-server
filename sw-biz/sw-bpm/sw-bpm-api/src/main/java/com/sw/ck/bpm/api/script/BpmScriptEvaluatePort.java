package com.sw.ck.bpm.api.script;

import java.util.Map;
import java.util.Optional;

/**
 * P64 受控判断脚本执行端口（ADR-P64-001 §3）。
 * <p>
 * 契约在 bpm-api，实现随脚本运行时所在模块装配（GraalJS 等重型依赖不得进入
 * sw-bpm-process 类路径——IotContractBoundaryIsolationTest 门禁）；调用方只消费
 * 本端口的结果记录。脚本上下文仅授权变量快照，输出仅 Number/String/Boolean/null。
 * 模块内部调用边界统一返回非空 {@link Optional}：执行/校验结果恒 present
 * （失败以 ScriptOutcome.kind 表达，不以上空表达）。
 * </p>
 */
public interface BpmScriptEvaluatePort {

    /** 判断执行默认超时毫秒（主方向 §3.5：单次判断最长 5000ms）。 */
    long DEFAULT_TIMEOUT_MS = 5_000L;

    /**
     * 执行判断脚本（函数体，顶层 return）。
     *
     * @param script    脚本函数体
     * @param variables 授权变量快照（只读）
     * @param timeoutMs 执行超时毫秒
     * @return present = 结果（kind: OK / SCRIPT_ERROR / TYPE_ERROR / RESOURCE_LIMIT / TIMEOUT）；
     *         当前契约恒 present
     */
    Optional<ScriptOutcome> run(String script, Map<String, Object> variables, long timeoutMs);

    /**
     * BF20261013-021：带宿主函数上下文的执行（流程变量读写 / 组织目录函数用）。
     * 默认实现忽略上下文，既有实现与调用方保持向后兼容。
     *
     * @param functionContext 组织目录与实例变量快照；{@code null} 等价 {@link ScriptFunctionContext#EMPTY}
     * @return present = 结果（variableWrites 为脚本 设置流程变量 收集到的写入，仅 OK 时消费方允许落库）
     */
    default Optional<ScriptOutcome> run(String script, Map<String, Object> variables, long timeoutMs,
                                        ScriptFunctionContext functionContext) {
        return run(script, variables, timeoutMs);
    }

    /**
     * 语法/安全校验（仅编译不执行）。
     *
     * @return present = 结果（OK 或 SCRIPT_ERROR，errorMessage 为编译失败原因）；
     *         当前契约恒 present
     */
    Optional<ScriptOutcome> validate(String script);

    /** 脚本执行结果记录。 */
    record ScriptOutcome(String kind, Object value, String typeName, String errorMessage, long durationMs,
                         Map<String, Object> variableWrites) {

        /** 兼容构造：无变量写入的场景（校验/旧调用链）默认空写入。 */
        public ScriptOutcome(String kind, Object value, String typeName, String errorMessage, long durationMs) {
            this(kind, value, typeName, errorMessage, durationMs, Map.of());
        }

        public static final String KIND_OK = "OK";
        public static final String KIND_SCRIPT_ERROR = "SCRIPT_ERROR";
        public static final String KIND_TYPE_ERROR = "TYPE_ERROR";
        public static final String KIND_RESOURCE_LIMIT = "RESOURCE_LIMIT";
        public static final String KIND_TIMEOUT = "TIMEOUT";

        public boolean ok() {
            return KIND_OK.equals(kind);
        }

        public boolean resourceLimited() {
            return KIND_RESOURCE_LIMIT.equals(kind) || KIND_TIMEOUT.equals(kind);
        }
    }
}
