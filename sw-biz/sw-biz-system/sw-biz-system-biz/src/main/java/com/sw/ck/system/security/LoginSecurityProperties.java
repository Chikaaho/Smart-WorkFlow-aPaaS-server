package com.sw.ck.system.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P45 登录安全配置。
 * <p>
 * 私钥只经文件路径注入（配置文件里只出现路径，私钥本体落在文件系统/密钥设施挂载的文件中）；
 * 本类不提供任何可用的默认私钥，未配置时由 {@link RsaLoginKeyManager} 在启动期 fail-fast，
 * 拒绝静默降级。
 */
@Data
@Component
@ConfigurationProperties(prefix = "sw.security.login")
public class LoginSecurityProperties {

    /** 登录挑战业务有效期（秒）：超过即返回 2102「验证码已过期」 */
    private long challengeTtlSeconds = 300;

    /**
     * 权威记录保留期（秒）：Redis TTL。必须长于业务有效期，使服务端在有效期结束后
     * 仍能稳定判别「内容匹配但已过期」(2102)；保留期结束后按挑战不存在处理 (2101)。
     */
    private long recordRetentionSeconds = 600;

    /** 同一挑战内验证码内容错误的最大允许次数，超过后挑战作废 */
    private int captchaFailLimit = 5;

    /** 客户端 timestamp 与服务器时间的最大容忍绝对差（毫秒） */
    private long clientTimeToleranceMillis = 180_000;

    /** 验证码字符数 */
    private int captchaLength = 4;

    /** 当前生效的 RSA 私钥版本标识（对应 rsa-private-key-file 指向文件的密钥） */
    private String rsaKeyVersion = "v1";

    /**
     * 当前生效版本的 RSA 私钥文件路径（PKCS#8 PEM 文件）。
     * <p>
     * 配置文件只放路径，私钥本体不落配置；文件内容允许含头尾 PEM 标记或纯 Base64 单行。
     */
    private String rsaPrivateKeyFile = "";

    /**
     * 轮换窗口内的旧版本私钥文件：版本 → PKCS#8 PEM 文件路径；
     * 至少保留到最后一个旧挑战过期。
     */
    private Map<String, String> rsaExtraKeyFiles = new LinkedHashMap<>();

    /** 验证码字符集（去除易混淆字符 0/O/1/I/l） */
    private String captchaCharset = "23456789abcdefghjkmnpqrstuvwxyz";

    /**
     * 验证码答案摘要的服务端密钥（HMAC-SHA256 密钥）：Redis 权威记录只保留
     * HMAC 摘要，Redis 泄露时不能凭摘要枚举恢复答案。生产经环境变量注入，
     * 未配置时启动期 fail-fast。
     */
    private String digestSecret = "";
}
