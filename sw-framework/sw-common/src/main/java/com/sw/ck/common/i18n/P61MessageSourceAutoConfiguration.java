package com.sw.ck.common.i18n;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * 用户消息目录装配（P61 §3.3）。
 *
 * <p>真实容器验证（P61RuntimeBehaviorBootTest）发现：仅写 {@code spring.messages.basename}
 * 并不保证 {@link MessageSource} 就绪——占位实现会让所有解析回退到中文缺省。
 * 因此这里<b>显式装配</b>消息源，并把 basename/编码固定为目录约定，
 * 使 {@code LocalizedMessages} 的双语解析不依赖部署配置。</p>
 *
 * <p>{@code before = MessageSourceAutoConfiguration.class}：本配置先行注册
 * {@code messageSource}，Boot 的默认装配随之退避，避免双 Bean 歧义。</p>
 */
@AutoConfiguration(before = MessageSourceAutoConfiguration.class)
public class P61MessageSourceAutoConfiguration {

    /** 消息目录基名：与 {@code sw-common/src/main/resources/i18n/messages*.properties} 对应。 */
    public static final String BASENAME = "i18n/messages";

    @Bean(name = "messageSource")
    public MessageSource p61MessageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename(BASENAME);
        source.setDefaultEncoding("UTF-8");
        // 缺键时抛 NoSuchMessageException，由 LocalizedMessages 回退到枚举中文缺省；
        // 绝不把键名当作文案返回给用户。
        source.setUseCodeAsDefaultMessage(false);
        source.setFallbackToSystemLocale(false);
        // 过滤器层（401/403/503）没有注入通道，安装为共享源供其解析
        LocalizedMessages.install(source);
        return source;
    }
}
