package com.sw.ck.agent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.agent.dto.AgentOrchestrationRunReqDTO;
import com.sw.ck.agent.dto.AgentOrchestrationRunRespDTO;
import com.sw.ck.agent.entity.AgentMessage;
import com.sw.ck.agent.entity.AgentModel;
import com.sw.ck.agent.entity.AgentSession;
import com.sw.ck.agent.entity.AgentToolCallLog;
import com.sw.ck.agent.mapper.AgentMessageMapper;
import com.sw.ck.agent.mapper.AgentSessionMapper;
import com.sw.ck.agent.mapper.AgentToolCallLogMapper;
import com.sw.ck.agent.orchestration.AgentGraphFactory;
import com.sw.ck.agent.orchestration.AgentModelRuntimeService;
import com.sw.ck.agent.orchestration.AgentToolCallbackFactory;
import com.sw.ck.agent.orchestration.ToolCallRecord;
import com.sw.ck.agent.service.AgentOrchestrationService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.exception.HttpException;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 编排执行引擎 Service 实现（M07 Step2 → F1 阶段 1 LangChain4j 化）。
 * <p>
 * 模型解析/装配/单例复用/启停校验全部下沉到 {@link AgentModelRuntimeService}
 * （D022-04）；多 Key 轮换（D022-05）由运行时服务承接并继续在本入口生效。
 * </p>
 * <p>
 * <b>异常处理双保险</b>：节点动作抛异常时 {@code CompiledGraph.invoke()} 以
 * {@code CompletionException} 原样抛出；同时兜底"invoke 返回空 Optional / 状态缺
 * output"，均转 {@code success=false}，不抛 500。
 * </p>
 * <p>
 * <b>多Key轮换/额度限流（M07-Step5 → D022-05）</b>：同一 {@code groupKey} 的模型归为
 * 候选池，遇限流（HTTP 429）时按 {@code sort} 升序切换下一候选重试；限流触发即锁定
 * （{@code lockedUntil = now + quotaCooldownSeconds}，惰性过期）。{@code groupKey}
 * 为 null 的独立模型行为不变（遇限流直接失败）。
 * </p>
 */
@Service
public class AgentOrchestrationServiceImpl implements AgentOrchestrationService {

    private final AgentModelRuntimeService modelRuntimeService;
    private final CompiledGraph<AgentState> agentCompiledGraph;

    /** 可选注入（同 M07 惯例）：{@code sw.agent.enabled} 未开启时为 null，不加载工具 */
    @Autowired(required = false)
    private AgentToolCallbackFactory agentToolCallbackFactory;

    @Autowired
    private AgentSessionMapper sessionMapper;

    @Autowired
    private AgentMessageMapper messageMapper;

    @Autowired
    private AgentToolCallLogMapper toolCallLogMapper;

    /** 会话状态常量：ACTIVE（状态写死，会话永久有效） */
    private static final String SESSION_STATUS_ACTIVE = "ACTIVE";

    private static final String ROLE_USER = "USER";

    private static final String ROLE_ASSISTANT = "ASSISTANT";

    public AgentOrchestrationServiceImpl(AgentModelRuntimeService modelRuntimeService,
                                         CompiledGraph<AgentState> agentCompiledGraph) {
        this.modelRuntimeService = modelRuntimeService;
        this.agentCompiledGraph = agentCompiledGraph;
    }

