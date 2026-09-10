package com.sw.ck.system.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 开发/测试辅助开关。
 *
 * <p>该配置只承载显式的开发测试行为，不改变登录安全链路本身。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ch.dev")
public class DevProperties {

    /** 显式固定开发测试验证码；缺省为关闭。 */
    private boolean testMock = false;
}
