package com.sw.ck.security.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "sw.security")
public class SecurityProperties {

    private String tokenHeader = "Authorization";
    private String tokenPrefix = "Bearer ";

    /**
     * 免认证白名单（Ant 风格路径），登录/openapi 等接口需加入此列表。
     * 业务方可通过 sw.security.permit-urls 整体覆盖；此处为最小可用的默认值。
     * I5 收口：默认白名单不再包含 swagger 与 /actuator/**——开发文档与监控端点
     * 不随生产 profile 匿名开放；需要时在对应 profile 显式声明最小集合。
     */
    private List<String> permitUrls = new ArrayList<>(List.of(
            "/auth/challenge", "/auth/login", "/auth/refresh", "/auth/logout",
            "/auth/sso/*/callback", "/auth/sso/ticket", "/auth/sso/candidate",
            "/auth/sso/*/authorize-login",
            "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"
    ));
}
