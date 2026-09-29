package com.sw.ck.bootstrap.i5;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G4-S / sso-admin-config §二：SSO 加密器显式依赖绑定运行时验证（非秘密）。
 * <p>
 * 真实完整启动（dev profile）后断言显式化改造后的容器事实：共享
 * {@code AesGcmCipher} bean 仅剩 agent 模块实例（SSO 侧不再参与同型条件竞争、
 * 不再被 bean 注册顺序隐式顶替）；SSO 凭据加密经 {@code SsoCredentialCipher}
 * 以专属密钥源 {@code sw.security.sso.cipher-key} 显式构造（加解密往返成功，
 * 密钥缺失 fail-fast）。只输出 bean 名、数量与布尔结果，不输出任何密钥值。
 * </p>
 */
@DisplayName("G4-S SSO 加密器显式绑定（agent bean 不受影响；SSO 专属密钥源往返/fail-fast）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class I5SsoCipherRuntimeDiagTest {

    private ConfigurableApplicationContext app;

    @BeforeAll
    void boot() {
        Map<String, Object> props = new HashMap<>();
        props.put("server.port", "0");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.profiles.active", "dev");
        props.put("ch.dev.test-mock", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "org.h2.Driver");
        props.put("spring.datasource.dynamic.datasource.master.url",
                "jdbc:h2:mem:i5g4sdiag;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        props.put("spring.datasource.dynamic.datasource.master.username", "sa");
        props.put("spring.datasource.dynamic.datasource.master.password", "");
        props.put("sw.security.jwt.secret", "i5-g4s-test-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8());
        props.put("sw.security.login.digest-secret", "i5-g4s-test-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("i5-g4s-diag", props));
                    context.getEnvironment().setActiveProfiles("dev");
                    context.getEnvironment().getSystemProperties().put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("i5G4sLoginContextProvider", provider);
                })
                .run();
    }

    private static String generatedRsaPkcs8() {
        try {
            java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            java.security.KeyPair kp = g.generateKeyPair();
            java.security.KeyFactory f = java.security.KeyFactory.getInstance("RSA");
            java.security.spec.PKCS8EncodedKeySpec spec =
                    new java.security.spec.PKCS8EncodedKeySpec(kp.getPrivate().getEncoded());
            f.generatePrivate(spec);
            return java.util.Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("运行容器：共享 AesGcmCipher bean 仅 agent 实例；SSO 专属密钥显式构造往返成功且缺失 fail-fast")
    void cipherExplicitBindingDiagnostic() {
        Map<String, com.sw.ck.common.crypto.AesGcmCipher> beans =
                app.getBeansOfType(com.sw.ck.common.crypto.AesGcmCipher.class);
        System.out.println("[G4S-DIAG] AesGcmCipher beans=" + beans.keySet()
                + " count=" + beans.size());
        // 共享 bean 只剩 agent 实例：SSO 不再注册同型 bean，密钥来源不随注册顺序漂移
        assertThat(beans).hasSize(1);
        String agentBeanName = beans.keySet().iterator().next();
        assertThat(agentBeanName).contains("agent");
        boolean ssoSharedCipherPresent = beans.keySet().stream().anyMatch(n -> n.contains("ssoCipher"));
        System.out.println("[G4S-DIAG] ssoSharedCipherPresent=" + ssoSharedCipherPresent
                + " agentCipherPresent=true");
        assertThat(ssoSharedCipherPresent).isFalse();

        // SSO 专属密钥源：与装配工厂同源（sw.security.sso.cipher-key）显式构造，
        // 加解密往返成功；密钥缺失 fail-fast
        String ssoKey = app.getEnvironment().getProperty("sw.security.sso.cipher-key");
        com.sw.ck.system.sso.SsoCredentialCipher ssoCipher =
                new com.sw.ck.system.sso.SsoCredentialCipher(ssoKey);
        String plain = "g4s-roundtrip-sample";
        String enc = ssoCipher.encrypt(plain);
        assertThat(ssoCipher.decrypt(enc)).isEqualTo(plain);
        assertThat(ssoCipher.decryptable(enc)).isTrue();
        System.out.println("[G4S-DIAG] ssoCredentialCipher roundtrip=OK (explicit sw.security.sso.cipher-key)");
        assertThatThrownBy(() -> new com.sw.ck.system.sso.SsoCredentialCipher(" "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sw.security.sso.cipher-key");
        System.out.println("[G4S-DIAG] blank-key fail-fast=OK");
    }
}
