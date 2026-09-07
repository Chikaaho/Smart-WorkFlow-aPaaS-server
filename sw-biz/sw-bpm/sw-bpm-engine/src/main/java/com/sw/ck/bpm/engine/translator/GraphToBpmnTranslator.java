package com.sw.ck.bpm.engine.translator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.node.BpmNodeDefinition;
import com.sw.ck.bpm.api.node.BpmNodeRegistry;
import com.sw.ck.common.exception.BaseException;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowableListener;
import org.flowable.bpmn.model.ImplementationType;
import org.flowable.bpmn.model.SequenceFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Comparator;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.flowable.bpmn.model.ExclusiveGateway;

/**
 * 流程图画布模型 → BPMN 2.0 模型翻译器。
 * <p>
 * 纯函数：同一 {@link ProcessGraph} 输入产出确定 {@link BpmnModel} 输出，
 * 可对 BpmnModel 结构断言，不需启动 Flowable 引擎。
 * </p>
 *
 * <h3>节点翻译注册化（M04-F08-01 B2）</h3>
 * 节点类型 → Flowable 元素的翻译从 switch 硬编码改为按 {@link NodeTypeTranslator}
 * 注册表 Map 分发（仿 {@code approverResolverMap} 的 {@code Map<String, ...>} 先例）：
 * <ul>
 *   <li>默认注册 START / END / APPROVAL 三个内置翻译器（行为与拆分前逐字节一致）</li>
 *   <li>新增节点类型 = 实现 {@link NodeTypeTranslator} 并经构造器注册，
     *       本翻译器零改动（可插拔性证明见测试）</li>
     *   <li>未注册翻译器的类型在发布翻译时确定性失败，不再 warn + skip 生成缺节点 BPMN</li>
 * </ul>
 *
 * <h3>映射规则</h3>
 * <ul>
 *   <li>START → {@link org.flowable.bpmn.model.StartEvent}（见 {@link StartEventTranslator}）</li>
 *   <li>END → {@link org.flowable.bpmn.model.EndEvent}（见 {@link EndEventTranslator}）</li>
 *   <li>APPROVAL → {@link org.flowable.bpmn.model.UserTask}（assignee 不写死，
 *       携 nodeKey + approver 扩展属性，见 {@link ApprovalUserTaskTranslator}）</li>
 *   <li>顺序边 → {@link SequenceFlow}</li>
 * </ul>
 */
public class GraphToBpmnTranslator {

    private static final Logger log = LoggerFactory.getLogger(GraphToBpmnTranslator.class);

    private static final String KIND_NODE = "node";
    private static final String KIND_EDGE = "edge";

    private final ObjectMapper objectMapper;

    /** 节点类型 → 翻译器注册表（按 type 字符串 key 分发，替代 switch） */
    private final Map<String, NodeTypeTranslator> translatorMap;

    public GraphToBpmnTranslator() {
        this(new ObjectMapper());
    }

    public GraphToBpmnTranslator(ObjectMapper objectMapper) {
        this(objectMapper, List.of());
    }

