package com.sw.ck.job.handler;

/**
 * 定时任务处理器执行结果（跨模块 API 结果类型）。
 * <p>
 * 供 {@link JobHandler#execute(String)} 表达"已执行"与"合法零变更"的区别；
 * 执行失败继续以异常表达，不得以上空或本类型常量伪装失败。
 * </p>
 */
public enum JobExecutionOutcome {

    /** 本次执行产生了预期业务效果。 */
    EXECUTED,

    /** 执行已合法完成但无变更（无待处理对象、幂等重复触发），不是失败。 */
    NO_CHANGE
}
