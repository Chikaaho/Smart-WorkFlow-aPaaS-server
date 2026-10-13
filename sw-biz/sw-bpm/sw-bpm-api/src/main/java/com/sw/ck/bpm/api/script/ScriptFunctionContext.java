package com.sw.ck.bpm.api.script;

import java.util.Map;

/**
 * BF20261013-021：脚本宿主函数上下文（组织目录 + 实例变量快照）。
 * <p>
 * 判断脚本在隔离 worker JVM 内执行、无 DB/宿主访问，因此组织与实例变量数据由宿主
 * 侧一次性解析后随执行请求下发（预取快照，沿用“只读快照进沙箱”的既有边界）：
 * <ul>
 *   <li>{@code deptParentMap}：部门 ID → 上级部门 ID（缺省/根为 0，不在表中 = 未知部门）；</li>
 *   <li>{@code deptLeaderMap}：部门 ID → 有效负责人用户 ID（负责人启用且同租户才登记）；</li>
 *   <li>{@code instanceVariables}：流程实例变量（脚本内 获取流程变量 的覆盖层，优先级低于
 *       本次脚本的 设置流程变量 写入）。</li>
 * </ul>
 * 无组织/变量函数引用时宿主以 {@link #EMPTY} 下发，零额外查询。
 * </p>
 */
public record ScriptFunctionContext(
        Map<Long, Long> deptParentMap,
        Map<Long, Long> deptLeaderMap,
        Map<String, Object> instanceVariables) {

    public static final ScriptFunctionContext EMPTY =
            new ScriptFunctionContext(Map.of(), Map.of(), Map.of());

    public ScriptFunctionContext {
        deptParentMap = deptParentMap == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(deptParentMap));
        deptLeaderMap = deptLeaderMap == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(deptLeaderMap));
        instanceVariables = instanceVariables == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(instanceVariables));
    }

    /** 是否无任何函数数据（宿主可跳过组织目录构建）。 */
    public boolean isEmpty() {
        return deptParentMap.isEmpty() && deptLeaderMap.isEmpty() && instanceVariables.isEmpty();
    }
}
