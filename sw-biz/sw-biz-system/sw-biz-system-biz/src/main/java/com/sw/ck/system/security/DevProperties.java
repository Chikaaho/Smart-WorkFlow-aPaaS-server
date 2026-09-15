package com.sw.ck.system.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 开发/测试辅助开关。
 *
 * <p>该配置只承载显式的开发测试行为，不改变登录安全链路本身。</p>
 * <p>
 * I5 收口：固定验证码只在受控 local/dev/test 条件生效——纯 local/dev/test profile 是必要条件，
 * 生产 profile 或空/混合 profile 即使误开 {@code ch.dev.test-mock} 也不得返回固定答案。
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ch.dev")
public class DevProperties {

    /** 显式固定开发测试验证码；缺省为关闭。 */
    private boolean testMock = false;

    /** 与 DebugAuthenticationProfile 同口径：纯 local/dev/test profile 才允许测试行为。 */
    public boolean isTestMockAllowed(Environment environment) {
        if (!testMock) {
            return false;
        }
        String[] activeProfiles = environment.getActiveProfiles();
        return activeProfiles.length > 0
                && Arrays.stream(activeProfiles)
                .allMatch(profile -> "local".equals(profile)
                        || "dev".equals(profile)
                        || "test".equals(profile));
    }
}
