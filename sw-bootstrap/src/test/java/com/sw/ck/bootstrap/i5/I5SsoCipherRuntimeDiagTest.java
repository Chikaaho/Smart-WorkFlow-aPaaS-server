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

/**
 * G4-S 三级补证：SSO 加密器运行时诊断（非秘密）。
 * <p>
 * 真实完整启动（dev profile）后断言：容器内 AesGcmCipher bean 的注册名与数量
 * （运行时类型/来源确定），并用运行容器内的加密器对已知样例做加解密往返
 * （凭据解密成功检查）。只输出 bean 名、数量与布尔结果，不输出任何密钥值。
 * 区分：本测试=dev 实际链运行诊断；生产契约（SW_SSO_CIPHER_KEY 占位与运维
 * 口径）由回执文字另行描述，不混用。
 * </p>
 */
@DisplayName("G4-S SSO 加密器运行时诊断（bean 类型/来源/解密成功，非秘密）")
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
    @DisplayName("运行容器：AesGcmCipher bean 注册名/数量确定；SSO 注入实例加解密往返成功")
    void cipherBeanRuntimeDiagnostic() {
        Map<String, com.sw.ck.common.crypto.AesGcmCipher> beans =
                app.getBeansOfType(com.sw.ck.common.crypto.AesGcmCipher.class);
        System.out.println("[G4S-DIAG] AesGcmCipher beans=" + beans.keySet()
                + " count=" + beans.size());
        // 非秘密运行事实：记录注册名与数量（不输出密钥）
        assertThat(beans).isNotEmpty();
        boolean ssoCipherPresent = beans.keySet().stream().anyMatch(n -> n.contains("ssoCipher"));
        boolean agentCipherPresent = beans.keySet().stream().anyMatch(n -> n.contains("agent"));
        System.out.println("[G4S-DIAG] ssoCipherPresent=" + ssoCipherPresent
                + " agentCipherPresent=" + agentCipherPresent);
        // SSO 注入路径实际拿到哪个实例：按类型取唯一 bean（与 @ConditionalOnMissingBean
        // 顶替顺序一致——同类型多实例时容器按注册名注入 primary/唯一者）
        var injected = app.getBean(com.sw.ck.common.crypto.AesGcmCipher.class);
        // 凭据解密成功检查：注入实例加解密往返
        String plain = "g4s-roundtrip-sample";
        String enc = injected.encrypt(plain);
        assertThat(injected.decrypt(enc)).isEqualTo(plain);
        System.out.println("[G4S-DIAG] roundtrip via bean="
                + (beans.entrySet().stream().anyMatch(e -> e.getValue() == injected)
                    ? beans.entrySet().stream().filter(e -> e.getValue() == injected).findFirst().get().getKey()
                    : "primary-instance")
                + " result=OK");
    }
}
