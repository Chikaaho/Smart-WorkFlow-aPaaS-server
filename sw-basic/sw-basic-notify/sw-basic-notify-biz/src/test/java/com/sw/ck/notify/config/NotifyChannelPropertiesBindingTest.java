package com.sw.ck.notify.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.2.4 缺陷修复回归：渠道配置键位必须与适配器 @ConditionalOnProperty
 * （sw.notify.channels.&lt;CHANNEL&gt;.enabled）及文档一致——曾因前缀与字段同名
 * 导致真实键位变成 sw.notify.channels.channels.*，按文档启用渠道后 from 恒为 null。
 */
class NotifyChannelPropertiesBindingTest {

    @Test
    void documentedChannelKeysBindIntoMap() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("sw.notify.channels.EMAIL.enabled", "true");
        source.put("sw.notify.channels.EMAIL.host", "127.0.0.1");
        source.put("sw.notify.channels.EMAIL.port", "1025");
        source.put("sw.notify.channels.EMAIL.from", "lc024-peer@controlled.local");
        Binder binder = new Binder(new MapConfigurationPropertySource(source));

        NotifyChannelProperties props = binder.bind("sw.notify",
                Bindable.of(NotifyChannelProperties.class)).orElseGet(NotifyChannelProperties::new);

        NotifyChannelProperties.ChannelProps email = props.getChannels().get("EMAIL");
        assertNotNull(email, "按文档键位配置的渠道必须进入 channels 映射");
        assertTrue(Boolean.TRUE.equals(email.getEnabled()));
        assertEquals("127.0.0.1", email.getHost());
        assertEquals(Integer.valueOf(1025), email.getPort());
        assertEquals("lc024-peer@controlled.local", email.getFrom(),
                "发件人地址必须可绑定（from 缺失会使邮件渠道恒失败）");
    }
}
