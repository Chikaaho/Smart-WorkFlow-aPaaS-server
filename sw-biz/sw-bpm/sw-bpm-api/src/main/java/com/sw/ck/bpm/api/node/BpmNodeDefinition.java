package com.sw.ck.bpm.api.node;

import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.GraphValidationError;

import java.util.List;

/**
 * 一种可用流程节点的 system 级定义。
 * <p>
 * 该契约只表达稳定产品语义；Flowable 翻译接口由 engine 内部扩展，不能反向污染本接口。
 * </p>
 */
public interface BpmNodeDefinition {

    /** 稳定、唯一、对外持久化的节点类型标识。 */
    String type();

    /** 节点元数据、拓扑和配置描述。 */
    default BpmNodeMetadata metadata() {
        // 允许旧的引擎测试翻译器先编译；统一注册时会将 null 视为非法契约并 fail-fast。
        return null;
    }

    /**
     * 校验节点配置。草稿可以保留不完整配置，但发布必须消费该结果。
     */
    default List<GraphValidationError> validateConfig(GraphElement node) {
        return List.of();
    }
}
