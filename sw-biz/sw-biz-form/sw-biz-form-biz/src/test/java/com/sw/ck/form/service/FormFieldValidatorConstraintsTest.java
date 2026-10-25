package com.sw.ck.form.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.mapper.FormConfigMapper;
import com.sw.ck.system.api.dict.DictFacade;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class FormFieldValidatorConstraintsTest {

    private final FormFieldValidator validator = new FormFieldValidator(
            mock(FormConfigMapper.class), new ObjectMapper());
    private final DictFacade dictFacade = mock(DictFacade.class);

    @Test
    void unicodeCodePointLengthAndFullPatternMatchApplyToText() {
        var field = text("account", 2, 4, "^[A-Za-z]+$", "账号格式不正确");
        assertThatCode(() -> validator.validateFields(Map.of("account", field),
                Map.of("account", "AB"), dictFacade)).doesNotThrowAnyException();
        var wideText = text("wide", 2, 4, null, null);
        assertThatCode(() -> validator.validateFields(Map.of("wide", wideText),
                Map.of("wide", "😀😀😀😀"), dictFacade)).doesNotThrowAnyException();

        assertThatThrownBy(() -> validator.validateFields(Map.of("account", field),
                Map.of("account", "A"), dictFacade))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("至少");
        assertThatThrownBy(() -> validator.validateFields(Map.of("account", field),
                Map.of("account", "A😀"), dictFacade))
                .hasMessage("账号格式不正确")
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((BaseException) e).getCode())
                        .isEqualTo(FormErrorCode.SUBMIT_FIELD_TYPE_MISMATCH.getCode()));
    }

    @Test
    void optionalBlankValueSkipsLengthAndPatterns() {
        var field = text("nickname", 5, 10, "^[A-Z]+$", null);
        assertThatCode(() -> validator.validateFields(Map.of("nickname", field),
                Map.of("nickname", ""), dictFacade)).doesNotThrowAnyException();
    }

    @Test
    void subfieldUsesTheSameTextConstraints() {
        var child = text("item", 2, 3, "^[a-z]+$", "明细格式不正确");
        var table = new FormFieldValidator.FieldDef(
                "items", "TABLE", false, null, List.of(child), null, "明细", false,
                null, null, List.of(), null, null, null, null);
        assertThatThrownBy(() -> validator.validateFields(Map.of("items", table),
                Map.of("items", List.of(Map.of("item", "AB"))), dictFacade))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("明细格式不正确");
    }

    @Test
    void richTextRequiredAndLengthUseVisibleTextNotMarkup() {
        var required = richText("body", true, null, null);
        Map<String, Object> formattingOnly = new HashMap<>();
        formattingOnly.put("body", RichTextSanitizer.PREFIX + "<p><br></p>");
        assertThatThrownBy(() -> validator.validateFields(Map.of("body", required),
                formattingOnly, dictFacade))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("必填");

        var bounded = richText("body", false, 2, 3);
        Map<String, Object> longMarkup = new HashMap<>();
        longMarkup.put("body", RichTextSanitizer.PREFIX
                + "<p><span data-rt-font=\"song\" data-rt-size=\"16\">你好</span></p>");
        assertThatCode(() -> validator.validateFields(Map.of("body", bounded),
                longMarkup, dictFacade)).doesNotThrowAnyException();
        // 校验时已按服务端权威规则规范化并写回：脚本/事件/样式不进入持久化值。
        String normalized = String.valueOf(longMarkup.get("body"));
        org.assertj.core.api.Assertions.assertThat(normalized)
                .contains("你好")
                .contains("data-rt-font=\"song\"")
                .doesNotContain("script")
                .doesNotContain("onerror");

        Map<String, Object> over = new HashMap<>();
        over.put("body", RichTextSanitizer.PREFIX + "<p>你好世界</p>");
        assertThatThrownBy(() -> validator.validateFields(Map.of("body", bounded),
                over, dictFacade))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("不能超过 3");
    }

    private static FormFieldValidator.FieldDef text(String name, Integer min, Integer max,
                                                     String pattern, String message) {
        return new FormFieldValidator.FieldDef(
                name, "TEXT", false, null, null, null, name, false,
                null, null, List.of(), max, min, pattern, message);
    }

    private static FormFieldValidator.FieldDef richText(String name, boolean required,
                                                        Integer min, Integer max) {
        return new FormFieldValidator.FieldDef(
                name, "RICH_TEXT", required, null, null, null, name, false,
                null, null, List.of(), max, min, null, null);
    }
}
