package com.sw.ck.storage.api;

/**
 * 文件存储变更类操作的结果（跨模块 API 结果类型）。
 * <p>
 * 供 {@link StorageFacade#delete(String)} 表达"已执行"与"合法幂等"的区别：
 * 目标缺失是幂等成功的一种，不得伪装成 empty 或异常。
 * </p>
 */
public enum StorageMutationOutcome {

    /** 本次调用产生了预期变更（存储记录与提供商侧均已处置）。 */
    APPLIED,

    /** 目标已处于期望状态（记录本就不存在或已逻辑删除），合法幂等，不是失败。 */
    ALREADY_APPLIED
}