    /**
     * 可插拔构造器：默认内置翻译器 + 插件翻译器合并注册。
     * <p>
     * 新增节点类型仅需在此注册一个 {@link NodeTypeTranslator} 实现，
     * 翻译与分发逻辑（{@link #translate(ProcessGraph)}）零改动。
     * 插件与内置类型冲突时直接拒绝重复注册。
     * </p>
     *
     * @param pluginTranslators 插件翻译器列表（可为空）
     */
    public GraphToBpmnTranslator(ObjectMapper objectMapper,
                                 List<NodeTypeTranslator> pluginTranslators) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper must not be null");
        this.translatorMap = new LinkedHashMap<>();
        register(new StartEventTranslator());
        register(new EndEventTranslator());
        register(new ApprovalUserTaskTranslator(objectMapper));
        // P58 节点由生产构造器从 BpmNodeRegistry 注入；此兼容构造器只保留
        // P57 以前的 START/END/APPROVAL 集合，避免旧调用方绕过统一注册结果。
        if (pluginTranslators != null) {
            for (NodeTypeTranslator plugin : pluginTranslators) {
                register(plugin);
            }
        }
        log.debug("GraphToBpmnTranslator initialized with {} node type translators",
                translatorMap.size());
    }

    /**
     * 生产发布路径使用的构造器：翻译器只消费统一节点注册结果，不再自行维护内置类型表。
     */
    public GraphToBpmnTranslator(ObjectMapper objectMapper, BpmNodeRegistry nodeRegistry) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper must not be null");
        Objects.requireNonNull(nodeRegistry, "BpmNodeRegistry must not be null");
        this.translatorMap = new LinkedHashMap<>();
        for (BpmNodeDefinition definition : nodeRegistry.definitions()) {
            if (!(definition instanceof NodeTypeTranslator translator)) {
                throw new IllegalStateException("节点缺少引擎翻译能力: " + definition.type());
            }
            register(translator);
        }
        if (translatorMap.isEmpty()) {
            throw new IllegalStateException("节点注册结果为空，无法创建 BPMN 翻译器");
        }
        log.debug("GraphToBpmnTranslator initialized from BpmNodeRegistry with {} translators",
                translatorMap.size());
    }

    private void register(NodeTypeTranslator translator) {
        if (translator == null || translator.type() == null || translator.type().isBlank()) {
            throw new IllegalStateException("节点翻译器类型标识为空");
        }
        NodeTypeTranslator previous = translatorMap.putIfAbsent(translator.type(), translator);
        if (previous != null) {
            throw new IllegalStateException("节点翻译器类型重复: " + translator.type());
        }
    }

    /**
     * 将 {@link ProcessGraph} 翻译为 {@link BpmnModel}。
     *
     * @param graph 流程设计器图模型（非空，已通过拓扑校验）
     * @return 标准 BPMN 2.0 模型
     * @throws BaseException 翻译失败时抛出 TRANSLATION_FAILED（2102）
     */
    public BpmnModel translate(ProcessGraph graph) {
        Objects.requireNonNull(graph, "ProcessGraph must not be null");
        try {
            return doTranslate(graph);
        } catch (BaseException e) {
            // 保留节点能力/配置等确定性业务错误码，避免被统一翻译失败码覆盖。
            throw e;
        } catch (Exception e) {
            log.error("BPMN translation failed: {}", e.getMessage(), e);
            throw new BaseException(BpmErrorCode.TRANSLATION_FAILED.getCode(),
                    "图翻译为 BPMN 失败: " + e.getMessage());
        }
    }

    private BpmnModel doTranslate(ProcessGraph graph) {
        List<GraphElement> elements = graph.getElements();
        if (elements == null || elements.isEmpty()) {
            throw new BaseException(BpmErrorCode.TRANSLATION_FAILED);
        }

        // 分离节点与边
        List<GraphElement> nodes = elements.stream()
                .filter(e -> KIND_NODE.equals(e.getKind()))
                .toList();
        List<GraphElement> edges = elements.stream()
                .filter(e -> KIND_EDGE.equals(e.getKind()))
                .toList();

        // 构建 BPMN Process（用全限定名解决 java.lang.Process 冲突）
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId(graph.getProcessKey());
        process.setName(graph.getName() != null ? graph.getName() : graph.getProcessKey());
        process.setExecutable(true);

        // 缓存节点 ID → BPMN FlowElement
        Map<String, FlowElement> flowElementMap = new LinkedHashMap<>();

        // 1. 翻译所有节点
        for (GraphElement node : nodes) {
            FlowElement flowElement = translateNode(node);
            flowElementMap.put(node.getId(), flowElement);
            process.addFlowElement(flowElement);
        }

        // 2. 翻译所有边。排他网关必须先按 priority 求值，DEFAULT 最后求值；否则
        // 设计器提交顺序可能让 DEFAULT 抢先命中，造成条件与轨迹不一致。
        List<GraphElement> orderedEdges = edges.stream()
                .sorted(Comparator.comparingInt(this::edgeEvaluationOrder))
                .toList();
        for (GraphElement edge : orderedEdges) {
            SequenceFlow sequenceFlow = translateEdge(edge, flowElementMap);
            if (sequenceFlow != null) {
                process.addFlowElement(sequenceFlow);
            }
        }

        // 3. 构建 BpmnModel
        BpmnModel bpmnModel = new BpmnModel();
        bpmnModel.addProcess(process);

        // 4. 生成 DI（图形交换信息）：图模型不携带坐标，按节点出现顺序做水平链式自动布局。
        //    缺失 DI 时 bpmn-js 会渲染空白画布（流程图不可见）。
        applyDiagramInterchange(bpmnModel, process, nodes, flowElementMap);

        return bpmnModel;
    }

    /** 布局常量：水平间距 / 中心线纵坐标 / 各类型节点尺寸 */
    private static final int DI_NODE_GAP = 170;
    private static final int DI_CENTER_Y = 220;
    private static final int DI_EVENT_SIZE = 30;
    private static final int DI_TASK_WIDTH = 100;
    private static final int DI_TASK_HEIGHT = 80;
    private static final int DI_START_X = 150;

    /**
     * 为节点与顺序边写入 DI 坐标。
     * <p>
     * 节点按出现顺序水平排布（事件 30×30 圆，任务 100×80 矩形，同一中心线）；
     * 边取源节点右中点到目标节点左中点的直线航点。
     * </p>
     */
    private void applyDiagramInterchange(BpmnModel bpmnModel,
                                         org.flowable.bpmn.model.Process process,
                                         List<GraphElement> nodes,
                                         Map<String, FlowElement> flowElementMap) {
        // 节点槽位（x, y, width, height）：按出现顺序水平排布，同一中心线
        Map<String, int[]> boxByNodeId = new LinkedHashMap<>();
        int slot = 0;
        for (GraphElement node : nodes) {
            FlowElement element = flowElementMap.get(node.getId());
            if (element == null) {
                continue;
            }
            boolean isEvent = !(element instanceof org.flowable.bpmn.model.UserTask);
            int width = isEvent ? DI_EVENT_SIZE : DI_TASK_WIDTH;
            int height = isEvent ? DI_EVENT_SIZE : DI_TASK_HEIGHT;
            int x = DI_START_X + slot * DI_NODE_GAP + (DI_TASK_WIDTH - width) / 2;
            int y = DI_CENTER_Y - height / 2;
            boxByNodeId.put(node.getId(), new int[] {x, y, width, height});

            org.flowable.bpmn.model.GraphicInfo gi = new org.flowable.bpmn.model.GraphicInfo();
            gi.setX(x);
            gi.setY(y);
            gi.setWidth(width);
            gi.setHeight(height);
            bpmnModel.addGraphicInfo(element.getId(), gi);
            slot++;
        }

        // 边航点：源右中 → 目标左中。顺序边不进 flowElementMap，须从 Process 取。
        for (FlowElement element : process.findFlowElementsOfType(SequenceFlow.class)) {
            SequenceFlow flow = (SequenceFlow) element;
            int[] src = boxByNodeId.get(flow.getSourceRef());
            int[] tgt = boxByNodeId.get(flow.getTargetRef());
            if (src == null || tgt == null) {
                continue;
            }
            org.flowable.bpmn.model.GraphicInfo from = new org.flowable.bpmn.model.GraphicInfo();
            from.setX(src[0] + src[2]);
            from.setY(src[1] + src[3] / 2.0);
            org.flowable.bpmn.model.GraphicInfo to = new org.flowable.bpmn.model.GraphicInfo();
            to.setX(tgt[0]);
            to.setY(tgt[1] + tgt[3] / 2.0);
            bpmnModel.addFlowGraphicInfoList(flow.getId(), List.of(from, to));
        }
    }

    /**
     * 翻译单个节点 —— 按 type 从统一注册结果分发；不存在时确定性失败。
     */
    private FlowElement translateNode(GraphElement node) {
        String type = node.getType();
        if (type == null) {
            throw new BaseException(BpmErrorCode.NODE_CAPABILITY_MISSING.getCode(),
                    "节点类型缺失: " + node.getId());
        }

        NodeTypeTranslator translator = translatorMap.get(type);
        if (translator == null) {
            throw new BaseException(BpmErrorCode.NODE_CAPABILITY_MISSING.getCode(),
                    "节点类型未注册或缺少翻译能力: " + type);
        }
        FlowElement translated = translator.translate(node);
        if (translated == null) {
            throw new BaseException(BpmErrorCode.NODE_CAPABILITY_MISSING.getCode(),
                    "节点翻译能力未返回 BPMN 元素: " + type);
        }
        return translated;
    }

    /**
     * 翻译顺序边。
     */
    private SequenceFlow translateEdge(GraphElement edge,
                                       Map<String, FlowElement> flowElementMap) {
        String source = edge.getSource();
        String target = edge.getTarget();

        if (source == null || target == null) {
            log.warn("Skipping edge with null source/target: id={}", edge.getId());
            return null;
        }

        SequenceFlow sequenceFlow = new SequenceFlow(source, target);
        sequenceFlow.setId(edge.getId());
        FlowElement sourceElement = flowElementMap.get(source);
        if (sourceElement instanceof ExclusiveGateway gateway) {
            Map<String, Object> config = edge.getConfig() == null ? Map.of() : edge.getConfig();
            boolean isDefault = Boolean.TRUE.equals(config.get("default"))
                    || Boolean.TRUE.equals(config.get("isDefault"));
            String branchId = config.get("branchId") == null ? edge.getId()
                    : String.valueOf(config.get("branchId"));
            sequenceFlow.setName(branchId);
            String priority = config.get("priority") == null ? ""
                    : String.valueOf(config.get("priority")).replace("'", "");
            String branchExpression = isDefault ? "DEFAULT" : extractCondition(config);
            ExtensionAttribute priorityAttribute = new ExtensionAttribute("branchPriority", priority);
            priorityAttribute.setNamespace("http://flowable.org/bpmn");
            priorityAttribute.setNamespacePrefix("flowable");
            sequenceFlow.addAttribute(priorityAttribute);
            ExtensionAttribute expressionAttribute = new ExtensionAttribute("branchExpression",
                    branchExpression == null ? "" : branchExpression);
            expressionAttribute.setNamespace("http://flowable.org/bpmn");
            expressionAttribute.setNamespacePrefix("flowable");
            sequenceFlow.addAttribute(expressionAttribute);
            // SequenceFlow 的通用扩展属性不会被 Flowable XML converter 保留；用
            // documentation 保存不可执行的审计元数据，部署后仍可从真实模型读取。
            String auditExpression = branchExpression == null ? "" : branchExpression;
            String encodedAuditExpression = Base64.getEncoder().encodeToString(
                    auditExpression.getBytes(StandardCharsets.UTF_8));
            sequenceFlow.setDocumentation("P58_BRANCH_META|" + priority + "|" + encodedAuditExpression);
            FlowableListener branchTraceListener = new FlowableListener();
            branchTraceListener.setEvent("take");
            branchTraceListener.setImplementationType(
                    ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION);
            branchTraceListener.setImplementation("${bpmBranchConditionEvaluator}");
            sequenceFlow.setExecutionListeners(new java.util.ArrayList<>(List.of(branchTraceListener)));
            if (isDefault) {
                gateway.setDefaultFlow(sequenceFlow.getId());
            } else {
                String expression = extractCondition(config);
                if (expression == null || expression.isBlank()) {
                    throw new BaseException(BpmErrorCode.BRANCH_CONFIG_INVALID.getCode(),
                            "非默认分支缺少条件表达式: " + edge.getId());
                }
                String encoded = Base64.getEncoder().encodeToString(
                        expression.getBytes(StandardCharsets.UTF_8));
                String branchEncoded = Base64.getEncoder().encodeToString(
                        branchId.getBytes(StandardCharsets.UTF_8));
                sequenceFlow.setConditionExpression(
                        "${bpmBranchConditionEvaluator.matches(execution, '" + encoded
                                + "', '" + branchEncoded + "', '"
                                + priority
                                + "')}");
            }
        }
        return sequenceFlow;
    }

    private int edgeEvaluationOrder(GraphElement edge) {
        if (edge == null || edge.getConfig() == null) return 0;
        Map<String, Object> config = edge.getConfig();
        if (Boolean.TRUE.equals(config.get("default"))
                || Boolean.TRUE.equals(config.get("isDefault"))) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(String.valueOf(config.getOrDefault("priority", 0)));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String extractCondition(Map<String, Object> config) {
        Object condition = config.get("condition");
        if (condition instanceof Map<?, ?> map && map.get("expression") != null) {
            return String.valueOf(map.get("expression"));
        }
        if (config.get("expression") != null) return String.valueOf(config.get("expression"));
        return null;
    }
}
