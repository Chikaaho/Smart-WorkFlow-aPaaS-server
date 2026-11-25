package com.sw.ck.bpm.engine.translator;

import com.sw.ck.agent.api.port.AgentGraphInvokePort;
import com.sw.ck.agent.api.port.AgentGraphVersionBinding;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.GraphValidationError;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.node.BpmNodeCapability;
import com.sw.ck.bpm.api.node.BpmNodeConfigField;
import com.sw.ck.bpm.api.node.BpmNodeMetadata;
import com.sw.ck.bpm.api.node.BpmNodeTopology;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Graph 执行器服务节点（1.2.0 P67 / E1-1、E1-2、E1-5、E1-8）。
 *
 * <p>服务节点的一种执行器（{@link ServiceTaskNodeTranslator} 子类，不新增 BPMN 节点类别）：
 * 绑定本租户<b>已发布</b> Graph 的<b>固定版本</b>（defVersion 快照），入参映射
 * {@code inputMappings:[{name:图入参, source:流程变量}]}，出参映射
 * {@code outputMappings:[{name:流程变量, source:图出参}]}（与 agent 子图节点同构）。</p>
 *
 * <p>发布期校验（validateConfig，在发布者租户内执行，跨租户按不存在拒绝）：
 * 图/版本存在且已发布、含人工节点的图拒绝（E1-5 裁定前口径）、必填入参都有映射、
 * 出参映射的目标变量与图出参声明合法、节点超时不超过图自身执行超时。</p>
 */
@Component
public class GraphExecNodeTranslator extends ServiceTaskNodeTranslator {

    @Autowired(required = false)
    private ObjectProvider<AgentGraphInvokePort> graphInvokePortProvider;

    @Override
    public Optional<String> type() {
        return Optional.of("GRAPH_EXEC");
    }

    @Override
    protected String delegateBean() {
        return "graphExecNodeDelegate";
    }

    @Override
    protected String nodeName() {
        return "Graph 执行器";
    }

    @Override
    public Optional<BpmNodeMetadata> metadata() {
        return Optional.of(new BpmNodeMetadata(nodeName(), nodeName() + "节点", "TASK",
                new BpmNodeTopology(1, 1, 1, 1),
                List.of(new BpmNodeConfigField("name", "节点名称", "string", false, Map.of()),
                        new BpmNodeConfigField("graphDefId", "绑定 Graph", "string", true, Map.of()),
                        new BpmNodeConfigField("graphDefVersion", "绑定版本", "string", true, Map.of()),
                        new BpmNodeConfigField("inputMappings", "入参映射", "object", false, Map.of()),
                        new BpmNodeConfigField("outputMappings", "出参映射", "object", false, Map.of()),
                        new BpmNodeConfigField("timeoutSeconds", "超时（秒）", "string", false, Map.of()),
                        new BpmNodeConfigField("failureStrategy", "失败策略", "string", false,
                                Map.of("values", List.of("BLOCK", "CONTINUE")))),
                "1", EnumSet.of(BpmNodeCapability.DESIGN, BpmNodeCapability.TRANSLATE,
                        BpmNodeCapability.RUNTIME, BpmNodeCapability.CONFIG_VALIDATE),
                false, false, false, true));
    }

