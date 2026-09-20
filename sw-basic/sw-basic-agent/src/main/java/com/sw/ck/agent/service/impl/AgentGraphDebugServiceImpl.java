package com.sw.ck.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.agent.dto.AgentGraphDebugNodeDTO;
import com.sw.ck.agent.dto.AgentGraphDebugSessionDTO;
import com.sw.ck.agent.dto.graph.GraphElement;
import com.sw.ck.agent.dto.graph.ProcessGraph;
import com.sw.ck.agent.entity.AgentGraphDebugNode;
import com.sw.ck.agent.entity.AgentGraphDebugSession;
import com.sw.ck.agent.entity.AgentGraphDef;
import com.sw.ck.agent.entity.AgentModelConfig;
import com.sw.ck.agent.entity.tool.AgentToolExternalConfig;
import com.sw.ck.agent.entity.tool.AgentToolInternalConfig;
import com.sw.ck.agent.mapper.AgentGraphDebugNodeMapper;
import com.sw.ck.agent.mapper.AgentGraphDebugSessionMapper;
import com.sw.ck.agent.mapper.AgentGraphDefMapper;
import com.sw.ck.agent.mapper.AgentModelConfigMapper;
import com.sw.ck.agent.mapper.tool.AgentToolExternalConfigMapper;
import com.sw.ck.agent.mapper.tool.AgentToolInternalConfigMapper;
import com.sw.ck.agent.orchestration.AgentGraphDebugEngine;
import com.sw.ck.agent.orchestration.AgentGraphInterpreter;
import com.sw.ck.agent.orchestration.AgentToolCallbackFactory;
import com.sw.ck.agent.orchestration.ChatModelFactory;
import com.sw.ck.agent.service.AgentGraphDebugService;
import com.sw.ck.common.crypto.AesGcmCipher;
import com.sw.ck.common.datascope.DataScopeFilter;
import com.sw.ck.common.datascope.DeptScopeProvider;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.security.LoginContextProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图调试服务实现（M07-F02-04 单步调试）。
 */
@Service
public class AgentGraphDebugServiceImpl implements AgentGraphDebugService {

    private static final String STATUS_PUBLISHED = "PUBLISHED";
    private static final String STATUS_PAUSED = "PAUSED";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_STOPPED = "STOPPED";
    private static final String STATUS_EXPIRED = "EXPIRED";

    private static final int EXPIRE_MINUTES = 30;

    private final ObjectMapper objectMapper;
    private final AgentGraphDefMapper graphDefMapper;
    private final AgentGraphDebugSessionMapper sessionMapper;
    private final AgentGraphDebugNodeMapper debugNodeMapper;
    private final AgentModelConfigMapper modelConfigMapper;
    private final AgentToolInternalConfigMapper internalToolMapper;
    private final AgentToolExternalConfigMapper externalToolMapper;
    private final ChatModelFactory chatModelFactory;
    private final AesGcmCipher cipher;
    private final LoginContextProvider loginContextProvider;
    private final DeptScopeProvider deptScopeProvider;

    @Autowired(required = false)
    private AgentToolCallbackFactory agentToolCallbackFactory;

    public AgentGraphDebugServiceImpl(ObjectMapper objectMapper,
                                      AgentGraphDefMapper graphDefMapper,
                                      AgentGraphDebugSessionMapper sessionMapper,
                                      AgentGraphDebugNodeMapper debugNodeMapper,
                                      AgentModelConfigMapper modelConfigMapper,
                                      AgentToolInternalConfigMapper internalToolMapper,
                                      AgentToolExternalConfigMapper externalToolMapper,
                                      ChatModelFactory chatModelFactory,
                                      AesGcmCipher cipher,
                                      LoginContextProvider loginContextProvider,
                                      DeptScopeProvider deptScopeProvider) {
        this.objectMapper = objectMapper;
        this.graphDefMapper = graphDefMapper;
        this.sessionMapper = sessionMapper;
        this.debugNodeMapper = debugNodeMapper;
        this.modelConfigMapper = modelConfigMapper;
        this.internalToolMapper = internalToolMapper;
        this.externalToolMapper = externalToolMapper;
        this.chatModelFactory = chatModelFactory;
        this.cipher = cipher;
        this.loginContextProvider = loginContextProvider;
        this.deptScopeProvider = deptScopeProvider;
    }