    @Override
    public AgentOrchestrationRunRespDTO run(AgentOrchestrationRunReqDTO req) {
        if (req == null || req.getAgentModelConfigId() == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "agentModelConfigId 不能为空");
        }
        if (req.getInput() == null || req.getInput().isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "input 不能为空");
        }
        long start = System.currentTimeMillis();
        AgentOrchestrationRunRespDTO resp = new AgentOrchestrationRunRespDTO();
        // D022-05：候选切换重试循环（triedIds 去重保证终止）
        Set<Long> triedIds = new HashSet<>();
        Long currentModelId = req.getAgentModelConfigId();
        Long sessionId = req.getSessionId();
        List<AgentMessage> dbMessages = null;
        boolean sessionResolved = false;
        while (true) {
            triedIds.add(currentModelId);
            // 运行时服务：启停校验 + LangChain4j 单例复用 + 修订失效重建
            AgentModelRuntimeService.ModelInstance instance = modelRuntimeService.acquire(currentModelId);
            try {
                // 会话获取/创建 + 历史消息加载，仅首次尝试执行
                if (!sessionResolved) {
                    if (sessionId == null) {
                        AgentSession session = new AgentSession();
                        session.setAgentModelConfigId(req.getAgentModelConfigId());
                        session.setStatus(SESSION_STATUS_ACTIVE);
                        sessionMapper.insert(session);
                        sessionId = session.getId();
                        dbMessages = List.of();
                    } else {
                        AgentSession existing = sessionMapper.selectById(sessionId);
                        if (existing == null) {
                            throw new BaseException(CommonErrorCode.NOT_FOUND, "会话不存在");
                        }
                        dbMessages = loadHistoryMessages(sessionId);
                    }
                    sessionResolved = true;
                }
                AgentGraphFactory.bindHistoryMessages(toLc4jMessages(dbMessages));
                AgentGraphFactory.bindToolCallRecords(new ArrayList<>());
                List<AgentToolCallbackFactory.ToolHandle> tools = agentToolCallbackFactory == null
                        ? List.of()
                        : agentToolCallbackFactory.buildToolCallbacks(null);
                AgentGraphFactory.bindChatModel(instance.chatModel());
                if (!tools.isEmpty()) {
                    AgentGraphFactory.bindTools(tools);
                }
                try {
                    Optional<AgentState> result = agentCompiledGraph.invoke(
                            Map.of("input", req.getInput(), "chatModel", instance.chatModel()));
                    if (result.isEmpty()) {
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
                            // 记录实际服务本次请求的模型 id（轮换切换后可能与请求 id 不同）
                            resp.setUsedModelConfigId(currentModelId);
                            AgentGraphFactory.UsageSnapshot usageSnapshot = AgentGraphFactory.getTokenUsage();
                            Long inputTokens = usageSnapshot != null ? usageSnapshot.inputTokens() : null;
                            Long outputTokens = usageSnapshot != null ? usageSnapshot.outputTokens() : null;
                            int nextOrder = dbMessages.size();
                            insertMessage(sessionId, ROLE_USER, req.getInput(), nextOrder);
                            insertMessage(sessionId, ROLE_ASSISTANT, outputText, nextOrder + 1,
                                    inputTokens, outputTokens);
                            persistToolCallLogs(sessionId);
                            resp.setSessionId(sessionId);
                        }
                    }
                } finally {
                    AgentGraphFactory.clearChatModel();
                    AgentGraphFactory.clearTools();
                    AgentGraphFactory.clearHistoryMessages();
                    AgentGraphFactory.clearToolCallRecords();
                    AgentGraphFactory.clearTokenUsage();
                }
                break;
            } catch (BaseException e) {
                // 业务异常（会话不存在等）保持上抛
                throw e;
            } catch (Exception e) {
                if (isQuotaExceededException(e) && instance.model().getGroupKey() != null) {
                    // D022-05：限流 → 锁定当前模型 → 切换组内下一候选重试
                    modelRuntimeService.lock(currentModelId, modelRuntimeService.cooldownSeconds(instance.model()));
                    AgentModel next = modelRuntimeService.nextCandidate(
                            instance.model().getGroupKey(), triedIds, LocalDateTime.now());
                    if (next != null) {
                        currentModelId = next.getId();
                        continue;
                    }
                }
                resp.setSuccess(false);
                resp.setErrorMessage(com.sw.ck.agent.orchestration.AgentFailureSummarizer.summarize(e));
                break;
            }
        }
        resp.setLatencyMs(System.currentTimeMillis() - start);
        return resp;
    }

    private List<AgentMessage> loadHistoryMessages(Long sessionId) {
        return messageMapper.selectList(
                Wrappers.<AgentMessage>lambdaQuery()
                        .eq(AgentMessage::getSessionId, sessionId)
                        .orderByAsc(AgentMessage::getMsgOrder));
    }

    /** DB 消息 → LangChain4j {@link ChatMessage} 列表（顺序保持） */
    private List<ChatMessage> toLc4jMessages(List<AgentMessage> dbMessages) {
        List<ChatMessage> messages = new ArrayList<>(dbMessages.size());
        for (AgentMessage db : dbMessages) {
            if (ROLE_USER.equals(db.getRole())) {
                messages.add(dev.langchain4j.data.message.UserMessage.from(db.getContent()));
            } else if (ROLE_ASSISTANT.equals(db.getRole())) {
                messages.add(dev.langchain4j.data.message.AiMessage.from(db.getContent()));
            }
        }
        return messages;
    }

    private void insertMessage(Long sessionId, String role, String content, int msgOrder) {
        insertMessage(sessionId, role, content, msgOrder, null, null);
    }

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
     * 限流异常识别（LangChain4j 版）：沿 cause 链逐层检查 {@link HttpException}
     * 的 {@code statusCode() == 429}（穿透 langgraph4j CompletionException 等包装层）。
     */
    private boolean isQuotaExceededException(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof HttpException http && http.statusCode() == 429) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }
}
