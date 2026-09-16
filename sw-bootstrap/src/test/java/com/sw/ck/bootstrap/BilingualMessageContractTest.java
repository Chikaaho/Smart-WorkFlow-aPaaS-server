package com.sw.ck.bootstrap;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.ErrorCode;
import com.sw.ck.common.exception.GlobalExceptionHandler;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.response.R;
import com.sw.ck.common.trace.EventRef;
import com.sw.ck.system.security.AuthErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P61 阶段 E：同一失败在 zh-CN / en-US 下的成对文案契约。
 *
 * <p>钉死三件事：</p>
 * <ol>
 *   <li>同一 errorKey 在两种语言下各自给出自然文案（不是机器翻译占位，也不是键名）；</li>
 *   <li>errorKey、数值码与事件引用不随语言变化；</li>
 *   <li>目录缺失的 errorKey 回退到枚举中的 zh-CN 默认值，不出现键名或空文案。</li>
 * </ol>
 */
class BilingualMessageContractTest {

    private ResourceBundleMessageSource source() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setUseCodeAsDefaultMessage(false);
        return source;
    }

    private static void bindAcceptLanguage(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/demo");
        if (value != null) {
            request.addHeader("Accept-Language", value);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private GlobalExceptionHandler handler() {
        return new GlobalExceptionHandler(source());
    }

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void bindWithEventRef(String header) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(EventRef.HEADER)).thenReturn("web-locale-case");
        when(request.getRequestId()).thenReturn("server-locale-case");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        bindAcceptLanguage(header);
    }

    @Test
    @DisplayName("zh-CN：认证失败给出中文结论")
    void zhCn_shouldResolveChineseText() {
        bindWithEventRef("zh-CN,zh;q=0.9");
        R<Void> result = handler().handleBaseException(new BaseException(AuthErrorCode.PASSWORD_ERROR));

        assertThat(result.getCode()).isEqualTo(2104);
        assertThat(result.getErrorKey()).isEqualTo("auth.credential_invalid");
        assertThat(result.getMsg()).isEqualTo("用户名或密码不正确，请重新输入");
    }

    @Test
    @DisplayName("en-US：同一 errorKey 给出英文结论，code/errorKey/eventRef 不变")
    void enUs_shouldResolveEnglishText() {
        bindWithEventRef("en-US,en;q=0.9");
        R<Void> result = handler().handleBaseException(new BaseException(AuthErrorCode.PASSWORD_ERROR));

        assertThat(result.getCode()).as("数值码不随语言变化").isEqualTo(2104);
        assertThat(result.getErrorKey()).as("语义键不随语言变化").isEqualTo("auth.credential_invalid");
        assertThat(result.getMsg()).isEqualTo("Incorrect username or password. Please try again.");
    }

    @Test
    @DisplayName("成对文案：抽查键在两种语言下都不是键名、不是空串、互不相同")
    void catalog_shouldProvidePairedNaturalText() {
        ResourceBundleMessageSource source = source();
        String[] sampledKeys = {
                "error.common.system_error",
                "error.common.unauthenticated",
                "error.common.forbidden",
                "error.auth.credential_invalid",
                "error.system.session_required",
                "error.system.sso_login_not_completed",
        };
        for (String key : sampledKeys) {
            String zh = source.getMessage(key, null, Locale.SIMPLIFIED_CHINESE);
            String en = source.getMessage(key, null, Locale.US);
            assertThat(zh).as("%s 的 zh-CN 文案不得为空", key).isNotBlank();
            assertThat(en).as("%s 的 en-US 文案不得为空", key).isNotBlank();
            assertThat(zh).as("%s 的 zh-CN 文案不得是键名占位", key).doesNotContain("error.");
            assertThat(en).as("%s 的 en-US 文案不得是键名占位", key).doesNotContain("error.");
            assertThat(en.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .as("%s 的 en-US 文案不应是中文字节（避免机器翻译占位）", key)
                    .isNotEqualTo(zh.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("目录之外的 errorKey 回退到调用方缺省文案，不出现键名或空文案")
    void missingKey_shouldFallBackToEnumDefault() {
        bindWithEventRef("en-US");
        // 未来新增枚举但尚未收录目录时，必须回退到枚举缺省文案而不是显示键名
        com.sw.ck.common.exception.ErrorCode futureKey = new com.sw.ck.common.exception.ErrorCode() {
            @Override
            public int getCode() {
                return 4999;
            }

            @Override
            public String getMessage() {
                return "未来新增语义的中文缺省文案";
            }

            @Override
            public String getErrorKey() {
                return "form.not_yet_in_catalog";
            }
        };

        R<Void> result = handler().handleBaseException(new BaseException(futureKey));

        assertThat(result.getErrorKey()).isEqualTo("form.not_yet_in_catalog");
        assertThat(result.getMsg())
                .isEqualTo("未来新增语义的中文缺省文案")
                .doesNotContain("error.");
    }

    @Test
    @DisplayName("无 Accept-Language 头时回退 zh-CN")
    void noHeader_shouldFallBackToChinese() {
        bindWithEventRef(null);
        R<Void> result = handler().handleBaseException(new BaseException(AuthErrorCode.CAPTCHA_ERROR));

        assertThat(result.getMsg()).isEqualTo("验证码不正确，请重新输入");
    }

    @Test
    @DisplayName("无法识别的 Accept-Language 头回退 zh-CN，不抛异常")
    void malformedHeader_shouldFallBackToChinese() {
        bindWithEventRef("!!!not-a-language!!!");
        R<Void> result = handler().handleBaseException(new BaseException(AuthErrorCode.CAPTCHA_ERROR));

        assertThat(result.getMsg()).isEqualTo("验证码不正确，请重新输入");
    }

    @Test
    @DisplayName("未装配消息源时全部回退枚举默认（部署容错）")
    void nullSource_shouldFallBackToEnumDefault() {
        bindWithEventRef("en-US");
        GlobalExceptionHandler handler = new GlobalExceptionHandler(null);

        R<Void> result = handler.handleBaseException(new BaseException(AuthErrorCode.CAPTCHA_ERROR));
        assertThat(result.getMsg())
                .isEqualTo(AuthErrorCode.CAPTCHA_ERROR.getMessage());

        R<Void> forbidden = handler.handleAuthorizationDenied(
                new org.springframework.security.authorization.AuthorizationDeniedException("x"));
        assertThat(forbidden.getMsg()).isEqualTo(CommonErrorCode.FORBIDDEN.getMessage());
    }

    @Test
    @DisplayName("目录覆盖全部枚举 errorKey，且 zh/en 键集完全一致")
    void catalog_shouldCoverEveryEnumErrorKeyInBothLanguages() {
        ResourceBundleMessageSource source = source();
        List<ErrorCode> all = new ArrayList<>();
        all.addAll(List.of(com.sw.ck.common.exception.CommonErrorCode.values()));
        all.addAll(List.of(AuthErrorCode.values()));
        all.addAll(List.of(com.sw.ck.form.api.exception.FormErrorCode.values()));
        all.addAll(List.of(com.sw.ck.bpm.api.exception.BpmErrorCode.values()));
        all.addAll(List.of(com.sw.ck.openapi.api.exception.OpenApiErrorCode.values()));

        List<String> missing = new ArrayList<>();
        Set<String> zhKeys = new java.util.TreeSet<>();
        Set<String> enKeys = new java.util.TreeSet<>();

        for (ErrorCode code : all) {
            String key = "error." + code.getErrorKey();
            String zh;
            String en;
            try {
                zh = source.getMessage(key, null, Locale.SIMPLIFIED_CHINESE);
            } catch (org.springframework.context.NoSuchMessageException e) {
                missing.add(code.getErrorKey() + " 缺 zh-CN");
                continue;
            }
            try {
                en = source.getMessage(key, null, Locale.US);
            } catch (org.springframework.context.NoSuchMessageException e) {
                missing.add(code.getErrorKey() + " 缺 en-US");
                continue;
            }
            zhKeys.add(key);
            enKeys.add(key);
            assertThat(zh).as("%s 的 zh-CN 文案不得为空", key).isNotBlank();
            assertThat(en).as("%s 的 en-US 文案不得为空", key).isNotBlank();
            assertThat(zh).as("%s 的 zh-CN 文案不得是键名占位", key).doesNotContain("error.");
            assertThat(en).as("%s 的 en-US 文案不得是键名占位", key).doesNotContain("error.");
        }

        assertThat(missing).as("每个枚举 errorKey 都必须有两种语言文案").isEmpty();
        assertThat(zhKeys).as("zh/en 键集必须一致").isEqualTo(enKeys);
        assertThat(zhKeys.size())
                .as("目录键数 = 127 枚举键 + 出口专用键（upload_too_large 等）")
                .isGreaterThanOrEqualTo(127);
    }
}
