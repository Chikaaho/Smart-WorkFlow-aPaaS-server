package com.sw.ck.bpm.api.node;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 发布节点允许操作的统一解析；未声明承接旧定义，显式空列表不允许任何操作。 */
public final class NodeActionPolicy {
    public static final List<String> ALL = List.of(
            "APPROVE", "DISAPPROVE", "REJECT", "RETURN",
            "TRANSFER", "DELEGATE", "COMMUNICATE", "ADD_SIGN");

    private NodeActionPolicy() { }

    public static Optional<List<String>> resolve(Map<String, Object> config) {
        if (config == null || !config.containsKey("allowedActions")) return Optional.of(ALL);
        Object raw = config.get("allowedActions");
        if (!(raw instanceof Collection<?> actions)
                || actions.stream().anyMatch(action -> !(action instanceof String)
                || !ALL.contains(action))) {
            throw new IllegalArgumentException("允许操作必须是有效操作名称的数组");
        }
        return Optional.of(actions.stream().map(String.class::cast).distinct().toList());
    }
}