    @Override
    public Optional<List<GraphValidationError>> validateConfig(GraphElement node) {
        List<GraphValidationError> errors = new ArrayList<>();
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Long graphDefId = positiveLong(config.get("graphDefId"));
        Integer defVersion = positiveInt(config.get("graphDefVersion"));
        if (graphDefId == null || defVersion == null) {
            return Optional.of(List.of(error(node,
                    "Graph 执行器节点必须绑定已发布 Graph 与固定版本（graphDefId/graphDefVersion）")));
        }
        String strategy = text(config.get("failureStrategy"));
        if (strategy != null && !List.of("BLOCK", "CONTINUE").contains(strategy.toUpperCase())) {
            errors.add(error(node, "失败策略不合法: " + strategy));
        }
        Set<String> mappedInputs = mappingNames(config.get("inputMappings"), "name");
        Set<String> mappedOutputs = mappingNames(config.get("outputMappings"), "source");

        AgentGraphInvokePort port = graphInvokePortProvider.getIfAvailable();
        if (port == null) {
            // 隔离引擎上下文未装配智能体模块：Graph 执行器 fail closed（无该节点的图不受影响）
            return Optional.of(List.of(error(node, "智能体模块未装配，Graph 执行器节点不可用")));
        }
        Optional<AgentGraphVersionBinding> binding = port.describeBinding(graphDefId, defVersion);
        if (binding.isEmpty()) {
            // 跨租户/不存在/未发布/版本无快照统一按不可绑定（E1-8 发布期拒绝）
            return Optional.of(List.of(error(node,
                    "绑定的 Graph 不存在、跨租户不可达或版本未发布: #" + graphDefId + " v" + defVersion)));
        }
        AgentGraphVersionBinding bound = binding.get();
        if (bound.containsHumanNode()) {
            // E1-5（裁定前口径）：同步调用不支持人工等待，发布期直接拒绝并写明原因
            errors.add(error(node, "节点[" + nodeDisplayName(node) + "] 绑定的 Graph v" + defVersion + " 含人工节点，不能作为同步执行器"));
        }
        List<String> missingInputs = new ArrayList<>();
        for (AgentGraphVersionBinding.ParamDecl param : bound.startParams()) {
            if (param.required() && !mappedInputs.contains(param.name())) {
                missingInputs.add(param.name());
            }
        }
        if (!missingInputs.isEmpty()) {
            errors.add(error(node, "节点[" + nodeDisplayName(node) + "] 必填入参缺少映射: " + String.join(", ", missingInputs)));
        }
        Set<String> declaredOutputs = new HashSet<>();
        bound.endParams().forEach(decl -> declaredOutputs.add(decl.name()));
        List<String> badOutputs = new ArrayList<>();
        for (String source : mappedOutputs) {
            if (!declaredOutputs.contains(source)) {
                badOutputs.add(source);
            }
        }
        if (!badOutputs.isEmpty()) {
            errors.add(error(node, "节点[" + nodeDisplayName(node) + "] 出参映射引用了图未声明的输出: " + String.join(", ", badOutputs)));
        }
        Set<String> outputTargets = mappingNames(config.get("outputMappings"), "name");
        if (outputTargets.contains("") || mappingNames(config.get("inputMappings"), "source").contains("")) {
            errors.add(error(node, "入参/出参映射的变量名不能为空"));
        }
        Integer timeout = positiveInt(config.get("timeoutSeconds"));
        if (timeout != null && bound.executionTimeoutSeconds() != null
                && timeout > bound.executionTimeoutSeconds()) {
            errors.add(error(node, "节点[" + nodeDisplayName(node) + "] 超时 " + timeout + " 秒超过图自身执行超时上限 "
                    + bound.executionTimeoutSeconds() + " 秒"));
        }
        return Optional.of(errors);
    }

    /** 取映射列表某键的非空取值集合（inputMappings[name/source]、outputMappings[name/source] 同构）。 */
    private Set<String> mappingNames(Object raw, String key) {
        Set<String> names = new HashSet<>();
        if (raw instanceof List<?> mappings) {
            for (Object item : mappings) {
                if (item instanceof Map<?, ?> mapping) {
                    Object value = mapping.get(key);
                    if (value != null) {
                        String text = String.valueOf(value).trim();
                        if (!text.isEmpty()) {
                            names.add(text);
                        }
                    }
                }
            }
        }
        return names;
    }

    private Long positiveLong(Object value) {
        if (value == null) return null;
        try {
            long parsed = Long.parseLong(String.valueOf(value).trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer positiveInt(Object value) {
        if (value == null) return null;
        try {
            int parsed = Integer.parseInt(String.valueOf(value).trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 节点显示名（config.name 缺省用 elementId）。 */
    private String nodeDisplayName(GraphElement node) {
        if (node.getConfig() != null && node.getConfig().get("name") instanceof String name && !name.isBlank()) {
            return name;
        }
        return node.getId();
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private GraphValidationError error(GraphElement node, String message) {
        return GraphValidationError.builder().elementId(node.getId())
                .errorCode(BpmErrorCode.NODE_CONFIG_INVALID.getCode()).message(message).build();
    }
}
