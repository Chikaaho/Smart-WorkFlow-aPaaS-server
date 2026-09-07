package com.sw.ck.security.config;

import org.springframework.core.env.Environment;

import java.util.Arrays;

/** 调试认证的 profile 安全边界。 */
public final class DebugAuthenticationProfile {

    private DebugAuthenticationProfile() {
    }

    /** 空 profile、prod 或与其他 profile 混用均 fail closed。 */
    public static boolean isDevelopmentOnly(Environment environment) {
        String[] activeProfiles = environment.getActiveProfiles();
        return activeProfiles.length > 0
                && Arrays.stream(activeProfiles)
                .allMatch(profile -> "dev".equals(profile) || "test".equals(profile));
    }
}
