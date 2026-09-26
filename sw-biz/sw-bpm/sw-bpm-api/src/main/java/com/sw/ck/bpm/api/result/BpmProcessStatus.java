package com.sw.ck.bpm.api.result;

/**
 * 流程实例对外状态（I4 §3.4 外部状态查询口径）。
 * <p>
 * 取代原先 {RUNNING/.../"NOT_FOUND"/"UNKNOWN"} 的字符串哨兵：
 * "实例不存在"由 {@code Optional.empty()} 表达，本类型只承载合法状态值。
 * </p>
 */
public enum BpmProcessStatus {

    /** 运行中：存在运行期实例。 */
    RUNNING,

    /** 正常走完（无删除原因）。 */
    APPROVED,

    /** 驳回终止。 */
    REJECTED,

    /** 已撤回。 */
    WITHDRAWN,

    /** 已作废/丢弃。 */
    DISCARDED,

    /** 执行失败终止。 */
    FAILED,

    /** 其他删除原因终止，或状态查询异常时的保守回退值。 */
    TERMINATED,

    /** 终态原因无法归类时的保守回退值（不冒充任何明确终态）。 */
    UNKNOWN
}
