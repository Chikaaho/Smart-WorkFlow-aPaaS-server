package com.sw.ck.bpm.engine.script;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.script.ScriptFunctionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BF20261013-021：脚本常用函数与 `#` 语法糖集成用例。
 * <p>
 * 覆盖：流程变量读（快照/实例变量/本脚本写入的优先级）、流程变量写（收集、类型、约束）、
 * 部门负责人/分管领导/上级部门（目录快照与上级链）、语法糖端到端。
 * </p>
 */
@DisplayName("BF021 脚本常用函数测试")
class BpmScriptFunctionsTest {

    private final BpmScriptRunner runner = new BpmScriptRunner(new ObjectMapper());

    private Map<String, Object> vars(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    /** 部门树：1(root) ← 2 ← 3；负责人：1→100，2→200；3 无。 */
    private ScriptFunctionContext orgContext() {
        Map<Long, Long> parents = new LinkedHashMap<>();
        parents.put(1L, 0L);
        parents.put(2L, 1L);
        parents.put(3L, 2L);
        Map<Long, Long> leaders = new LinkedHashMap<>();
        leaders.put(1L, 100L);
        leaders.put(2L, 200L);
        return new ScriptFunctionContext(parents, leaders, Map.of());
    }

    @Test
    @DisplayName("获取流程变量：# 糖与裸名参数、快照取值")
    void readVariableWithSugar() {
        BpmScriptRunner.Result result = runner.run("return #获取流程变量(dept);",
                vars("dept", "D1"), 5000L, ScriptFunctionContext.EMPTY);
        assertThat(result.kind()).isEqualTo(BpmScriptRunner.Result.Kind.OK);
        assertThat(result.value()).isEqualTo("D1");
    }

    @Test
    @DisplayName("获取流程变量：实例变量覆盖快照（脚本间传递落点）")
    void instanceVariableOverridesSnapshot() {
        ScriptFunctionContext ctx = new ScriptFunctionContext(Map.of(), Map.of(), vars("flag", 9L));
        BpmScriptRunner.Result result = runner.run("return #获取流程变量(flag);",
                vars("flag", 1L), 5000L, ctx);
        assertThat(result.kind()).isEqualTo(BpmScriptRunner.Result.Kind.OK);
        assertThat(result.value()).isEqualTo(9L);
    }

    @Test
    @DisplayName("获取流程变量：未授权且实例中不存在 → 可诊断脚本错误")
    void unknownVariableIsScriptError() {
        BpmScriptRunner.Result result = runner.run("return #获取流程变量(nope);",
                vars("dept", "D1"), 5000L, ScriptFunctionContext.EMPTY);
        assertThat(result.kind()).isEqualTo(BpmScriptRunner.Result.Kind.SCRIPT_ERROR);
        assertThat(result.errorMessage()).contains("变量未授权或不存在");
    }

    @Test
    @DisplayName("设置流程变量：写入收集随结果回传（数字/文本/布尔/数组）且脚本内立即可读")
    void writeVariableCollectsAndReadsBack() {
        BpmScriptRunner.Result result = runner.run(
                "#设置流程变量(count, 2); #设置流程变量(label, \"ok\"); #设置流程变量(ids, [1,2,3]);"
                        + " return #获取流程变量(count) + 1;",
                vars("count", 1L), 5000L, ScriptFunctionContext.EMPTY);
        assertThat(result.kind()).isEqualTo(BpmScriptRunner.Result.Kind.OK);
        assertThat(result.value()).isEqualTo(3L);
        assertThat(result.variableWrites()).containsEntry("count", 2L);
        assertThat(result.variableWrites()).containsEntry("label", "ok");
        assertThat(result.variableWrites()).containsEntry("ids", List.of(1, 2, 3));
    }

    @Test
    @DisplayName("设置流程变量：系统保留键与空名拒绝")
    void writeVariableRejectsReservedNames() {
        assertThat(runner.run("#设置流程变量(processInstanceId, \"x\"); return 1;",
                vars(), 5000L, ScriptFunctionContext.EMPTY).kind())
                .isEqualTo(BpmScriptRunner.Result.Kind.SCRIPT_ERROR);
        assertThat(runner.run("#设置流程变量(\"\", 1); return 1;",
                vars(), 5000L, ScriptFunctionContext.EMPTY).kind())
                .isEqualTo(BpmScriptRunner.Result.Kind.SCRIPT_ERROR);
    }

    @Test
    @DisplayName("设置流程变量：值类型不支持（函数）判脚本错误")
    void writeVariableRejectsUnsupportedValue() {
        BpmScriptRunner.Result result = runner.run("#设置流程变量(fn, function(){}); return 1;",
                vars(), 5000L, ScriptFunctionContext.EMPTY);
        assertThat(result.kind()).isEqualTo(BpmScriptRunner.Result.Kind.SCRIPT_ERROR);
        assertThat(result.errorMessage()).contains("变量值仅支持");
    }

    @Test
    @DisplayName("部门负责人：目录命中返回用户ID，未设置/未知部门返回 null")
    void deptLeaderLookup() {
        BpmScriptRunner.Result hit = runner.run("return #获取部门负责人(2);",
                vars(), 5000L, orgContext());
        assertThat(hit.kind()).isEqualTo(BpmScriptRunner.Result.Kind.OK);
        assertThat(hit.value()).isEqualTo(200L);

        BpmScriptRunner.Result noLeader = runner.run("return #获取部门负责人(3);",
                vars(), 5000L, orgContext());
        assertThat(noLeader.kind()).isEqualTo(BpmScriptRunner.Result.Kind.OK);
        assertThat(noLeader.value()).isNull();

        BpmScriptRunner.Result unknown = runner.run("return #获取部门负责人(99);",
                vars(), 5000L, orgContext());
        assertThat(unknown.kind()).isEqualTo(BpmScriptRunner.Result.Kind.OK);
        assertThat(unknown.value()).isNull();
    }

    @Test
    @DisplayName("获取分管领导：沿上级链找最近负责人；无上级返回 null")
    void deptSupervisorWalksUp() {
        // 部门3 无负责人 → 上级2 有负责人 200
        BpmScriptRunner.Result fromThree = runner.run("return #获取分管领导(3);",
                vars(), 5000L, orgContext());
        assertThat(fromThree.value()).isEqualTo(200L);
        // 部门2 自身负责人是 200（部门负责人），分管看上上级 → 1 的负责人 100
        BpmScriptRunner.Result fromTwo = runner.run("return #获取分管领导(2);",
                vars(), 5000L, orgContext());
        assertThat(fromTwo.value()).isEqualTo(100L);
        // 根部门无上级
        BpmScriptRunner.Result fromOne = runner.run("return #获取分管领导(1);",
                vars(), 5000L, orgContext());
        assertThat(fromOne.value()).isNull();
    }

    @Test
    @DisplayName("获取上级部门：返回父部门ID；根/未知返回 null")
    void parentDeptLookup() {
        assertThat(runner.run("return #获取上级部门(3);", vars(), 5000L, orgContext()).value())
                .isEqualTo(2L);
        assertThat(runner.run("return #获取上级部门(1);", vars(), 5000L, orgContext()).value())
                .isNull();
        assertThat(runner.run("return #获取上级部门(99);", vars(), 5000L, orgContext()).value())
                .isNull();
    }

    @Test
    @DisplayName("英文别名同样可用（getVariable/setVariable/deptLeader）")
    void englishAliasesWork() {
        BpmScriptRunner.Result result = runner.run(
                "#setVariable(x, #getVariable(src)); return #deptLeader(2);",
                vars("src", 7L), 5000L, orgContext());
        assertThat(result.kind()).isEqualTo(BpmScriptRunner.Result.Kind.OK);
        assertThat(result.value()).isEqualTo(200L);
        assertThat(result.variableWrites()).containsEntry("x", 7L);
    }

    @Test
    @DisplayName("部门ID支持数字文本（变量值常为文本）")
    void deptIdAcceptsNumericString() {
        BpmScriptRunner.Result result = runner.run("return #获取部门负责人(\"2\");",
                vars(), 5000L, orgContext());
        assertThat(result.value()).isEqualTo(200L);
    }

    @Test
    @DisplayName("语法校验：糖编译失败给出可诊断错误")
    void validateReportsSugarErrors() {
        assertThat(runner.validate("return #获取流程变量(1);").kind())
                .isEqualTo(BpmScriptRunner.Result.Kind.OK);
        BpmScriptRunner.Result bad = runner.validate("return #;");
        assertThat(bad.kind()).isEqualTo(BpmScriptRunner.Result.Kind.SCRIPT_ERROR);
        assertThat(bad.errorMessage()).contains("语法糖");
    }
}
