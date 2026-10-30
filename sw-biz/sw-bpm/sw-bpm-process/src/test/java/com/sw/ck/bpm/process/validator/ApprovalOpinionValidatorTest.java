package com.sw.ck.bpm.process.validator;

import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.common.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 审批意见字段校验：USER 人员字段只接受稳定用户 ID（direction-ui-completion-020），
 * 旧 TEXT 字段的文本输入保持兼容，不做显示名到 ID 的猜测转换。
 */
class ApprovalOpinionValidatorTest {

    private static Map<String, Object> opinionForm(Map<String, Object> field) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("formId", "approval_comment");
        form.put("version", "1");
        form.put("fields", List.of(field));
        return form;
    }

    private static Map<String, Object> userField(boolean required) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("key", "assignee");
        field.put("type", "USER");
        field.put("required", required);
        return field;
    }

    private static ApprovalActionRequest request(Object value) {
        ApprovalActionRequest request = new ApprovalActionRequest();
        request.setAction(ApprovalAction.APPROVE);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("assignee", value);
        request.setOpinionData(data);
        return request;
    }

    private static void assertInvalid(Runnable action) {
        BaseException error = assertThrows(BaseException.class, action::run);
        assertEquals(BpmErrorCode.APPROVAL_OPINION_INVALID.getCode(), error.getCode());
    }

    @Test
    void userFieldAcceptsStableId() {
        assertDoesNotThrow(() -> ApprovalOpinionValidator.validate(
                request("1001"), opinionForm(userField(true)), Map.of()));
        assertDoesNotThrow(() -> ApprovalOpinionValidator.validate(
                request(1001L), opinionForm(userField(true)), Map.of()));
    }

    @Test
    void userFieldAcceptsStableIdArray() {
        assertDoesNotThrow(() -> ApprovalOpinionValidator.validate(
                request(List.of("1001", 1002)), opinionForm(userField(true)), Map.of()));
    }

    @Test
    void userFieldRejectsDisplayNameInsteadOfId() {
        assertInvalid(() -> ApprovalOpinionValidator.validate(
                request("张三"), opinionForm(userField(true)), Map.of()));
        assertInvalid(() -> ApprovalOpinionValidator.validate(
                request(List.of("1001", "李四")), opinionForm(userField(true)), Map.of()));
    }

    @Test
    void userFieldRejectsNonPositiveAndOversizedLists() {
        assertInvalid(() -> ApprovalOpinionValidator.validate(
                request("0"), opinionForm(userField(true)), Map.of()));
        List<String> tooMany = new ArrayList<>();
        for (int index = 0; index < 51; index++) tooMany.add(String.valueOf(1000 + index));
        assertInvalid(() -> ApprovalOpinionValidator.validate(
                request(tooMany), opinionForm(userField(true)), Map.of()));
    }

    @Test
    void requiredUserFieldRejectsEmptyValue() {
        BaseException error = assertThrows(BaseException.class, () ->
                ApprovalOpinionValidator.validate(
                        request(""), opinionForm(userField(true)), Map.of()));
        assertEquals(BpmErrorCode.APPROVAL_OPINION_REQUIRED.getCode(), error.getCode());
    }

    @Test
    void legacyTextFieldStillAcceptsPlainText() {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("key", "assignee");
        field.put("type", "TEXT");
        field.put("required", true);
        assertDoesNotThrow(() -> ApprovalOpinionValidator.validate(
                request("张三"), opinionForm(field), Map.of()));
    }
}
