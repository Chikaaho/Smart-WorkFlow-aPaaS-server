package com.sw.ck.bootstrap.p62;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * P62 S6 补证（G2）：真实双进程恢复演练编排入口。
 *
 * <p>由 {@link P62RecoveryDrillOrchestrationTest} 在测试 JVM（进程 A，dispatch 停摆）
 * 受理 100 条命令后以 {@code java P62RecoveryDrillWorker} fork 进程 B；进程 B 独立
 * 启动应用（同一持久库）开始消费，输出 READY 后由编排侧计时至 100 条全部收敛。</p>
 * <p>运行方式（不直接运行）：</p>
 * <pre>
 * java -cp &lt;test classpath&gt; com.sw.ck.bootstrap.p62.P62RecoveryDrillWorker \
 *   &lt;jdbcUrl&gt; &lt;evidenceDir&gt;
 * </pre>
 */
public final class P62RecoveryDrillWorker {

    private P62RecoveryDrillWorker() {
    }

    public static void main(String[] args) throws Exception {
        String jdbcUrl = args[0];
        Path evidenceDir = Path.of(args[1]);
        Files.createDirectories(evidenceDir);

        String pgUrl = jdbcUrl;
        Map<String, Object> props = baseProps(pgUrl);
        // 进程 B：正常调度（默认轮询），生产语义消费
        ConfigurableApplicationContext app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p62-drain", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p62DrainLoginContextProvider", provider);
                })
                .run();
        // 可服务标记：context 就绪 + 调度线程已启动（dispatcher 500ms 首轮内即开始领取）
        ProcessHandle.current().pid();
        Files.writeString(evidenceDir.resolve("worker-ready.txt"),
                "pid=" + ProcessHandle.current().pid()
                        + " port=" + app.getEnvironment().getProperty("local.server.port")
                        + " at=" + java.time.LocalDateTime.now() + "\n");
        System.out.println("[P62-EV] drain.worker ready");
        // 保持运行至被编排方终止（收敛判定在编排侧轮询 DB）
        Thread.sleep(10 * 60 * 1000L);
        app.close();
    }

    /** 测试与 worker 共用的基础属性（同一持久库）。 */
    static Map<String, Object> baseProps(String pgUrl) {
        Map<String, Object> props = new HashMap<>();
        props.put("server.port", "0");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "org.postgresql.Driver");
        props.put("spring.datasource.dynamic.datasource.master.url", pgUrl);
        props.put("spring.datasource.dynamic.datasource.master.username", "postgres");
        props.put("spring.datasource.dynamic.datasource.master.password", "postgres");
        props.put("sw.security.jwt.secret", "p62-drill-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", RsaKeyHolder.RSA_KEY);
        props.put("sw.security.login.digest-secret", "p62-drill-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("logging.level.root", "WARN");
        return props;
    }

    /** 固定 RSA（worker 与编排进程同值，避免每次生成分叉）。 */
    static final class RsaKeyHolder {
        static final String RSA_KEY = generated();

        private RsaKeyHolder() {
        }

        private static String generated() {
            try {
                java.security.KeyPairGenerator generator =
                        java.security.KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                return java.util.Base64.getEncoder()
                        .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
