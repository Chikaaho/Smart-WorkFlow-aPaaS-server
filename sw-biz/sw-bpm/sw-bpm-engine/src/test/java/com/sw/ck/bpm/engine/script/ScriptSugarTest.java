package com.sw.ck.bpm.engine.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BF20261013-021：`#函数名(...)` 语法糖编译器用例（与 Web 侧 script-sugar.spec.ts 同语义）。
 */
class ScriptSugarTest {

    @Test
    void plainScriptWithoutHashPassesThrough() {
        assertEquals("return 1;", ScriptSugar.transpile("return 1;"));
        assertEquals("", ScriptSugar.transpile(""));
        assertEquals(null, ScriptSugar.transpile(null));
    }

    @Test
    void bareIdentifierArgBecomesQuotedString() {
        assertEquals("获取流程变量(\"var_dept_ids\")", ScriptSugar.transpile("#获取流程变量(var_dept_ids)"));
        assertEquals("获取流程变量(\"a\")", ScriptSugar.transpile("#获取流程变量( a )"));
    }

    @Test
    void quotedAndLiteralArgsPassThrough() {
        assertEquals("获取流程变量(\"var_x\")", ScriptSugar.transpile("#获取流程变量(\"var_x\")"));
        assertEquals("设置流程变量(\"count\",1)", ScriptSugar.transpile("#设置流程变量(count, 1)"));
        assertEquals("设置必填(\"f\",true)", ScriptSugar.transpile("#设置必填(f, true)"));
        assertEquals("设置隐藏(\"f\",null)", ScriptSugar.transpile("#设置隐藏(f, null)"));
    }

    @Test
    void expressionArgsKeepSemantics() {
        assertEquals("设置流程变量(\"count\",获取流程变量(\"a\") + 1)",
                ScriptSugar.transpile("#设置流程变量(count, #获取流程变量(a) + 1)"));
        assertEquals("获取部门负责人(获取流程变量(\"dept\"))",
                ScriptSugar.transpile("#获取部门负责人(#获取流程变量(dept))"));
        assertEquals("设置字段值(\"total\",Number(获取字段值(\"a\")) * Number(获取字段值(\"b\")))",
                ScriptSugar.transpile("#设置字段值(total, Number(#获取字段值(a)) * Number(#获取字段值(b)))"));
    }

    @Test
    void multiArgSplittingRespectsNestingAndStrings() {
        assertEquals("fn(\"a\",inner(\"b\",2),\"c,d\")",
                ScriptSugar.transpile("#fn(a, #inner(b, 2), \"c,d\")"));
        assertEquals("fn()", ScriptSugar.transpile("#fn()"));
    }

    @Test
    void hashInsideStringsAndCommentsIsUntouched() {
        assertEquals("return \"#不是调用\";", ScriptSugar.transpile("return \"#不是调用\";"));
        assertEquals("// #注释行\nreturn 1;", ScriptSugar.transpile("// #注释行\nreturn 1;"));
        assertEquals("/* #块注释 */ return 1;", ScriptSugar.transpile("/* #块注释 */ return 1;"));
        assertEquals("return '#x(1)';", ScriptSugar.transpile("return '#x(1)';"));
    }

    @Test
    void templateLiteralInterpolationIsCompiled() {
        assertEquals("return `值=${获取字段值(\"f\")}`;",
                ScriptSugar.transpile("return `值=${#获取字段值(f)}`;"));
        assertEquals("return `a${ {x:1} }b`;",
                ScriptSugar.transpile("return `a${ {x:1} }b`;"));
    }

    @Test
    void wholeStatementExample() {
        assertEquals("var value = 获取流程变量(\"var_dept_ids\"); return value;",
                ScriptSugar.transpile("var value = #获取流程变量(var_dept_ids); return value;"));
    }

    @Test
    void malformedSugarThrowsDiagnosableError() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> ScriptSugar.transpile("var a = #;"));
        assertTrue(missing.getMessage().contains("应为函数名"));
        assertThrows(IllegalArgumentException.class, () -> ScriptSugar.transpile("#foo"));
        assertThrows(IllegalArgumentException.class, () -> ScriptSugar.transpile("#foo(1"));
        // 无 # 的脚本不做糖校验（语法由 JS 引擎负责）；带 # 且字符串未闭合须报错
        assertEquals("return '未闭合;", ScriptSugar.transpile("return '未闭合;"));
        assertThrows(IllegalArgumentException.class, () -> ScriptSugar.transpile("return '#未闭合"));
    }

    @Test
    void dynamicNameViaExpressionEscapeHatch() {
        assertEquals("获取流程变量(\"\" + name)", ScriptSugar.transpile("#获取流程变量(\"\" + name)"));
    }
}
