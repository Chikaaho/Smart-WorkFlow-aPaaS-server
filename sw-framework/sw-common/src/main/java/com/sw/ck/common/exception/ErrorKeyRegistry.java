package com.sw.ck.common.exception;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 数值码 → 全局唯一 errorKey 的运行期注册表（P61）。
 *
 * <p>背景：抛异常的路径经 {@code GlobalExceptionHandler} 携带 errorKey/eventRef；
 * 而直接 {@code R.fail(code, msg)} 返回的业务失败此前两者皆无，客户端只能靠数值码分流——
 * 而数值码存在已登记的跨模块重复，无法作为权威标识。</p>
 *
 * <p>注册在启动期由聚合模块（bootstrap）完成，注册源与 error-code-catalog 的枚举集合一致。
 * 对已登记的**数值冲突**码不猜测含义：注册表返回 {@code null}，宁可无键也不给错键。</p>
 */
public final class ErrorKeyRegistry {

    /** 数值码 → errorKey（首个注册者胜出；冲突码记入 ambiguous 并返回 null）。 */
    private static final Map<Integer, String> BY_CODE = new LinkedHashMap<>();
    private static final Set<Integer> AMBIGUOUS = new LinkedHashSet<>();

    private ErrorKeyRegistry() {
    }

    /** 注册一批错误码枚举；重复注册同一码且 errorKey 不同则标记为冲突。 */
    public static synchronized void register(ErrorCode... codes) {
        if (codes == null) {
            return;
        }
        for (ErrorCode code : codes) {
            if (code == null) {
                continue;
            }
            int numeric = code.getCode();
            String key = code.getErrorKey();
            String existing = BY_CODE.get(numeric);
            if (existing == null) {
                BY_CODE.put(numeric, key);
            } else if (!existing.equals(key)) {
                AMBIGUOUS.add(numeric);
            }
        }
    }

    /** 按数值码取 errorKey；未登记或存在数值冲突时返回 {@code null}。 */
    public static synchronized String lookup(int code) {
        if (AMBIGUOUS.contains(code)) {
            return null;
        }
        return BY_CODE.get(code);
    }

    /** 已登记的冲突数值码（供诊断与测试断言使用）。 */
    public static synchronized Set<Integer> ambiguousCodes() {
        return new LinkedHashSet<>(AMBIGUOUS);
    }

    /** 已登记的数值码数量。 */
    public static synchronized int size() {
        return BY_CODE.size();
    }
}
