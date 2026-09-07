package com.sw.ck.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.security.cache.LoginUserLoader;
import com.sw.ck.security.filter.AccessLoggingFilter;
import com.sw.ck.security.filter.DebugAuthenticationFilter;
import com.sw.ck.security.filter.JwtAuthenticationFilter;
import com.sw.ck.security.handler.RestAccessDeniedHandler;
import com.sw.ck.security.handler.RestAuthenticationEntryPoint;
import com.sw.ck.security.jwt.JwtTokenProvider;
import com.sw.ck.security.support.PermissionService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * before = {SecurityFilterAutoConfiguration, UserDetailsServiceAutoConfiguration}：
 * 必须早于 Spring Boot 官方安全自动配置注册，否则官方会因为"此时还看不到我们的
 * SecurityFilterChain/UserDetailsService Bean"而创建默认放行链、以及随机密码登录用户
 * （并打印 "Using generated security password" 警告日志）——与
 * {@code RedisConfig}/{@code SecurityAutoConfiguration} 中已采用的 before 排序套路同源。
 */
@AutoConfiguration(before = {SecurityFilterAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
@EnableWebSecurity
@EnableMethodSecurity
public class WebSecurityAutoConfiguration {

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                                             LoginUserLoader loginUserLoader,
                                                             SecurityProperties securityProperties) {
        return new JwtAuthenticationFilter(jwtTokenProvider, loginUserLoader, securityProperties);
    }

    @Bean
    public DebugAuthenticationFilter debugAuthenticationFilter(
            LoginUserLoader loginUserLoader,
            SecurityProperties securityProperties,
            DebugAuthenticationProperties debugAuthenticationProperties,
            org.springframework.core.env.Environment environment,
            ObjectMapper objectMapper) {
        return new DebugAuthenticationFilter(loginUserLoader, securityProperties,
                debugAuthenticationProperties, environment, objectMapper);
    }

    @Bean
    public AccessLoggingFilter accessLoggingFilter() {
        return new AccessLoggingFilter();
    }

    @Bean
    public RestAuthenticationEntryPoint restAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return new RestAuthenticationEntryPoint(objectMapper);
    }

    @Bean
    public RestAccessDeniedHandler restAccessDeniedHandler(ObjectMapper objectMapper) {
        return new RestAccessDeniedHandler(objectMapper);
    }

    @Bean("ss")
    public PermissionService permissionService() {
        return new PermissionService();
    }

    /**
     * 占位 UserDetailsService：本项目认证完全由 {@link JwtAuthenticationFilter} 手动装配
     * SecurityContext 完成，不经过 AuthenticationManager/UserDetailsService。仅用于消除
     * Boot 官方在找不到任何 UserDetailsService/AuthenticationProvider 时自动生成随机密码
     * 登录用户并打印警告日志的默认行为。
     */
    @Bean
    @ConditionalOnMissingBean(UserDetailsService.class)
    public UserDetailsService noopUserDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("本系统认证不经过 UserDetailsService：" + username);
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                     JwtAuthenticationFilter jwtAuthenticationFilter,
                                                     RestAuthenticationEntryPoint authenticationEntryPoint,
                                                     RestAccessDeniedHandler accessDeniedHandler,
                                                     SecurityProperties securityProperties,
                                                     DebugAuthenticationFilter debugAuthenticationFilter,
                                                     AccessLoggingFilter accessLoggingFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(securityProperties.getPermitUrls().toArray(new String[0])).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(debugAuthenticationFilter, JwtAuthenticationFilter.class)
                .addFilterAfter(accessLoggingFilter, DebugAuthenticationFilter.class);
        return http.build();
    }
}
