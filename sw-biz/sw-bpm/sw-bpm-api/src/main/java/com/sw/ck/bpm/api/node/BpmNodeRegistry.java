package com.sw.ck.bpm.api.node;

import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.GraphValidationError;
import com.sw.ck.bpm.api.exception.BpmErrorCode;

import java.util.List;
import java.util.Optional;

/**
 * 当前应用可用节点的唯一注册结果。
 * <p>
 * 设计能力清单、图校验和引擎接缝都必须消费同一个实例；本接口不暴露 Flowable 类型。
 * </p>
 */
public interface BpmNodeRegistry {

    /** 按稳定类型排序的只读定义列表。 */
    List<BpmNodeDefinition> definitions();

    /** 查询已注册节点。 */
    Optional<BpmNodeDefinition> find(String type);

    /** 返回供流程设计端消费的完整能力清单。 */
    default List<BpmNodeCapabilityDTO> capabilities() {
        return definitions().stream().map(BpmNodeCapabilityDTO::from).toList();
    }

    /**
     * 统一配置校验入口，未知节点也必须在发布链中显式失败。
     */
    default List<GraphValidationError> validateConfig(GraphElement node) {
        if (node == null || node.getType() == null || find(node.getType()).isEmpty()) {
            return List.of(GraphValidationError.builder()
                    .elementId(node == null ? null : node.getId())
                    .errorCode(BpmErrorCode.NODE_CAPABILITY_MISSING.getCode())
                    .message(BpmErrorCode.NODE_CAPABILITY_MISSING.getMessage())
                    .build());
        }
        return find(node.getType()).orElseThrow().validateConfig(node);
    }
}
