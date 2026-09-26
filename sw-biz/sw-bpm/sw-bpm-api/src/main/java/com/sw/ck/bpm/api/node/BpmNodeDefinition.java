package com.sw.ck.bpm.api.node;

import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.GraphValidationError;

import java.util.List;
import java.util.Optional;

/**
 * 一种可用流程节点的 system 级定义。
 * <p>
 * 该契约只表达稳定产品语义；Flowable 翻译接口由 engine 内部扩展，不能反向污染本接口。
 * </p>
 * <p>
 * 模块内部调用边界统一返回非空 {@link Optional}：empty 只表达"该节点定义未提供该信息"。
 * </p>
 */
public interface BpmNodeDefinition {

    /** 稳定、唯一、对外持久化的节点类型标识。 */
    Optional<String> type();

    /**
     * 节点元数据、拓扑和配置描述。
     *
     * @return present = 元数据；empty = 该定义未提供元数据（旧翻译器兼容路径），
     *         统一注册时视为非法契约并 fail-fast
     */
    default Optional<BpmNodeMetadata> metadata() {
        // 允许旧的引擎测试翻译器先编译；统一注册时会将未提供元数据视为非法契约并 fail-fast。
        return Optional.empty();
    }

    /**
     * 校验节点配置。草稿可以保留不完整配置，但发布必须消费该结果。
     *
     * @return present = 校验问题列表（通过校验时为空列表）；当前契约恒 present
     */
    default Optional<List<GraphValidationError>> validateConfig(GraphElement node) {
        return Optional.of(List.of());
    }
}
