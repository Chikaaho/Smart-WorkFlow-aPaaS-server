package com.sw.ck.bpm.api.node;

import java.util.List;
import java.util.Set;

/**
 * 节点的 system 级元数据，不依赖任何 BPM 引擎实现。
 *
 * @param displayName 显示名称
 * @param description 节点说明
 * @param category 节点类别
 * @param topology 入/出边约束
 * @param configFields 配置字段描述
 * @param contractVersion 契约版本
 * @param capabilities 节点能力集合
 * @param startNode 是否为流程入口节点
 * @param endNode 是否为流程出口节点
 * @param systemManaged 是否由系统管理
 * @param deletable 是否允许设计端删除
 */
public record BpmNodeMetadata(
        String displayName,
        String description,
        String category,
        BpmNodeTopology topology,
        List<BpmNodeConfigField> configFields,
        String contractVersion,
        Set<BpmNodeCapability> capabilities,
        boolean startNode,
        boolean endNode,
        boolean systemManaged,
        boolean deletable) {
}
