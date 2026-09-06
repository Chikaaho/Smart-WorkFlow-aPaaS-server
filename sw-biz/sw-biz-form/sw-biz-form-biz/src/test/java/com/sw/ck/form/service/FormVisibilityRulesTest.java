package com.sw.ck.form.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.exception.FormErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 显隐联动规则（v0.0.2 P2）解析 / 发布校验 / 求值单测。
 * 覆盖：形状校验、字段存在性、op/logic 值域、每字段单规则、无环依赖、EQ/NE/EMPTY/NOT_EMPTY、ALL/ANY。
 */
class FormVisibilityRulesTest {

    private FormVisibilityRules rules;

    @BeforeEach
    void setUp() {
        rules = new FormVisibilityRules(new ObjectMapper());
    }

    private String definition(String ruleJson) {
        return "{\"fields\":[{\"name\":\"a\",\"type\":\"TEXT\"},{\"name\":\"b\",\"type\":\"TEXT\"}],"
                + "\"rules\":{\"visibility\":[" + ruleJson + "]}}";
    }

    @Test
    @DisplayName("无 rules / 空 visibility → 空规则集")
    void parseEmpty() {
        assertThat(rules.parse(null)).isEmpty();
        assertThat(rules.parse("{}")).isEmpty();
        assertThat(rules.parse("{\"fields\":[]}")).isEmpty();
    }

    @Test
    @DisplayName("合法规则解析：target/logic/conditions 齐全")
    void parseValid() {
        List<FormVisibilityRules.VisibilityRule> parsed = rules.parse(
                definition("{\"target\":\"b\",\"logic\":\"ALL\",\"conditions\":[{\"field\":\"a\",\"op\":\"EQ\",\"value\":\"x\"}]}"));
        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).target()).isEqualTo("b");
        assertThat(parsed.get(0).conditions()).hasSize(1);
    }

    @Test
    @DisplayName("发布校验：target 非已定义字段 → 拒绝")
    void validateUnknownTarget() {
        Set<String> fields = Set.of("a", "b");
        List<FormVisibilityRules.VisibilityRule> parsed = rules.parse(
                definition("{\"target\":\"zz\",\"logic\":\"ALL\",\"conditions\":[{\"field\":\"a\",\"op\":\"EMPTY\"}]}"));
        assertThatThrownBy(() -> rules.parseAndValidate(
                definition("{\"target\":\"zz\",\"logic\":\"ALL\",\"conditions\":[{\"field\":\"a\",\"op\":\"EMPTY\"}]}"), fields))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> assertThat(((BaseException) e).getCode())
                        .isEqualTo(FormErrorCode.DEFINITION_INVALID.getCode()));
        assertThat(parsed).isNotEmpty(); // parse 本身不抛
    }

    @Test
    @DisplayName("发布校验：op 非法 → 拒绝")
    void validateBadOp() {
        Set<String> fields = Set.of("a", "b");
        assertThatThrownBy(() -> rules.parseAndValidate(
                definition("{\"target\":\"b\",\"logic\":\"ALL\",\"conditions\":[{\"field\":\"a\",\"op\":\"LIKE\"}]}"), fields))
                .isInstanceOf(BaseException.class);
    }

    @Test
    @DisplayName("发布校验：同字段多条规则 → 拒绝")
    void validateDuplicateTarget() {
        Set<String> fields = Set.of("a", "b");
        String two = "{\"fields\":[{\"name\":\"a\",\"type\":\"TEXT\"},{\"name\":\"b\",\"type\":\"TEXT\"}],"
                + "\"rules\":{\"visibility\":["
                + "{\"target\":\"b\",\"logic\":\"ALL\",\"conditions\":[{\"field\":\"a\",\"op\":\"EMPTY\"}]},"
                + "{\"target\":\"b\",\"logic\":\"ANY\",\"conditions\":[{\"field\":\"a\",\"op\":\"EQ\",\"value\":\"1\"}]}"
                + "]}}";
        assertThatThrownBy(() -> rules.parseAndValidate(two, fields))
                .isInstanceOf(BaseException.class);
    }

    @Test
    @DisplayName("发布校验：环依赖（a→b→a）→ 拒绝")
    void validateCycle() {
        Set<String> fields = Set.of("a", "b");
        String cyclic = "{\"fields\":[{\"name\":\"a\",\"type\":\"TEXT\"},{\"name\":\"b\",\"type\":\"TEXT\"}],"
                + "\"rules\":{\"visibility\":["
                + "{\"target\":\"a\",\"logic\":\"ALL\",\"conditions\":[{\"field\":\"b\",\"op\":\"EMPTY\"}]},"
                + "{\"target\":\"b\",\"logic\":\"ALL\",\"conditions\":[{\"field\":\"a\",\"op\":\"EMPTY\"}]}"
                + "]}}";
        assertThatThrownBy(() -> rules.parseAndValidate(cyclic, fields))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("循环依赖");
    }

    @Test
    @DisplayName("求值：EQ/NE/EMPTY/NOT_EMPTY 与 ALL/ANY 组合")
    void evaluate() {
        List<FormVisibilityRules.VisibilityRule> eq = rules.parse(
                definition("{\"target\":\"b\",\"logic\":\"ALL\",\"conditions\":[{\"field\":\"a\",\"op\":\"EQ\",\"value\":\"x\"}]}"));
        Map<String, Object> data = new HashMap<>();
        data.put("a", "x");
        assertThat(rules.hiddenFields(eq, data)).doesNotContain("b");
        data.put("a", "y");
        assertThat(rules.hiddenFields(eq, data)).contains("b");
        data.remove("a");
        assertThat(rules.hiddenFields(eq, data)).contains("b");

        List<FormVisibilityRules.VisibilityRule> ne = rules.parse(
                definition("{\"target\":\"b\",\"logic\":\"ALL\",\"conditions\":[{\"field\":\"a\",\"op\":\"NE\",\"value\":\"x\"}]}"));
        data.put("a", "x");
        assertThat(rules.hiddenFields(ne, data)).contains("b");
        data.put("a", "");
        assertThat(rules.hiddenFields(ne, data)).doesNotContain("b");

        List<FormVisibilityRules.VisibilityRule> any = rules.parse(
                definition("{\"target\":\"b\",\"logic\":\"ANY\",\"conditions\":["
                        + "{\"field\":\"a\",\"op\":\"EQ\",\"value\":\"x\"},"
                        + "{\"field\":\"a\",\"op\":\"NOT_EMPTY\"}]}"));
        data.put("a", "other");
        assertThat(rules.hiddenFields(any, data)).doesNotContain("b");
    }
}
