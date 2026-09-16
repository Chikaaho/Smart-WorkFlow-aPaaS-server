package com.sw.ck.agent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.agent.dto.AgentOrchestrationRunReqDTO;
import com.sw.ck.agent.dto.AgentOrchestrationRunRespDTO;
import com.sw.ck.agent.entity.AgentMessage;
import com.sw.ck.agent.entity.AgentModelConfig;
import com.sw.ck.agent.entity.AgentSession;
import com.sw.ck.agent.entity.AgentToolCallLog;
import com.sw.ck.agent.mapper.AgentMessageMapper;
import com.sw.ck.agent.mapper.AgentModelConfigMapper;
import com.sw.ck.agent.mapper.AgentSessionMapper;
import com.sw.ck.agent.mapper.AgentToolCallLogMapper;
import com.sw.ck.agent.orchestration.AgentGraphFactory;
import com.sw.ck.agent.orchestration.AgentToolCallbackFactory;
import com.sw.ck.agent.orchestration.ChatModelFactory;
import com.sw.ck.agent.orchestration.ToolCallRecord;
import com.sw.ck.agent.service.AgentOrchestrationService;
import com.sw.ck.common.crypto.AesGcmCipher;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 编排执行引擎 Service 实现（M07 Step2）。
 * <p>
 * <b>明文 API Key 生命周期最短化（方案 §10 约束 2）</b>：解密出的明文 Key 仅存在于
 * 局部变量，用于当次 {@code ChatModelFactory.build}，不进日志、不进异常消息、不进
 * 响应 DTO 任何字段，方法结束前置 null 释放引用（延续 Step1 同款惯例）。
 * </p>
 * <p>
 * <b>异常处理双保险（依据 langgraph4j 1.5.14 实测）</b>：节点动作抛异常时
 * {@code CompiledGraph.invoke()} 以 {@code CompletionException}（CompletableFuture.join
 * 包装）原样抛出、不会返回空 Optional；为稳妥同时兜底"invoke 返回空 Optional /
 * 状态缺 output"两种情况，均转 {@code success=false}。模型服务不可达/超时等经
 * Spring AI RetryTemplate 重试耗尽后抛出，同样转 {@code success=false}，不抛 500。
 * </p>
 * <p>
 * <b>多Key轮询/额度限流（M07-Step5）</b>：同一 {@code groupKey} 的配置归为候选池，
 * 高优先级 Key 遇限流（HTTP 429）时按 {@code sort} 升序切换下一候选重试；限流触发即
 * 锁定当前配置（{@code lockedUntil = now + quotaCooldownSeconds}，惰性过期，无清理任务）。
 * 切换重试循环受 {@code triedIds} 去重约束保证终止（组内成员有限）；{@code groupKey}
 * 为 null 的独立配置行为与 Step2-4 完全一致（遇限流直接失败，零回归）。
 * </p>
 */
@Service
public class AgentOrchestrationServiceImpl implements AgentOrchestrationService {

    private final AgentModelConfigMapper mapper;
    private final AesGcmCipher cipher;
    private final ChatModelFactory chatModelFactory;
    private final CompiledGraph<AgentState> agentCompiledGraph;

    /**
     * M07 Step3 工具沙箱工厂（可选注入）。{@code required = false}：保留 Step2 的
     * 4 参直构路径（既有测试直接 new，不注入工厂时行为与 Step2 完全一致——不加载
     * 工具、不绑定、不清理）；生产环境由 Spring 注入（{@code sw.agent.enabled=true}
     * 时 {@code AgentToolCallbackFactory} 为 Bean）。
     */
    @Autowired(required = false)
    private AgentToolCallbackFactory agentToolCallbackFactory;

    /** M07 Step4 F04 会话主表 Mapper（字段注入：保留 4 参直构路径，既有测试不受影响） */
    @Autowired
    private AgentSessionMapper sessionMapper;

    /** M07 Step4 F04 会话消息明细 Mapper */
    @Autowired
    private AgentMessageMapper messageMapper;

    /** M07 Step4 F04 工具调用日志 Mapper */
    @Autowired
    private AgentToolCallLogMapper toolCallLogMapper;

