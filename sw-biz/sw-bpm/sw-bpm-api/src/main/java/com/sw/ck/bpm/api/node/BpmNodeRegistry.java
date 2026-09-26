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
 * <p>
 * 模块内部调用边界统一返回非空 {@link Optional}：registry 构造期已保证定义集非空，
 * 因此列表类结果恒 present；{@link #find(String)} 的 empty 是唯一"未注册"语义出口。
 * </p>
 */
public interface BpmNodeRegistry {

    /**
     * 按稳定类型排序的只读定义列表。
     *
     * @return present = 定义列表（构造期已校验非空）；当前契约恒 present
     */
    Optional<List<BpmNodeDefinition>> definitions();

    /**
     * 查询已注册节点。
     *
     * @return present = 已注册节点定义；empty = 该类型未注册
     */
    Optional<BpmNodeDefinition> find(String type);

    /**
     * 返回供流程设计端消费的完整能力清单。
     *
     * @return present = 能力清单（由已注册定义派生，数量与 {@link #definitions()} 一致）；
     *         当前契约恒 present
     */
    default Optional<List<BpmNodeCapabilityDTO>> capabilities() {
        return definitions().map(list -> list.stream().map(BpmNodeCapabilityDTO::from).toList());
    }

    /**
     * 统一配置校验入口，未知节点也必须在发布链中显式失败。
     *
     * @return present = 校验问题列表（通过校验时为空列表）；当前契约恒 present，
     *         未知节点以该列表中的明确错误码表达，不以上空表达
     */
    default Optional<List<GraphValidationError>> validateConfig(GraphElement node) {
        if (node == null || node.getType() == null || find(node.getType()).isEmpty()) {
            return Optional.of(List.of(GraphValidationError.builder()
                    .elementId(node == null ? null : node.getId())
                    .errorCode(BpmErrorCode.NODE_CAPABILITY_MISSING.getCode())
                    .message(BpmErrorCode.NODE_CAPABILITY_MISSING.getMessage())
                    .build()));
        }
        return find(node.getType())
                .flatMap(definition -> definition.validateConfig(node))
                .or(() -> Optional.of(List.of()));
    }
}
