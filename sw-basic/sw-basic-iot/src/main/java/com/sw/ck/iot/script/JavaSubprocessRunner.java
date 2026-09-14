package com.sw.ck.iot.script;

import com.sw.ck.iot.script.api.IotScriptApi;
import com.sw.ck.iot.script.api.IotJavaScript;

import java.io.BufferedReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.io.InputStreamReader;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Java 受控脚本子进程入口（仅在被隔离的子 JVM 中运行）。
 * <p>
 * 子进程 classpath 只包含脚本 API 类目录（parent = platform classloader），
 * 无法触及主业务类；宿主函数经 stdin/stdout 行协议回传父进程执行。
 * 结果行：{@code __RESULT__<json>} / 错误行：{@code __ERROR__<json>}；
 * 宿主调用行：{@code __CALL__<json>}，父进程应答 {@code __RET__<json>} / {@code __ERR__<json>}。
 * </p>
 */
public final class JavaSubprocessRunner {

    private static final String PREFIX_CALL = "__CALL__";
    private static final String PREFIX_RESULT = "__RESULT__";
    private static final String PREFIX_ERROR = "__ERROR__";

    private JavaSubprocessRunner() {
    }

    /**
     * 子进程入口：java -cp &lt;apiDir&gt; com.sw.ck.iot.script.JavaSubprocessRunner &lt;classesDir&gt; &lt;entryClass&gt; &lt;inputJson&gt;
     */
    public static void main(String[] args) {
        // Windows 子 JVM 的 native.encoding 可能不是 UTF-8；父进程按 UTF-8
        // 读取协议流，必须先固定 stdout 编码，否则中文安全拒绝信息会损坏。
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        if (args.length < 3) {
            System.out.println(PREFIX_ERROR + MiniJson.write(Map.of("error", "参数不足")));
            return;
        }
        try {
            Path classesDir = Paths.get(args[0]);
            URLClassLoader scriptLoader = new URLClassLoader(
                    new java.net.URL[]{classesDir.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader());
            Class<?> entryClass = Class.forName(args[1], true, scriptLoader);
            // 接口一致性必须经同一 scriptLoader 解析，避免双份类对象误判
            Class<?> apiInterface = Class.forName(
                    "com.sw.ck.iot.script.api.IotJavaScript", true, scriptLoader);
            if (!apiInterface.isAssignableFrom(entryClass)) {
                throw new IllegalArgumentException("脚本类未实现 IotJavaScript: " + args[1]);
            }
            String inputJson = new String(Base64.getDecoder().decode(args[2]), StandardCharsets.UTF_8);
            Map<String, Object> input = MiniJson.parseObject(inputJson);
            Object script = entryClass.getDeclaredConstructor().newInstance();
            // RpcApi 同样经 scriptLoader 加载，保证与接口参数类型一致
            Object api = Class.forName("com.sw.ck.iot.script.JavaSubprocessRunner$RpcApi",
                    true, scriptLoader).getDeclaredConstructor().newInstance();
            // 全程经 apiInterface 反射调用，避免跨 Loader 的类型强转
            Object result = entryClass.getMethod("execute",
                    Class.forName("com.sw.ck.iot.script.api.IotScriptApi", true, scriptLoader),
                    Map.class).invoke(script, api, input);
            System.out.println(PREFIX_RESULT + MiniJson.write(result));
            System.out.flush();
            System.exit(0);
        } catch (Throwable e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite
                    && ite.getCause() != null ? ite.getCause() : e;
            String message = cause.getMessage() == null ? cause.getClass().getName() : cause.getMessage();
            System.out.println(PREFIX_ERROR + MiniJson.write(Map.of("error", message)));
            System.out.flush();
            System.exit(1);
        }
    }

    /**
     * 子进程侧宿主函数 RPC 客户端。
     */
    public static final class RpcApi implements IotScriptApi {

        private final BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));

