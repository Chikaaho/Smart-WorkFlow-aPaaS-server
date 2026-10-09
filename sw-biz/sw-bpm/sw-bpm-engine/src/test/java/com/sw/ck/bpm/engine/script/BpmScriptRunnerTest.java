package com.sw.ck.bpm.engine.script;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BpmScriptRunner} 受控判断脚本运行器单测（ADR-P64-001 §3）。
 * <p>
 * 覆盖：返回类型矩阵（Number/String/Boolean/null/非法类型）、宿主函数读取与未授权拒绝、
 * 语句上限、语法校验；边界证明脚本无宿主访问。
 * </p>
 */
@DisplayName("P64 受控判断脚本运行器测试")
class BpmScriptRunnerTest {

    private final BpmScriptRunner runner = new BpmScriptRunner(new ObjectMapper());

    private Map<String, Object> vars(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("返回 Number/String/Boolean/null 四类合法结果")
    void shouldReturnTypedResults() {
        assertThat(runner.run("return 1;", vars()).kind()).isEqualTo(BpmScriptRunner.Result.Kind.OK);
        assertThat(runner.run("return 1;", vars()).value()).isEqualTo(1L);

        BpmScriptRunner.Result text = runner.run("return 'NG';", vars());
        assertThat(text.kind()).isEqualTo(BpmScriptRunner.Result.Kind.OK);
        assertThat(text.typeName()).isEqualTo("STRING");
        assertThat(text.value()).isEqualTo("NG");

        BpmScriptRunner.Result bool = runner.run("return true;", vars());
        assertThat(bool.kind()).isEqualTo(BpmScriptRunner.Result.Kind.OK);
        assertThat(bool.typeName()).isEqualTo("BOOLEAN");
        assertThat(bool.value()).isEqualTo(Boolean.TRUE);

        BpmScriptRunner.Result nil = runner.run("return null;", vars());
        assertThat(nil.kind()).isEqualTo(BpmScriptRunner.Result.Kind.OK);
        assertThat(nil.typeName()).isEqualTo("NULL");
        assertThat(nil.value()).isNull();
    }

    @Test
    @DisplayName("非法返回类型（对象/数组/函数）判 TYPE_ERROR")
    void shouldRejectIllegalReturnTypes() {
        assertThat(runner.run("return {a:1};", vars()).kind())
                .isEqualTo(BpmScriptRunner.Result.Kind.TYPE_ERROR);
        assertThat(runner.run("return [1,2];", vars()).kind())
                .isEqualTo(BpmScriptRunner.Result.Kind.TYPE_ERROR);
    }

    @Test
    @DisplayName("流程变量取值读取授权快照；未授权/不存在变量为明确脚本错误")
    void shouldReadAuthorizedVariablesAndRejectUnknown() {
        BpmScriptRunner.Result ok = runner.run(
                "const v = 流程变量取值('status'); if (v === 'PASS') { return 1; } return 2;",
                vars("status", "PASS"));
        assertThat(ok.kind()).isEqualTo(BpmScriptRunner.Result.Kind.OK);
        assertThat(ok.value()).isEqualTo(1L);

        BpmScriptRunner.Result alias = runner.run("return getVariable('count') === 3;", vars("count", 3));
        assertThat(alias.value()).isEqualTo(Boolean.TRUE);

        BpmScriptRunner.Result unknown = runner.run("return 流程变量取值('other');", vars("status", "PASS"));
        assertThat(unknown.kind()).isEqualTo(BpmScriptRunner.Result.Kind.SCRIPT_ERROR);
        assertThat(unknown.errorMessage()).contains("other");

        // 值为 null 的授权变量返回 null（可空变量语义）
        BpmScriptRunner.Result nullValue = runner.run("return 流程变量取值('maybe');", vars("maybe", null));
        assertThat(nullValue.kind()).isEqualTo(BpmScriptRunner.Result.Kind.OK);
        assertThat(nullValue.value()).isNull();
    }

    @Test
    @DisplayName("语句上限触发 RESOURCE_LIMIT（显式放宽超时以隔离语句限制路径）")
    void shouldEnforceStatementLimit() {
        StringBuilder loop = new StringBuilder("let s = 0;");
        loop.append("for (let i = 0; i < 1000000; i++) { s = s + 1; }");
        loop.append("return s;");
        BpmScriptRunner.Result result = runner.run(loop.toString(), vars(), 120_000L);
        assertThat(result.kind()).isEqualTo(BpmScriptRunner.Result.Kind.RESOURCE_LIMIT);
    }

    @Test
    @DisplayName("wall clock 截止强制中断：重宿主交互循环先撞 5s watcher 判 TIMEOUT，无遗留执行")
    void shouldInterruptOnWallClockTimeout() {
        // 大字符串变量使每次宿主往返（JSON 重建 eval）耗时远超语句计数增速：
        // 循环在 500k 语句内耗尽 wall clock，验证 watcher close(true) 路径可独立到达。
        Map<String, Object> variables = vars("big", "x".repeat(60_000));
        String script = "let r; while (true) { r = 流程变量取值('big'); }";
        long begin = System.currentTimeMillis();
        BpmScriptRunner.Result result = runner.run(script, variables, 500L);
        long elapsed = System.currentTimeMillis() - begin;
        assertThat(result.kind()).isEqualTo(BpmScriptRunner.Result.Kind.TIMEOUT);
        assertThat(result.errorMessage()).contains("超时");
        // 截止（≥500ms）生效且远早于语句上限/无限等待；上下文已收敛（run 返回即关闭）
        assertThat(elapsed).isBetween(400L, 300_000L);
    }

    @Test
    @DisplayName("脚本内存耗尽（超宿主堆分配）判 RESOURCE_LIMIT，上下文正常关闭")
    void shouldClassifyGuestHeapExhaustionAsResourceLimit() {
        // 数组持续持有 1MB 字符串：总内存需求无界，语句需求有界（500k 语句 ≈ 125k 次 ≈ 125GB），
        // 任何测试堆配置下宿主堆耗尽先于语句上限；guest OOM 由 Graal 转为
        // PolyglotException（isResourceExhausted）→ RESOURCE_LIMIT。
        BpmScriptRunner.Result result = runner.run(
                "const a = []; while (true) { a.push('x'.repeat(1000000)); }", vars(), 60_000L);
        assertThat(result.kind()).isEqualTo(BpmScriptRunner.Result.Kind.RESOURCE_LIMIT);
        // 运行器返回即收敛：同一 runner 可继续正常执行（上下文无遗留）
        BpmScriptRunner.Result after = runner.run("return 1;", vars());
        assertThat(after.kind()).isEqualTo(BpmScriptRunner.Result.Kind.OK);
    }

    @Test
    @DisplayName("输出序列化超 4KiB 判 RESOURCE_LIMIT")
    void shouldCapOutputAt4KiB() {
        BpmScriptRunner.Result result = runner.run("return 'x'.repeat(100000);", vars());
        assertThat(result.kind()).isEqualTo(BpmScriptRunner.Result.Kind.RESOURCE_LIMIT);
        assertThat(result.errorMessage()).contains("4");
    }

    @Test
    @DisplayName("脚本实际读取变量决定结果：同脚本不同变量值产出不同分支结果")
    void shouldDriveResultByVariableValue() {
        String script = "const v = 流程变量取值('verdict');"
                + " if (v === 'REWORK') { return 'REWORK'; } return 'NORMAL';";
        BpmScriptRunner.Result rework = runner.run(script, vars("verdict", "REWORK"));
        assertThat(rework.kind()).isEqualTo(BpmScriptRunner.Result.Kind.OK);
        assertThat(rework.value()).isEqualTo("REWORK");
        BpmScriptRunner.Result normal = runner.run(script, vars("verdict", "PASS"));
        assertThat(normal.kind()).isEqualTo(BpmScriptRunner.Result.Kind.OK);
        assertThat(normal.value()).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("并发评估隔离：多线程共用同一 runner，各上下文变量互不串扰")
    void shouldEvaluateConcurrentlyWithoutInterference() throws Exception {
        int threads = 4;
        int roundsPerThread = 10;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.List<java.util.concurrent.Callable<Boolean>> tasks = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final String expected = "T" + t;
            final String script = "return 流程变量取值('who');";
            tasks.add(() -> {
                for (int i = 0; i < roundsPerThread; i++) {
                    BpmScriptRunner.Result result = runner.run(script, vars("who", expected));
                    if (result.kind() != BpmScriptRunner.Result.Kind.OK
                            || !expected.equals(result.value())) {
                        return false;
                    }
                }
                return true;
            });
        }
        java.util.List<java.util.concurrent.Future<Boolean>> futures =
                pool.invokeAll(tasks);
        pool.shutdown();
        assertThat(futures).allSatisfy(future -> assertThat(future.get()).isTrue());
    }

    @Test
    @DisplayName("语法校验：合法脚本体通过，语法错误/缺函数体拒绝")
    void shouldValidateSyntax() {
        assertThat(runner.validate("return 1;").kind()).isEqualTo(BpmScriptRunner.Result.Kind.OK);
        assertThat(runner.validate("return ???;").kind()).isEqualTo(BpmScriptRunner.Result.Kind.SCRIPT_ERROR);
    }

    @Test
    @DisplayName("脚本无宿主访问：访问 Java/宿主类为脚本错误")
    void shouldDenyHostAccess() {
        BpmScriptRunner.Result result = runner.run(
                "return java.lang.System.getProperty('user.dir') !== null;", vars());
        assertThat(result.kind()).isEqualTo(BpmScriptRunner.Result.Kind.SCRIPT_ERROR);
    }

    @Test
    @DisplayName("集合变量以纯 JS 值进入上下文（数组/对象可读）")
    void shouldExposeCollectionsAsPlainJs() {
        BpmScriptRunner.Result list = runner.run(
                "const ids = 流程变量取值('handlers'); return ids.length;", vars("handlers", java.util.List.of("1", "2", "3")));
        assertThat(list.value()).isEqualTo(3L);
    }
}
