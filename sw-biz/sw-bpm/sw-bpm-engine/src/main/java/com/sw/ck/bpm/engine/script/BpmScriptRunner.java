package com.sw.ck.bpm.engine.script;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.script.ScriptFunctionContext;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * P64 受控判断脚本运行器（ADR-P64-001 §3；BPM 独立只读判断上下文，不复用 iot 宿主函数）。
 * <p>
 * 边界：脚本体为函数体（顶层 {@code return}），经宿主函数读取授权变量快照；无宿主访问、
 * 无 IO/进程/线程/原生访问；语句 ≤500,000（ResourceLimits）、wall clock ≤5,000ms
 * （watcher 强制 close）、输出仅 Number/String/Boolean/null 且序列化 ≤4KiB；
 * 其余一律判 TYPE/RESOURCE 错误，不产生默认成功结果。
 * </p>
 * <p>
 * BF20261013-021 常用函数与 {@code #} 语法糖（{@link ScriptSugar} 编译，业务写法
 * {@code #获取流程变量(var_x)}）：
 * <ul>
 *   <li>{@code 获取流程变量(name)}（别名 流程变量取值 / getVariable）：取值优先级 =
 *       本脚本写入 &gt; 实例变量快照 &gt; 授权变量快照；未授权且实例中不存在 → 脚本错误；</li>
 *   <li>{@code 设置流程变量(name, value)}（别名 setVariable）：收集写入随结果回传，
 *       由宿主落库；值支持 数字/文本/是-否/空/数组/对象（≤64KiB）；系统保留键与空名拒绝；</li>
 *   <li>{@code 获取部门负责人(deptId)} / {@code 获取分管领导(deptId)} /
 *       {@code 获取上级部门(deptId)}（别名 deptLeader / deptSupervisor / parentDept）：
 *       基于宿主预取的部门目录快照；未找到返回 null（不抛错）。</li>
 * </ul>
 * </p>
 */
@Component
public class BpmScriptRunner {

    private static final Logger log = LoggerFactory.getLogger(BpmScriptRunner.class);

    public static final long MAX_STATEMENTS = 500_000L;
    public static final long DEFAULT_TIMEOUT_MS = 5_000L;
    /** 输出序列化字节上限（主方向 §3.5：标量输出最多 4KiB）。 */
    public static final int MAX_OUTPUT_BYTES = 4 * 1024;
    /** 设置流程变量单值序列化上限（BF20261013-021）。 */
    public static final int MAX_WRITE_BYTES = 64 * 1024;
    /** 变量名长度上限（BF20261013-021）。 */
    public static final int MAX_VARIABLE_NAME_LENGTH = 100;

    /** 变量读取宿主函数名（Owner 摘要约定）+ 别名。 */
    public static final String FN_READ_VARIABLE = "流程变量取值";
    public static final String FN_READ_VARIABLE_OWNER = "获取流程变量";
    public static final String FN_READ_VARIABLE_ALIAS = "getVariable";
    /** 变量写入宿主函数名 + 别名（BF20261013-021）。 */
    public static final String FN_WRITE_VARIABLE = "设置流程变量";
    public static final String FN_WRITE_VARIABLE_ALIAS = "setVariable";
    /** 组织函数名 + 别名（BF20261013-021）。 */
    public static final String FN_DEPT_LEADER = "获取部门负责人";
    public static final String FN_DEPT_LEADER_ALIAS = "deptLeader";
    public static final String FN_DEPT_SUPERVISOR = "获取分管领导";
    public static final String FN_DEPT_SUPERVISOR_ALIAS = "deptSupervisor";
    public static final String FN_PARENT_DEPT = "获取上级部门";
    public static final String FN_PARENT_DEPT_ALIAS = "parentDept";

    /** 系统保留变量键：同快照服务口径，脚本不可写入。 */
    private static final Set<String> SYSTEM_KEYS = Set.of(
            "processInstanceId", "processDefKey", "businessKey", "formKey",
            "initiatorId", "tenantId", "currentNodeKey", "roundNo");

    private final ObjectMapper objectMapper;

    /**
     * BF20261019-02：可共享的 Polyglot {@link Engine}（worker 进程专用）。共享后语言初始化
     * 与 JS 内建编译只付一次；进程内路径（validate/单测）保持 null = 每次 Context 独立 Engine。
     */
    private final Engine sharedEngine;

    @org.springframework.beans.factory.annotation.Autowired
    public BpmScriptRunner(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    public BpmScriptRunner(ObjectMapper objectMapper, Engine sharedEngine) {
        this.objectMapper = objectMapper;
        this.sharedEngine = sharedEngine;
    }

    /**
     * BF20261019-02：worker 启动预热——在握手自报就绪前完成语言初始化与 JS 内建编译，
     * 首个真实判断不再支付冷启动（慢宿主上曾超出固定 5s 预算导致 worker 被销毁后每次冷启动）。
     */
    public Result warmUp() {
        return warmUp(DEFAULT_TIMEOUT_MS);
    }

    /**
     * BF20261019-04：按调用方预算预热。GraalJS 首次语言初始化在慢宿主上可远超单次判断
     * 的 5s 预算（实测 2 vCPU/1.6G 主机约需 6s 以上），预热必须使用 worker 启动预算，
     * 不能复用 {@link #DEFAULT_TIMEOUT_MS}。
     */
    public Result warmUp(long timeoutMs) {
        return run("return 1;", Map.of(),
                timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS, ScriptFunctionContext.EMPTY);
    }

    private String jsonOf(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "null";
        }
    }

    /** 执行结果（variableWrites = 设置流程变量 收集的写入，仅 OK 允许消费方落库）。 */
    public record Result(Kind kind, Object value, String typeName, String errorMessage, long durationMs,
                         Map<String, Object> variableWrites) {

        /** 兼容构造：无写入回传的既有调用链。 */
        public Result(Kind kind, Object value, String typeName, String errorMessage, long durationMs) {
            this(kind, value, typeName, errorMessage, durationMs, Map.of());
        }

        public enum Kind { OK, SCRIPT_ERROR, TYPE_ERROR, RESOURCE_LIMIT, TIMEOUT }
    }

    /**
     * 执行判断脚本（无函数上下文；兼容既有调用链）。
     *
     * @param script    脚本函数体（顶层 return）
     * @param variables 授权变量快照（只读；未授权变量在解析层已被排除，此处缺名 = 读取错误）
     */
    public Result run(String script, Map<String, Object> variables) {
        return run(script, variables, DEFAULT_TIMEOUT_MS, null);
    }

    public Result run(String script, Map<String, Object> variables, long timeoutMs) {
        return run(script, variables, timeoutMs, null);
    }

    /**
     * 执行判断脚本（BF20261013-021 带宿主函数上下文）。
     *
     * @param functionContext 组织目录与实例变量快照；null = 空上下文
     */
    public Result run(String script, Map<String, Object> variables, long timeoutMs,
                      ScriptFunctionContext functionContext) {
        long begin = System.currentTimeMillis();
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicBoolean statementLimitTripped = new AtomicBoolean(false);
        Map<String, Object> writes = new LinkedHashMap<>();
        Context context = null;
        String compiled;
        try {
            // 语法糖编译在沙箱外完成：解析失败为可诊断脚本错误
            compiled = ScriptSugar.transpile(script);
        } catch (IllegalArgumentException e) {
            return new Result(Result.Kind.SCRIPT_ERROR, null, null, "脚本语法糖解析失败: " + e.getMessage(),
                    elapsed(begin));
        }
        ScriptFunctionContext ctxData = functionContext == null ? ScriptFunctionContext.EMPTY : functionContext;
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
            if (sharedEngine != null) {
                // 共享 Engine：语言初始化只付一次，Context 隔离（绑定/ResourceLimits）不受影响
                builder = builder.engine(sharedEngine);
            }
            context = builder.build();
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
                String name = requireNameArg(args, FN_READ_VARIABLE);
                return readVariable(ctx, writes, ctxData, snapshot, name);
            };
            bindings.putMember(FN_READ_VARIABLE, reader);
            bindings.putMember(FN_READ_VARIABLE_OWNER, reader);
            bindings.putMember(FN_READ_VARIABLE_ALIAS, reader);

            org.graalvm.polyglot.proxy.ProxyExecutable writer = args -> {
                if (args.length < 2) {
                    throw new RuntimeException(FN_WRITE_VARIABLE + "(变量名, 变量值) 需要两个参数");
                }
                String name = requireNameArg(args, FN_WRITE_VARIABLE);
                validateWritableName(name);
                writes.put(name, toHostValue(ctx, args[1]));
                return null;
            };
            bindings.putMember(FN_WRITE_VARIABLE, writer);
            bindings.putMember(FN_WRITE_VARIABLE_ALIAS, writer);

            org.graalvm.polyglot.proxy.ProxyExecutable deptLeader = args -> {
                long deptId = requireDeptId(args, FN_DEPT_LEADER);
                return ctxData.deptLeaderMap().get(deptId);
            };
            bindings.putMember(FN_DEPT_LEADER, deptLeader);
            bindings.putMember(FN_DEPT_LEADER_ALIAS, deptLeader);

            org.graalvm.polyglot.proxy.ProxyExecutable deptSupervisor = args -> {
                long deptId = requireDeptId(args, FN_DEPT_SUPERVISOR);
                return resolveSupervisor(ctxData, deptId);
            };
            bindings.putMember(FN_DEPT_SUPERVISOR, deptSupervisor);
            bindings.putMember(FN_DEPT_SUPERVISOR_ALIAS, deptSupervisor);

            org.graalvm.polyglot.proxy.ProxyExecutable parentDept = args -> {
                long deptId = requireDeptId(args, FN_PARENT_DEPT);
                Long parent = ctxData.deptParentMap().get(deptId);
                return parent == null || parent == 0L ? null : parent;
            };
            bindings.putMember(FN_PARENT_DEPT, parentDept);
            bindings.putMember(FN_PARENT_DEPT_ALIAS, parentDept);

            // 函数体包裹：以匿名函数表达式求值（BF20261019-02）——不向全局对象绑定任何名字。
            // 共享 Engine 下语言实例跨 Context 复用，具名函数声明会污染后续判断的全局查找；
            // 匿名表达式只返回函数值，配合每次独立 Context 保持脚本间零残留。
            Value out = context.eval("js", "(function() {\n" + compiled + "\n})").execute();
            finished.set(true);
            watcher.interrupt();
            return classify(out, elapsed(begin), writes);
        } catch (org.graalvm.polyglot.PolyglotException e) {
            finished.set(true);
            long duration = elapsed(begin);
            if (statementLimitTripped.get() || e.isResourceExhausted()) {
                // 语句上限触发的取消同时带 cancelled/resource 标记，先按资源超限归类；
                // watcher 强制中断只置 cancelled，落到下方 TIMEOUT。
                return new Result(Result.Kind.RESOURCE_LIMIT, null, null, "脚本资源超限（语句上限）: " + e.getMessage(),
                        duration, writes);
            }
            if (e.isCancelled()) {
                return new Result(Result.Kind.TIMEOUT, null, null, "脚本执行超时被强制中断: " + e.getMessage(),
                        duration, writes);
            }
            return new Result(Result.Kind.SCRIPT_ERROR, null, null, "脚本执行失败: " + e.getMessage(),
                    duration, writes);
        } catch (Exception e) {
            finished.set(true);
            return new Result(Result.Kind.SCRIPT_ERROR, null, null, "脚本执行失败: " + e.getMessage(),
                    elapsed(begin), writes);
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

    /** 变量读取：本脚本写入 &gt; 实例变量快照 &gt; 授权变量快照；全缺为可诊断脚本错误。 */
    private Value readVariable(Context ctx, Map<String, Object> writes, ScriptFunctionContext ctxData,
                               Map<String, Object> snapshot, String name) {
        Object value;
        if (writes.containsKey(name)) {
            value = writes.get(name);
        } else if (ctxData.instanceVariables().containsKey(name)) {
            value = ctxData.instanceVariables().get(name);
        } else if (snapshot.containsKey(name)) {
            value = snapshot.get(name);
        } else {
            // 未授权/不存在变量：明确脚本错误，不静默回 null（方向 §3.1 可诊断失败）
            throw new RuntimeException("变量未授权或不存在: " + name);
        }
        // 以 JSON 重建纯 JS 值，宿主对象不进入脚本上下文
        return ctx.eval("js", value == null ? "null" : "(" + mapperWrite(value) + ")");
    }

    private String mapperWrite(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("变量值序列化失败: " + e.getMessage());
        }
    }

    /** 参数名提取：读取/写入函数共用（首个参数须为非空字符串）。 */
    private String requireNameArg(Value[] args, String fn) {
        if (args.length < 1 || args[0] == null || !args[0].isString()) {
            throw new RuntimeException(fn + "(变量名...) 需要字符串变量名");
        }
        String name = args[0].asString();
        if (name.isBlank()) {
            throw new RuntimeException(fn + " 的变量名不能为空");
        }
        return name;
    }

    /** 写入变量名约束：长度、空白、系统保留键。 */
    private void validateWritableName(String name) {
        if (name.length() > MAX_VARIABLE_NAME_LENGTH) {
            throw new RuntimeException("变量名长度超出上限 " + MAX_VARIABLE_NAME_LENGTH + ": " + name);
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isWhitespace(name.charAt(i))) {
                throw new RuntimeException("变量名不能包含空白字符: " + name);
            }
        }
        if (SYSTEM_KEYS.contains(name)) {
            throw new RuntimeException("系统保留变量不可设置: " + name);
        }
    }

    /** JS 值 → 宿主 JSON 值（数字/文本/是-否/空/数组/对象；其余类型拒绝）。 */
    private Object toHostValue(Context ctx, Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.fitsInLong() ? (Object) value.asLong() : (Object) value.asDouble();
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isString()) {
            return value.asString();
        }
        if (!value.canExecute() && (value.hasArrayElements() || value.hasMembers())) {
            Value stringify = ctx.eval("js", "(function(v){ return JSON.stringify(v); })");
            Value json = stringify.execute(value);
            if (json == null || json.isNull()) {
                throw new RuntimeException("变量值无法序列化");
            }
            String text = json.asString();
            if (text.getBytes(StandardCharsets.UTF_8).length > MAX_WRITE_BYTES) {
                throw new RuntimeException("变量值超出 " + MAX_WRITE_BYTES + " 字节上限");
            }
            try {
                return objectMapper.readValue(text, Object.class);
            } catch (Exception e) {
                throw new RuntimeException("变量值解析失败: " + e.getMessage());
            }
        }
        throw new RuntimeException("变量值仅支持 数字/文本/是-否/空/数组/对象");
    }

    /** 参数部门 ID：数字或数字文本。 */
    private long requireDeptId(Value[] args, String fn) {
        if (args.length < 1 || args[0] == null || args[0].isNull()) {
            throw new RuntimeException(fn + "(部门ID) 需要部门ID");
        }
        Value v = args[0];
        if (v.isNumber()) {
            return v.asLong();
        }
        if (v.isString()) {
            try {
                return Long.parseLong(v.asString().trim());
            } catch (NumberFormatException e) {
                throw new RuntimeException(fn + " 的部门ID必须是数字或数字文本: " + v.asString());
            }
        }
        throw new RuntimeException(fn + " 的部门ID必须是数字或数字文本");
    }

    /**
     * 分管领导解析：沿部门树向上找最近的、已登记负责人的上级部门（不含本部门）；
     * 无上级或无负责人返回 null；深度异常（成环/超深）按找不到处置。
     */
    private Long resolveSupervisor(ScriptFunctionContext ctxData, long deptId) {
        long cursor = deptId;
        for (int hops = 0; hops < 64; hops++) {
            Long parent = ctxData.deptParentMap().get(cursor);
            if (parent == null || parent == 0L) {
                return null;
            }
            Long leader = ctxData.deptLeaderMap().get(parent);
            if (leader != null) {
                return leader;
            }
            if (parent == cursor) {
                return null;
            }
            cursor = parent;
        }
        return null;
    }

    /** 语法/安全校验（仅编译不执行；与 run 相同糖编译、包裹与宿主函数形状）。 */
    public Result validate(String script) {
        try (Context context = Context.newBuilder("js")
                .allowAllAccess(false)
                .allowHostAccess(HostAccess.NONE)
                .allowHostClassLoading(false)
                .allowIO(IOAccess.NONE)
                .allowCreateProcess(false)
                .allowNativeAccess(false)
                .build()) {
            String compiled = ScriptSugar.transpile(script);
            context.eval("js", "function __p64Handle__() {\n" + compiled + "\n}");
            return new Result(Result.Kind.OK, null, null, null, 0);
        } catch (IllegalArgumentException e) {
            return new Result(Result.Kind.SCRIPT_ERROR, null, null, "脚本语法糖解析失败: " + e.getMessage(), 0);
        } catch (Exception e) {
            return new Result(Result.Kind.SCRIPT_ERROR, null, null, "语法校验失败: " + e.getMessage(), 0);
        }
    }

    private Result classify(Value out, long durationMs, Map<String, Object> writes) {
        if (out == null || out.isNull()) {
            return new Result(Result.Kind.OK, null, "NULL", null, durationMs, writes);
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
                    "脚本返回类型必须是 Number/String/Boolean/null，实际: " + out.toString(), durationMs, writes);
        }
        String serialized = jsonOf(value);
        if (serialized != null && serialized.getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES) {
            return new Result(Result.Kind.RESOURCE_LIMIT, null, null,
                    "脚本输出超出 " + MAX_OUTPUT_BYTES + " 字节上限", durationMs, writes);
        }
        return new Result(Result.Kind.OK, value, typeName, null, durationMs, writes);
    }

    private long elapsed(long begin) {
        return System.currentTimeMillis() - begin;
    }
}
