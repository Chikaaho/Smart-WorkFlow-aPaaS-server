package com.sw.ck.bpm.engine.script;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * P64 受控判断脚本运行器（ADR-P64-001 §3；BPM 独立只读判断上下文，不复用 iot 宿主函数）。
 * <p>
 * 边界：脚本体为函数体（顶层 {@code return}），经宿主函数 {@code 流程变量取值(name)}（别名
 * {@code getVariable}）读取授权变量快照；无宿主访问、无 IO/进程/线程/原生访问；
 * 语句 ≤500,000（ResourceLimits）、wall clock ≤5,000ms（watcher 强制 close）、
 * 输出仅 Number/String/Boolean/null 且序列化 ≤4KiB；其余一律判 TYPE/RESOURCE 错误，
 * 不产生默认成功结果。
 * </p>
 */
@Component
public class BpmScriptRunner {

    private static final Logger log = LoggerFactory.getLogger(BpmScriptRunner.class);

    public static final long MAX_STATEMENTS = 500_000L;
    public static final long DEFAULT_TIMEOUT_MS = 5_000L;
    /** 输出序列化字节上限（主方向 §3.5：标量输出最多 4KiB）。 */
    public static final int MAX_OUTPUT_BYTES = 4 * 1024;

    /** 变量读取宿主函数名（Owner 摘要约定）+ 英文别名。 */
    public static final String FN_READ_VARIABLE = "流程变量取值";
    public static final String FN_READ_VARIABLE_ALIAS = "getVariable";

    private final ObjectMapper objectMapper;

