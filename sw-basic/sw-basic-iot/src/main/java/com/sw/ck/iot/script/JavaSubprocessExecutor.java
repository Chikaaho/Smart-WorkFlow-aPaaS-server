package com.sw.ck.iot.script;

import com.alibaba.fastjson2.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java 受控脚本执行器（父进程侧）。
 * <p>
 * 流程：内存编译 → 与 API 类一起导出到临时目录 → 启动最小 classpath 子 JVM
 * （parent = platform classloader + SecurityManager 白名单 + -Xmx 限额）→
 * stdin/stdout 行协议桥接宿主函数 → 超时 destroyForcibly 强制终止。
 * 管理员提交的代码不会加载进主应用 ClassLoader。
 * </p>
 */
@Component
public class JavaSubprocessExecutor {

    private static final Logger log = LoggerFactory.getLogger(JavaSubprocessExecutor.class);

    private static final Pattern CLASS_NAME = Pattern.compile("public\\s+class\\s+(\\w+)");
    private static final long CHILD_XMX_MB = 128;
    private static final String SECURITY_POLICY = """
            // 受控脚本子进程最小权限策略：仅允许读取自身类目录与只读系统属性
            grant {
                permission java.util.PropertyPermission "*", "read";
                permission java.io.FilePermission "${java.io.tmpdir}/-", "read";
                permission java.io.FilePermission "${java.home}/-", "read";
                permission java.lang.RuntimePermission "accessDeclaredMembers";
                permission java.lang.RuntimePermission "createClassLoader";
                permission java.lang.RuntimePermission "getClassLoader";
                permission java.lang.RuntimePermission "setContextClassLoader";
                permission java.lang.RuntimePermission "setIO";
            };
            """;

    /** 需要导出到子进程的协议/API 类。 */
    private static final String[] EXPORT_CLASSES = {
            "com.sw.ck.iot.script.api.IotScriptApi",
            "com.sw.ck.iot.script.api.IotJavaScript",
            "com.sw.ck.iot.script.JavaSubprocessRunner",
            "com.sw.ck.iot.script.JavaSubprocessRunner$RpcApi",
            "com.sw.ck.iot.script.JavaSubprocessRunner$Outcome",
            "com.sw.ck.iot.script.MiniJson",
            "com.sw.ck.iot.script.MiniJson$Parser",
    };

