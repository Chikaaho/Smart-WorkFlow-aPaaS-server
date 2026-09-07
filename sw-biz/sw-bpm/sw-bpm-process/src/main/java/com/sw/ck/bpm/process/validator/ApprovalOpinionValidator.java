package com.sw.ck.bpm.process.validator;

import com.sw.ck.bpm.api.expression.RestrictedExpressionEvaluator;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.common.exception.BaseException;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 审批意见的后端权威校验器。
 * <p>
 * 只解释轻量字段配置和受控表达式，不执行脚本，也不把前端校验结果作为权限依据。
 * </p>
 */
public final class ApprovalOpinionValidator {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "TEXT", "TEXTAREA", "NUMBER", "RADIO", "CHECKBOX", "SELECT", "DATETIME", "NOTE");

    private ApprovalOpinionValidator() {
    }

    @SuppressWarnings("unchecked")
    public static void validate(ApprovalActionRequest request,
                                Map<String, Object> opinionForm,
                                Map<String, Object> variables) {
        if (request == null) {
            throw new BaseException(BpmErrorCode.APPROVAL_ACTION_INVALID);
        }
        if (request.getAction() == null) {
            throw new BaseException(BpmErrorCode.APPROVAL_ACTION_INVALID);
        }
        if (opinionForm == null || opinionForm.isEmpty()) {
            ensureDefaultRemark(request);
            return;
        }
        String formId = text(opinionForm.get("formId"));
        String version = text(opinionForm.get("version"));
        if (formId == null || version == null) {
            throw new BaseException(BpmErrorCode.APPROVAL_OPINION_INVALID);
        }
        if (text(request.getOpinionFormId()) != null
                && !formId.equals(text(request.getOpinionFormId()))) {
            throw new BaseException(BpmErrorCode.APPROVAL_OPINION_INVALID);
        }
        if (text(request.getOpinionFormVersion()) != null
                && !version.equals(text(request.getOpinionFormVersion()))) {
            throw new BaseException(BpmErrorCode.APPROVAL_OPINION_INVALID);
        }
        Object rawFields = opinionForm.get("fields");
        if (!(rawFields instanceof Collection<?> fields)) {
            throw new BaseException(BpmErrorCode.APPROVAL_OPINION_INVALID);
        }

        Map<String, Object> data = request.getOpinionData() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getOpinionData());
        // DEFAULT_REMARK 是共享的兼容意见契约：即使前端没有显式回传字段，
        // 也必须把空备注固化到动作快照，避免“请求成功但历史意见无 comment”
        // 破坏 v1 默认意见的可回放语义。
        if ("DEFAULT_REMARK".equals(formId)) {
            data.putIfAbsent("comment", request.getComment() == null ? "" : request.getComment());
        }
        Set<String> declaredKeys = new java.util.HashSet<>();
        for (Object rawField : fields) {
            if (!(rawField instanceof Map<?, ?> field)) {
                throw new BaseException(BpmErrorCode.APPROVAL_OPINION_INVALID);
            }
            String key = text(field.get("key"));
            String type = text(field.get("type"));
            if (key == null || type == null || !SUPPORTED_TYPES.contains(type.toUpperCase())) {
                throw new BaseException(BpmErrorCode.APPROVAL_OPINION_INVALID);
            }
            if (!declaredKeys.add(key)) {
                throw new BaseException(BpmErrorCode.APPROVAL_OPINION_INVALID);
            }
            boolean visible = true;
            String visibleWhen = text(field.get("visibleWhen"));
            if (visibleWhen != null) {
                try {
                    visible = RestrictedExpressionEvaluator.matches(visibleWhen, variables == null
                            ? Map.of() : variables);
                } catch (RuntimeException e) {
                    throw new BaseException(BpmErrorCode.APPROVAL_OPINION_INVALID);
                }
            }
            if (visible && !data.containsKey(key)) {
                String initialExpression = text(field.get("initialExpression"));
                if (initialExpression != null) {
                    try {
                        Object initialValue = RestrictedExpressionEvaluator.value(initialExpression,
                                variables == null ? Map.of() : variables);
                        if (initialValue != null) data.put(key, initialValue);
                    } catch (RuntimeException e) {
                        throw new BaseException(BpmErrorCode.APPROVAL_OPINION_INVALID);
                    }
                }
            }
            if (visible && data.get(key) == null && text(data.get(key)) == null
                    && Boolean.TRUE.equals(field.get("required"))) {
                throw new BaseException(BpmErrorCode.APPROVAL_OPINION_REQUIRED);
            }
            Object value = data.get(key);
            if (value == null || !visible) continue;
            validateLength(field, value);
            validateNumber(field, value);
            validateOptions(field, value);
        }
        if (!declaredKeys.containsAll(data.keySet())) {
            throw new BaseException(BpmErrorCode.APPROVAL_OPINION_INVALID);
        }
        request.setOpinionFormId(formId);
        request.setOpinionFormVersion(version);
        request.setOpinionData(data);
    }

    private static void ensureDefaultRemark(ApprovalActionRequest request) {
        if (request.getOpinionData() == null) request.setOpinionData(new LinkedHashMap<>());
        if (request.getComment() != null && !request.getComment().isBlank()) {
            request.getOpinionData().putIfAbsent("comment", request.getComment());
        } else {
            request.getOpinionData().putIfAbsent("comment", "");
        }
        if (request.getOpinionFormId() == null || request.getOpinionFormId().isBlank()) {
            request.setOpinionFormId("DEFAULT_REMARK");
        }
        if (request.getOpinionFormVersion() == null || request.getOpinionFormVersion().isBlank()) {
            request.setOpinionFormVersion("1");
        }
    }

    private static void validateLength(Map<?, ?> field, Object value) {
        Object maxLength = field.get("maxLength");
        if (maxLength == null || !(value instanceof CharSequence)) return;
        try {
            if (((CharSequence) value).length() > Integer.parseInt(String.valueOf(maxLength))) {
                throw new BaseException(BpmErrorCode.APPROVAL_OPINION_INVALID);
            }
        } catch (NumberFormatException e) {
            throw new BaseException(BpmErrorCode.APPROVAL_OPINION_INVALID);
        }
    }

    private static void validateNumber(Map<?, ?> field, Object value) {
        if (!(value instanceof Number) && !(value instanceof String)) return;
        Object min = field.get("min");
        Object max = field.get("max");
        if (min == null && max == null) return;
        try {
            double number = Double.parseDouble(String.valueOf(value));
            if (min != null && number < Double.parseDouble(String.valueOf(min))) {
                throw new BaseException(BpmErrorCode.APPROVAL_OPINION_INVALID);
            }
            if (max != null && number > Double.parseDouble(String.valueOf(max))) {
                throw new BaseException(BpmErrorCode.APPROVAL_OPINION_INVALID);
            }
        } catch (NumberFormatException e) {
            throw new BaseException(BpmErrorCode.APPROVAL_OPINION_INVALID);
        }
    }

    private static void validateOptions(Map<?, ?> field, Object value) {
        Object rawOptions = field.get("options");
        if (!(rawOptions instanceof Collection<?> options)) return;
        if (value instanceof Collection<?> values) {
            if (!values.stream().allMatch(item -> options.stream().map(String::valueOf)
                    .anyMatch(option -> option.equals(String.valueOf(item))))) {
                throw new BaseException(BpmErrorCode.APPROVAL_OPINION_INVALID);
            }
        } else if (options.stream().map(String::valueOf)
                .noneMatch(option -> option.equals(String.valueOf(value)))) {
            throw new BaseException(BpmErrorCode.APPROVAL_OPINION_INVALID);
        }
    }

    private static String text(Object value) {
        if (value == null) return null;
        String result = String.valueOf(value).trim();
        return result.isEmpty() ? null : result;
    }
}
