package com.sw.ck.bootstrap.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;

/**
 * 测试用的登录 RSA 私钥文件：进程内生成 2048 位密钥，写成 PKCS#8 PEM 临时文件，
 * 返回文件路径供 {@code sw.security.login.rsa-private-key-file} 使用。
 * <p>
 * 生产契约只接受私钥文件路径，配置文件中不承载私钥本体；测试同样按该契约提供文件，
 * 不通过内联属性绕过。
 */
public final class LoginRsaTestKeyFiles {

    private LoginRsaTestKeyFiles() {
    }

    /** 生成临时私钥文件并返回其绝对路径（JVM 退出时自动清理）。 */
    public static String create() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            String base64 = Base64.getEncoder()
                    .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
            Path file = Files.createTempFile("sw-login-rsa-", ".pem");
            Files.writeString(file, base64);
            file.toFile().deleteOnExit();
            return file.toString();
        } catch (Exception e) {
            throw new IllegalStateException("测试登录 RSA 私钥文件生成失败", e);
        }
    }
}