    /**
     * 执行 Java 脚本。
     */
    public ScriptRunResult run(ScriptExecutionSpec spec, ScriptHostFunctions host) {
        long begin = System.currentTimeMillis();
        Path workDir = null;
        Process process = null;
        try {
            workDir = Files.createTempDirectory("sw-iot-script-");
            String className = extractClassName(spec.getSourceCode());
            if (className == null) {
                return ScriptRunResult.failure("脚本缺少 public class 声明", 0, host.getLogs());
            }
            exportApiClasses(workDir);
            List<String> compileErrors = compile(spec.getSourceCode(), className, workDir);
            if (!compileErrors.isEmpty()) {
                return ScriptRunResult.failure("编译失败: " + String.join("; ", compileErrors),
                        System.currentTimeMillis() - begin, host.getLogs());
            }
            Path policyFile = workDir.resolve("script.policy");
            Files.writeString(policyFile, SECURITY_POLICY);

            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-Xmx" + CHILD_XMX_MB + "m");
            command.add("-Djava.security.manager=default");
            command.add("-Djava.security.policy==" + policyFile.toAbsolutePath());
            command.add("-cp");
            command.add(workDir.toAbsolutePath().toString());
            command.add("com.sw.ck.iot.script.JavaSubprocessRunner");
            command.add(workDir.toAbsolutePath().toString());
            command.add(className);
            String inputJson = JSON.toJSONString(ScriptHostFunctions.baseInput(spec, spec.getInput()));
            // Windows CreateProcess 会吞掉 JSON 参数中的双引号；用 Base64 传递协议
            // 输入，保持跨平台子进程参数字节不变，再由 Runner 在隔离边界内解码。
            command.add(Base64.getEncoder().encodeToString(inputJson.getBytes(StandardCharsets.UTF_8)));

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(false);
            process = builder.start();
            final Process proc = process;
            String[] outcome = new String[1];
            // RPC 读取放后台线程：脚本死循环无输出时主线程仍可执行超时强杀
            Thread rpcThread = new Thread(() -> {
                try {
                    handleRpcLoop(proc, host, outcome);
                } catch (IOException ignore) {
                    // 流随进程终止关闭
                }
            });
            rpcThread.setDaemon(true);
            rpcThread.start();
            boolean finished = process.waitFor(spec.getTimeoutMs(),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ScriptRunResult.timeout("Java 脚本执行超时，子进程已强制终止",
                        System.currentTimeMillis() - begin, host.getLogs());
            }
            
            if (process.exitValue() != 0) {
                String detail = outcome[0] != null ? extractError(outcome[0]) : drainStderr(process);
                return ScriptRunResult.failure(detail,
                        System.currentTimeMillis() - begin, host.getLogs());
            }
            return ScriptRunResult.success(outcome[0],
                    System.currentTimeMillis() - begin, host.getLogs());
        } catch (Exception e) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            return ScriptRunResult.failure("Java 脚本执行失败: " + e.getMessage(),
                    System.currentTimeMillis() - begin, host.getLogs());
        } finally {
            cleanup(workDir);
        }
    }

    /**
     * 编译校验（不执行）。
     */
    public ScriptRunResult validate(String sourceCode) {
        String className = extractClassName(sourceCode);
        if (className == null) {
            return ScriptRunResult.failure("脚本缺少 public class 声明", 0, List.of());
        }
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("sw-iot-script-check-");
            exportApiClasses(workDir);
            List<String> errors = compile(sourceCode, className, workDir);
            if (!errors.isEmpty()) {
                return ScriptRunResult.failure("编译失败: " + String.join("; ", errors), 0, List.of());
            }
            if (!sourceCode.contains("IotScriptApi") || !sourceCode.contains("IotJavaScript")) {
                return ScriptRunResult.failure(
                        "脚本类必须实现 com.sw.ck.iot.script.api.IotJavaScript", 0, List.of());
            }
            return ScriptRunResult.success(null, 0, List.of());
        } catch (Exception e) {
            return ScriptRunResult.failure("编译失败: " + e.getMessage(), 0, List.of());
        } finally {
            cleanup(workDir);
        }
    }

    // ---------------- RPC ----------------

    private void handleRpcLoop(Process process, ScriptHostFunctions host, String[] outcome)
            throws IOException {
        BufferedReader stdout = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        OutputStreamWriter stdin = new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8);
        String line;
        while ((line = stdout.readLine()) != null) {
            JavaSubprocessRunner.Outcome parsed = JavaSubprocessRunner.parseLine(line);
            switch (parsed.kind) {
                case "CALL" -> {
                    String reply = dispatchCall(host, parsed.payloadJson);
                    stdin.write(reply);
                    stdin.write("\n");
                    stdin.flush();
                }
                case "RESULT" -> {
                    host.funLog("INFO", "脚本完成", Map.of("output", truncate(parsed.payloadJson, 300)));
                    outcome[0] = parsed.payloadJson;
                    return;
                }
                case "ERROR" -> {
                    outcome[0] = parsed.payloadJson;
                    return;
                }
                default -> log.debug("脚本子进程输出: {}", truncate(line, 200));
            }
        }
    }

    private String drainStderr(Process process) {
        try {
            byte[] bytes = process.getErrorStream().readAllBytes();
            String text = new String(bytes, StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? "脚本子进程异常退出" : truncate(text, 500);
        } catch (IOException e) {
            return "脚本子进程异常退出";
        }
    }

    private String extractError(String json) {
        try {
            return String.valueOf(MiniJson.parseObject(json).get("error"));
        } catch (Exception ignore) {
            return json;
        }
    }

    private String dispatchCall(ScriptHostFunctions host, String requestJson) {
        try {
            Map<String, Object> request = MiniJson.parseObject(requestJson);
            String fn = String.valueOf(request.get("fn"));
            List<Object> args = (List<Object>) request.get("args");
            java.lang.reflect.Method method = null;
            for (java.lang.reflect.Method m : ScriptHostFunctions.class.getDeclaredMethods()) {
                if (m.getName().equals(fn)) {
                    method = m;
                    break;
                }
            }
            if (method == null) {
                return JavaSubprocessRunner.errPrefix() + MiniJson.write(
                        Map.of("error", "未知宿主函数: " + fn));
            }
            Object[] converted = convertArgs(method, args);
            Object result = method.invoke(host, converted);
            return JavaSubprocessRunner.retPrefix() + MiniJson.write(result);
        } catch (Exception e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null
                    ? e.getCause() : e;
            return JavaSubprocessRunner.errPrefix() + MiniJson.write(
                    Map.of("error", cause.getMessage() == null ? cause.getClass().getName() : cause.getMessage()));
        }
    }

    private Object[] convertArgs(java.lang.reflect.Method method, List<Object> args) {
        Class<?>[] types = method.getParameterTypes();
        Object[] converted = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Object arg = i < args.size() ? args.get(i) : null;
            Class<?> type = types[i];
            if (arg == null) {
                converted[i] = null;
            } else if (type == Long.class) {
                converted[i] = ((Number) arg).longValue();
            } else if (type == Integer.class) {
                converted[i] = ((Number) arg).intValue();
            } else if (type == Map.class) {
                converted[i] = arg;
            } else {
                converted[i] = type.cast(arg);
            }
        }
        return converted;
    }

    // ---------------- 编译与导出 ----------------

    private String extractClassName(String source) {
        Matcher matcher = CLASS_NAME.matcher(source);
        return matcher.find() ? matcher.group(1) : null;
    }

    private List<String> compile(String sourceCode, String className, Path workDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return List.of("运行环境缺少 JDK 编译器（需 JDK 而非 JRE）");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            fileManager.setLocation(javax.tools.StandardLocation.CLASS_OUTPUT,
                    List.of(workDir.toFile()));
            // Spring Boot 可执行 JAR 的 API 位于嵌套依赖中，javac 不会从当前
            // java.class.path 解析它们；统一使用已导出到 workDir 的 API 类作为编译 classpath。
            List<String> options = List.of("-encoding", "UTF-8",
                    "-classpath", workDir.toAbsolutePath().toString());
            JavaFileObject source = new SimpleJavaFileObject(
                    URI.create("string:///" + className + ".java"), javax.tools.JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return sourceCode;
                }
            };
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics,
                    options, null, List.of(source));
            boolean ok = Boolean.TRUE.equals(task.call());
            if (ok) {
                return List.of();
            }
            List<String> errors = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) {
                    errors.add(truncate(String.format("line %d: %s", d.getLineNumber(), d.getMessage(null)), 200));
                }
            }
            return errors;
        }
    }

    private void exportApiClasses(Path workDir) throws IOException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader() == null
                ? JavaSubprocessExecutor.class.getClassLoader()
                : Thread.currentThread().getContextClassLoader();
        for (String className : EXPORT_CLASSES) {
            String resource = className.replace('.', '/') + ".class";
            URL url = loader.getResource(resource);
            if (url == null) {
                throw new IOException("无法定位导出类: " + className);
            }
            Path target = workDir.resolve(resource);
            Files.createDirectories(target.getParent());
            try (var in = url.openStream()) {
                Files.copy(in, target);
            }
        }
    }

    private void cleanup(Path workDir) {
        if (workDir == null) {
            return;
        }
        try (var paths = Files.walk(workDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // 临时目录清理失败不阻塞主流程
                }
            });
        } catch (IOException ignore) {
            // 同上
        }
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