    // ==================== 创建会话 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentGraphDebugSessionDTO createSession(Long graphDefId, String input) {
        if (input == null || input.isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "input 不能为空");
        }
        AgentGraphDef def = requireEntity(graphDefId);
        if (!STATUS_PUBLISHED.equals(def.getStatus())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "图未发布，无法调试");
        }
        ProcessGraph graph = parseGraph(def.getGraphJson());
        if (graph == null || graph.getElements() == null || graph.getElements().isEmpty()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "图数据为空，无法调试");
        }
        Map<Long, AgentModelConfig> modelConfigs = validateForExecution(graph);

        int maxSteps = computeMaxSteps(graph);
        AgentGraphDebugEngine engine = new AgentGraphDebugEngine(graph, input, maxSteps,
                chatModelFactory, agentToolCallbackFactory, modelConfigs, cipher,
                loginContextProvider.getTenantId());

        AgentGraphDebugSession session = new AgentGraphDebugSession();
        session.setGraphDefId(def.getId());
        session.setGraphDefVersion(def.getDefVersion());
        session.setGraphJson(def.getGraphJson());
        session.setStatus(STATUS_PAUSED);
        session.setInput(input);
        session.setBreakpoints("[]");
        try {
            session.setStateJson(engine.serializeState(objectMapper));
        } catch (Exception e) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "调试状态序列化失败: " + e.getMessage());
        }
        session.setExpiresAt(LocalDateTime.now().plusMinutes(EXPIRE_MINUTES));
        sessionMapper.insert(session);

        return toDTO(session, engine, 0);
    }

    // ==================== 查询会话 ====================

    @Override
    public AgentGraphDebugSessionDTO getSession(Long sessionId) {
        AgentGraphDebugSession session = requireSession(sessionId);
        expireIfNeeded(session);
        // 重新加载以获取可能更新的过期状态
        if (STATUS_EXPIRED.equals(session.getStatus())) {
            // 已在 expireIfNeeded 中更新
        }
        // 需要 engine 来解析 variables/nextNodeId
        return toDTOWithState(session);
    }

    // ==================== 单步 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentGraphDebugSessionDTO step(Long sessionId, Long expectedVersion) {
        AgentGraphDebugSession session = requireSession(sessionId);
        expireIfNeeded(session);
        if (session.getStatus() != null && !STATUS_PAUSED.equals(session.getStatus())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "会话已终结，无法继续");
        }
        if (expectedVersion != null && !expectedVersion.equals(session.getVersion())) {
            throw new BaseException(409, "并发冲突，请重试");
        }
        if (session.getStateJson() == null || session.getStateJson().isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "调试状态为空，无法继续");
        }

        ProcessGraph graph = parseGraph(session.getGraphJson());
        Map<Long, AgentModelConfig> modelConfigs = validateForExecution(graph);
        int maxSteps = computeMaxSteps(graph);

        AgentGraphDebugEngine engine;
        try {
            engine = AgentGraphDebugEngine.deserializeState(session.getStateJson(), objectMapper,
                    graph, maxSteps, chatModelFactory, agentToolCallbackFactory,
                    modelConfigs, cipher, loginContextProvider.getTenantId());
        } catch (Exception e) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "调试状态反序列化失败: " + e.getMessage());
        }

        if (engine.isTerminal()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "会话已到达终态，无可执行节点");
        }

        long latencyMs = latencyFrom(session.getCreateTime());
        AgentGraphDebugEngine.StepResult result;
        try {
            result = engine.step();
        } catch (AgentGraphInterpreter.GraphExecutionException e) {
            // 失败路径：仍需落一条 trace
            persistTrace(session.getId(), engine.getLastTrace());
            session.setStatus(STATUS_FAILED);
            session.setErrorCategory(e.getCategory());
            session.setErrorMessage(summarizeError(e));
            session.setLatencyMs(latencyMs);
            // 更新 token 汇总（若 trace 有 token）
            updateTokenSummary(session, engine.getLastTrace());
            try {
                session.setStateJson(engine.serializeState(objectMapper));
            } catch (Exception se) {
                // 序列化失败不影响错误状态落库
            }
            int updated = sessionMapper.updateById(session);
            if (updated == 0) {
                throw new BaseException(409, "并发冲突，请重试");
            }
            // 重新加载返回
            throw e;
        } catch (Exception e) {
            // 非 GraphExecutionException 包装
            AgentGraphInterpreter.GraphExecutionException wrapped =
                    new AgentGraphInterpreter.GraphExecutionException(
                            AgentGraphInterpreter.ERROR_CATEGORY_UNKNOWN, e.getMessage(), e);
            if (engine.getLastTrace() != null) {
                persistTrace(session.getId(), engine.getLastTrace());
            }
            session.setStatus(STATUS_FAILED);
            session.setErrorCategory(wrapped.getCategory());
            session.setErrorMessage(summarizeError(wrapped));
            session.setLatencyMs(latencyMs);
            try {
                session.setStateJson(engine.serializeState(objectMapper));
            } catch (Exception se) { }
            int updated = sessionMapper.updateById(session);
            if (updated == 0) {
                throw new BaseException(409, "并发冲突，请重试");
            }
            throw wrapped;
        }

        // 成功路径：落 trace
        persistTrace(session.getId(), result.getTrace());
        updateTokenSummary(session, result.getTrace());

        try {
            session.setStateJson(engine.serializeState(objectMapper));
        } catch (Exception e) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "调试状态序列化失败: " + e.getMessage());
        }

        if (result.isTerminal()) {
            session.setStatus(STATUS_COMPLETED);
            session.setResultText(result.getResultText());
            session.setLatencyMs(latencyMs);
        } else {
            // pendingJoin 也是 PAUSED
            session.setStatus(STATUS_PAUSED);
        }

        int updated = sessionMapper.updateById(session);
        if (updated == 0) {
            throw new BaseException(409, "并发冲突，请重试");
        }

        int traceCount = countTraces(session.getId());
        return toDTO(session, engine, traceCount);
    }

    // ==================== 继续到断点 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentGraphDebugSessionDTO continueUntilBreakpoint(Long sessionId) {
        AgentGraphDebugSession session = requireSession(sessionId);
        expireIfNeeded(session);
        if (!STATUS_PAUSED.equals(session.getStatus())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "会话已终结，无法继续");
        }

        Set<String> breakpoints = parseBreakpoints(session.getBreakpoints());
        // 空断点集合 = 一直跑到终态
        ProcessGraph graph = parseGraph(session.getGraphJson());
        Map<Long, AgentModelConfig> modelConfigs = validateForExecution(graph);
        int maxSteps = computeMaxSteps(graph);

        AgentGraphDebugEngine engine;
        try {
            engine = AgentGraphDebugEngine.deserializeState(session.getStateJson(), objectMapper,
                    graph, maxSteps, chatModelFactory, agentToolCallbackFactory,
                    modelConfigs, cipher, loginContextProvider.getTenantId());
        } catch (Exception e) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "调试状态反序列化失败: " + e.getMessage());
        }

        // 断点前语义：若当前队首已在断点上，直接暂停不执行
        String peek = engine.peekNextNodeId();
        if (peek != null && breakpoints.contains(peek)) {
            return toDTOWithState(session);
        }

        int safety = maxSteps > 0 ? maxSteps : 1000;
        for (int i = 0; i < safety; i++) {
            if (engine.isTerminal()) {
                break;
            }
            // 检查下一个待执行节点是否命中断点（执行前检查）
            String nextId = engine.peekNextNodeId();
            if (nextId != null && breakpoints.contains(nextId)) {
                break;
            }

            AgentGraphDebugEngine.StepResult result;
            try {
                result = engine.step();
            } catch (AgentGraphInterpreter.GraphExecutionException e) {
                if (engine.getLastTrace() != null) {
                    persistTrace(session.getId(), engine.getLastTrace());
                    updateTokenSummary(session, engine.getLastTrace());
                }
                session.setStatus(STATUS_FAILED);
                session.setErrorCategory(e.getCategory());
                session.setErrorMessage(summarizeError(e));
                session.setLatencyMs(latencyFrom(session.getCreateTime()));
                try {
                    session.setStateJson(engine.serializeState(objectMapper));
                } catch (Exception se) { }
                sessionMapper.updateById(session);
                throw e;
            } catch (Exception e) {
                AgentGraphInterpreter.GraphExecutionException wrapped =
                        new AgentGraphInterpreter.GraphExecutionException(
                                AgentGraphInterpreter.ERROR_CATEGORY_UNKNOWN, e.getMessage(), e);
                if (engine.getLastTrace() != null) {
                    persistTrace(session.getId(), engine.getLastTrace());
                }
                session.setStatus(STATUS_FAILED);
                session.setErrorCategory(wrapped.getCategory());
                session.setErrorMessage(summarizeError(wrapped));
                session.setLatencyMs(latencyFrom(session.getCreateTime()));
                try {
                    session.setStateJson(engine.serializeState(objectMapper));
                } catch (Exception se) { }
                sessionMapper.updateById(session);
                throw wrapped;
            }

            persistTrace(session.getId(), result.getTrace());
            updateTokenSummary(session, result.getTrace());

            if (result.isTerminal()) {
                session.setStatus(STATUS_COMPLETED);
                session.setResultText(result.getResultText());
                session.setLatencyMs(latencyFrom(session.getCreateTime()));
                try {
                    session.setStateJson(engine.serializeState(objectMapper));
                } catch (Exception se) { }
                sessionMapper.updateById(session);
                break;
            }

            // 断点前检查：执行完一步后，peek 下一步是否命中断点
            String afterPeek = engine.peekNextNodeId();
            if (afterPeek != null && breakpoints.contains(afterPeek)) {
                try {
                    session.setStateJson(engine.serializeState(objectMapper));
                } catch (Exception e) {
                    throw new BaseException(CommonErrorCode.PARAM_ERROR, "调试状态序列化失败: " + e.getMessage());
                }
                session.setStatus(STATUS_PAUSED);
                sessionMapper.updateById(session);
                break;
            }

            // 非断点命中，继续循环；每步后持久化 state，避免长循环中丢失
            try {
                session.setStateJson(engine.serializeState(objectMapper));
            } catch (Exception e) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR, "调试状态序列化失败: " + e.getMessage());
            }
            // 若是最后一次循环，保存 PAUSED
            if (i == safety - 1) {
                session.setStatus(STATUS_PAUSED);
                sessionMapper.updateById(session);
            }
        }

        // 若循环正常结束但未在循环内保存（例如空循环），确保状态落库
        // 查询最新的 session 状态判断是否已更新
        AgentGraphDebugSession refreshed = sessionMapper.selectById(session.getId());
        if (refreshed != null && STATUS_PAUSED.equals(refreshed.getStatus())
                && engine != null && !engine.isTerminal()) {
            // 情况：safety 耗尽前未命中终态且未命中保存点，补保存
            // 但若上面已保存过，refreshed 的 stateJson 已是最新
            // 为安全起见，若 refreshed 的 stateJson 与 engine 当前不一致，补更新
            try {
                String currentState = engine.serializeState(objectMapper);
                if (!currentState.equals(refreshed.getStateJson())) {
                    refreshed.setStateJson(currentState);
                    sessionMapper.updateById(refreshed);
                    return toDTO(refreshed, engine, countTraces(refreshed.getId()));
                }
            } catch (Exception e) { }
        }

        // 若未进入终态/失败分支，当前 engine 为最新
        AgentGraphDebugSession latest = sessionMapper.selectById(session.getId());
        if (latest != null && STATUS_PAUSED.equals(latest.getStatus())) {
            return toDTO(latest, engine, countTraces(latest.getId()));
        }
        if (latest != null) {
            return toDTOWithState(latest);
        }
        return toDTO(session, engine, countTraces(session.getId()));
    }

    // ==================== 断点更新 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentGraphDebugSessionDTO updateBreakpoints(Long sessionId, Set<String> breakpoints) {
        AgentGraphDebugSession session = requireSession(sessionId);
        expireIfNeeded(session);
        if (session.getStatus() != null && !STATUS_PAUSED.equals(session.getStatus())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "仅 PAUSED 会话可更新断点");
        }
        Set<String> normalized = breakpoints != null ? breakpoints : Set.of();
        if (!normalized.isEmpty()) {
            ProcessGraph graph = parseGraph(session.getGraphJson());
            Set<String> nodeIds = new HashSet<>();
            for (GraphElement el : graph.getElements()) {
                if ("node".equals(el.getKind())) {
                    nodeIds.add(el.getId());
                }
            }
            for (String bp : normalized) {
                if (!nodeIds.contains(bp)) {
                    throw new BaseException(CommonErrorCode.PARAM_ERROR, "断点节点不存在: " + bp);
                }
            }
        }
        try {
            session.setBreakpoints(objectMapper.writeValueAsString(normalized));
        } catch (Exception e) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "断点序列化失败: " + e.getMessage());
        }
        sessionMapper.updateById(session);
        return toDTOWithState(session);
    }

    // ==================== 停止 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentGraphDebugSessionDTO stop(Long sessionId) {
        AgentGraphDebugSession session = requireSession(sessionId);
        expireIfNeeded(session);
        if (!STATUS_PAUSED.equals(session.getStatus())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "仅 PAUSED 会话可停止");
        }
        session.setStatus(STATUS_STOPPED);
        session.setLatencyMs(latencyFrom(session.getCreateTime()));
        sessionMapper.updateById(session);
        return toDTOWithState(session);
    }

    // ==================== 明细 ====================

    @Override
    public List<AgentGraphDebugNodeDTO> listNodes(Long sessionId) {
        requireSession(sessionId);
        List<AgentGraphDebugNode> nodes = debugNodeMapper.selectList(
                Wrappers.<AgentGraphDebugNode>lambdaQuery()
                        .eq(AgentGraphDebugNode::getDebugSessionId, sessionId)
                        .orderByAsc(AgentGraphDebugNode::getNodeSeq));
        List<AgentGraphDebugNodeDTO> result = new ArrayList<>(nodes.size());
        for (AgentGraphDebugNode n : nodes) {
            result.add(toNodeDTO(n));
        }
        return result;
    }

    // ==================== 分页 ====================

    @Override
    public PageResult<AgentGraphDebugSessionDTO> pageSessions(PageParam pageParam, Long graphDefId) {
        LambdaQueryWrapper<AgentGraphDebugSession> wrapper = Wrappers.<AgentGraphDebugSession>lambdaQuery()
                .eq(graphDefId != null, AgentGraphDebugSession::getGraphDefId, graphDefId)
                .orderByDesc(AgentGraphDebugSession::getCreateTime);
        IPage<AgentGraphDebugSession> page = sessionMapper.selectPage(
                new Page<>(pageParam.getPageNum(), pageParam.getPageSize()), wrapper);
        // 过期检查：对当前页的 PAUSED 会话做惰性过期
        for (AgentGraphDebugSession s : page.getRecords()) {
            expireIfNeeded(s);
        }
        IPage<AgentGraphDebugSessionDTO> dtoPage = page.convert(this::toSummaryDTO);
        return PageResult.of(dtoPage);
    }

    // ==================== 内部辅助 ====================

    private void expireIfNeeded(AgentGraphDebugSession session) {
        if (STATUS_PAUSED.equals(session.getStatus())
                && session.getExpiresAt() != null
                && LocalDateTime.now().isAfter(session.getExpiresAt())) {
            session.setStatus(STATUS_EXPIRED);
            sessionMapper.updateById(session);
        }
    }

    private AgentGraphDebugSession requireSession(Long sessionId) {
        if (sessionId == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "sessionId 不能为空");
        }
        AgentGraphDebugSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND, "调试会话不存在");
        }
        return session;
    }

    private AgentGraphDef requireEntity(Long id) {
        if (id == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "id 不能为空");
        }
        AgentGraphDef entity = graphDefMapper.selectById(id);
        if (entity == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND);
        }
        return entity;
    }

    private ProcessGraph parseGraph(String graphJson) {
        if (graphJson == null || graphJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(graphJson, ProcessGraph.class);
        } catch (Exception e) {
            return null;
        }
    }

    private int computeMaxSteps(ProcessGraph graph) {
        List<GraphElement> elements = graph.getElements();
        int nodeCount = (int) elements.stream()
                .filter(e -> "node".equals(e.getKind()))
                .count();
        int loopBudget = 0;
        for (GraphElement element : elements) {
            if ("node".equals(element.getKind())
                    && AgentGraphInterpreter.NODE_TYPE_LOOP.equals(element.getType())) {
                loopBudget += maxIterationsOf(element);
            }
        }
        return nodeCount * 2 + loopBudget * nodeCount;
    }

    private int maxIterationsOf(GraphElement node) {
        Map<String, Object> config = node.getConfig();
        if (config == null) {
            return AgentGraphInterpreter.DEFAULT_MAX_ITERATIONS;
        }
        Object value = config.get(AgentGraphInterpreter.CONFIG_KEY_MAX_ITERATIONS);
        if (!(value instanceof Number n)) {
            return AgentGraphInterpreter.DEFAULT_MAX_ITERATIONS;
        }
        return n.intValue();
    }

    private void persistTrace(Long sessionId, AgentGraphInterpreter.NodeExecutionTrace trace) {
        if (trace == null) {
            return;
        }
        AgentGraphDebugNode node = new AgentGraphDebugNode();
        node.setDebugSessionId(sessionId);
        node.setNodeSeq((int) trace.getNodeSeq());
        node.setBranchId(trace.getBranchId());
        node.setNodeId(trace.getNodeId());
        node.setNodeType(trace.getNodeType());
        node.setNodeLatencyMs(trace.getNodeLatencyMs());
        node.setVariableSnapshot(toJson(trace.getVariableSnapshot()));
        node.setInputTokens(trace.getInputTokens());
        node.setOutputTokens(trace.getOutputTokens());
        debugNodeMapper.insert(node);
    }

    private void updateTokenSummary(AgentGraphDebugSession session,
                                    AgentGraphInterpreter.NodeExecutionTrace trace) {
        if (trace == null) {
            return;
        }
        boolean changed = false;
        if (trace.getInputTokens() != null) {
            long cur = session.getInputTokens() != null ? session.getInputTokens() : 0;
            session.setInputTokens(cur + trace.getInputTokens());
            changed = true;
        }
        if (trace.getOutputTokens() != null) {
            long cur = session.getOutputTokens() != null ? session.getOutputTokens() : 0;
            session.setOutputTokens(cur + trace.getOutputTokens());
            changed = true;
        }
        // 非 trace 级别更新由调用方落库，此处仅改内存对象
    }

    private int countTraces(Long sessionId) {
        Long cnt = debugNodeMapper.selectCount(
                Wrappers.<AgentGraphDebugNode>lambdaQuery()
                        .eq(AgentGraphDebugNode::getDebugSessionId, sessionId));
        return cnt != null ? cnt.intValue() : 0;
    }

    private String toJson(Map<String, String> variables) {
        if (variables == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(variables);
        } catch (Exception e) {
            return null;
        }
    }

    private Set<String> parseBreakpoints(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Set<String>>() {});
        } catch (Exception e) {
            return Set.of();
        }
    }

    private Map<String, String> parseVariables(String stateJson) {
        if (stateJson == null || stateJson.isBlank()) {
            return Map.of();
        }
        try {
            AgentGraphDebugEngine.DebugState state = objectMapper.readValue(stateJson, AgentGraphDebugEngine.DebugState.class);
            return state.variables != null ? state.variables : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String summarizeError(Throwable t) {
        // P61：按异常类型分层——平台业务异常与编排自身状态失败保留可定位文案，
        // 传输/服务商类异常替换为安全结论并写日志（见 AgentFailureSummarizer）。
        return com.sw.ck.agent.orchestration.AgentFailureSummarizer.summarize(t);
    }

    @SuppressWarnings("unused")
    private String classifyError(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof AgentGraphInterpreter.GraphExecutionException gex
                    && gex.getCategory() != null) {
                return gex.getCategory();
            }
            cur = cur.getCause();
        }
        return AgentGraphInterpreter.ERROR_CATEGORY_UNKNOWN;
    }

    private long latencyFrom(LocalDateTime createTime) {
        if (createTime == null) {
            return 0;
        }
        return java.time.Duration.between(createTime, LocalDateTime.now()).toMillis();
    }

    private AgentGraphDebugSessionDTO toDTO(AgentGraphDebugSession session,
                                            AgentGraphDebugEngine engine,
                                            int traceCount) {
        AgentGraphDebugSessionDTO dto = new AgentGraphDebugSessionDTO();
        dto.setId(session.getId());
        dto.setGraphDefId(session.getGraphDefId());
        dto.setGraphDefVersion(session.getGraphDefVersion());
        dto.setStatus(session.getStatus());
        dto.setInput(session.getInput());
        dto.setBreakpoints(parseBreakpoints(session.getBreakpoints()));
        if (engine != null) {
            dto.setVariables(engine.getVariables());
            dto.setNextNodeId(engine.peekNextNodeId());
            dto.setNextBranchId(engine.peekNextBranchId());
        } else {
            dto.setVariables(parseVariables(session.getStateJson()));
            dto.setNextNodeId(null);
            dto.setNextBranchId(null);
        }
        dto.setTraceCount(traceCount);
        dto.setResultText(session.getResultText());
        dto.setErrorCategory(session.getErrorCategory());
        dto.setErrorMessage(session.getErrorMessage());
        dto.setLatencyMs(session.getLatencyMs());
        dto.setInputTokens(session.getInputTokens());
        dto.setOutputTokens(session.getOutputTokens());
        dto.setExpiresAt(session.getExpiresAt());
        dto.setCreateTime(session.getCreateTime());
        dto.setUpdateTime(session.getUpdateTime());
        dto.setVersion(session.getVersion());
        return dto;
    }

    private AgentGraphDebugSessionDTO toDTOWithState(AgentGraphDebugSession session) {
        // 尝试恢复 engine 以获取 nextNodeId/variables
        AgentGraphDebugEngine engine = null;
        int traceCount = countTraces(session.getId());
        if (STATUS_PAUSED.equals(session.getStatus())
                && session.getStateJson() != null && !session.getStateJson().isBlank()
                && session.getGraphJson() != null) {
            try {
                ProcessGraph graph = parseGraph(session.getGraphJson());
                if (graph != null) {
                    Map<Long, AgentModelConfig> modelConfigs = validateForExecution(graph);
                    int maxSteps = computeMaxSteps(graph);
                    engine = AgentGraphDebugEngine.deserializeState(session.getStateJson(), objectMapper,
                            graph, maxSteps, chatModelFactory, agentToolCallbackFactory,
                            modelConfigs, cipher, loginContextProvider.getTenantId());
                }
            } catch (Exception e) {
                // 忽略，反序列化失败则不提供 nextNodeId
            }
        }
        AgentGraphDebugSessionDTO dto = toDTO(session, engine, traceCount);
        // 终态下 nextNodeId 保持 null
        if (engine == null) {
            // variables 已在 toDTO 中从 stateJson 解析
        }
        return dto;
    }

    private AgentGraphDebugSessionDTO toSummaryDTO(AgentGraphDebugSession session) {
        AgentGraphDebugSessionDTO dto = new AgentGraphDebugSessionDTO();
        dto.setId(session.getId());
        dto.setGraphDefId(session.getGraphDefId());
        dto.setGraphDefVersion(session.getGraphDefVersion());
        dto.setStatus(session.getStatus());
        dto.setInput(session.getInput());
        dto.setBreakpoints(parseBreakpoints(session.getBreakpoints()));
        dto.setTraceCount(countTraces(session.getId()));
        dto.setResultText(session.getResultText());
        dto.setErrorCategory(session.getErrorCategory());
        dto.setErrorMessage(session.getErrorMessage());
        dto.setLatencyMs(session.getLatencyMs());
        dto.setInputTokens(session.getInputTokens());
        dto.setOutputTokens(session.getOutputTokens());
        dto.setExpiresAt(session.getExpiresAt());
        dto.setCreateTime(session.getCreateTime());
        dto.setUpdateTime(session.getUpdateTime());
        dto.setVersion(session.getVersion());
        // 摘要不含 variables/nextNodeId 明细
        return dto;
    }

    private AgentGraphDebugNodeDTO toNodeDTO(AgentGraphDebugNode node) {
        AgentGraphDebugNodeDTO dto = new AgentGraphDebugNodeDTO();
        dto.setId(node.getId());
        dto.setDebugSessionId(node.getDebugSessionId());
        dto.setNodeSeq(node.getNodeSeq());
        dto.setBranchId(node.getBranchId());
        dto.setNodeId(node.getNodeId());
        dto.setNodeType(node.getNodeType());
        dto.setNodeLatencyMs(node.getNodeLatencyMs());
        dto.setVariableSnapshot(node.getVariableSnapshot());
        dto.setInputTokens(node.getInputTokens());
        dto.setOutputTokens(node.getOutputTokens());
        return dto;
    }

    // ==================== 执行前校验（与执行服务同语义，本地复制） ====================

    private Map<Long, AgentModelConfig> validateForExecution(ProcessGraph graph) {
        List<GraphElement> elements = graph.getElements();
        List<GraphElement> nodes = elements.stream()
                .filter(e -> "node".equals(e.getKind()))
                .toList();

        List<GraphElement> starts = nodes.stream()
                .filter(n -> AgentGraphInterpreter.NODE_TYPE_START.equals(n.getType()))
                .toList();
        if (starts.size() != 1) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR,
                    "图中 START 节点必须唯一（当前 " + starts.size() + " 个）");
        }
        // 简化：调试会话创建时同样要求 END 可达（与执行服务一致）
        if (!hasReachableEnd(starts.get(0), elements, nodes)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "图中不存在可达的 END 节点");
        }

        Map<Long, AgentModelConfig> modelConfigs = new HashMap<>();
        for (GraphElement node : nodes) {
            switch (node.getType()) {
                case AgentGraphInterpreter.NODE_TYPE_LLM -> {
                    Object idObj = configValue(node, AgentGraphInterpreter.CONFIG_KEY_AGENT_MODEL_CONFIG_ID);
                    if (!(idObj instanceof Number n)) {
                        throw new BaseException(CommonErrorCode.PARAM_ERROR,
                                "LLM 节点缺少模型配置引用: " + node.getId());
                    }
                    Long modelConfigId = n.longValue();
                    AgentModelConfig mc = modelConfigMapper.selectById(modelConfigId);
                    if (mc == null) {
                        throw new BaseException(CommonErrorCode.PARAM_ERROR,
                                "LLM 节点引用的模型配置不存在: " + modelConfigId);
                    }
                    modelConfigs.put(modelConfigId, mc);
                }
                case AgentGraphInterpreter.NODE_TYPE_TOOL -> {
                    Object nameObj = configValue(node, AgentGraphInterpreter.CONFIG_KEY_TOOL_NAME);
                    if (!(nameObj instanceof String toolName) || toolName.isBlank()) {
                        throw new BaseException(CommonErrorCode.PARAM_ERROR,
                                "TOOL 节点缺少工具名: " + node.getId());
                    }
                    if (!toolExists(toolName)) {
                        throw new BaseException(CommonErrorCode.PARAM_ERROR,
                                "工具节点引用的工具不存在或未启用: " + toolName);
                    }
                }
                case AgentGraphInterpreter.NODE_TYPE_CONDITION -> {
                    long defaultEdgeCount = outgoingEdges(node, elements).stream()
                            .filter(e -> AgentGraphInterpreter.keywordOf(e) == null)
                            .count();
                    if (defaultEdgeCount > 1) {
                        throw new BaseException(CommonErrorCode.PARAM_ERROR,
                                "条件分支默认边不唯一: " + node.getId());
                    }
                }
                case AgentGraphInterpreter.NODE_TYPE_FORK -> {
                    if (outgoingEdges(node, elements).size() < 2) {
                        throw new BaseException(CommonErrorCode.PARAM_ERROR,
                                "FORK 节点扇出分支数必须 ≥ 2: " + node.getId());
                    }
                }
                case AgentGraphInterpreter.NODE_TYPE_JOIN -> {
                    if (incomingEdges(node, elements).size() < 2) {
                        throw new BaseException(CommonErrorCode.PARAM_ERROR,
                                "JOIN 节点汇合入边数必须 ≥ 2: " + node.getId());
                    }
                }
                case AgentGraphInterpreter.NODE_TYPE_LOOP -> {
                    Object maxIterations = configValue(node, AgentGraphInterpreter.CONFIG_KEY_MAX_ITERATIONS);
                    if (maxIterations instanceof Number n && n.intValue() < 1) {
                        throw new BaseException(CommonErrorCode.PARAM_ERROR,
                                "LOOP 节点 maxIterations 必须 ≥ 1: " + node.getId());
                    }
                }
                default -> { }
            }
        }
        return modelConfigs;
    }

    private Object configValue(GraphElement node, String key) {
        return node.getConfig() == null ? null : node.getConfig().get(key);
    }

    private List<GraphElement> outgoingEdges(GraphElement node, List<GraphElement> elements) {
        List<GraphElement> edges = new ArrayList<>();
        for (GraphElement element : elements) {
            if ("edge".equals(element.getKind()) && node.getId().equals(element.getSource())) {
                edges.add(element);
            }
        }
        return edges;
    }

    private List<GraphElement> incomingEdges(GraphElement node, List<GraphElement> elements) {
        List<GraphElement> edges = new ArrayList<>();
        for (GraphElement element : elements) {
            if ("edge".equals(element.getKind()) && node.getId().equals(element.getTarget())) {
                edges.add(element);
            }
        }
        return edges;
    }

    private boolean hasReachableEnd(GraphElement start, List<GraphElement> elements, List<GraphElement> nodes) {
        Map<String, String> typeById = new HashMap<>();
        for (GraphElement node : nodes) {
            typeById.put(node.getId(), node.getType());
        }
        Map<String, List<String>> adjacency = new HashMap<>();
        for (GraphElement edge : elements) {
            if ("edge".equals(edge.getKind()) && edge.getSource() != null && edge.getTarget() != null) {
                adjacency.computeIfAbsent(edge.getSource(), k -> new ArrayList<>()).add(edge.getTarget());
            }
        }
        Set<String> visited = new HashSet<>();
        java.util.Deque<String> queue = new java.util.ArrayDeque<>();
        queue.add(start.getId());
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!visited.add(id)) {
                continue;
            }
            if (AgentGraphInterpreter.NODE_TYPE_END.equals(typeById.get(id))) {
                return true;
            }
            for (String next : adjacency.getOrDefault(id, List.of())) {
                if (!visited.contains(next)) {
                    queue.add(next);
                }
            }
        }
        return false;
    }

    private boolean toolExists(String toolName) {
        Long internal = internalToolMapper.selectCount(
                Wrappers.<AgentToolInternalConfig>lambdaQuery()
                        .eq(AgentToolInternalConfig::getName, toolName)
                        .eq(AgentToolInternalConfig::getEnabled, 1));
        if (internal != null && internal > 0) {
            return true;
        }
        Long external = externalToolMapper.selectCount(
                Wrappers.<AgentToolExternalConfig>lambdaQuery()
                        .eq(AgentToolExternalConfig::getName, toolName)
                        .eq(AgentToolExternalConfig::getEnabled, 1));
        return external != null && external > 0;
    }
}
