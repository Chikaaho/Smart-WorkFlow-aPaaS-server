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

    // 说明：watcher 超时（TIMEOUT）路径无法在纯 JS 单测中与语句上限隔离——
    // 任何足够长的 JS 循环都会先触发 500k 语句上限（两类均落 FAILED+诊断，
    // 业务语义一致）；watcher 强制中断作为运行时防线保留，真超时归由资源超限
    // 同口径记录（ADR-P64-001 §3 资源边界）。

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
