package com.sw.ck.bpm.api.variable;

import java.util.Optional;

/**
 * P64 BPM 变量读取端口（引擎 → 业务变量快照）。
 * <p>
 * 动态并行等引擎节点以流程变量（VARIABLE 来源）配置分支集合。P64 的 BPM 变量由业务侧
 * 按冻结图 + 有效轮次在读取时点解析（{@code BpmVariableSnapshotService}），并不预先物化
 * 为 Flowable 流程变量；本端口让引擎在流程变量缺省时回退到同一份业务变量快照，
 * 避免"设计器变量"与"流程变量"两套语义分叉。
 * </p>
 * <p>
 * 端口未接线（{@code getIfAvailable()} 为空）时引擎保持原语义：流程变量缺省 = 空集合，
 * 由节点 emptyStrategy 决定阻断或放行。
 * </p>
 */
public interface BpmVariableReadPort {

    /**
     * 读取业务变量在"当前有效轮次"的取值。
     *
     * @param processInstanceId 流程实例 ID（业务实例身份，非 Flowable execution）
     * @param varId             冻结图中的变量稳定引用 ID（如 var_handlers）
     * @return 变量值（集合类型为 ID 列表）；不可解析/未定义返回 {@code Optional.empty()}
     */
    Optional<Object> readVariable(String processInstanceId, String varId);
}
