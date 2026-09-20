package com.sw.ck.common.i18n;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Locale;

/**
 * 用户消息本地化解析器（P61 §3.3）。
 *
 * <p>语言来源是请求的 {@code Accept-Language}；无法识别时回退 zh-CN。解析键为
 * {@code error.<errorKey>}，目录缺失时回退到调用方给定的缺省文案（即枚举中的
 * zh-CN 默认值），保证任何 errorKey 都有可用文案。</p>
 *
 * <p>{@code errorKey}、数值码与事件引用<b>不随语言变化</b>；只有人类可读结论本地化。</p>
 */
public final class LocalizedMessages {

    /** 本轮支持的语言（方向 §3.3：只交付 zh-CN / en-US，不扩充）。 */
    public static final List<Locale> SUPPORTED = List.of(Locale.SIMPLIFIED_CHINESE, Locale.US);

    /** 无法识别时回退 zh-CN。 */
    public static final Locale FALLBACK = Locale.SIMPLIFIED_CHINESE;

    /** 消息目录键前缀。 */
    public static final String KEY_PREFIX = "error.";

    /**
     * 启动期安装的共享消息源。
     *
     * <p>过滤器层（401/403/503 出口）没有构造注入通道，因此由
     * {@code P61MessageSourceAutoConfiguration} 在装配消息源时安装到这里。
     * 未安装（如纯单元测试）时一律回退调用方给定的缺省文案。</p>
     */
    private static volatile org.springframework.context.MessageSource sharedSource;

    private LocalizedMessages() {
    }

    /** 由消息源装配方在启动期调用一次；传 {@code null} 可卸载（测试用）。 */
    public static void install(org.springframework.context.MessageSource messageSource) {
        sharedSource = messageSource;
    }

    /**
     * 用共享消息源按当前请求语言解析文案；未安装消息源时回退缺省文案。
     */
    public static String text(String errorKey, String fallback) {
        return text(sharedSource, errorKey, fallback, current());
    }

    /**
     * 从当前请求解析语言。
     *
     * @return 受支持的语言；无请求上下文或均不匹配时返回 zh-CN
     */
    public static Locale current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return FALLBACK;
        }
        return resolve(servletAttributes.getRequest());
    }

    /**
     * 从请求头解析语言。
     *
     * <p>R6 真实容器验证：JDK 的 {@link Locale#lookup} 对单标签头（如仅 {@code en-US}）
     * 在受支持集合为 [zh_CN, en_US] 时可能不命中，导致静默回退中文。因此这里不做
     * 通用质量协商，只做确定性的语言前缀映射——本轮只支持两种语言，
     * 按客户端给出顺序取第一个可识别的，识别不了回退 zh-CN。</p>
     */
    public static Locale resolve(HttpServletRequest request) {
        String acceptLanguage = request.getHeader("Accept-Language");
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return FALLBACK;
        }
        for (String part : acceptLanguage.split(",")) {
            String tag = part.split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (tag.startsWith("en")) {
                return Locale.US;
            }
            if (tag.startsWith("zh")) {
                return Locale.SIMPLIFIED_CHINESE;
            }
        }
        return FALLBACK;
    }

    /**
     * 解析某语义键在指定语言下的文案。
     *
     * @param messageSource Spring 消息源；为 {@code null} 时直接返回缺省文案
     * @param errorKey      稳定语义标识
     * @param fallback      目录缺失时的缺省文案（枚举中的 zh-CN 默认值）
     * @param locale        目标语言
     */
    public static String text(MessageSource messageSource, String errorKey, String fallback,
                              Locale locale) {
        if (messageSource == null || errorKey == null || errorKey.isBlank()) {
            return fallback;
        }
        try {
            String resolved = messageSource.getMessage(KEY_PREFIX + errorKey, null, locale);
            if (resolved == null || resolved.isBlank()) {
                return fallback;
            }
            // 无参调用永远无法填充目录占位符：与其把 {0} 原样透给用户，不如回退调用方文案
            return resolved.matches(".*\\{\\d.*") ? fallback : resolved;
        } catch (NoSuchMessageException missing) {
            return fallback;
        }
    }

    /**
     * 解析带参数的语义键（P61 R3）。目录条目用 {@code {0}}/{@code {1}} 占位，参数由调用方提供。
     *
     * <p>为什么需要它：目录若只有一句无参文案，会**覆盖**调用方传入的业务细节。字段权限与
     * 显隐规则的拒绝必须带上字段显示名，否则用户看不到是哪个字段出了问题；把这些细节做成
     * 目录参数，既保留目录作为唯一文案权威，又不丢失可定位性。</p>
     */
    public static String textArgs(String errorKey, String fallback, Object... args) {
        if (sharedSource == null || errorKey == null || errorKey.isBlank()) {
            return format(fallback, args);
        }
        return textArgs(sharedSource, errorKey, fallback, current(), args);
    }

    /** 带参数版本的指定语言解析；目录缺失时用缺省文案按同一套下标填充，细节不丢。 */
    public static String textArgs(MessageSource messageSource, String errorKey, String fallback,
                                  Locale locale, Object... args) {
        if (messageSource == null || errorKey == null || errorKey.isBlank()) {
            return format(fallback, args);
        }
        if (args == null || args.length == 0) {
            return text(messageSource, errorKey, fallback, locale);
        }
        try {
            String resolved = messageSource.getMessage(KEY_PREFIX + errorKey, null, locale);
            if (resolved == null || resolved.isBlank()) {
                return format(fallback, args);
            }
            // 目录条目未用占位符即为无参通用文案：调用方既然传了参数（字段显示名等细节），
            // 通用文案会把这些细节抹掉，此时优先调用方文案，细节不丢
            if (!resolved.contains("{")) {
                return format(fallback, args);
            }
            return format(resolved, args);
        } catch (NoSuchMessageException missing) {
            return format(fallback, args);
        }
    }

    private static String format(String template, Object... args) {
        if (template == null || args == null || args.length == 0) {
            return template;
        }
        return java.text.MessageFormat.format(template.replace("'", "''"), args);
    }
}