        @Override
        public Map<String, Object> funPublish(String topic, String payload, Map<String, Object> options) {
            return call("funPublish", Arrays.asList(topic, payload, options));
        }

        @Override
        public Map<String, Object> funSubscribe(String topicFilter, Map<String, Object> options) {
            return call("funSubscribe", Arrays.asList(topicFilter, options));
        }

        @Override
        public Map<String, Object> funGetProperty(Long deviceId, String propertyId) {
            return call("funGetProperty", Arrays.asList(deviceId, propertyId));
        }

        @Override
        public Map<String, Object> funSetProperty(Long deviceId, String propertyId, Object value,
                                                  Map<String, Object> options) {
            return call("funSetProperty", Arrays.asList(deviceId, propertyId, value, options));
        }

        @Override
        public Map<String, Object> funEmitEvent(Long deviceId, String eventId, Object payload) {
            return call("funEmitEvent", Arrays.asList(deviceId, eventId, payload));
        }

        @Override
        public Map<String, Object> funInvokeAction(Long deviceId, String actionId, Object input,
                                                   Map<String, Object> options) {
            return call("funInvokeAction", Arrays.asList(deviceId, actionId, input, options));
        }

        @Override
        public Map<String, Object> funStartProcess(String templateKey, Map<String, Object> formData,
                                                   Map<String, Object> options) {
            return call("funStartProcess", Arrays.asList(templateKey, formData, options));
        }

        @Override
        public void funLog(String level, String message, Map<String, Object> fields) {
            call("funLog", Arrays.asList(level, message, fields));
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> call(String fn, List<Object> args) {
            // List.of 不接受 null：参数可能为 null，使用可空列表
            List<Object> safeArgs = new ArrayList<>(args);
            Map<String, Object> request = new HashMap<>();
            request.put("fn", fn);
            request.put("args", safeArgs);
            System.out.println(PREFIX_CALL + MiniJson.write(request));
            System.out.flush();
            try {
                String line = reader.readLine();
                if (line == null) {
                    throw new IllegalStateException("父进程已关闭（可能超时终止）");
                }
                if (line.startsWith("__ERR__")) {
                    Map<String, Object> body = MiniJson.parseObject(line.substring(7));
                    throw new IllegalStateException(String.valueOf(body.get("error")));
                }
                if (!line.startsWith("__RET__")) {
                    throw new IllegalStateException("未知协议应答: " + truncate(line));
                }
                String payload = line.substring(7);
                if (payload.isEmpty() || "null".equals(payload)) {
                    return Map.of();
                }
                return MiniJson.parseObject(payload);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("RPC 读取失败: " + e.getMessage(), e);
            }
        }

        private String truncate(String line) {
            return line.length() <= 120 ? line : line.substring(0, 120);
        }
    }

    /**
     * 结果容器（父进程读取）。
     */
    public static final class Outcome {
        public final String kind;
        public final String payloadJson;

        public Outcome(String kind, String payloadJson) {
            this.kind = kind;
            this.payloadJson = payloadJson;
        }
    }

    /**
     * 解析子进程输出行（父进程侧工具）。
     */
    public static Outcome parseLine(String line) {
        if (line.startsWith(PREFIX_RESULT)) {
            return new Outcome("RESULT", line.substring(PREFIX_RESULT.length()));
        }
        if (line.startsWith(PREFIX_ERROR)) {
            return new Outcome("ERROR", line.substring(PREFIX_ERROR.length()));
        }
        if (line.startsWith(PREFIX_CALL)) {
            return new Outcome("CALL", line.substring(PREFIX_CALL.length()));
        }
        return new Outcome("UNKNOWN", line);
    }

    /**
     * 协议前缀常量（父进程应答使用）。
     */
    public static String retPrefix() {
        return "__RET__";
    }

    public static String errPrefix() {
        return "__ERR__";
    }

    /**
     * 原子引用占位（保持工具类封闭）。
     */
    static final AtomicReference<String> LAST_PROTOCOL_LINE = new AtomicReference<>();
}
