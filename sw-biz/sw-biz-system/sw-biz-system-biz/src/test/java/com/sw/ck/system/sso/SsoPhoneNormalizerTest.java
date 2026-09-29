package com.sw.ck.system.sso;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B 端手机号规范化（sso-admin-config §一）：国家区号归一、厂商脱敏值失效、
 * 禁止"截后 11 位"近似比较。
 */
@DisplayName("SSO 可信手机号规范化")
class SsoPhoneNormalizerTest {

    @Test
    @DisplayName("大陆手机号：裸 11 位补 +86；带 +86 原样保留；分隔符清除")
    void mainlandFormats_converge() {
        assertThat(SsoPhoneNormalizer.normalize("17817683690")).isEqualTo("+8617817683690");
        assertThat(SsoPhoneNormalizer.normalize("+8617817683690")).isEqualTo("+8617817683690");
        assertThat(SsoPhoneNormalizer.normalize("+86 178-1768-3690")).isEqualTo("+8617817683690");
        assertThat(SsoPhoneNormalizer.normalize("(+86) 178 1768 3690")).isEqualTo("+8617817683690");
    }

    @Test
    @DisplayName("他国号码：保留显式国家码；无国家码的非大陆形式不猜测（fail closed）")
    void internationalFormats() {
        assertThat(SsoPhoneNormalizer.normalize("+1 (650) 253-0000")).isEqualTo("+16502530000");
        assertThat(SsoPhoneNormalizer.normalize("+44 20 7123 4567")).isEqualTo("+442071234567");
        // 12 位裸数字：无法唯一确定国家码 → 无效，绝不按"截后 11 位"近似
        assertThat(SsoPhoneNormalizer.normalize("8617817683690")).isNull();
        // 10 位裸数字：非大陆手机号形态且无国家码 → 无效
        assertThat(SsoPhoneNormalizer.normalize("6502530000")).isNull();
    }

    @Test
    @DisplayName("厂商脱敏值与脏输入 → 无效（绝不参与匹配）")
    void maskedOrDirtyInput_invalid() {
        assertThat(SsoPhoneNormalizer.normalize("178****3690")).isNull();
        assertThat(SsoPhoneNormalizer.normalize("")).isNull();
        assertThat(SsoPhoneNormalizer.normalize(null)).isNull();
        assertThat(SsoPhoneNormalizer.normalize("abc")).isNull();
        assertThat(SsoPhoneNormalizer.normalize("+861781768369")).isNull();
    }
}
