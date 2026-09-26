package com.sw.ck.bpm.engine.datasource;

/**
 * 外部数据源目标不可用（未登记或已停用）。
 * <p>
 * 继承 {@link IllegalArgumentException}，保持既有以 IAE 判定"非法数据源"的调用方兼容；
 * 与 SQL 非法（{@code IllegalArgumentException}）、执行失败（{@code RuntimeException}）
 * 可区分表达，使 {@code ExtDatasourceQueryPort} 适配实现能只将"目标缺失"转为
 * {@code Optional.empty()}，其余异常照抛。
 * </p>
 */
public class ExternalDatasourceUnavailableException extends IllegalArgumentException {

    public ExternalDatasourceUnavailableException(String message) {
        super(message);
    }
}
