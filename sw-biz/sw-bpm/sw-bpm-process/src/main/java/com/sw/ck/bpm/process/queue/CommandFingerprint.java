package com.sw.ck.bpm.process.queue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 命令载荷指纹（P62 分级执行）：同逻辑身份不同载荷必须拒绝。
 * <p>指纹口径固定为载荷原文的 SHA-256 十六进制小写；调用方在受理时写入，
 * 重放请求比对同一指纹判定"同一操作重放"（放行）或"同身份异载荷"（拒绝）。</p>
 */
public final class CommandFingerprint {

    private CommandFingerprint() {
    }

    /** @return 载荷原文的 SHA-256（小写十六进制）；空载荷使用空串口径。 */
    public static String of(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    (payload == null ? "" : payload).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("载荷指纹计算失败", e);
        }
    }
}
