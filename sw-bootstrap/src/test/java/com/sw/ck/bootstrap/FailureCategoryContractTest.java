package com.sw.ck.bootstrap;

import com.sw.ck.common.exception.FailureCategory;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P61 阶段 B：失败分类与恢复语义一致性的常驻回归测试。
 *
 * <p>钉死两件事，防止「九类分类」退化为装饰性枚举：</p>
 * <ol>
 *   <li>通用出口的每个错误码都有明确分类，且文案与分类的恢复动作一致
 *       ——可重试类必须给出重试时机，不可重试类不得写「稍后重试」误导用户重复操作；</li>
 *   <li>分类集合恰好是方向 §3.5 规定的九类，不增不减。</li>
 * </ol>
 */
class FailureCategoryContractTest {

    /** 方向 §3.5 规定的九类失败语义。 */
    private static final Set<String> REQUIRED_CATEGORIES = Set.of(
            "INPUT_CORRECTABLE",
            "AUTHENTICATION_REQUIRED",
            "PERMISSION_DENIED",
            "OBJECT_NOT_FOUND",
            "BUSINESS_CONFLICT",
            "IN_PROGRESS",
            "RETRYABLE_INFRASTRUCTURE",
            "NON_RETRYABLE_CONFIGURATION",
            "SYSTEM_FAULT");

    @Test
    @DisplayName("失败分类恰好覆盖方向 §3.5 的九类")
    void categories_shouldMatchRequiredNine() {
        Set<String> actual = new LinkedHashSet<>();
        for (FailureCategory category : FailureCategory.values()) {
            actual.add(category.name());
        }
        assertThat(actual).containsExactlyInAnyOrderElementsOf(REQUIRED_CATEGORIES);
    }

    @Test
    @DisplayName("每类都有默认结论、恢复动作，且可重试标记与恢复措辞自洽")
    void categories_shouldCarryCoherentRecoverySemantics() {
        for (FailureCategory category : FailureCategory.values()) {
            assertThat(category.getDefaultMessage())
                    .as("%s 必须有面向用户的默认结论", category)
                    .isNotBlank();
            assertThat(category.getRecoveryHint())
                    .as("%s 必须有恢复动作", category)
                    .isNotBlank();
            assertThat(category.getDefaultMessage())
                    .as("%s 的默认结论不得出现堆栈、类名或代码标识", category)
                    .doesNotContain("Exception")
                    .doesNotContain("null")
                    .doesNotContain("{}");
        }
    }

    @Test
    @DisplayName("通用出口错误码全部分类，且文案与分类的恢复动作一致")
    void commonErrorCodes_shouldHaveCoherentCategoryAndMessage() {
        List<String> problems = new ArrayList<>();
        for (CommonErrorCode code : CommonErrorCode.values()) {
            FailureCategory category = ((ErrorCode) code).getCategory();
            String message = code.getMessage();
            if (message == null || message.isBlank()) {
                problems.add(code + " 缺少文案");
                continue;
            }
            if (category.isRetryable()) {
                // 可重试类必须说明何时或如何重试
                if (!message.contains("重试") && !message.contains("稍后") && !message.contains("刷新")) {
                    problems.add(code + " 属可重试类但未给出重试时机: " + message);
                }
            } else {
                // 不可重试类不得误导用户重复操作
                if (message.contains("稍后重试")) {
                    problems.add(code + " 属不可重试类却写「稍后重试」，会误导用户重复操作: " + message);
                }
            }
        }
        assertThat(problems).as("通用出口文案与失败分类必须自洽").isEmpty();
    }

    @Test
    @DisplayName("权限类与不存在类分别给出「联系管理员」「返回列表」的恢复动作")
    void specificCategories_shouldGiveTheirRecoveryAction() {
        assertThat(FailureCategory.PERMISSION_DENIED.getDefaultMessage())
                .as("权限不足不可重试，必须指出找谁处理")
                .contains("管理员");
        assertThat(FailureCategory.OBJECT_NOT_FOUND.getDefaultMessage())
                .as("对象不存在必须指出可自行恢复的动作")
                .contains("刷新");
        assertThat(FailureCategory.NON_RETRYABLE_CONFIGURATION.getDefaultMessage())
                .as("配置类失败不得让用户反复重试")
                .doesNotContain("稍后重试")
                .contains("管理员");
    }

    @Test
    @DisplayName("未显式分类的错误码默认归为系统故障（安全兜底，不误报为用户可修正）")
    void unclassifiedErrorCode_shouldDefaultToSystemFault() {
        ErrorCode anonymous = new ErrorCode() {
            @Override
            public int getCode() {
                return 9999;
            }

            @Override
            public String getMessage() {
                return "未分类";
            }

            @Override
            public String getErrorKey() {
                return "test.unclassified";
            }
        };
        assertThat(anonymous.getCategory()).isEqualTo(FailureCategory.SYSTEM_FAULT);
    }
}
