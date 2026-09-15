package com.sw.ck.form.service;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.exception.FormErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * I2 公式引擎单元测试：引用提取、依赖校验（未知字段/循环）、求值语义
 * （精度、空值传播、日期差、白名单函数、除零、非法语法）。
 */
@DisplayName("公式引擎·单元测试")
class FormulaEngineTest {

    private final FormulaEngine engine = new FormulaEngine();

    @Test
    @DisplayName("extractRefs 提取全部 ${field} 引用")
    void extractRefs_shouldCollectAll() {
        Set<String> refs = engine.extractRefs("ROUND(${a} * ${b} + ${a}, 2)");
        assertThat(refs).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    @DisplayName("未知字段引用 → FORMULA_UNKNOWN_FIELD")
    void validateDependencies_unknownField_shouldReject() {
        assertThatThrownBy(() -> engine.validateDependencies(
                Map.of("total", "${price} * ${qty}"),
                Set.of("price")))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> assertThat(((BaseException) e).getCode())
                        .isEqualTo(FormErrorCode.FORMULA_UNKNOWN_FIELD.getCode()));
    }

    @Test
    @DisplayName("公式循环依赖 → FORMULA_CYCLE")
    void validateDependencies_cycle_shouldReject() {
        assertThatThrownBy(() -> engine.validateDependencies(
                Map.of("a", "${b} + 1", "b", "${a} * 2"),
                Set.of("a", "b")))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> assertThat(((BaseException) e).getCode())
                        .isEqualTo(FormErrorCode.FORMULA_CYCLE.getCode()));
    }

    @Test
    @DisplayName("求值：算术 + 函数 + 精度 scale 6")
    void evaluate_arithmetic_shouldScale6() {
        BigDecimal result = engine.evaluate("ROUND(${price} * ${qty} + ${fee}, 2)",
                Map.of("price", new BigDecimal("19.999"), "qty", 3, "fee", new BigDecimal("0.5")));
        // 19.999*3+0.5 = 60.497 → ROUND(.,2) = 60.50
        assertThat(result).isEqualByComparingTo(new BigDecimal("60.50"));
        assertThat(result.scale()).isEqualTo(6);
    }

    @Test
    @DisplayName("空值传播：任一引用字段为空 → 结果为空")
    void evaluate_nullInput_shouldReturnNull() {
        Map<String, Object> values = new HashMap<>();
        values.put("a", null);
        values.put("b", 2);
        assertThat(engine.evaluate("${a} + ${b}", values)).isNull();
    }

    @Test
    @DisplayName("日期字段引用 → epoch 天差值（DAYS）")
    void evaluate_dates_shouldUseEpochDays() {
        LocalDate end = LocalDate.of(2026, 9, 10);
        LocalDate start = LocalDate.of(2026, 9, 1);
        BigDecimal result = engine.evaluate("DAYS(${end}, ${start})",
                Map.of("end", end.toString(), "start", start.toString()));
        assertThat(result).isEqualByComparingTo(new BigDecimal(9));
    }

    @Test
    @DisplayName("除数为 0 → FORMULA_INVALID")
    void evaluate_divideByZero_shouldReject() {
        assertThatThrownBy(() -> engine.evaluate("${a} / 0", Map.of("a", 1)))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> assertThat(((BaseException) e).getCode())
                        .isEqualTo(FormErrorCode.FORMULA_INVALID.getCode()));
    }

    @Test
    @DisplayName("非白名单函数与任意脚本形态 → FORMULA_INVALID")
    void evaluate_nonWhitelist_shouldReject() {
        List<String> bad = List.of("exec('rm')", "`${a}`.concat", "MIN(1;2)", "a & b");
        for (String expr : bad) {
            assertThatThrownBy(() -> engine.evaluate(expr, Map.of("a", 1)))
                    .as("表达式 '%s' 应被拒绝", expr)
                    .isInstanceOf(BaseException.class);
        }
    }

    @Test
    @DisplayName("MIN/MAX/ABS 正常路径")
    void evaluate_functions_ok() {
        assertThat(engine.evaluate("MIN(${a}, ${b})", Map.of("a", 3, "b", 5)))
                .isEqualByComparingTo(new BigDecimal(3));
        assertThat(engine.evaluate("MAX(${a}, ${b})", Map.of("a", 3, "b", 5)))
                .isEqualByComparingTo(new BigDecimal(5));
        assertThat(engine.evaluate("ABS(0 - ${a})", Map.of("a", 7)))
                .isEqualByComparingTo(new BigDecimal(7));
    }
}
