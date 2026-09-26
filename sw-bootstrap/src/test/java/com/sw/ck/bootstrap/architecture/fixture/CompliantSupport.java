package com.sw.ck.bootstrap.architecture.fixture;

import java.util.Optional;

/** 合规夹具：公开类的 public static 方法返回参数化 Optional<T>。 */
public final class CompliantSupport {

    private CompliantSupport() {
    }

    public static Optional<String> value(String expression) {
        return Optional.ofNullable(expression);
    }

    static String helper() {
        return "非 public static，不属于跨模块契约，不纳入守门";
    }
}
