package com.sw.ck.notify.service;

import com.sw.ck.common.crypto.AesGcmCipher;
import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.api.NotifyChannelAdapter;
import com.sw.ck.notify.dto.NotifyChannelStatusDTO;
import com.sw.ck.notify.entity.NotifyChannelConfig;
import com.sw.ck.notify.mapper.NotifyChannelConfigMapper;
import com.sw.ck.notify.service.impl.NotifyChannelConfigServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 渠道凭据入库（0.2.4）：写入即 AEAD 加密、回读仅服务端解密、明文不回显；
 * 旧密文保留语义（空白 configSecret 不覆盖既有密文）；损坏密文按未配置处理不阻断通知。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotifyChannelConfigServiceSecretTest {

    private static final String CIPHER_KEY =
            Base64.getEncoder().encodeToString(new byte[32]);

    @Mock
    private NotifyChannelAdapter emailAdapter;
    @Mock
    private NotifyChannelConfigMapper configMapper;

    private NotifyChannelConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        when(emailAdapter.channel()).thenReturn(Optional.of(NotifyChannel.EMAIL));
        service = new NotifyChannelConfigServiceImpl(List.of(emailAdapter), configMapper, CIPHER_KEY);
    }

    @Test
    void updateWithSecret_EncryptsAtRest_AndResolvesForServerSideUse() {
        when(configMapper.selectOne(any())).thenReturn(null);

        service.updateTenantChannel("EMAIL", true, "163 企业邮箱", "host=smtp.example.com",
                "{\"host\":\"smtp.example.com\",\"port\":465,\"username\":\"ops@example.com\","
                        + "\"password\":\"unit-test-secret\",\"ssl\":true}");

        ArgumentCaptor<NotifyChannelConfig> insertCaptor = ArgumentCaptor.forClass(NotifyChannelConfig.class);
        verify(configMapper).insert(insertCaptor.capture());
        NotifyChannelConfig saved = insertCaptor.getValue();
        assertNotNull(saved.getConfigCipher());
        assertFalse(saved.getConfigCipher().contains("unit-test-secret"));
        assertFalse(saved.getConfigCipher().contains("smtp.example.com"),
                "密文不得包含可读的 host/账号信息");

        when(configMapper.selectOne(any())).thenReturn(saved);
        Map<String, String> resolved = service.resolveChannelConfig("EMAIL").orElseThrow();
        assertEquals("smtp.example.com", resolved.get("host"));
        assertEquals("465", resolved.get("port"));
        assertEquals("ops@example.com", resolved.get("username"));
        assertEquals("unit-test-secret", resolved.get("password"));
    }

    @Test
    void blankSecret_PreservesExistingCipher() {
        NotifyChannelConfig existing = new NotifyChannelConfig();
        existing.setId(3L);
        existing.setChannel("EMAIL");
        existing.setEnabled(false);
        existing.setConfigCipher(new AesGcmCipher(CIPHER_KEY).encrypt("{\"host\":\"old\"}"));
        when(configMapper.selectOne(any())).thenReturn(existing);

        service.updateTenantChannel("EMAIL", false, "显示名", "摘要", "   ");

        verify(configMapper).updateById(existing);
        assertNotNull(existing.getConfigCipher());
        assertTrue(new AesGcmCipher(CIPHER_KEY).decrypt(existing.getConfigCipher())
                .contains("\"host\":\"old\""), "空白凭据不得覆盖既有密文");
    }

    @Test
    void corruptedCipher_TreatedAsUnconfigured() {
        NotifyChannelConfig existing = new NotifyChannelConfig();
        existing.setId(4L);
        existing.setChannel("EMAIL");
        existing.setConfigCipher("not-a-valid-cipher");
        when(configMapper.selectOne(any())).thenReturn(existing);

        assertTrue(service.resolveChannelConfig("EMAIL").isEmpty());
    }

    @Test
    void listStatus_ExposesHasSecretWithoutPlaintext() {
        NotifyChannelConfig existing = new NotifyChannelConfig();
        existing.setChannel("EMAIL");
        existing.setEnabled(true);
        existing.setConfigCipher(new AesGcmCipher(CIPHER_KEY).encrypt("{\"password\":\"x\"}"));
        when(configMapper.selectList(any())).thenReturn(List.of(existing));

        List<NotifyChannelStatusDTO> status = service.listChannelStatus();

        assertEquals(1, status.size());
        assertTrue(status.get(0).getHasSecret());
        assertNull(status.get(0).getConfigSecret(), "回读路径不得回显凭据明文");
    }
}