    /** 会话状态常量：ACTIVE（方案 §3：状态写死，会话永久有效） */
    private static final String SESSION_STATUS_ACTIVE = "ACTIVE";

    /** 消息角色：USER（用户输入） */
    private static final String ROLE_USER = "USER";

    /** 消息角色：ASSISTANT（模型最终回复） */
    private static final String ROLE_ASSISTANT = "ASSISTANT";

    /** 限流锁定冷却时长默认值（秒）：与 V24 列默认值 60 一致，防御手动 new 实体时字段为 null */
    private static final int DEFAULT_QUOTA_COOLDOWN_SECONDS = 60;

    public AgentOrchestrationServiceImpl(AgentModelConfigMapper mapper,
                                         AesGcmCipher cipher,
                                         ChatModelFactory chatModelFactory,
                                         CompiledGraph<AgentState> agentCompiledGraph) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.chatModelFactory = chatModelFactory;
        this.agentCompiledGraph = agentCompiledGraph;
    }

    @Override
    public AgentOrchestrationRunRespDTO run(AgentOrchestrationRunReqDTO req) {
        // 参数校验（DTO 层无 bean-validation：模块类路径无 jakarta.validation-api，
        // 沿用 Step1 Service 层手动校验惯例）
        if (req == null || req.getAgentModelConfigId() == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "agentModelConfigId 不能为空");
        }
        if (req.getInput() == null || req.getInput().isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "input 不能为空");
        }
        // baseMapper.selectById 经租户拦截器自动过滤 tenant_id（Step1 同款）；不存在 → 404 语义
        AgentModelConfig entity = mapper.selectById(req.getAgentModelConfigId());
        if (entity == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND);
        }

        long start = System.currentTimeMillis();
        AgentOrchestrationRunRespDTO resp = new AgentOrchestrationRunRespDTO();
        // M07-Step5 多Key轮询：候选切换重试循环。triedIds 累积已试配置 id，保证组内
        // 每条候选最多尝试一次（组内成员有限，循环必然终止，无无限重试）。
        Set<Long> triedIds = new HashSet<>();
        AgentModelConfig currentConfig = entity;
        // 会话 id 在循环外解析一次：候选切换复用同一会话，不重复创建；首次尝试时若
        // 配置非法（ChatModelFactory 抛 IllegalArgumentException）则解析尚未执行，不落脏数据
        Long sessionId = req.getSessionId();
        List<AgentMessage> dbMessages = null;
        boolean sessionResolved = false;
        String plainApiKey = null;
        try {
            while (true) {
                triedIds.add(currentConfig.getId());
                // 解密当前候选的明文 Key：仅用于本次构造 ChatModel，切换候选后重新解密
                // 对应 apiKeyCipher（解密异常直接上抛，与 Step2-4 行为一致）
                plainApiKey = null;
                if (currentConfig.getApiKeyCipher() != null && !currentConfig.getApiKeyCipher().isEmpty()) {
                    plainApiKey = cipher.decrypt(currentConfig.getApiKeyCipher());
                }
                try {
                    ChatModel chatModel = chatModelFactory.build(currentConfig, plainApiKey);
                    // M07 Step4 F04：会话获取/创建 + 历史消息加载，仅首次尝试执行（在
                    // chatModel 构造之后：配置非法时 ChatModelFactory 抛
                    // IllegalArgumentException，不落会话脏数据）
                    if (!sessionResolved) {
                        if (sessionId == null) {
                            AgentSession session = new AgentSession();
                            session.setAgentModelConfigId(req.getAgentModelConfigId());
                            session.setStatus(SESSION_STATUS_ACTIVE);
                            // id（雪花 ASSIGN_ID）/createTime/createBy/tenantId/deleted/version
                            // 由 MyBatis-Plus + CommonMetaObjectHandler 填充
                            sessionMapper.insert(session);
                            sessionId = session.getId();
                            dbMessages = List.of();
                        } else {
                            // selectById 经租户拦截器自动过滤 tenant_id：跨租户/已删除/不存在会话 → null → 404 语义
                            AgentSession existing = sessionMapper.selectById(sessionId);
                            if (existing == null) {
                                throw new BaseException(CommonErrorCode.NOT_FOUND, "会话不存在");
                            }
                            dbMessages = loadHistoryMessages(sessionId);
                        }
                        sessionResolved = true;
                    }
                    // 历史消息经 ThreadLocal 注入 callModel 节点（与 chatModel/tools 同款绑定模式）
                    AgentGraphFactory.bindHistoryMessages(toSpringAiMessages(dbMessages));
                    AgentGraphFactory.bindToolCallRecords(new ArrayList<>());
                    // M07 Step3：加载本租户启用的工具白名单 → 绑定到图执行线程（无工具/工厂
                    // 未注入时跳过，Prompt 构造与 Step2 完全一致）；租户隔离由租户拦截器自动完成
                    // （buildToolCallbacks(null) 不显式过滤，同 Step2 selectById 先例）
                    List<ToolCallback> tools = agentToolCallbackFactory == null
                            ? List.of()
                            : agentToolCallbackFactory.buildToolCallbacks(null);
                    AgentGraphFactory.bindChatModel(chatModel);
                    if (!tools.isEmpty()) {
                        AgentGraphFactory.bindTools(tools);
                    }
                    try {
                        Optional<AgentState> result = agentCompiledGraph.invoke(
                                Map.of("input", req.getInput(), "chatModel", chatModel));
                        if (result.isEmpty()) {
                            // 兜底：invoke 返回空 Optional（实测正常路径不会发生）
                            resp.setSuccess(false);
                            resp.setErrorMessage("编排引擎执行未产生结果");
                        } else {
                            Optional<Object> output = result.get().value("output");
                            if (output.isEmpty()) {
                                resp.setSuccess(false);
                                resp.setErrorMessage("编排引擎执行未产生输出");
                            } else {
                                String outputText = String.valueOf(output.get());
                                resp.setSuccess(true);
                                resp.setOutput(outputText);
                                // M07-Step5：记录实际服务本次请求的配置 id（轮询切换后可能
                                // 与请求携带的 agentModelConfigId 不同，便于排查/审计）
                                resp.setUsedModelConfigId(currentConfig.getId());
                                // M07-F04-02: 提取 usage 数据（从 ThreadLocal 读取）
                                AgentGraphFactory.UsageSnapshot usageSnapshot = AgentGraphFactory.getTokenUsage();
                                Long inputTokens = usageSnapshot != null ? usageSnapshot.inputTokens() : null;
                                Long outputTokens = usageSnapshot != null ? usageSnapshot.outputTokens() : null;
                                // M07 Step4 F04：持久化本轮 USER + ASSISTANT 消息（msg_order =
                                // 已有消息数，0-based 单调递增）与工具调用日志，并回传会话 id
                                int nextOrder = dbMessages.size();
                                insertMessage(sessionId, ROLE_USER, req.getInput(), nextOrder);
                                insertMessage(sessionId, ROLE_ASSISTANT, outputText, nextOrder + 1,
                                        inputTokens, outputTokens);
                                persistToolCallLogs(sessionId);
                                resp.setSessionId(sessionId);
                            }
                        }
                    } finally {
                        // bind/clear 对称：正常完成与异常完成（invoke 抛异常）均执行清除，防 ThreadLocal 泄漏
                        AgentGraphFactory.clearChatModel();
                        AgentGraphFactory.clearTools();
                        AgentGraphFactory.clearHistoryMessages();
                        AgentGraphFactory.clearToolCallRecords();
                        AgentGraphFactory.clearTokenUsage();
                    }
                    // 成功路径（含 invoke 空结果兜底分支）均跳出重试循环，不再尝试其他候选
                    break;
                } catch (IllegalArgumentException e) {
                    // 协议不支持/配置非法：ChatModelFactory 拒绝构造 → success=false，不触发
                    // 切换（配置静态错误而非运行时可恢复的限流状态，切换意义不大且会掩盖配置问题）
                    resp.setSuccess(false);
                    resp.setErrorMessage(summarizeError(e));
                    break;
                } catch (BaseException e) {
                    // 业务异常（如会话不存在）保持上抛，由全局异常处理器转 404 语义，不吞为 success=false
                    throw e;
                } catch (Exception e) {
                    if (isQuotaExceededException(e) && currentConfig.getGroupKey() != null) {
                        // M07-Step5：限流 → 锁定当前配置（冷却期）→ 切换组内下一候选重试
                        LocalDateTime now = LocalDateTime.now();
                        int cooldownSeconds = currentConfig.getQuotaCooldownSeconds() == null
                                ? DEFAULT_QUOTA_COOLDOWN_SECONDS
                                : currentConfig.getQuotaCooldownSeconds();
                        currentConfig.setLockedUntil(now.plusSeconds(cooldownSeconds));
                        lockCurrentConfig(currentConfig);
                        AgentModelConfig next = findNextCandidate(currentConfig.getGroupKey(), triedIds, now);
                        if (next != null) {
                            currentConfig = next;
                            continue;   // 回到循环顶部：重建 ChatModel + 重新解密下一候选 Key
                        }
                    }
                    // 非限流异常，或限流但无候选可切（含 groupKey=null 的独立配置）：
                    // 按 Step2-4 原有行为失败（success=false + 异常摘要）
                    resp.setSuccess(false);
                    resp.setErrorMessage(summarizeError(e));
                    break;
                }
            }
        } finally {
            plainApiKey = null;
        }
        resp.setLatencyMs(System.currentTimeMillis() - start);
        return resp;
    }

    /**
     * 加载会话历史消息（按 msg_order 升序；租户隔离由租户拦截器自动完成，
     * 与模块全部查询同路径）。
     */
    private List<AgentMessage> loadHistoryMessages(Long sessionId) {
        return messageMapper.selectList(
                Wrappers.<AgentMessage>lambdaQuery()
                        .eq(AgentMessage::getSessionId, sessionId)
                        .orderByAsc(AgentMessage::getMsgOrder));
    }

    /**
     * DB 消息 → Spring AI {@link Message} 列表（USER → {@link UserMessage}，
     * ASSISTANT → {@link AssistantMessage}；其他角色留后续迭代，跳过）。
     * 入参已按 msg_order 升序，转换后顺序保持。
     */
    private List<Message> toSpringAiMessages(List<AgentMessage> dbMessages) {
        List<Message> messages = new ArrayList<>(dbMessages.size());
        for (AgentMessage db : dbMessages) {
            if (ROLE_USER.equals(db.getRole())) {
                messages.add(new UserMessage(db.getContent()));
            } else if (ROLE_ASSISTANT.equals(db.getRole())) {
                messages.add(new AssistantMessage(db.getContent()));
            }
        }
        return messages;
    }

    /** 写入一条会话消息（msg_order 由调用方计算，0-based 单调递增） */
    private void insertMessage(Long sessionId, String role, String content, int msgOrder) {
        insertMessage(sessionId, role, content, msgOrder, null, null);
    }

    /** 写入一条会话消息（含 token usage，M07-F04-02） */
    private void insertMessage(Long sessionId, String role, String content, int msgOrder,
                               Long inputTokens, Long outputTokens) {
        AgentMessage msg = new AgentMessage();
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setMsgOrder(msgOrder);
        msg.setInputTokens(inputTokens);
        msg.setOutputTokens(outputTokens);
        messageMapper.insert(msg);
    }

    /** 将本轮捕获的工具调用记录批量落库（无记录时为空操作） */
    private void persistToolCallLogs(Long sessionId) {
        List<ToolCallRecord> records = AgentGraphFactory.getToolCallRecords();
        if (records == null || records.isEmpty()) {
            return;
        }
        for (ToolCallRecord record : records) {
            AgentToolCallLog log = new AgentToolCallLog();
            log.setSessionId(sessionId);
            log.setToolName(record.getToolName());
            log.setToolCallArgs(record.getArgs());
            log.setToolCallResult(record.getResult());
            log.setLatencyMs(record.getLatencyMs());
            toolCallLogMapper.insert(log);
        }
    }

    /**
     * M07-Step5 限流异常识别（§5 V1 实测，spike 测试 ChatModelFactory429SpikeTest）：
     * Spring AI 1.0.4 默认错误处理器（RetryUtils DEFAULT_RESPONSE_ERROR_HANDLER）对
     * 4xx 直接抛 {@link NonTransientAiException}，消息格式固定为 "&lt;status&gt; - &lt;body&gt;"
     * （实测 "429 - too many requests"），cause 链中不存在 RestClientResponseException。
     * 沿 cause 链逐层检查（穿透 langgraph4j CompletionException 等包装层），并保留
     * RestClientResponseException 状态码判断兜底（未来版本/非 Spring AI 路径）。
     * <p>
     * P61：Spring AI 1.0.4 的 {@code NonTransientAiException} 不暴露结构化状态码，
     * 因此这里对<b>已实测固定的协议格式</b>做锚定解析——只接受以状态码开头的消息，
     * 响应体内部出现数字串不会误判。该第三方格式依赖登记在
     * {@code docs/governance/error-code-catalog.md}「第三方格式依赖」。
     * </p>
     */
    private boolean isQuotaExceededException(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof NonTransientAiException e && statusCodeOf(e.getMessage()) == 429) {
                return true;
            }
            if (cur instanceof RestClientResponseException rcre
                    && rcre.getStatusCode().value() == 429) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * 解析 Spring AI 异常消息开头的 HTTP 状态码（格式 {@code "<status> - <body>"}）。
     *
     * @return 状态码；消息不是以合法状态码开头时返回 {@code -1}
     */
    private static int statusCodeOf(String message) {
        if (message == null) {
            return -1;
        }
        String trimmed = message.stripLeading();
        int i = 0;
        while (i < trimmed.length() && Character.isDigit(trimmed.charAt(i)) && i < 4) {
            i++;
        }
        if (i == 0 || i >= trimmed.length() || trimmed.charAt(i) != ' ') {
            return -1;
        }
        return Integer.parseInt(trimmed.substring(0, i));
    }

    /**
     * M07-Step5 锁定当前配置：仅更新 locked_until 列（lambdaUpdate 局部更新，避免整行
     * 覆盖并发修改；租户隔离由租户拦截器自动完成）。
     */
    private void lockCurrentConfig(AgentModelConfig config) {
        mapper.update(null,
                Wrappers.<AgentModelConfig>lambdaUpdate()
                        .eq(AgentModelConfig::getId, config.getId())
                        .set(AgentModelConfig::getLockedUntil, config.getLockedUntil()));
    }

    /**
     * M07-Step5 组内下一候选：同 groupKey、enabled=1（数字字面量，74fc415 先例——SMALLINT
     * 列禁止 Boolean 参数比对）、未锁定（locked_until 为 null 或已过期，惰性过期判断，
     * 无主动清理任务）、未试过（excludeIds 排除，含当前失败配置自身），按 sort 升序 +
     * id 升序（同 sort 时确定性）取第一条；无候选返回 null。租户隔离由租户拦截器自动完成。
     */
    private AgentModelConfig findNextCandidate(String groupKey, Set<Long> excludeIds, LocalDateTime now) {
        List<AgentModelConfig> candidates = mapper.selectList(
                Wrappers.<AgentModelConfig>lambdaQuery()
                        .eq(AgentModelConfig::getGroupKey, groupKey)
                        .eq(AgentModelConfig::getEnabled, 1)
                        .notIn(!excludeIds.isEmpty(), AgentModelConfig::getId, excludeIds)
                        .and(w -> w.isNull(AgentModelConfig::getLockedUntil)
                                .or().le(AgentModelConfig::getLockedUntil, now))
                        .orderByAsc(AgentModelConfig::getSort)
                        .orderByAsc(AgentModelConfig::getId));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * 异常摘要：沿 cause 链取最深层非空 message（如 CompletionException →
     * ExecutionException → IllegalStateException("...") 取节点真实原因；连接拒绝取
     * ResourceAccessException 的 I/O 描述）。只取 message，不含堆栈，杜绝明文 Key
     * 通过异常信息泄漏（方案 §12 风险表）。
     */
    private String summarizeError(Throwable t) {
        // P61：按异常类型分层——平台业务异常与编排自身状态失败保留可定位文案，
        // 传输/服务商类异常替换为安全结论并写日志（见 AgentFailureSummarizer）。
        return com.sw.ck.agent.orchestration.AgentFailureSummarizer.summarize(t);
    }
}
