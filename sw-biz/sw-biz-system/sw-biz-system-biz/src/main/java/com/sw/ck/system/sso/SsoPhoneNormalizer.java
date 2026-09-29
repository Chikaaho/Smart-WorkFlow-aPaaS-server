package com.sw.ck.system.sso;

/**
 * SSO 可信手机号规范化（B 端准入，sso-admin-config 方向）。
 * <p>
 * 厂商与本地 sys_user.phone 的书写格式不统一（{@code +86 178-1768-3690}、
 * {@code 17817683690}、{@code (650) 253-0000} 等）；绑定/登录匹配必须基于
 * 统一规范化结果，禁止任何"截后 N 位"的近似比较。规范化规则：
 * </p>
 * <ul>
 *   <li>去除空格、连字符、点、圆括号；</li>
 *   <li>带 {@code +} 国家码：保留并校验其余全为数字、总长 6—15（E.164 上限）；</li>
 *   <li>不带国家码的大陆手机号（11 位且 1 开头）补默认国家码 {@code +86}；</li>
 *   <li>其余无国家码形式一律不猜测国家码 → 视为无效（fail closed）；</li>
 *   <li>含字母/星号（如厂商脱敏值 {@code 178****3690}）→ 无效；脱敏值绝不参与匹配。</li>
 * </ul>
 * 输出形如 {@code +8617817683690}；无效输入返回 {@code null}，由调用方按
 * "缺可信手机号" fail closed，不得把无效值当作匹配依据。
 */
public final class SsoPhoneNormalizer {

    private SsoPhoneNormalizer() {
    }

    /**
     * 规范化手机号为 {@code +<国家码><号码>} 形态；无法唯一确定国家码或含非数字
     * 残留（含厂商脱敏星号）时返回 {@code null}。
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replaceAll("[\\s\\-().]", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.startsWith("+")) {
            String rest = cleaned.substring(1);
            if (!isAllDigits(rest) || rest.length() < 6 || rest.length() > 15) {
                return null;
            }
            // +86 只接受大陆手机号形态（国家码后 11 位且 1 开头）：缺位/多位的
            // "+86xxxx" 视为无效（脱敏/截断值绝不参与匹配）
            if (rest.startsWith("86")) {
                String national = rest.substring(2);
                if (national.length() == 11 && national.startsWith("1")) {
                    return "+" + rest;
                }
                return null;
            }
            return "+" + rest;
        }
        if (!isAllDigits(cleaned)) {
            return null;
        }
        // 大陆手机号国内写法：11 位且 1 开头 → 补默认国家码 +86
        if (cleaned.length() == 11 && cleaned.startsWith("1")) {
            return "+86" + cleaned;
        }
        // 其余无国家码形式不猜测国家码（fail closed），不按"截后 11 位"近似
        return null;
    }

    private static boolean isAllDigits(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
