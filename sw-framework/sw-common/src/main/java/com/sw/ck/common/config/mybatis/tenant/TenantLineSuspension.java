package com.sw.ck.common.config.mybatis.tenant;

/**
 * 租户行过滤的线程级挂起开关（认证身份装载专用）。
 * <p>
 * 仅限「登录前/认证上下文建立前」的身份装载链使用（如 UserDetailsProvider 按用户名或
 * 用户主键全局装载用户、角色、授权关系）：此时线程尚无租户上下文，租户行过滤会把
 * 非 0 租户账号挡在认证之外。装载完成后，业务请求一律按 {@code LoginUserHolder}
 * 中的租户正常过滤，本开关不得用于任何业务读写。
 * </p>
 */
public final class TenantLineSuspension {

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private TenantLineSuspension() {
    }

    public static boolean isSuspended() {
        return DEPTH.get() > 0;
    }

    public static void suspend() {
        DEPTH.set(DEPTH.get() + 1);
    }

    public static void restore() {
        int next = DEPTH.get() - 1;
        if (next <= 0) {
            DEPTH.remove();
        } else {
            DEPTH.set(next);
        }
    }

    /** 自动恢复的挂起句柄（try-with-resources 用）。 */
    public static final class Suspended implements AutoCloseable {

        private Suspended() {
        }

        @Override
        public void close() {
            restore();
        }
    }

    public static Suspended suspended() {
        suspend();
        return new Suspended();
    }
}
