package com.sw.ck.iot.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * IoT 预约/下发链统一时钟装配（P63）。
 * <p>
 * 默认系统 UTC 时钟；上下文已显式注册 Clock Bean 时（受控验证场景以同类型 Bean
 * 注入）自动让位，使受控时间贯穿 createIntent 过期判定、到点对账/认领/下发与命令
 * 外发过期守卫等真实入口，不以等待自然到点替代。
 * </p>
 */
@Configuration
public class IotClockConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock iotClock() {
        return Clock.systemUTC();
    }
}
