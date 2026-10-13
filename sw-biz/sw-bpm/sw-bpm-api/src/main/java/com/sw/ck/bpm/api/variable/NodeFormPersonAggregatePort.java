package com.sw.ck.bpm.api.variable;

import java.util.List;
import java.util.Optional;

/**
 * P64 阶段Ⅱ（A07 聚合会签）节点表单人员字段读取端口。
 * <p>
 * engine 侧参与人解析器经本端口读取指定节点指定轮次全部有效最终提交的节点表单人员字段值；
 * 实现位于 sw-bpm-process（节点表单数据权威在业务编排域），未接线时保持原语义（解析为空）。
 * 人员值形态由实现解析为稳定用户 ID 字符串（单值或多值展平），去重由调用方按身份并集完成。
 * </p>
 */
public interface NodeFormPersonAggregatePort {

    /**
     * 读取人员字段值。
     *
     * @param tenantId          租户 ID
     * @param processInstanceId 流程实例 ID
     * @param nodeKey           来源节点 key
     * @param formField         节点表单人员字段名
     * @param roundOffset       轮次偏移：0 = 当前轮，1 = 上一轮（负数/更大偏移由实现按无数据处理）
     * @return present = 人员 ID 值列表（无有效提交时为空列表，属合法零匹配）；
     *         empty = 上下文缺失（实例/租户/字段不可解析）
     */
    Optional<List<String>> readPersonFieldValues(Long tenantId, String processInstanceId,
                                                 String nodeKey, String formField, int roundOffset);
}