    public BpmScriptRunner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    private String jsonOf(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "null";
        }
    }

    /** 执行结果。 */
    public record Result(Kind kind, Object value, String typeName, String errorMessage, long durationMs) {
        public enum Kind { OK, SCRIPT_ERROR, TYPE_ERROR, RESOURCE_LIMIT, TIMEOUT }
    }

    /**
     * 执行判断脚本。
     *
     * @param script    脚本函数体（顶层 return）
     * @param variables 授权变量快照（只读；未授权变量在解析层已被排除，此处缺名 = 读取错误）
     */
    public Result run(String script, Map<String, Object> variables) {
        return run(script, variables, DEFAULT_TIMEOUT_MS);
    }

    public Result run(String script, Map<String, Object> variables, long timeoutMs) {
        long begin = System.currentTimeMillis();
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicBoolean statementLimitTripped = new AtomicBoolean(false);
        Context context = null;
        try {
            context = Context.newBuilder("js")
                    .allowAllAccess(false)
                    .allowHostAccess(HostAccess.NONE)
                    .allowHostClassLoading(false)
                    .allowHostClassLookup(cls -> false)
                    .allowIO(IOAccess.NONE)
                    .allowCreateProcess(false)
                    .allowNativeAccess(false)
                    .allowCreateThread(false)
                    .allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.NONE)
                    .resourceLimits(ResourceLimits.newBuilder()
                            .statementLimit(MAX_STATEMENTS, null)
                            .onLimit(event -> statementLimitTripped.set(true))
                            .build())
                    .build();
            final Context ctx = context;
            Map<String, Object> snapshot = variables == null ? Map.of() : variables;

            Thread watcher = new Thread(() -> {
                try {
                    Thread.sleep(Math.max(500, timeoutMs));
                    if (!finished.get()) {
                        log.warn("P64 判断脚本执行超时，强制中断");
                        ctx.close(true);
                    }
                } catch (InterruptedException ignore) {
                    Thread.currentThread().interrupt();
                }
            });
            watcher.setDaemon(true);
            watcher.start();

            Value bindings = context.getBindings("js");
            org.graalvm.polyglot.proxy.ProxyExecutable reader = args -> {
                if (args.length < 1 || args[0] == null || !args[0].isString()) {
                    throw new RuntimeException("流程变量取值(name) 需要字符串变量名");
                }
                String name = args[0].asString();
                if (!snapshot.containsKey(name)) {
                    // 未授权/不存在变量：明确脚本错误，不静默回 null（方向 §3.1 可诊断失败）
                    throw new RuntimeException("变量未授权或不存在: " + name);
                }
                Object value = snapshot.get(name);
                // 以 JSON 重建纯 JS 值，宿主对象不进入脚本上下文
                return ctx.eval("js", value == null ? "null" : "(" + jsonOf(value) + ")");
            };
            bindings.putMember(FN_READ_VARIABLE, reader);
            bindings.putMember(FN_READ_VARIABLE_ALIAS, reader);

            // 函数体包裹：脚本顶层 return 即函数返回
            context.eval("js", "function __p64Handle__() {\n" + script + "\n}");
            Value handle = bindings.getMember("__p64Handle__");
            if (handle == null || !handle.canExecute()) {
                return new Result(Result.Kind.SCRIPT_ERROR, null, null, "脚本缺少可执行函数体", elapsed(begin));
            }
            Value out = handle.execute();
            finished.set(true);
            watcher.interrupt();
            return classify(out, elapsed(begin));
        } catch (org.graalvm.polyglot.PolyglotException e) {
            finished.set(true);
            long duration = elapsed(begin);
            if (statementLimitTripped.get() || e.isResourceExhausted()) {
                // 语句上限触发的取消同时带 cancelled/resource 标记，先按资源超限归类；
                // watcher 强制中断只置 cancelled，落到下方 TIMEOUT。
                return new Result(Result.Kind.RESOURCE_LIMIT, null, null, "脚本资源超限（语句上限）: " + e.getMessage(), duration);
            }
            if (e.isCancelled()) {
                return new Result(Result.Kind.TIMEOUT, null, null, "脚本执行超时被强制中断: " + e.getMessage(), duration);
            }
            return new Result(Result.Kind.SCRIPT_ERROR, null, null, "脚本执行失败: " + e.getMessage(), duration);
        } catch (Exception e) {
            finished.set(true);
            return new Result(Result.Kind.SCRIPT_ERROR, null, null, "脚本执行失败: " + e.getMessage(), elapsed(begin));
        } finally {
            if (context != null) {
                try {
                    context.close();
                } catch (org.graalvm.polyglot.PolyglotException ignore) {
                    // 已取消/超限的 Context 关闭会重抛原异常，忽略
                }
            }
        }
    }

    /** 语法/安全校验（仅编译不执行；与 run 相同包裹与宿主函数形状）。 */
    public Result validate(String script) {
        try (Context context = Context.newBuilder("js")
                .allowAllAccess(false)
                .allowHostAccess(HostAccess.NONE)
                .allowHostClassLoading(false)
                .allowIO(IOAccess.NONE)
                .allowCreateProcess(false)
                .allowNativeAccess(false)
                .build()) {
            context.eval("js", "function __p64Handle__() {\n" + script + "\n}");
            return new Result(Result.Kind.OK, null, null, null, 0);
        } catch (Exception e) {
            return new Result(Result.Kind.SCRIPT_ERROR, null, null, "语法校验失败: " + e.getMessage(), 0);
        }
    }

    private Result classify(Value out, long durationMs) {
        if (out == null || out.isNull()) {
            return new Result(Result.Kind.OK, null, "NULL", null, durationMs);
        }
        Object value;
        String typeName;
        if (out.isNumber()) {
            value = out.fitsInLong() ? (Object) out.asLong() : (Object) out.asDouble();
            typeName = "NUMBER";
        } else if (out.isBoolean()) {
            value = out.asBoolean();
            typeName = "BOOLEAN";
        } else if (out.isString()) {
            value = out.asString();
            typeName = "STRING";
        } else {
            return new Result(Result.Kind.TYPE_ERROR, null, null,
                    "脚本返回类型必须是 Number/String/Boolean/null，实际: " + out.toString(), durationMs);
        }
        String serialized = jsonOf(value);
        if (serialized != null && serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES) {
            return new Result(Result.Kind.RESOURCE_LIMIT, null, null,
                    "脚本输出超出 " + MAX_OUTPUT_BYTES + " 字节上限", durationMs);
        }
        return new Result(Result.Kind.OK, value, typeName, null, durationMs);
    }

    private long elapsed(long begin) {
        return System.currentTimeMillis() - begin;
    }
}
