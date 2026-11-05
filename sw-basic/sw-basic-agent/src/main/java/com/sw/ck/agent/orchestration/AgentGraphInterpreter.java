package com.sw.ck.agent.orchestration;

import com.sw.ck.agent.dto.graph.GraphElement;
import com.sw.ck.agent.dto.graph.ProcessGraph;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.knowledge.dto.KnowledgeHitDTO;
import com.sw.ck.knowledge.service.KnowledgeRetrievalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图解释执行引擎（M07-F02 Step8 第一版 + Step10 多变量执行上下文，纯 Java 无 Spring
 * 注解，可独立单测）。
 * <p>
 * 直接解释 {@link ProcessGraph#getElements()}（Step7 产物）：从唯一 START 节点出发，
 * 按 elements 顺序遍历节点与边，执行 LLM 节点（单跳调用，无工具无历史）/工具节点
 * （按名称精确定位单个 {@link ToolCallback} 直接调用）/条件分支节点（关键词子串匹配
 * 选路），直到 END 节点，返回最终输出文本。
 * </p>
 * <p>
 * <b>执行上下文（Step10 多变量版）</b>：命名变量表 {@code Map<String, String>}，
 * 初始仅含默认变量 {@value #DEFAULT_VARIABLE_NAME}（值 = 请求 {@code input}）。
 * LLM/TOOL 节点可经 config.{@code inputVar}（从哪个变量读输入）与 config.
 * {@code outputVar}（结果写到哪个变量）指定命名变量存取；CONDITION 节点经
 * config.{@code inputVar} 指定关键词匹配基于哪个变量；END 节点经 config.
 * {@code inputVar} 指定最终输出取自哪个变量。各键缺失/空白 = 默认变量——旧图
 * （无变量名字段）行为与 Step8 单一 currentText 语义完全一致（零迁移）。
 * 未定义变量引用（读一个从未写入的变量）为运行时错误，不做执行前数据流静态校验。
 * </p>
 * <p>
 * <b>与 F01 的关系（方案 §2-A）</b>：本类是与 {@link AgentGraphFactory}/LangGraph4j
 * 并存的独立执行路径，互不修改、互不依赖。F01 是"LLM 自主决定是否调工具"的单跳
 * agentic 调用；本类是"用户显式画出的多步顺序，工具何时调、条件怎么分支由图拓扑
 * 决定"的图解释执行——复用更底层的构造块 {@link AgentModelRuntimeService}（LangChain4j 装配 + 单例复用）与
 * {@link AgentToolCallbackFactory#buildToolCallbacks}，但以不同方式调用：
 * LLM 节点按 {@code config.agentModelConfigId} 指向单个配置单跳调用；
 * 工具节点从白名单装载结果中<b>按名称精确定位单个</b>执行器调用，
 * 而非像 F01 把全部工具注入 LLM。
 * </p>
 * <p>
 * <b>节点 config 语义（本 Step 定义的执行契约）</b>：LLM 节点
 * {@code config.agentModelConfigId}（Long，必填）；TOOL 节点
 * {@code config.toolName}（String，必填）；LLM/TOOL 节点 {@code config.inputVar} /
 * {@code config.outputVar}（String，可选，缺失/空白 = 默认变量，Step10 新增）；
 * CONDITION 节点 {@code config.inputVar}（String，可选，缺失/空白 = 默认变量，
 * Step10 新增）；CONDITION 出边 {@code config.keyword}（String，可选，空/null 的边
 * 为默认边）；END 节点 {@code config.inputVar}（String，可选，缺失/空白 = 默认变量，
 * Step10 新增）。config 其余字段仍为不透明 Map 原样透传（Step7 禁令），本类只消费
 * 上述已定义键。
 * </p>
 * <p>
 * <b>明文 API Key 生命周期</b>（对齐 F01 惯例）：解密出的明文 Key 仅存在于局部变量，
 * 用于当次 {@code ChatModelFactory.build}，finally 中置 null，不进日志/异常/响应。
 * </p>
 * <p>
 * <b>死循环防护</b>：{@code maxSteps}（由调用方注入，见执行 Service 预算公式）硬上限，
 * 超限抛 {@link GraphExecutionException}，不无限执行。
 * </p>
 * <p>
 * <b>并行/循环执行模型（Step11）</b>：主循环从"单指针 while"改为<b>多活跃执行点</b>
 * 集合交替推进（FIFO 取队首活跃点执行一步，产出 0/1/N 个后继活跃点；单线程交错 =
 * 逻辑并发，非线程级并行）。LOOP 节点（config.{@code maxIterations}，缺省
 * {@value #DEFAULT_MAX_ITERATIONS}）按节点 id 迭代计数，超限抛"循环迭代次数超限"；
 * FORK 节点将当前活跃点替换为全部出边分支（出边在 elements 中的出现顺序 = 确定性
 * 分支顺序）；JOIN 节点按静态入边数聚合，未达入边数挂起等待、达到后合成单个活跃点
 * 沿唯一出边继续；任一活跃点到达 END 即<b>终止全部执行</b>并返回该 END 输出。
 * 并行分支写同一变量名 = <b>最后写入覆盖</b>（用户已决策，不拦截不告警）——变量表仍
 * 为单一共享 {@code Map}，单线程推进无并发安全问题。
 * </p>
 * <p>
 * <b>执行历史轨迹采集（Step12）</b>：每次节点出队产生一条 {@link NodeExecutionTrace}
 * （全局步序 nodeSeq + 并行分支标识 branchId + 节点级耗时 + 变量表快照），经
 * {@link #getTraces()} 由调用方（执行 Service）读取落库——解释器只采集不持久化，
 * 不持有 Mapper 依赖，保持纯 Java 可独立单测定位；成功与失败路径都完整留痕。
 * 运行时错误统一携带<b>错误分类</b>（{@code GraphExecutionException.category}，
 * 见 {@link #ERROR_CATEGORY_STEP_LIMIT} 等），第三方异常（模型/工具调用）在调用点
 * 包装为带分类的图执行异常，失败记录可结构化落库。
 * </p>
 *
 * @see GraphExecutionException
 */
public class AgentGraphInterpreter {

    // ==================== 节点类型常量（String 非 enum，D52 精神） ====================

    /** 开始节点 */
    public static final String NODE_TYPE_START = "START";

    /** 结束节点 */
    public static final String NODE_TYPE_END = "END";

    /** LLM 节点（config.agentModelConfigId 指定模型配置） */
    public static final String NODE_TYPE_LLM = "LLM";

    /** 工具节点（config.toolName 指定白名单工具） */
    public static final String NODE_TYPE_TOOL = "TOOL";

    /** 条件分支节点（纯路由点，按出边 config.keyword 子串匹配选路） */
    public static final String NODE_TYPE_CONDITION = "CONDITION";

    /** 循环节点（循环头：按节点 id 迭代计数，config.maxIterations 超限抛错；唯一出边进循环体） */
    public static final String NODE_TYPE_LOOP = "LOOP";

    /** 并行扇出节点（出边 ≥ 2，每条出边一个分支，全部分支执行后于 JOIN 汇合） */
    public static final String NODE_TYPE_FORK = "FORK";

    /** 汇合节点（入边 ≥ 2，静态入边数全部到达后合成单个活跃点继续，出边唯一） */
    public static final String NODE_TYPE_JOIN = "JOIN";

    /** 知识检索节点（F1 阶段 3：按知识库检索，结果写入变量供下游 LLM 使用） */
    public static final String NODE_TYPE_KNOWLEDGE = "KNOWLEDGE";

    // ==================== 节点 config 键（本 Step 定义的执行契约） ====================

    /** LLM 节点 config 键：模型配置 id（Long） */
    public static final String CONFIG_KEY_AGENT_MODEL_CONFIG_ID = "agentModelConfigId";

    /** LLM 节点 config 键：所选供应商 id（Long，可选；存在时校验模型归属该供应商） */
    public static final String CONFIG_KEY_PROVIDER_ID = "providerId";

    /** LLM 节点 config 键：挂载的工具名列表（List&lt;String&gt; 或逗号分隔字符串，可选） */
    public static final String CONFIG_KEY_TOOL_NAMES = "toolNames";

    /** LLM 节点 config 键：工具调用最大轮次（Integer，可选，缺省 {@value #DEFAULT_MAX_TOOL_ROUNDS}） */
    public static final String CONFIG_KEY_MAX_TOOL_ROUNDS = "maxToolRounds";

    /** LLM 节点工具调用默认最大轮次 */
    public static final int DEFAULT_MAX_TOOL_ROUNDS = 5;

    /** TOOL 节点 config 键：白名单工具名（String） */
    public static final String CONFIG_KEY_TOOL_NAME = "toolName";

    /** KNOWLEDGE 节点 config 键：知识库 id（Long，必填） */
    public static final String CONFIG_KEY_KNOWLEDGE_BASE_ID = "knowledgeBaseId";

    /** KNOWLEDGE 节点 config 键：返回条数（Integer，缺省 {@value #DEFAULT_KNOWLEDGE_TOP_K}） */
    public static final String CONFIG_KEY_TOP_K = "topK";

    /** KNOWLEDGE 节点 config 键：相似度阈值（Double，0~1，缺省 0） */
    public static final String CONFIG_KEY_SIMILARITY_THRESHOLD = "similarityThreshold";

    /** KNOWLEDGE 节点默认 topK */
    public static final int DEFAULT_KNOWLEDGE_TOP_K = 5;

    /** CONDITION 出边 config 键：关键词（String，空/null 的边为默认边） */
    public static final String CONFIG_KEY_KEYWORD = "keyword";

    /** LLM/TOOL/CONDITION/END 节点 config 键：读取的变量名（String，缺失/空白 = 默认变量） */
    public static final String CONFIG_KEY_INPUT_VAR = "inputVar";

    /** LLM/TOOL 节点 config 键：结果写入的变量名（String，缺失/空白 = 默认变量） */
    public static final String CONFIG_KEY_OUTPUT_VAR = "outputVar";

    /** LOOP 节点 config 键：迭代上限（Integer，可选，缺省 {@value #DEFAULT_MAX_ITERATIONS}；<1 由执行前校验拦截） */
    public static final String CONFIG_KEY_MAX_ITERATIONS = "maxIterations";

    /** LLM 节点 config 键：系统 Prompt（String，可选，空白 = 不注入 SystemMessage） */
    public static final String CONFIG_KEY_SYSTEM_PROMPT = "systemPrompt";

    /** LLM 节点 config 键：用户 Prompt 模板（String，可选，支持 {{variableName}} 占位符，空白 = 退化为 inputVar 原文） */
    public static final String CONFIG_KEY_USER_PROMPT_TEMPLATE = "userPromptTemplate";

    /** 用户 Prompt 模板占位符正则：{{variableName}}，变量名按 Java 标识符规则（首字
     * 母/下划线，后续字母数字下划线），一次性纯字符串替换（不做二次展开，避免变量值注入）。 */
    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\{\\{([A-Za-z_][A-Za-z0-9_]*)}}");

    /**
     * 默认变量名（旧图语义锚点）：graph 入参写入该变量；未指定变量名的节点读写该
     * 变量（Step8 单一 currentText 语义）；CONDITION/END 未指定 inputVar 时分别基于
     * 该变量匹配/取最终输出。零迁移关键：旧图无变量名字段，全部落此变量。
     */
    public static final String DEFAULT_VARIABLE_NAME = "input";

    /**
     * LOOP 节点默认迭代上限：config.maxIterations 缺失时使用（执行前校验保证显式值 ≥ 1；
     * 步数预算公式对缺省 LOOP 同用此常量）。
     */
    public static final int DEFAULT_MAX_ITERATIONS = 10;

    // ==================== 错误分类常量（Step12 执行历史持久化新增） ====================
    // GraphExecutionException 携带分类（varchar + String，D52 精神不建 enum），
    // 供执行 Service 结构化落库 error_category；既有 1 参构造保留（分类 = UNKNOWN）。

    /** 执行步数超限（疑似环路） */
    public static final String ERROR_CATEGORY_STEP_LIMIT = "STEP_LIMIT";

    /** 图级执行超时（阶段 2：执行服务按图配置的总时限终止并落失败记录） */
    public static final String ERROR_CATEGORY_GRAPH_TIMEOUT = "GRAPH_TIMEOUT";

    /** 循环迭代超限（LOOP maxIterations 耗尽） */
    public static final String ERROR_CATEGORY_LOOP_LIMIT = "LOOP_LIMIT";

    /** 引用了未定义的变量 */
    public static final String ERROR_CATEGORY_UNDEFINED_VARIABLE = "UNDEFINED_VARIABLE";

    /** 条件分支无匹配且无默认边 */
    public static final String ERROR_CATEGORY_CONDITION_NO_MATCH = "CONDITION_NO_MATCH";

    /** 图拓扑/配置非法（出边数量、悬空引用、未知节点类型、节点缺配置等防御性兜底） */
    public static final String ERROR_CATEGORY_TOPOLOGY_INVALID = "TOPOLOGY_INVALID";

    /** LLM 模型调用失败（第三方异常/未返回文本/配置缺失） */
    public static final String ERROR_CATEGORY_MODEL_CALL_FAILED = "MODEL_CALL_FAILED";

    /** 工具调用失败（第三方异常/工具不存在/未返回文本） */
    public static final String ERROR_CATEGORY_TOOL_CALL_FAILED = "TOOL_CALL_FAILED";

    /** 知识检索失败（知识库不存在/跨租户/Embedding 或向量检索异常） */
    public static final String ERROR_CATEGORY_KNOWLEDGE_RETRIEVAL_FAILED = "KNOWLEDGE_RETRIEVAL_FAILED";

    /** 未分类兜底（理论不可达：全部抛出点均已带分类） */
    public static final String ERROR_CATEGORY_UNKNOWN = "UNKNOWN";

    /** 工具入参 JSON 编码（Jackson，替代旧 spring-ai JsonParser） */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 根分支路径（非 FORK 分支的 branchId；FORK 扇出后按出边顺序追加 "-<下标>"） */
    private static final String ROOT_BRANCH_ID = "0";

    // ==================== 依赖（纯构造注入，无 Spring 注解，可 mock 单测） ====================

    /** 模型运行时（LangChain4j 装配 + 单例复用 + 启停校验，F1 阶段 1） */
    private final AgentModelRuntimeService modelRuntimeService;

    private final AgentToolCallbackFactory toolCallbackFactory;

    /** 知识检索服务（阶段 3；null 时 KNOWLEDGE 节点显式报错，保持旧单测可构造） */
    private final KnowledgeRetrievalService knowledgeRetrievalService;

    /** 工具白名单装载的租户过滤条件（null 时不显式过滤，由 MyBatis-Plus 租户拦截器隔离） */
    private final Long tenantId;

    /** 执行步数硬上限（防死循环兜底，调用方按 elements 节点数 × 2 计算） */
    private final int maxSteps;

    /** 本次执行的节点轨迹（Step12 采集，每次 run 重建；由调用方读取落库，本类不持 Mapper） */
    private List<NodeExecutionTrace> traces;

    /**
     * @param modelRuntimeService 模型运行时（LangChain4j 装配 + 单例复用 + 启停校验）
     * @param toolCallbackFactory 工具工厂（只读复用；null 时 TOOL 节点抛错）
     * @param tenantId            当前租户（透传给工具白名单装载）
     * @param maxSteps            执行步数上限（防死循环）
     */
    public AgentGraphInterpreter(AgentModelRuntimeService modelRuntimeService,
                                 AgentToolCallbackFactory toolCallbackFactory,
                                 Long tenantId,
                                 int maxSteps) {
        this(modelRuntimeService, toolCallbackFactory, null, tenantId, maxSteps);
    }

    /**
     * 阶段 3 构造：额外注入知识检索服务（图执行/调试按租户检索知识库）。
     */
    public AgentGraphInterpreter(AgentModelRuntimeService modelRuntimeService,
                                 AgentToolCallbackFactory toolCallbackFactory,
                                 KnowledgeRetrievalService knowledgeRetrievalService,
                                 Long tenantId,
                                 int maxSteps) {
        this.modelRuntimeService = modelRuntimeService;
        this.toolCallbackFactory = toolCallbackFactory;
        this.knowledgeRetrievalService = knowledgeRetrievalService;
        this.tenantId = tenantId;
        this.maxSteps = maxSteps;
    }

    /**
     * 解释执行整图：从唯一 START 出发维护一组活跃执行点，每轮取队首活跃点执行一步
     * （FORK 扇出多分支并存、JOIN 聚合、LOOP 迭代计数），任一活跃点到达 END 即终止
     * 全部执行并返回该 END 输出。
     *
     * @param graph 已发布图（Step7 产物，config 不透明字段按本类契约消费）
     * @param input 请求入参文本（写入默认变量 {@value #DEFAULT_VARIABLE_NAME}）
     * @return 最先到达的 END 节点处最终输出（END config.inputVar 指定变量，缺失/空白 = 默认变量）
     * @throws GraphExecutionException 条件分支无匹配且无默认边 / 未定义变量引用 /
     *                                 步数超限 / 循环迭代超限 / 拓扑非法等运行时错误
     */
    public String run(ProcessGraph graph, String input) {
        List<GraphElement> elements = graph.getElements();
        // 命名变量表（Step10）：初始仅含默认变量（= 请求入参）。未指定变量名的节点
        // 读写默认变量，旧图（无变量名字段）行为与 Step8 单一 currentText 语义一致。
        // 并行分支写同一变量名 = 最后写入覆盖（用户已决策，不拦截不告警；单线程交错
        // 推进下即按图推进顺序覆盖，行为可预期）。
        Map<String, String> variables = new HashMap<>();
        variables.put(DEFAULT_VARIABLE_NAME, input);
        // 活跃执行点集合（Step11）：FORK 扇出后多分支并存，每轮取队首活跃点执行一步，
        // 产出 0/1/N 个后继活跃点（FIFO 交替推进，确定性顺序；逻辑并发非线程级并行）。
        // Step12 起活跃点携带分支路径（branchPath，FORK 按出边出现顺序追加下标）——
        // FORK 分支复用同一节点 id，靠 branchPath 区分"哪个分支的第几次访问"。
        List<ActiveExecutionPoint> activePoints = new ArrayList<>();
        activePoints.add(new ActiveExecutionPoint(findStart(elements).getId(), ROOT_BRANCH_ID));
        // LOOP 迭代计数（按节点 id）与 JOIN 到达计数（按节点 id）
        Map<String, Integer> loopIterationCounts = new HashMap<>();
        Map<String, Integer> joinArrivalCounts = new HashMap<>();
        // Step12 轨迹采集：节点出队即分配全局步序（1-based），节点执行/路由完成后补
        // 节点级耗时与变量快照；成功/失败路径都完整留痕（失败节点在 catch 中补录后
        // 上抛）。轨迹由调用方（执行 Service）经 getTraces() 读取落库——解释器只采集
        // 不持久化，保持纯 Java 无 Mapper 依赖（方向文档 §5.4）。
        // CopyOnWriteArrayList：执行超时取消时主线程可安全读取已产生的轨迹
        traces = new java.util.concurrent.CopyOnWriteArrayList<>();
        long traceSeq = 0;
        int steps = 0;
        while (!activePoints.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new GraphExecutionException(ERROR_CATEGORY_UNKNOWN, "执行已取消");
            }
            ActiveExecutionPoint point = activePoints.remove(0);
            String currentId = point.nodeId;
            long stepStartNanos = System.nanoTime();
            GraphElement current = findNode(currentId, elements);
            NodeExecutionTrace trace = new NodeExecutionTrace(++traceSeq, point.branchPath,
                    current.getId(), current.getType());
            traces.add(trace);
            try {
                // 任一活跃点到达 END → 立即终止全部执行（其余分支停止推进），返回该 END
                // 节点 config.inputVar 读取值（缺失/空白 = 默认变量，Step8 语义）
                if (NODE_TYPE_END.equals(current.getType())) {
                    finishTrace(trace, stepStartNanos, variables);
                    return readVariable(current, variables, CONFIG_KEY_INPUT_VAR);
                }
                // 全局步数上限（跨所有活跃点累计）：死循环 / JOIN 挂起死锁统一由超限兜底
                if (++steps > maxSteps) {
                    throw new GraphExecutionException(ERROR_CATEGORY_STEP_LIMIT,
                            "执行步数超限，图可能存在环路");
                }
                switch (current.getType()) {
                    case NODE_TYPE_LLM -> writeOutput(current, variables,
                            callLlmNode(current, readInput(current, variables), variables));
                    case NODE_TYPE_TOOL -> {
                        // F1S2-G1：工具节点真实入参/输出写入轨迹（节点卡片可见）
                        String toolInput = readInput(current, variables);
                        trace.setInputText(toolInput);
                        String toolOutput = callToolNode(current, toolInput);
                        trace.setOutputText(toolOutput);
                        writeOutput(current, variables, toolOutput);
                    }
                    case NODE_TYPE_KNOWLEDGE -> writeOutput(current, variables,
                            retrieveKnowledge(knowledgeRetrievalService, current,
                                    readInput(current, variables), trace));
                    // START/CONDITION 为纯路由点：START 不写变量（入参已在初始变量表），
                    // CONDITION 只读匹配文本（inputVar）不写变量
                    case NODE_TYPE_START, NODE_TYPE_CONDITION -> { }
                    case NODE_TYPE_LOOP -> {
                        // 按节点 id 迭代计数 +1，超限抛错；否则沿唯一出边进循环体
                        int iteration = loopIterationCounts.merge(current.getId(), 1, Integer::sum);
                        if (iteration > maxIterationsOf(current)) {
                            throw new GraphExecutionException(ERROR_CATEGORY_LOOP_LIMIT,
                                    "循环迭代次数超限: " + current.getId());
                        }
                    }
                    case NODE_TYPE_FORK -> { /* 扇出：无动作，路由段按全部出边产出多活跃点 */ }
                    case NODE_TYPE_JOIN -> {
                        // 到达计数 +1；未达静态入边数 → 本活跃点挂起（不入队），等待其余分支；
                        // 达到 → 合成单个活跃点沿唯一出边继续（路由段统一处理）
                        int arrived = joinArrivalCounts.merge(current.getId(), 1, Integer::sum);
                        if (arrived < incomingEdgeCount(current, elements)) {
                            finishTrace(trace, stepStartNanos, variables);
                            continue;
                        }
                    }
                    default -> throw new GraphExecutionException(ERROR_CATEGORY_TOPOLOGY_INVALID,
                            "不支持的节点类型: " + current.getType() + "（节点 " + current.getId() + "）");
                }
                // 后继路由：FORK 将当前活跃点替换为其全部出边分支（确定性顺序 = 出边在
                // elements 中的出现顺序，分支路径追加出边下标）；其余节点单后继（CONDITION
                // 单选一；LOOP/JOIN/START/LLM/TOOL 出边唯一约束由 nextNodeId 强制）
                if (NODE_TYPE_FORK.equals(current.getType())) {
                    int branchIndex = 0;
                    for (GraphElement edge : outgoingEdges(current, elements)) {
                        activePoints.add(new ActiveExecutionPoint(edge.getTarget(),
                                point.branchPath + "-" + branchIndex++));
                    }
                } else {
                    activePoints.add(new ActiveExecutionPoint(
                            nextNodeId(current, elements, variables), point.branchPath));
                }
                finishTrace(trace, stepStartNanos, variables);
            } catch (RuntimeException e) {
                // 本步失败：节点轨迹仍完整留痕（耗时 + 失败时点变量快照），再上抛
                trace.setStatus("FAILED");
                trace.setErrorMessage(e.getMessage());
                finishTrace(trace, stepStartNanos, variables);
                throw e;
            }
        }
        // 活跃点耗尽且无 END 到达（如 JOIN 静态入边数无法由当前路径满足 → 挂起后无后继）。
        // 执行前校验只保证 END 可达、不保证汇合可满足，此处按图设计缺陷显式抛错，不静默返回。
        throw new GraphExecutionException(ERROR_CATEGORY_TOPOLOGY_INVALID,
                "所有执行点已终止但未到达 END 节点（JOIN 汇合入边数无法满足）");
    }

    // ==================== 轨迹读取（Step12 执行历史持久化） ====================

    /**
     * 本次执行的节点轨迹（Step12）：run 前调用返回空列表；run 后（含失败路径）
     * 返回全部访问记录（nodeSeq 升序，含 END 与失败节点）。由调用方（执行 Service）
     * 读取落库——解释器只采集不持久化，保持纯 Java 可独立单测定位。
     */
    public List<NodeExecutionTrace> getTraces() {
        // 阶段 2：执行超时后主线程在取消工作线程的同时读取轨迹，返回稳定快照
        return traces == null ? List.of() : List.copyOf(traces);
    }

    /** 完成一条节点轨迹：节点级耗时（出队到本步结束）+ 该时点变量表快照 */
    private void finishTrace(NodeExecutionTrace trace, long stepStartNanos,
                             Map<String, String> variables) {
        trace.nodeLatencyMs = (System.nanoTime() - stepStartNanos) / 1_000_000;
        trace.endTime = LocalDateTime.now();
        trace.variableSnapshot = new HashMap<>(variables);
    }

    // ==================== LLM 节点 ====================

    /**
     * LLM 节点执行：config.agentModelConfigId → 解密 Key → {@code ChatModelFactory.build}
     * → 按 config.systemPrompt / config.userPromptTemplate 组装消息列表单跳调用（不带工具、
     * 不带历史）→ 返回输出（由调用方写入 outputVar 指定变量）。
     * <p>
     * <b>Prompt 契约（M07-F02-02 新增）</b>：
     * <ul>
     *   <li>config.systemPrompt（可选）：空白 = 不注入 SystemMessage；非空白整体作为
     *       SystemMessage（不做变量插值——系统 Prompt 用于角色/规则/背景，与变量无关）</li>
     *   <li>config.userPromptTemplate（可选）：空白/缺失 = 退化为 inputVar 原文（历史行为）；
     *       配置后按 {{variableName}} 做一次性纯字符串插值；未定义变量抛 UNDEFINED_VARIABLE；
     *       变量值按普通文本替换，不再次解析其中的 {{...}}（避免二次展开 / 表达式注入）</li>
     * </ul>
     * 历史图（无 systemPrompt/userPromptTemplate）行为 = 仅 UserMessage(inputVar 值)，
     * 与 Step8/10/11/12 完全一致（零迁移）。
     *
     * @param node      当前 LLM 节点
     * @param text      inputVar 读出的原始值（未配置 userPromptTemplate 时直接作为用户消息）
     * @param variables 当前变量表（供 userPromptTemplate 插值使用）
     */
    private String callLlmNode(GraphElement node, String text, Map<String, String> variables) {
        Long modelConfigId = requireConfigId(node, CONFIG_KEY_AGENT_MODEL_CONFIG_ID);
        try {
            // F1 阶段 1：经运行时服务解析（启停校验 + LangChain4j 单例复用 + 模板装配；
            // D022-05：429 时组内密钥轮换，与编排入口同语义）
            // === Prompt 契约组装 ===
            String systemPrompt = configString(node, CONFIG_KEY_SYSTEM_PROMPT);
            String userPromptTemplate = configString(node, CONFIG_KEY_USER_PROMPT_TEMPLATE);
            List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(SystemMessage.from(systemPrompt));
            }
            String userText;
            if (userPromptTemplate == null || userPromptTemplate.isBlank()) {
                // 历史行为：直接用 inputVar 值作为用户消息（旧图零迁移关键）
                userText = text;
            } else {
                userText = interpolateTemplate(userPromptTemplate, node, variables);
            }
            messages.add(UserMessage.from(userText));
            NodeExecutionTrace currentTrace = traces.get(traces.size() - 1);
            // F1S2-G1：节点真实输入（模板插值后的用户消息）
            currentTrace.setInputText(userText);
            // 阶段 2：节点可挂工具，模型请求工具时在节点内完成工具调用循环
            return executeChatWithTools(modelRuntimeService, toolCallbackFactory, tenantId,
                    node, modelConfigId, messages, currentTrace);
        } catch (GraphExecutionException e) {
            // 自有异常（未返回文本等）已带分类，原样上抛
            throw e;
        } catch (BaseException e) {
            // 运行时服务拒绝（模型/供应商停用或不存在）：归入模型调用失败分类
            throw new GraphExecutionException(ERROR_CATEGORY_MODEL_CALL_FAILED,
                    e.getMessage(), e);
        } catch (Exception e) {
            // 第三方异常（解密失败/模型网络超时/429/未识别响应等）：包装为带分类的
            // 图执行异常（Step12 错误分类维度），message 沿用 cause 最深非空文本，
            // errorMessage 摘要语义不变
            throw new GraphExecutionException(ERROR_CATEGORY_MODEL_CALL_FAILED,
                    e.getMessage(), e);
        }
    }

    /**
     * 知识检索节点（阶段 3，图执行与单步调试共用）：读取 knowledgeBaseId/topK/
     * similarityThreshold，检索结果按"文档 + 相似度 + 分片正文"拼成文本写入输出变量，
     * 供下游 LLM 的 userPromptTemplate 直接引用。空结果写固定提示，避免下游模型把
     * 空字符串误当上下文。
     */
    static String retrieveKnowledge(KnowledgeRetrievalService service, GraphElement node,
                                    String query, NodeExecutionTrace trace) {
        trace.setInputText(query);
        if (service == null) {
            throw new GraphExecutionException(ERROR_CATEGORY_TOPOLOGY_INVALID,
                    "知识检索服务未装配，无法执行 KNOWLEDGE 节点: " + node.getId());
        }
        Long kbId = parseConfigLong(node.getConfig() == null
                ? null : node.getConfig().get(CONFIG_KEY_KNOWLEDGE_BASE_ID));
        if (kbId == null) {
            throw new GraphExecutionException(ERROR_CATEGORY_TOPOLOGY_INVALID,
                    "KNOWLEDGE 节点缺少知识库引用: " + node.getId());
        }
        int topK = configInt(node, CONFIG_KEY_TOP_K, DEFAULT_KNOWLEDGE_TOP_K);
        double threshold = configDouble(node, CONFIG_KEY_SIMILARITY_THRESHOLD, 0.0);
        java.util.List<KnowledgeHitDTO> hits;
        try {
            hits = service.retrieve(kbId, query, topK, threshold);
        } catch (GraphExecutionException e) {
            throw e;
        } catch (BaseException e) {
            throw new GraphExecutionException(ERROR_CATEGORY_KNOWLEDGE_RETRIEVAL_FAILED,
                    e.getMessage(), e);
        } catch (Exception e) {
            throw new GraphExecutionException(ERROR_CATEGORY_KNOWLEDGE_RETRIEVAL_FAILED,
                    e.getMessage(), e);
        }
        String output = formatKnowledgeHits(hits);
        trace.setOutputText(output);
        return output;
    }

    private static String formatKnowledgeHits(java.util.List<KnowledgeHitDTO> hits) {
        if (hits == null || hits.isEmpty()) {
            return "未检索到相关内容。";
        }
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (KnowledgeHitDTO hit : hits) {
            if (sb.length() > 0) {
                sb.append("\n\n---\n\n");
            }
            sb.append('[').append(index++).append("] 文档：")
                    .append(hit.getDocumentName() == null ? "-" : hit.getDocumentName())
                    .append("（相似度 ").append(String.format(java.util.Locale.ROOT, "%.3f",
                            hit.getSimilarity() == null ? 0.0 : hit.getSimilarity()))
                    .append("）\n").append(hit.getContent());
        }
        return sb.toString();
    }

    /** Double 配置读取（Number/数字字符串；null/非法 → 默认值）。 */
    public static double configDouble(GraphElement node, String key, double defaultValue) {
        Object value = node.getConfig() == null ? null : node.getConfig().get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }


    /**
     * 模型调用 + 节点内工具循环（阶段 2，图执行与单步调试共用）：
     * 按 config.toolNames 取启用工具规格随请求下发；模型返回工具请求时逐个执行并把
     * 结果回喂模型，直到模型给出最终文本或轮次超限。每次工具调用的入参与输出写入
     * 当前节点轨迹。
     */
    static String executeChatWithTools(AgentModelRuntimeService runtime,
                                       AgentToolCallbackFactory factory,
                                       Long tenantId,
                                       GraphElement node,
                                       Long modelConfigId,
                                       List<dev.langchain4j.data.message.ChatMessage> messages,
                                       NodeExecutionTrace trace) {
        List<String> toolNames = configStringList(node, CONFIG_KEY_TOOL_NAMES);
        List<AgentToolCallbackFactory.ToolHandle> handles = new ArrayList<>();
        if (!toolNames.isEmpty()) {
            if (factory == null) {
                throw new GraphExecutionException(ERROR_CATEGORY_TOOL_CALL_FAILED,
                        "工具工厂未装配，无法执行 LLM 节点工具: " + node.getId());
            }
            List<AgentToolCallbackFactory.ToolHandle> available = factory.buildToolCallbacks(tenantId);
            for (String toolName : toolNames) {
                AgentToolCallbackFactory.ToolHandle handle = available.stream()
                        .filter(h -> toolName.equals(h.specification().name()))
                        .findFirst()
                        .orElseThrow(() -> new GraphExecutionException(ERROR_CATEGORY_TOOL_CALL_FAILED,
                                "LLM 节点引用的工具不存在或未启用: " + toolName));
                handles.add(handle);
            }
        }
        List<dev.langchain4j.agent.tool.ToolSpecification> specs = handles.stream()
                .map(AgentToolCallbackFactory.ToolHandle::specification)
                .toList();
        int maxRounds = configInt(node, CONFIG_KEY_MAX_TOOL_ROUNDS, DEFAULT_MAX_TOOL_ROUNDS);
        long inputTokens = 0L;
        long outputTokens = 0L;
        boolean inputSeen = false;
        boolean outputSeen = false;
        int rounds = 0;
        while (true) {
            ChatResponse response = AgentExecutionEventContext.get() == null
                    ? runtime.chatWithRotation(modelConfigId, messages, specs)
                    : runtime.chatStreamingWithRotation(modelConfigId, messages, specs,
                            delta -> AgentExecutionEventContext.emit("MODEL_DELTA", delta));
            dev.langchain4j.model.output.TokenUsage usage =
                    response.metadata() != null ? response.metadata().tokenUsage() : null;
            if (usage != null && usage.inputTokenCount() != null) {
                inputTokens += usage.inputTokenCount();
                inputSeen = true;
            }
            if (usage != null && usage.outputTokenCount() != null) {
                outputTokens += usage.outputTokenCount();
                outputSeen = true;
            }
            var ai = response.aiMessage();
            if (ai == null) {
                throw new GraphExecutionException(ERROR_CATEGORY_MODEL_CALL_FAILED,
                        "LLM 节点未返回消息: " + node.getId());
            }
            if (!ai.hasToolExecutionRequests()) {
                String output = ai.text();
                if (output == null) {
                    throw new GraphExecutionException(ERROR_CATEGORY_MODEL_CALL_FAILED,
                            "LLM 节点未返回文本: " + node.getId());
                }
                trace.setInputTokens(inputSeen ? inputTokens : null);
                trace.setOutputTokens(outputSeen ? outputTokens : null);
                // F1S2-G1：节点真实输出 = 模型最终回答（含工具循环后的收敛结果）
                trace.setOutputText(output);
                return output;
            }
            if (++rounds > maxRounds) {
                throw new GraphExecutionException(ERROR_CATEGORY_TOOL_CALL_FAILED,
                        "工具调用轮次超过上限 " + maxRounds + ": " + node.getId());
            }
            messages.add(ai);
            for (ToolExecutionRequest request : ai.toolExecutionRequests()) {
                AgentToolCallbackFactory.ToolHandle handle = handles.stream()
                        .filter(h -> request.name().equals(h.specification().name()))
                        .findFirst()
                        .orElseThrow(() -> new GraphExecutionException(ERROR_CATEGORY_TOOL_CALL_FAILED,
                                "模型请求了未挂载的工具: " + request.name()));
                String result;
                try {
                    result = handle.executor().execute(request, null);
                } catch (Exception e) {
                    // 工具执行失败回喂错误文本，让模型自行收敛；不中断整个节点
                    result = "工具执行失败: " + e.getMessage();
                }
                String decoded = decodeIfJsonString(result);
                trace.addToolCall(new ToolCallTrace(request.name(), request.arguments(), decoded));
                messages.add(dev.langchain4j.data.message.ToolExecutionResultMessage
                        .from(request, decoded));
            }
        }
    }

    /** config 读取字符串列表（支持 List&lt;String&gt; 与逗号分隔字符串；空/缺失返回空列表）。 */
    public static List<String> configStringList(GraphElement node, String key) {
        Object value = node.getConfig() == null ? null : node.getConfig().get(key);
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s && !s.isBlank()) {
                    result.add(s.trim());
                }
            }
        } else if (value instanceof String s && !s.isBlank()) {
            for (String part : s.split(",")) {
                if (!part.isBlank()) {
                    result.add(part.trim());
                }
            }
        }
        return result;
    }

    /** config 读取正整数（缺失/非法用默认值；最小 1）。 */
    public static int configInt(GraphElement node, String key, int defaultValue) {
        Long parsed = parseConfigLong(node.getConfig() == null ? null : node.getConfig().get(key));
        return parsed == null ? defaultValue : (int) Math.max(1L, Math.min(parsed, 50L));
    }

    // ==================== TOOL 节点 ====================

    /**
     * TOOL 节点执行：config.toolName → 白名单装载结果中按名称精确匹配单个
     * {@link ToolCallback} → 以入参文本（inputVar 变量值，由调用方解析）为入参直接
     * 调用 → 返回文本（由调用方写入 outputVar 指定变量）。
     * <p>
     * 与 F01 的区别：F01 把全部启用工具注入 LLM 由模型自行决定；本节点由图的拓扑
     * 决定调用哪个工具，只定位这一个回调并直接调用。每次执行即时装载（工厂非启动
     * 缓存语义：白名单配置变更即时生效）。
     * </p>
     */
    private String callToolNode(GraphElement node, String text) {
        String toolName = requireConfigString(node, CONFIG_KEY_TOOL_NAME);
        if (toolCallbackFactory == null) {
            throw new GraphExecutionException(ERROR_CATEGORY_TOOL_CALL_FAILED,
                    "工具工厂未装配，无法执行 TOOL 节点: " + toolName);
        }
        AgentToolCallbackFactory.ToolHandle target = toolCallbackFactory.buildToolCallbacks(tenantId).stream()
                .filter(handle -> toolName.equals(handle.specification().name()))
                .findFirst()
                .orElseThrow(() -> new GraphExecutionException(ERROR_CATEGORY_TOOL_CALL_FAILED,
                        "TOOL 节点引用的工具不存在或未启用: " + toolName));
        // 工具回调契约（与 F01 一致，工厂回执 §3 实测）：FunctionToolCallback 的 call()
        // 入参为 JSON 字符串字面量（LLM 按 {"type":"string"} schema 发送），白名单方法
        // 收到 JSON 编码字符串；返回值同样经 JSON 编码（实测 "echo:你好" → "\"echo:你好\""）。
        // 解释器把累积文本 JSON 编码后传入，并把返回的编码文本解码还原为纯文本后覆盖
        // 累积文本（图执行上下文是给下游节点/最终输出使用的用户可读文本，与 F01 中
        // LLM 直接消费编码文本的用途不同）。
        try {
            // 执行器契约：arguments 为 JSON 编码字符串（与旧 FunctionToolCallback 的
            // call(JsonParser.toJson(text)) 语义一致），返回值经 JSON 编码
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id("graph-tool-" + node.getId())
                    .name(toolName)
                    .arguments(JSON.writeValueAsString(text))
                    .build();
            String result = target.executor().execute(request, null);
            String decoded = decodeIfJsonString(result);
            if (decoded == null) {
                throw new GraphExecutionException(ERROR_CATEGORY_TOOL_CALL_FAILED,
                        "TOOL 节点未返回文本: " + node.getId());
            }
            return decoded;
        } catch (GraphExecutionException e) {
            throw e;
        } catch (Exception e) {
            // 第三方异常（工具执行抛错等）：包装为带分类的图执行异常（Step12 错误分类维度）
            throw new GraphExecutionException(ERROR_CATEGORY_TOOL_CALL_FAILED,
                    e.getMessage(), e);
        }
    }

    // ==================== 多变量执行上下文（Step10） ====================

    /**
     * 读取节点输入文本：config.{@code inputVar} 指定的变量值（缺失/空白 = 默认变量
     * {@value #DEFAULT_VARIABLE_NAME}）。
     *
     * @throws GraphExecutionException 变量未定义（从未写入且非默认变量）——未定义
     * 变量引用为运行时错误，不做执行前数据流静态校验（方向文档已确认）
     */
    private String readInput(GraphElement node, Map<String, String> variables) {
        return readVariable(node, variables, CONFIG_KEY_INPUT_VAR);
    }

    /**
     * 按变量名键读取变量值：config 键缺失/空白 = 默认变量；变量不存在抛运行时错误。
     * 默认变量恒存在（run 开头写入请求入参），因此旧图（无变量名字段）永不触发。
     */
    private String readVariable(GraphElement node, Map<String, String> variables, String varKey) {
        String varName = resolveVarName(node, varKey);
        String value = variables.get(varName);
        if (value == null) {
            throw new GraphExecutionException(ERROR_CATEGORY_UNDEFINED_VARIABLE,
                    "引用了未定义的变量: " + varName + "（节点 " + node.getId() + "）");
        }
        return value;
    }

    /**
     * 写入节点输出：config.{@code outputVar} 指定结果写入的变量（缺失/空白 = 默认变量，
     * 覆盖语义与 Step8 单文本版一致；指定新变量名 = 创建变量）。LLM/TOOL 结果恒为
     * 非空文本，变量值类型全为 String（非文本类型不在本 Step 范围）。
     */
    private void writeOutput(GraphElement node, Map<String, String> variables, String output) {
        variables.put(resolveVarName(node, CONFIG_KEY_OUTPUT_VAR), output);
    }

    /**
     * 解析节点 config 中的变量名键：config 缺失 / 键缺失 / 值非 String / 空白 → 默认
     * 变量（与 {@link #keywordOf} 同款宽松语义：旧图无变量名字段即落默认变量，零迁移）。
     */
    private String resolveVarName(GraphElement node, String varKey) {
        Map<String, Object> config = node.getConfig();
        if (config == null) {
            return DEFAULT_VARIABLE_NAME;
        }
        Object value = config.get(varKey);
        if (!(value instanceof String s) || s.isBlank()) {
            return DEFAULT_VARIABLE_NAME;
        }
        return s;
    }

    /**
     * 读取节点 config 中的 String 型可选键（与 {@link #requireConfigString} 不同：
     * 缺失/非 String 返回 null 而非抛错，供 systemPrompt / userPromptTemplate 等可选契约使用）。
     */
    private String configString(GraphElement node, String key) {
        Map<String, Object> cfg = node.getConfig();
        if (cfg == null) {
            return null;
        }
        Object v = cfg.get(key);
        if (!(v instanceof String s)) {
            return null;
        }
        return s;
    }

    /**
     * 对模板中的 {@code {{variableName}}} 占位符做一次性纯字符串插值（M07-F02-02）。
     * 变量名按 Java 标识符规则（{@code [A-Za-z_][A-Za-z0-9_]*}）匹配；未定义变量抛
     * {@link GraphExecutionException}（分类 {@link #ERROR_CATEGORY_UNDEFINED_VARIABLE}）；
     * 变量值按普通文本替换（{@link Matcher#quoteReplacement}），不再次解析其中的
     * {@code {{...}}}，避免二次展开 / 表达式注入。
     * <p>
     * 不匹配占位符模式的文本（如 {@code "{{ invalid name }}"} 含空格 /
     * {@code "{{123numeric}}"}} 非标识符开头）原样保留，不抛错。
     */
    private String interpolateTemplate(String template, GraphElement node,
                                       Map<String, String> variables) {
        Matcher m = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder(template.length());
        while (m.find()) {
            String varName = m.group(1);
            String value = variables.get(varName);
            if (value == null) {
                throw new GraphExecutionException(ERROR_CATEGORY_UNDEFINED_VARIABLE,
                        "引用了未定义的变量: " + varName + "（节点 " + node.getId() + "）");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ==================== 条件分支与选路 ====================

    /**
     * 确定下一节点 id：CONDITION 节点按 §2-C 关键词子串匹配（elements 原始顺序即优先级，
     * 不排序），匹配文本 = CONDITION 节点 config.{@code inputVar} 指定的变量值（缺失/
     * 空白 = 默认变量，Step10 语义）；其余节点（含 LOOP/JOIN，出边唯一约束保持生效）
     * 取唯一出边。
     *
     * @throws GraphExecutionException 条件无匹配且无默认边 / 默认边不唯一 / 出边数量非法 /
     *                                 未定义变量引用
     */
    private String nextNodeId(GraphElement current, List<GraphElement> elements,
                              Map<String, String> variables) {
        List<GraphElement> edges = outgoingEdges(current, elements);
        if (NODE_TYPE_CONDITION.equals(current.getType())) {
            // 匹配文本 = CONDITION 节点 inputVar 指定的变量值（缺失 = 默认变量）
            String matchText = readInput(current, variables);
            // 按 elements 出现顺序逐条匹配关键词，取第一个命中（不排序，原始顺序即优先级）
            for (GraphElement edge : edges) {
                String keyword = keywordOf(edge);
                if (keyword != null && matchText.contains(keyword)) {
                    return edge.getTarget();
                }
            }
            // 未命中 → 唯一无 keyword 边为默认边；默认边不存在 → 图设计缺陷，不静默吞掉
            List<GraphElement> defaultEdges = edges.stream()
                    .filter(e -> keywordOf(e) == null)
                    .toList();
            if (defaultEdges.size() == 1) {
                return defaultEdges.get(0).getTarget();
            }
            if (defaultEdges.isEmpty()) {
                throw new GraphExecutionException(ERROR_CATEGORY_CONDITION_NO_MATCH,
                        "条件分支无匹配且无默认边: " + current.getId());
            }
            throw new GraphExecutionException(ERROR_CATEGORY_TOPOLOGY_INVALID,
                    "条件分支默认边不唯一: " + current.getId());
        }
        // 非条件节点：必须且只能有一条出边（START/END/LLM/TOOL/LOOP/JOIN；FORK 不
        // 经此方法，由 run() 路由段按全部出边扇出）
        if (edges.isEmpty()) {
            throw new GraphExecutionException(ERROR_CATEGORY_TOPOLOGY_INVALID,
                    "节点没有出边，无法继续执行: " + current.getId());
        }
        if (edges.size() > 1) {
            throw new GraphExecutionException(ERROR_CATEGORY_TOPOLOGY_INVALID,
                    "非条件节点的出边不唯一: " + current.getId());
        }
        return edges.get(0).getTarget();
    }

    /**
     * 读取边的条件关键词（执行契约键 {@code config.keyword}）：无 config / 键缺失 /
     * 值非 String / 空串均视为"无关键词"（该边为默认边候选）。
     */
    public static String keywordOf(GraphElement edge) {
        Map<String, Object> config = edge.getConfig();
        if (config == null) {
            return null;
        }
        Object keyword = config.get(CONFIG_KEY_KEYWORD);
        if (!(keyword instanceof String s) || s.isBlank()) {
            return null;
        }
        return s;
    }

    /** 当前节点的出边列表（kind=edge 且 source == 节点 id），按 elements 出现顺序 */
    private List<GraphElement> outgoingEdges(GraphElement node, List<GraphElement> elements) {
        List<GraphElement> edges = new ArrayList<>();
        for (GraphElement element : elements) {
            if ("edge".equals(element.getKind()) && node.getId().equals(element.getSource())) {
                edges.add(element);
            }
        }
        return edges;
    }

    /** 当前节点的入边数（JOIN 静态入边数 = 汇合分支数），按 target == 节点 id 统计 */
    private int incomingEdgeCount(GraphElement node, List<GraphElement> elements) {
        int count = 0;
        for (GraphElement element : elements) {
            if ("edge".equals(element.getKind()) && node.getId().equals(element.getTarget())) {
                count++;
            }
        }
        return count;
    }

    /**
     * LOOP 节点迭代上限：config.{@value #CONFIG_KEY_MAX_ITERATIONS}（Number → int；
     * 缺失/非数值 = 默认 {@value #DEFAULT_MAX_ITERATIONS}；<1 由执行前校验拦截，若被
     * 直接构造绕过校验，首次到达（计数 1）即触发"循环迭代次数超限"，fail-fast 不静默）。
     */
    private int maxIterationsOf(GraphElement node) {
        Map<String, Object> config = node.getConfig();
        if (config == null) {
            return DEFAULT_MAX_ITERATIONS;
        }
        Long parsed = parseConfigLong(config.get(CONFIG_KEY_MAX_ITERATIONS));
        if (parsed == null) {
            return DEFAULT_MAX_ITERATIONS;
        }
        return parsed.intValue();
    }

    // ==================== 内部辅助 ====================

    /** 定位唯一 START 节点（执行前校验已保证唯一，此处防御性兜底） */
    private GraphElement findStart(List<GraphElement> elements) {
        for (GraphElement element : elements) {
            if ("node".equals(element.getKind()) && NODE_TYPE_START.equals(element.getType())) {
                return element;
            }
        }
        throw new GraphExecutionException(ERROR_CATEGORY_TOPOLOGY_INVALID, "图中不存在 START 节点");
    }

    /**
     * 工具返回解码：JSON 字符串字面量（如 {@code "hello"}）解码还原为纯文本；
     * 非 JSON 字符串（如外部工具返回的裸文本/JSON 对象）原样保留，不做转换。
     */
    static String decodeIfJsonString(String result) {
        if (result == null) {
            return null;
        }
        try {
            return JSON.readValue(result, String.class);
        } catch (Exception e) {
            return result;
        }
    }

    /** 按 id 定位节点（防御：边引用了不存在的节点） */
    private GraphElement findNode(String id, List<GraphElement> elements) {
        for (GraphElement element : elements) {
            if ("node".equals(element.getKind()) && id.equals(element.getId())) {
                return element;
            }
        }
        throw new GraphExecutionException(ERROR_CATEGORY_TOPOLOGY_INVALID, "边引用了不存在的节点: " + id);
    }

    /**
     * 解析节点 config 中的数值 id（{@code agentModelConfigId} 等）。
     * <p>
     * 接受 {@link Number} 与十进制数字字符串：项目全局 Jackson 对 Long 启用
     * ToStringSerializer（防 JS 精度丢失），graph_json 落库后 id 一律是字符串形态；
     * 仅收 Number 会让任何含 LLM 节点的已发布图都无法执行（F1 阶段 1 真实联调发现）。
     * 空白或非数字 → null（由调用方决定错误语义）。
     * </p>
     */
    public static Long parseConfigLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** 读取节点 config 中的 Long 型必填键（缺失/非数值 → 运行时错误，防御性兜底） */
    private Long requireConfigId(GraphElement node, String key) {
        Map<String, Object> config = node.getConfig();
        Long parsed = config == null ? null : parseConfigLong(config.get(key));
        if (parsed == null) {
            throw new GraphExecutionException(ERROR_CATEGORY_TOPOLOGY_INVALID,
                    "节点缺少 " + key + ": " + node.getId());
        }
        return parsed;
    }

    /** 读取节点 config 中的 String 型必填键（缺失/空白 → 运行时错误，防御性兜底） */
    private String requireConfigString(GraphElement node, String key) {
        Map<String, Object> config = node.getConfig();
        if (config == null || !(config.get(key) instanceof String s) || s.isBlank()) {
            throw new GraphExecutionException(ERROR_CATEGORY_TOPOLOGY_INVALID,
                    "节点缺少 " + key + ": " + node.getId());
        }
        return s;
    }

    /**
     * 图执行运行时错误（Step8 定义 + Step10 扩展 + Step12 错误分类）：条件分支无匹配
     * 且无默认边 / 未定义变量引用 / 步数超限（疑似环路）/ 拓扑非法（出边数量、悬空引用、
     * 未知节点类型等）/ 模型或工具调用第三方异常。由执行 Service 捕获并转
     * {@code success=false} + errorMessage + errorCategory（不上抛，与 F01 run()
     * success=false 语义一致）。
     * <p>
     * <b>Step12 分类维度</b>：{@code category} 在抛出点显式携带（
     * {@link #ERROR_CATEGORY_STEP_LIMIT} 等），第三方异常（模型/工具调用）在调用点
     * 包装为带分类的实例——全部运行时失败均可结构化落库，不靠文本子串匹配。
     * 既有 1/2 参构造保留（分类 = {@link #ERROR_CATEGORY_UNKNOWN}，兼容外部直接构造）。
     * </p>
     */
    public static class GraphExecutionException extends RuntimeException {

        private final String category;

        public GraphExecutionException(String message) {
            this(ERROR_CATEGORY_UNKNOWN, message);
        }

        public GraphExecutionException(String message, Throwable cause) {
            this(ERROR_CATEGORY_UNKNOWN, message, cause);
        }

        public GraphExecutionException(String category, String message) {
            super(message);
            this.category = category;
        }

        public GraphExecutionException(String category, String message, Throwable cause) {
            super(message, cause);
            this.category = category;
        }

        /** 错误分类（Step12，落库 {@code error_category}） */
        public String getCategory() {
            return category;
        }
    }

    /**
     * 节点执行轨迹（Step12 采集，纯数据载体，无 Spring 依赖）：每次节点出队 = 一条
     * 记录。{@code nodeSeq} 为本次执行内全局步序（1-based，含 END）；{@code branchId}
     * 为并行分支标识（FORK 按出边出现顺序追加下标，"0" 为根路径；JOIN 汇合后沿用最后
     * 到达分支的 branchId；LOOP 同分支迭代 = 多条 nodeSeq 递增记录）。失败路径同样
     * 留痕：失败节点在 catch 中补录耗时与失败时点变量快照后上抛。
     */
    public static class NodeExecutionTrace {

        private final long nodeSeq;

        private final String branchId;

        private final String nodeId;

        private final String nodeType;

        private long nodeLatencyMs;

        /** 节点开始时间（出队时点；F1S2-G1 节点卡片时间展示） */
        private final LocalDateTime startTime;

        /** 节点结束时间（本步执行/路由完成时点） */
        private LocalDateTime endTime;

        /** 该节点真实输入（LLM=实际发送的用户消息；TOOL=工具入参文本） */
        private String inputText;

        /** 该节点真实输出（LLM=模型最终回答；TOOL=工具返回文本） */
        private String outputText;

        /** 节点执行状态：SUCCESS / FAILED */
        private String status = "SUCCESS";

        /** 节点失败原因（成功时为 null） */
        private String errorMessage;

        private Map<String, String> variableSnapshot;

        /** 该节点 LLM 调用的输入 Token（非 LLM 节点或供应商未返回时为 null） */
        private Long inputTokens;

        /** 该节点 LLM 调用的输出 Token（非 LLM 节点或供应商未返回时为 null） */
        private Long outputTokens;

        /** 该节点内 LLM 工具调用轨迹（阶段 2；非工具调用节点为空列表） */
        private final java.util.List<ToolCallTrace> toolCalls = new java.util.ArrayList<>();

        public NodeExecutionTrace(long nodeSeq, String branchId, String nodeId, String nodeType) {
            this.nodeSeq = nodeSeq;
            this.branchId = branchId;
            this.nodeId = nodeId;
            this.nodeType = nodeType;
            this.startTime = LocalDateTime.now();
        }

        public long getNodeSeq() {
            return nodeSeq;
        }

        public String getBranchId() {
            return branchId;
        }

        public String getNodeId() {
            return nodeId;
        }

        public String getNodeType() {
            return nodeType;
        }

        public long getNodeLatencyMs() {
            return nodeLatencyMs;
        }

        public void setNodeLatencyMs(long nodeLatencyMs) {
            this.nodeLatencyMs = nodeLatencyMs;
        }

        public LocalDateTime getStartTime() {
            return startTime;
        }

        public LocalDateTime getEndTime() {
            return endTime;
        }

        public void setEndTime(LocalDateTime endTime) {
            this.endTime = endTime;
        }

        public String getInputText() {
            return inputText;
        }

        public void setInputText(String inputText) {
            this.inputText = inputText;
        }

        public String getOutputText() {
            return outputText;
        }

        public void setOutputText(String outputText) {
            this.outputText = outputText;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        public Map<String, String> getVariableSnapshot() {
            return variableSnapshot;
        }

        public void setVariableSnapshot(Map<String, String> variableSnapshot) {
            this.variableSnapshot = variableSnapshot;
        }

        public Long getInputTokens() {
            return inputTokens;
        }

        public void setInputTokens(Long inputTokens) {
            this.inputTokens = inputTokens;
        }

        public Long getOutputTokens() {
            return outputTokens;
        }

        public void setOutputTokens(Long outputTokens) {
            this.outputTokens = outputTokens;
        }

        public java.util.List<ToolCallTrace> getToolCalls() {
            return toolCalls;
        }

        public void addToolCall(ToolCallTrace toolCall) {
            this.toolCalls.add(toolCall);
        }
    }

    /** LLM 节点工具调用轨迹（阶段 2：执行轨迹里可看到工具输入与输出） */
    public static class ToolCallTrace {

        private final String tool;

        private final String arguments;

        private final String result;

        public ToolCallTrace(String tool, String arguments, String result) {
            this.tool = tool;
            this.arguments = arguments;
            this.result = result;
        }

        public String getTool() {
            return tool;
        }

        public String getArguments() {
            return arguments;
        }

        public String getResult() {
            return result;
        }
    }

    /** 活跃执行点（Step11 多活跃点模型 + Step12 分支路径）：节点 id + 分支路径 */
    private static class ActiveExecutionPoint {

        private final String nodeId;

        private final String branchPath;

        ActiveExecutionPoint(String nodeId, String branchPath) {
            this.nodeId = nodeId;
            this.branchPath = branchPath;
        }
    }
}
