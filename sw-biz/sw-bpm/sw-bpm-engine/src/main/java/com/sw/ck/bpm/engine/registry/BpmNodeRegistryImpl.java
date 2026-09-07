package com.sw.ck.bpm.engine.registry;

import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.node.BpmNodeCapability;
import com.sw.ck.bpm.api.node.BpmNodeConfigField;
import com.sw.ck.bpm.api.node.BpmNodeDefinition;
import com.sw.ck.bpm.api.node.BpmNodeMetadata;
import com.sw.ck.bpm.api.node.BpmNodeRegistry;
import com.sw.ck.bpm.api.node.BpmNodeTopology;
import com.sw.ck.bpm.engine.translator.NodeTypeTranslator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 引擎侧节点注册结果构造器。
 * <p>
 * 输入来自 Spring 自动发现的 {@link BpmNodeDefinition} beans；构造阶段完成完整性校验，
 * 通过后按稳定 type 排序并冻结。任何重复、非法或缺能力定义都阻止应用继续装配。
 * </p>
 */
public final class BpmNodeRegistryImpl implements BpmNodeRegistry {

    private static final Pattern TYPE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern CONFIG_NAME_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9_.]{0,63}");
    private static final Set<String> NODE_CATEGORIES = Set.of("EVENT", "TASK", "GATEWAY", "OTHER");
    private static final EnumSet<BpmNodeCapability> REQUIRED_CAPABILITIES = EnumSet.of(
            BpmNodeCapability.DESIGN,
            BpmNodeCapability.TRANSLATE,
            BpmNodeCapability.RUNTIME,
            BpmNodeCapability.CONFIG_VALIDATE);

    private final Map<String, BpmNodeDefinition> definitionsByType;

    public BpmNodeRegistryImpl(Collection<? extends BpmNodeDefinition> definitions) {
        if (definitions == null || definitions.stream().anyMatch(definition -> definition == null)) {
            throw invalid("节点定义集合为空项");
        }

        List<BpmNodeDefinition> ordered = definitions.stream()
                .map(definition -> (BpmNodeDefinition) definition)
                .sorted(Comparator
                        .comparing(BpmNodeRegistryImpl::typeForSort,
                                Comparator.nullsFirst(String::compareTo))
                        .thenComparing(definition -> definition.getClass().getName()))
                .toList();
        Map<String, BpmNodeDefinition> result = new TreeMap<>();
        for (BpmNodeDefinition definition : ordered) {
            validateDefinition(definition);
            if (result.putIfAbsent(definition.type(), definition) != null) {
                throw invalid("节点类型重复: " + definition.type());
            }
        }
        if (result.isEmpty()) {
            throw invalid("未发现任何节点定义");
        }
        this.definitionsByType = Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    @Override
    public List<BpmNodeDefinition> definitions() {
        return List.copyOf(definitionsByType.values());
    }

    @Override
    public Optional<BpmNodeDefinition> find(String type) {
        return Optional.ofNullable(definitionsByType.get(type));
    }

    private static String typeForSort(BpmNodeDefinition definition) {
        return definition == null ? null : definition.type();
    }

    private static void validateDefinition(BpmNodeDefinition definition) {
        String type = definition.type();
        if (type == null || !TYPE_PATTERN.matcher(type).matches()) {
            throw invalid("节点类型标识非法: " + type);
        }
        if (!(definition instanceof NodeTypeTranslator)) {
            throw invalid("节点缺少翻译能力实现: " + type);
        }

        BpmNodeMetadata metadata = definition.metadata();
        if (metadata == null) {
            throw invalid("节点元数据缺失: " + type);
        }
        requireText(metadata.displayName(), "显示名称", type);
        requireText(metadata.description(), "说明", type);
        requireText(metadata.category(), "类别", type);
        if (!NODE_CATEGORIES.contains(metadata.category())) {
            throw invalid("节点类别非法: " + type + "." + metadata.category());
        }
        requireText(metadata.contractVersion(), "契约版本", type);
        validateTopology(metadata.topology(), type);
        validateConfigFields(metadata.configFields(), type);

        Set<BpmNodeCapability> capabilities = metadata.capabilities();
        if (capabilities == null || !capabilities.containsAll(REQUIRED_CAPABILITIES)) {
            throw invalid("节点缺少必要能力: " + type + "，需要 " + REQUIRED_CAPABILITIES);
        }
        if (metadata.startNode() && metadata.endNode()) {
            throw invalid("节点不能同时作为 START 和 END: " + type);
        }
        if (metadata.systemManaged() && metadata.deletable()) {
            throw invalid("系统管理节点不能允许删除: " + type);
        }
    }

    private static void validateTopology(BpmNodeTopology topology, String type) {
        if (topology == null
                || topology.minIncoming() < 0
                || topology.minOutgoing() < 0
                || topology.maxIncoming() < topology.minIncoming()
                || topology.maxOutgoing() < topology.minOutgoing()) {
            throw invalid("节点拓扑约束非法: " + type);
        }
    }

    private static void validateConfigFields(List<BpmNodeConfigField> fields, String type) {
        if (fields == null) {
            throw invalid("节点配置描述缺失: " + type);
        }
        List<String> names = new ArrayList<>();
        for (BpmNodeConfigField field : fields) {
            if (field == null
                    || field.key() == null
                    || !CONFIG_NAME_PATTERN.matcher(field.key()).matches()
                    || field.label() == null
                    || field.label().isBlank()
                    || field.type() == null
                    || field.type().isBlank()
                    || field.validation() == null) {
                throw invalid("节点配置字段描述非法: " + type);
            }
            if (!names.add(field.key())) {
                throw invalid("节点配置字段重复: " + type + "." + field.key());
            }
        }
    }

    private static void requireText(String value, String field, String type) {
        if (value == null || value.isBlank()) {
            throw invalid("节点" + field + "缺失: " + type);
        }
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException(BpmErrorCode.NODE_REGISTRATION_INVALID.getMessage() + ": " + message);
    }
}
