package com.sw.ck.bpm.engine.script;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.script.ScriptFunctionContext;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P64 判断脚本专职 worker 进程入口（ADR-P64-001 §3 隔离修订）。
 * <p>
 * 以独立 JVM（宿主以 -Xmx128m 等堆上限拉起）承载单次判断：stdin 按行读 JSON 请求，
 * 复用 {@link BpmScriptRunner} 执行，stdout 按行写 JSON 结果。进程内堆耗尽即进程死亡
 * （真实 128MiB 执行空间上限），宿主以 EOF/退出识别并归类 RESOURCE_LIMIT 后重启 worker；
 * 超时由宿主 destroy 进程兜底，确保不留活判断。
 * </p>
 */
public final class ScriptWorkerMain {

    private ScriptWorkerMain() {
    }

    public static void main(String[] args) throws Exception {
        // stdout 为协议专用流，显式 UTF-8（JDK18+ 的 System.out 默认跟随控制台编码，不可依赖）
        PrintStream protocolOut = new PrintStream(new FileOutputStream(FileDescriptor.out), true,
                StandardCharsets.UTF_8);
        // 进程内日志框架的控制台输出改道 stderr，避免污染应答行
        System.setOut(System.err);
        ObjectMapper mapper = new ObjectMapper();
        BpmScriptRunner runner = new BpmScriptRunner(mapper);
        // 启动握手：宿主据此确认 worker 就绪并核对本 JVM 实际堆上限（-Xmx 生效值）
        Map<String, Object> hello = new LinkedHashMap<>();
        hello.put("id", "__ready__");
        hello.put("ready", true);
        hello.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        protocolOut.println(mapper.writeValueAsString(hello));
        protocolOut.flush();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            String response;
            try {
                Map<?, ?> request = mapper.readValue(line, Map.class);
                String id = String.valueOf(request.get("id"));
                String script = (String) request.get("script");
                @SuppressWarnings("unchecked")
                Map<String, Object> variables = request.get("variables") == null
                        ? Map.of()
                        : new LinkedHashMap<String, Object>((Map<String, Object>) request.get("variables"));
                long timeoutMs = request.get("timeoutMs") == null
                        ? BpmScriptRunner.DEFAULT_TIMEOUT_MS
                        : Long.parseLong(String.valueOf(request.get("timeoutMs")));
                ScriptFunctionContext functionContext = parseFunctionContext(request.get("functionContext"));
                BpmScriptRunner.Result result = runner.run(script, variables, timeoutMs, functionContext);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", id);
                out.put("kind", result.kind().name());
                out.put("value", result.value());
                out.put("typeName", result.typeName());
                out.put("errorMessage", result.errorMessage());
                out.put("durationMs", result.durationMs());
                if (result.variableWrites() != null && !result.variableWrites().isEmpty()) {
                    out.put("variableWrites", result.variableWrites());
                }
                response = mapper.writeValueAsString(out);
            } catch (Exception e) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", "unknown");
                out.put("kind", "SCRIPT_ERROR");
                out.put("errorMessage", "worker 请求处理失败: " + e.getMessage());
                response = mapper.writeValueAsString(out);
            }
            protocolOut.println(response);
            protocolOut.flush();
        }
    }

    /**
     * BF20261013-021：解析宿主下发的函数上下文。
     * JSON 形态：{"deptParentMap":{"1":0,...},"deptLeaderMap":{"1":100,...},"instanceVariables":{...}}
     */
    @SuppressWarnings("unchecked")
    private static ScriptFunctionContext parseFunctionContext(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return ScriptFunctionContext.EMPTY;
        }
        Map<Long, Long> parentMap = parseLongMap(map.get("deptParentMap"));
        Map<Long, Long> leaderMap = parseLongMap(map.get("deptLeaderMap"));
        Map<String, Object> instanceVariables = map.get("instanceVariables") instanceof Map<?, ?> iv
                ? new LinkedHashMap<>((Map<String, Object>) iv)
                : Map.of();
        return new ScriptFunctionContext(parentMap, leaderMap, instanceVariables);
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, Long> parseLongMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            try {
                out.put(Long.parseLong(String.valueOf(entry.getKey())),
                        Long.parseLong(String.valueOf(entry.getValue())));
            } catch (NumberFormatException ignore) {
                // 非数字键/值不进入目录（宿主侧构建不会产生，此处防御）
            }
        }
        return out;
    }
}
