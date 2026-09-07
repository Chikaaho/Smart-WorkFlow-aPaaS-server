package com.sw.ck.security.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地开发调试认证开关。默认关闭，且不能单独决定调试认证是否可用；
 * {@link DebugAuthenticationProfile} 还会校验运行 profile。
 */
@Data
@ConfigurationProperties(prefix = "sw.security.debug-auth")
public class DebugAuthenticationProperties {

    /** 默认关闭，开发环境也必须显式开启。 */
    private boolean enabled;
}
