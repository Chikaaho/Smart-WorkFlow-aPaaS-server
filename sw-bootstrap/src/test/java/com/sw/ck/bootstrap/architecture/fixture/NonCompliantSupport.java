package com.sw.ck.bootstrap.architecture.fixture;

/** 违规夹具：公开类的 public static 方法返回非 Optional。 */
public final class NonCompliantSupport {

    private NonCompliantSupport() {
    }

    public static String value(String expression) {
        return expression;
    }
}
