package com.sw.ck.iot.script;

import com.alibaba.fastjson2.JSON;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * GraalJS 沙箱执行器。
 * <p>
 * 无宿主访问、无 IO、无进程/原生访问/环境访问，语句数与内存受限；
 * 超时经 watcher 线程强制中断；宿主函数仅以 fun_* 白名单暴露。
 * </p>
 */
@Component
public class GraalJsRunner {

    private static final Logger log = LoggerFactory.getLogger(GraalJsRunner.class);

    /** 供宿主函数返回值在 JS 侧重建为纯 JS 对象（线程内绑定）。 */
    private static final ThreadLocal<Context> CURRENT_CONTEXT = new ThreadLocal<>();

    private static final long MAX_STATEMENTS = 500_000;
    private static final long MEMORY_LIMIT_BYTES = 64L * 1024 * 1024;

    /**
     * 执行 JavaScript 脚本源码。
     */
    public ScriptRunResult run(ScriptExecutionSpec spec, ScriptHostFunctions host) {
        long begin = System.currentTimeMillis();
        AtomicBoolean finished = new AtomicBoolean(false);
        // 语句上限由本类设置，触发信号也由本类持有（onLimit），与异常描述文本无关。
        AtomicBoolean statementLimitTripped = new AtomicBoolean(false);
        Context context = null;
        try {
            Context.Builder builder = Context.newBuilder("js")
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
                            .build());
            context = builder.build();
            final Context ctx = context;
            CURRENT_CONTEXT.set(context);

            Thread watcher = new Thread(() -> {
                try {
                    Thread.sleep(Math.max(500, spec.getTimeoutMs()));
                    if (!finished.get()) {
                        log.warn("JS 脚本执行超时，强制中断: scriptId={}", spec.getScriptId());
                        ctx.close(true);
                    }
                } catch (InterruptedException ignore) {
                    Thread.currentThread().interrupt();
                }
            });
            watcher.setDaemon(true);
            watcher.start();

            Value bindings = context.getBindings("js");
            bindHostFunctions(bindings, host);
            bindings.putMember("__input__", toJsValue(context, spec.getInput()));

            // 约定：脚本顶层定义 handler(input) 函数；缺省时仅执行顶层语句
            context.eval("js", spec.getSourceCode());
            Value handler = bindings.getMember("handler");
            String outputJson = null;
            if (handler != null && handler.canExecute()) {
                Value out = handler.execute(toJsValue(context, spec.getInput()));
                outputJson = out == null || out.isNull() ? null : out.toString();
            }
            finished.set(true);
            watcher.interrupt();
            CURRENT_CONTEXT.remove();
            return ScriptRunResult.success(outputJson,
                    System.currentTimeMillis() - begin, host.getLogs());
        } catch (org.graalvm.polyglot.PolyglotException e) {
            finished.set(true);
            CURRENT_CONTEXT.remove();
            long duration = System.currentTimeMillis() - begin;
            if (e.isCancelled() || e.isResourceExhausted() || statementLimitTripped.get()) {
                return ScriptRunResult.timeout("脚本被终止（超时或资源超限）: " + e.getMessage(),
                        duration, host.getLogs());
            }
            return ScriptRunResult.failure("JS 执行失败: " + e.getMessage(), duration, host.getLogs());
        } catch (Exception e) {
            finished.set(true);
            CURRENT_CONTEXT.remove();
            return ScriptRunResult.failure("JS 执行失败: " + e.getMessage(),
                    System.currentTimeMillis() - begin, host.getLogs());
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

    /**
     * 语法/安全校验（仅编译不执行 handler）。
     */
    public ScriptRunResult validate(String sourceCode) {
        try (Context context = Context.newBuilder("js")
                .allowAllAccess(false)
                .allowHostAccess(HostAccess.NONE)
                .allowHostClassLoading(false)
                .allowIO(IOAccess.NONE)
                .allowCreateProcess(false)
                .allowNativeAccess(false)
                .build()) {
            context.eval("js", sourceCode);
            boolean hasHandler = context.getBindings("js").getMember("handler") != null;
            if (!hasHandler) {
                return ScriptRunResult.failure("脚本缺少 handler(input) 入口函数", 0, List.of());
            }
            return ScriptRunResult.success(null, 0, List.of());
        } catch (Exception e) {
            return ScriptRunResult.failure("语法校验失败: " + e.getMessage(), 0, List.of());
        }
    }

    // ---------------- 绑定 ----------------

    private void bindHostFunctions(Value bindings, ScriptHostFunctions host) {
        for (Method method : ScriptHostFunctions.class.getDeclaredMethods()) {
            if (!method.getName().startsWith("fun")) {
                continue;
            }
            String jsName = toJsName(method.getName());
            Method target = method;
            bindings.putMember(jsName, (ProxyExecutable) args -> {
                Object[] converted = convertArgs(target, args);
                try {
                    Object out = target.invoke(host, converted);
                    return CURRENT_CONTEXT.get().eval("js", out == null ? "null" :
                            "(" + JSON.toJSONString(out) + ")");
                } catch (java.lang.reflect.InvocationTargetException
                        | java.lang.IllegalAccessException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    throw new RuntimeException(cause.getMessage(), cause);
                }
            });
        }
    }

    private Object[] convertArgs(Method method, Value[] args) {
        Class<?>[] types = method.getParameterTypes();
        Object[] converted = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Value arg = i < args.length ? args[i] : null;
            Class<?> type = types[i];
            if (type == Map.class) {
                converted[i] = arg == null || arg.isNull() ? null : arg.as(Map.class);
            } else if (type == Object.class) {
                converted[i] = arg == null ? null : fromJsAny(arg);
            } else if (type == String.class) {
                converted[i] = arg == null || arg.isNull() ? null : arg.asString();
            } else if (type == Long.class) {
                // deviceId 以字符串跨语言传递（规避 JS 2^53 精度丢失），此处兼容字符串解析
                converted[i] = arg == null || arg.isNull() ? null
                        : (arg.isString() ? Long.parseLong(arg.asString()) : arg.asLong());
            } else if (type == Integer.class) {
                converted[i] = arg == null || arg.isNull() ? null : arg.asInt();
            } else {
                converted[i] = null;
            }
        }
        return converted;
    }

    private Object fromJsAny(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.isNumber()) {
            return value.fitsInLong() ? (Object) value.asLong() : (Object) value.asDouble();
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        return value.toString();
    }

    private Value toJsValue(Context context, Object value) {
        // 以 JSON 字符串在 JS 侧求值，避免宿主对象进入脚本上下文
        return context.eval("js", value == null ? "null" : "(" + JSON.toJSONString(value) + ")");
    }

    private String toJsName(String methodName) {
        // funPublish -> fun_publish；funEmitEvent -> fun_emitEvent
        // 规则：仅 "fun" 后首字母小写并加下划线，后续保持原驼峰（对齐方向规定的 fun_* 名称）
        if (!methodName.startsWith("fun") || methodName.length() <= 3) {
            return methodName;
        }
        String rest = methodName.substring(3);
        return "fun_" + Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
    }
}
