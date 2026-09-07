package com.sw.ck.bpm.api.node;

import java.util.List;
import java.util.Set;

/**
 * 节点能力清单项，作为流程设计权限用户的后端消费契约。
 */
public record BpmNodeCapabilityDTO(
        String type,
        String displayName,
        String description,
        String category,
        BpmNodeTopology topology,
        List<BpmNodeConfigField> configFields,
        String version,
        BpmNodeSupports supports,
        boolean startNode,
        boolean endNode,
        boolean systemManaged,
        boolean deletable) {

    public static BpmNodeCapabilityDTO from(BpmNodeDefinition definition) {
        BpmNodeMetadata metadata = definition.metadata();
        Set<BpmNodeCapability> capabilities = metadata.capabilities();
        return new BpmNodeCapabilityDTO(
                definition.type(),
                metadata.displayName(),
                metadata.description(),
                metadata.category(),
                metadata.topology(),
                metadata.configFields(),
                metadata.contractVersion(),
                new BpmNodeSupports(
                        capabilities.contains(BpmNodeCapability.DESIGN),
                        capabilities.contains(BpmNodeCapability.DESIGN),
                        capabilities.contains(BpmNodeCapability.TRANSLATE)
                                && capabilities.contains(BpmNodeCapability.CONFIG_VALIDATE),
                        capabilities.contains(BpmNodeCapability.RUNTIME)),
                metadata.startNode(),
                metadata.endNode(),
                metadata.systemManaged(),
                metadata.deletable());
    }
}
