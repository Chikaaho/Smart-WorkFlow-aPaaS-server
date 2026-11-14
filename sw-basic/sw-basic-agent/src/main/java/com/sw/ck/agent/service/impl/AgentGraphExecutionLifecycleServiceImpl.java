package com.sw.ck.agent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.agent.dto.AgentGraphExecutionAcceptedDTO;
import com.sw.ck.agent.dto.AgentGraphExecutionLifecycleDTO;
import com.sw.ck.agent.dto.AgentGraphExecutionEventDTO;
import com.sw.ck.agent.dto.AgentHumanTaskDTO;
import com.sw.ck.agent.entity.AgentGraphHumanTask;
import com.sw.ck.agent.mapper.AgentGraphHumanTaskMapper;
import com.sw.ck.agent.dto.AgentGraphExecuteRespDTO;
import com.sw.ck.agent.entity.AgentGraphDef;
import com.sw.ck.agent.entity.AgentGraphExecutionRequest;
import com.sw.ck.agent.entity.AgentGraphExecutionEvent;
import com.sw.ck.agent.mapper.AgentGraphDefMapper;
import com.sw.ck.agent.mapper.AgentGraphExecutionEventMapper;
import com.sw.ck.agent.mapper.AgentGraphExecutionRequestMapper;
import com.sw.ck.agent.orchestration.AgentExecutionEventContext;
import com.sw.ck.agent.orchestration.AgentGraphInterpreter;
import com.sw.ck.agent.service.AgentGraphExecutionLifecycleService;
import com.sw.ck.agent.service.AgentGraphExecutionService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
public class AgentGraphExecutionLifecycleServiceImpl implements AgentGraphExecutionLifecycleService {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AgentGraphExecutionLifecycleServiceImpl.class);

    /** 结构化入参/出参 JSON 编解码（inputs_json / outputs_json 列）。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    static final String QUEUED = "QUEUED";
    static final String RUNNING = "RUNNING";
    static final String CANCEL_REQUESTED = "CANCEL_REQUESTED";
    static final String SUCCESS = "SUCCESS";
    static final String FAILED = "FAILED";
    static final String CANCELLED = "CANCELLED";
    static final String INTERRUPTED = "INTERRUPTED";
    static final String WAITING_HUMAN = "WAITING_HUMAN";

    private final AgentGraphExecutionRequestMapper requestMapper;
    private final AgentGraphExecutionEventMapper eventMapper;
    private final AgentGraphHumanTaskMapper humanTaskMapper;
    private final com.sw.ck.agent.mapper.AgentGraphExecutionMapper executionMapper;
    private final AgentGraphDefMapper graphDefMapper;
    private final AgentGraphExecutionService executionService;
    private final LoginContextProvider loginContext;
    private final TransactionTemplate transactionTemplate;
    /** 受理专用模板（REQUIRES_NEW：受理先独立提交再派发，跨模块触发可见性保证）。 */
    private final TransactionTemplate acceptTemplate;
    private final ThreadPoolExecutor executor;
    private final ConcurrentHashMap<Long, Thread> runningThreads = new ConcurrentHashMap<>();
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    public AgentGraphExecutionLifecycleServiceImpl(
            AgentGraphExecutionRequestMapper requestMapper,
            AgentGraphExecutionEventMapper eventMapper,
            AgentGraphHumanTaskMapper humanTaskMapper,
            com.sw.ck.agent.mapper.AgentGraphExecutionMapper executionMapper,
            AgentGraphDefMapper graphDefMapper,
            AgentGraphExecutionService executionService,
            LoginContextProvider loginContext,
            PlatformTransactionManager transactionManager,
            org.springframework.context.ApplicationEventPublisher eventPublisher,
            @Value("${sw.agent.execution.workers:4}") int workers,
            @Value("${sw.agent.execution.queue-capacity:64}") int queueCapacity) {
        this.requestMapper = requestMapper;
        this.eventMapper = eventMapper;
        this.humanTaskMapper = humanTaskMapper;
        this.executionMapper = executionMapper;
        this.graphDefMapper = graphDefMapper;
        this.executionService = executionService;
        this.loginContext = loginContext;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.acceptTemplate = new TransactionTemplate(transactionManager);
        this.acceptTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.eventPublisher = eventPublisher;
        int boundedWorkers = Math.max(1, Math.min(workers, 16));
        int boundedCapacity = Math.max(1, Math.min(queueCapacity, 1000));
        this.executor = new ThreadPoolExecutor(boundedWorkers, boundedWorkers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(boundedCapacity), Thread.ofVirtual().name("agent-graph-exec-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PostConstruct
    void recover() {
        if (!lifecycleTableAvailable()) return;
        requestMapper.update(null, Wrappers.<AgentGraphExecutionRequest>lambdaUpdate()
                .in(AgentGraphExecutionRequest::getStatus, RUNNING, CANCEL_REQUESTED)
                .set(AgentGraphExecutionRequest::getStatus, INTERRUPTED)
                .set(AgentGraphExecutionRequest::getErrorCategory, "SERVICE_RESTART")
                .set(AgentGraphExecutionRequest::getErrorMessage,
                        "服务重启前的执行效果无法确认，已停止自动重放，请人工核实外部副作用")
                .set(AgentGraphExecutionRequest::getFinishedAt, LocalDateTime.now()));
        List<AgentGraphExecutionRequest> queued = requestMapper.selectList(
                Wrappers.<AgentGraphExecutionRequest>lambdaQuery()
                        .eq(AgentGraphExecutionRequest::getStatus, QUEUED)
                        .orderByAsc(AgentGraphExecutionRequest::getCreateTime));
        for (AgentGraphExecutionRequest request : queued) {
            LoginUser recovered = new LoginUser();
            recovered.setUserId(request.getCreateBy());
            recovered.setTenantId(request.getTenantId());
            dispatch(request.getId(), recovered);
        }
    }

    private boolean lifecycleTableAvailable() {
        try {
            requestMapper.selectCount(Wrappers.<AgentGraphExecutionRequest>lambdaQuery()
                    .eq(AgentGraphExecutionRequest::getId, -1L));
            return true;
        } catch (org.springframework.dao.DataAccessException missingInReducedTestContext) {
            return false;
        }
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }

    @Override
    public AgentGraphExecutionAcceptedDTO submit(Long graphDefId, String input,
                                                 Map<String, String> inputs, String idempotencyKey) {
        boolean hasStructuredInputs = inputs != null && !inputs.isEmpty();
        if (graphDefId == null || ((input == null || input.isBlank()) && !hasStructuredInputs)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "graphDefId 和入参不能为空");
        }
        String key = normalizeKey(idempotencyKey);
        String inputsJson = toJson(inputs);
        String fingerprint = fingerprint(graphDefId, input, inputsJson);
        Long userId = requireUserId();
        LoginUser loginUser = LoginUserHolder.get();

        // 受理必须独立提交后再派发（0.2.4 缺陷修复）：跨模块触发（IoT 桥、异步监听）
        // 调用时外层事务尚未提交，REQUIRED 会把 QUEUED 行的可见性推迟到 dispatch 之后，
        // 工作线程的 QUEUED→RUNNING 转换因此永远失配。受理是独立工作单，按 REQUIRES_NEW
        // 先落库提交，再进入执行队列。
        AgentGraphExecutionRequest accepted = acceptTemplate.execute(status -> {
            AgentGraphDef graph = graphDefMapper.selectById(graphDefId);
            if (graph == null || !"PUBLISHED".equals(graph.getStatus())) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR, "图不存在或未发布");
            }
            if (key != null) {
                AgentGraphExecutionRequest existing = requestMapper.selectOne(
                        Wrappers.<AgentGraphExecutionRequest>lambdaQuery()
                                .eq(AgentGraphExecutionRequest::getCreateBy, String.valueOf(userId))
                                .eq(AgentGraphExecutionRequest::getIdempotencyKey, key)
                                .last("LIMIT 1"));
                if (existing != null) {
                    if (!Objects.equals(existing.getRequestFingerprint(), fingerprint)) {
                        throw new BaseException(CommonErrorCode.PARAM_ERROR, "相同幂等键对应的执行输入不一致");
                    }
                    return existing;
                }
            }
            if (executor.getQueue().remainingCapacity() == 0) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR, "执行容量已满，请稍后重试");
            }
            AgentGraphExecutionRequest request = new AgentGraphExecutionRequest();
            request.setGraphDefId(graphDefId);
            request.setGraphDefVersion(graph.getDefVersion());
            request.setIdempotencyKey(key);
            request.setRequestFingerprint(fingerprint);
            request.setStatus(QUEUED);
            request.setInput(input == null ? "" : input);
            request.setInputsJson(inputsJson);
            requestMapper.insert(request);
            return request;
        });
        if (QUEUED.equals(accepted.getStatus())) {
            dispatch(accepted.getId(), loginUser);
        }
        return new AgentGraphExecutionAcceptedDTO(accepted.getId(), accepted.getStatus());
    }

    @Override
    public AgentGraphExecutionLifecycleDTO get(Long executionId) {
        return toDto(requireOwned(executionId));
    }

    @Override
    public List<AgentGraphExecutionEventDTO> listEvents(Long executionId, long afterSequence) {
        // W5：事件按受理行 id 落库；入参可为执行编号（结果行）
        Long requestId = requireOwned(executionId).getId();
        return eventMapper.selectList(Wrappers.<AgentGraphExecutionEvent>lambdaQuery()
                        .eq(AgentGraphExecutionEvent::getExecutionId, requestId)
                        .gt(AgentGraphExecutionEvent::getSequenceNo, Math.max(0, afterSequence))
                        .orderByAsc(AgentGraphExecutionEvent::getSequenceNo)
                        .last("LIMIT 500"))
                .stream().map(this::toEventDto).toList();
    }

    @Override
    public AgentGraphExecutionLifecycleDTO cancel(Long executionId) {
        AgentGraphExecutionRequest current = requireOwned(executionId);
        // W5/P71：入参可为执行编号（结果行）——取消变更一律落在受理行 id 上，
        // 否则按执行编号取消时 UPDATE 命中 0 行，接口 200 却静默无效
        Long requestId = current.getId();
        if (QUEUED.equals(current.getStatus())) {
            if (requestMapper.cancelQueued(requestId) == 1) {
                appendEvent(requestMapper.selectById(requestId), "STATE", null, CANCELLED);
            }
        } else if (WAITING_HUMAN.equals(current.getStatus())) {
            if (requestMapper.cancelWaiting(requestId) == 1) {
                humanTaskMapper.cancelPending(requestId);
                cancelChildWaitingExecutions(current.getResultExecutionId());
                appendEvent(requestMapper.selectById(requestId), "STATE", null, CANCELLED);
            }
        } else if (RUNNING.equals(current.getStatus())) {
            if (requestMapper.requestRunningCancellation(requestId) == 1) {
                appendEvent(requestMapper.selectById(requestId), "STATE", null, CANCEL_REQUESTED);
                Thread thread = runningThreads.get(requestId);
                if (thread != null) thread.interrupt();
            }
        }
        return toDto(requireOwned(requestId));
    }

    private void dispatch(Long executionId, LoginUser loginUser) {
        try {
            executor.execute(() -> run(executionId, loginUser));
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            requestMapper.update(null, Wrappers.<AgentGraphExecutionRequest>lambdaUpdate()
                    .eq(AgentGraphExecutionRequest::getId, executionId)
                    .eq(AgentGraphExecutionRequest::getStatus, QUEUED)
                    .set(AgentGraphExecutionRequest::getStatus, FAILED)
                    .set(AgentGraphExecutionRequest::getErrorCategory, "CAPACITY_REJECTED")
                    .set(AgentGraphExecutionRequest::getErrorMessage, "执行容量已满，请稍后重试")
                    .set(AgentGraphExecutionRequest::getFinishedAt, LocalDateTime.now()));
        }
    }

    private void run(Long executionId, LoginUser loginUser) {
        if (loginUser != null) LoginUserHolder.set(loginUser);
        if (requestMapper.transition(executionId, QUEUED, RUNNING) != 1) {
            // G3：人工提交/超时恢复已由提交方把状态置为 RUNNING；仅该状态允许继续
            AgentGraphExecutionRequest current = requestMapper.selectById(executionId);
            if (current == null || !RUNNING.equals(current.getStatus())) {
                LoginUserHolder.clear();
                return;
            }
        }
        Thread current = Thread.currentThread();
        runningThreads.put(executionId, current);
        AgentGraphExecutionRequest request = requestMapper.selectById(executionId);
        request.setStartedAt(LocalDateTime.now());
        requestMapper.updateById(request);
        long started = System.currentTimeMillis();
        try {
            AgentExecutionEventContext.set((type, nodeId, payload) ->
                    appendEvent(request, type, nodeId, payload));
            appendEvent(request, "STATE", null, RUNNING);
            Map<String, String> structuredInputs = fromJson(request.getInputsJson());
            // G3：带暂停状态的执行从人工节点恢复；否则从头执行
            AgentGraphExecutionService.ExecutionOutcome outcome;
            if (request.getPauseStateJson() != null && !request.getPauseStateJson().isBlank()) {
                AgentGraphInterpreter.PauseState pauseState = JSON.readValue(
                        request.getPauseStateJson(), AgentGraphInterpreter.PauseState.class);
                // V3（planning-review-110-02）：恢复复用暂停前的执行记录 id，用户看到同一条执行
                outcome = executionService.runLifecycle(request.getGraphDefId(),
                        request.getGraphDefVersion(), request.getInput(), structuredInputs,
                        pauseState, latestHumanDecision(executionId), request.getResultExecutionId());
            } else {
                outcome = executionService.runLifecycle(request.getGraphDefId(),
                        request.getGraphDefVersion(), request.getInput(), structuredInputs,
                        null, null, null);
            }
            if (outcome.pauseState != null) {
                enterWaitingHuman(executionId, request, outcome);
                return;
            }
            AgentGraphExecuteRespDTO result = outcome.response;
            AgentGraphExecutionRequest latest = requestMapper.selectById(executionId);
            String outputsJson = result.isSuccess() ? toJson(result.getOutputs()) : null;
            if (Thread.currentThread().isInterrupted() || CANCEL_REQUESTED.equals(latest.getStatus())) {
                finish(executionId, CANCELLED, null, "执行已取消", result.getExecutionId(), started, null);
            } else if (result.isSuccess()) {
                finish(executionId, SUCCESS, null, null, result.getExecutionId(), started, outputsJson);
            } else {
                // X3：沿用执行结果的分类（如 HUMAN_TIMEOUT），未知时才回退通用分类
                String category = result.getErrorCategory();
                if (category == null || category.isBlank()) {
                    category = "GRAPH_EXECUTION_FAILED";
                }
                finish(executionId, FAILED, category, result.getErrorMessage(),
                        result.getExecutionId(), started, null);
            }
        } catch (Exception error) {
            AgentGraphExecutionRequest latest = requestMapper.selectById(executionId);
            if (Thread.currentThread().isInterrupted() || CANCEL_REQUESTED.equals(latest.getStatus())) {
                finish(executionId, CANCELLED, null, "执行已取消", null, started, null);
            } else {
                finish(executionId, FAILED, "EXECUTION_ERROR", "图执行失败，请查看节点记录", null, started, null);
            }
        } finally {
            AgentExecutionEventContext.clear();
            runningThreads.remove(executionId);
            LoginUserHolder.clear();
        }
    }

    // ==================== G3 人工节点等待/提交/超时 ====================

    /** 进入人工等待：持久化恢复状态 + 生成待处理任务行 + WAITING_HUMAN 事件。 */
    private void enterWaitingHuman(Long executionId, AgentGraphExecutionRequest request,
                                   AgentGraphExecutionService.ExecutionOutcome outcome) {
        AgentGraphInterpreter.HumanTaskSpec spec = outcome.humanTask;
        String payloadJson = toJsonObject(spec);
        request.setStatus(WAITING_HUMAN);
        request.setPauseStateJson(toJsonObject(outcome.pauseState));
        request.setResultExecutionId(outcome.executionId);
        request.setErrorMessage(null);
        requestMapper.updateById(request);
        boolean hasTargets = (spec.assigneeUserIds != null && !spec.assigneeUserIds.isEmpty())
                || (spec.assigneeRoleCodes != null && !spec.assigneeRoleCodes.isEmpty());
        for (Long userId : spec.assigneeUserIds) {
            insertHumanTask(request, executionId, spec, payloadJson, userId, null);
        }
        for (String roleCode : spec.assigneeRoleCodes) {
            insertHumanTask(request, executionId, spec, payloadJson, null, roleCode);
        }
        if (!hasTargets) {
            // 发布校验应拦截"未配置处理人"；防御性兜底生成一条无指定处理人任务（超管可处理）
            insertHumanTask(request, executionId, spec, payloadJson, null, null);
        }
        appendEvent(requestMapper.selectById(executionId), "STATE", spec.humanNodeId, WAITING_HUMAN);
    }

    private int insertHumanTask(AgentGraphExecutionRequest request, Long executionId,
                                AgentGraphInterpreter.HumanTaskSpec spec, String payloadJson,
                                Long assigneeUserId, String assigneeRoleCode) {
        AgentGraphHumanTask task = new AgentGraphHumanTask();
        task.setExecutionId(executionId);
        task.setNodeId(spec.humanNodeId);
        task.setStatus("PENDING");
        task.setAssigneeUserId(assigneeUserId);
        task.setAssigneeRoleCode(assigneeRoleCode);
        task.setPayloadJson(payloadJson);
        return humanTaskMapper.insert(task);
    }

    /** 最近一次人工决策（SUBMITTED/TIMEOUT 任务的最新结果），供恢复执行使用。 */
    private List<AgentGraphInterpreter.HumanDecision> latestHumanDecision(Long executionId) {
        AgentGraphHumanTask task = humanTaskMapper.selectOne(
                Wrappers.<AgentGraphHumanTask>lambdaQuery()
                        .eq(AgentGraphHumanTask::getExecutionId, executionId)
                        .in(AgentGraphHumanTask::getStatus, "SUBMITTED", "TIMEOUT")
                        .orderByDesc(AgentGraphHumanTask::getFinishedAt)
                        .last("LIMIT 1"));
        if (task == null || task.getResultJson() == null || task.getResultJson().isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> parsed = JSON.readValue(task.getResultJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
                    });
            String decision = parsed.get("decision") == null
                    ? null : String.valueOf(parsed.get("decision"));
            Map<String, String> values = new LinkedHashMap<>();
            Object rawValues = parsed.get("values");
            if (rawValues instanceof Map<?, ?> map) {
                map.forEach((key, value) -> {
                    if (key != null && value != null) {
                        values.put(String.valueOf(key), String.valueOf(value));
                    }
                });
            }
            return List.of(new AgentGraphInterpreter.HumanDecision(task.getNodeId(), decision, values));
        } catch (Exception malformed) {
            return List.of();
        }
    }

    /**
     * 提交人工任务（G3-2）：校验处理人身份、单次提交（PENDING→SUBMITTED 认领）、
     * 同等待其余待处理行一并关闭，然后重新派发执行。
     */
    public AgentGraphExecutionLifecycleDTO submitHumanTask(Long executionId, Long taskId,
                                                           Map<String, String> values,
                                                           String decision) {
        AgentGraphExecutionRequest request = requireOwned(executionId);
        // W5：入参可为执行编号（结果行）——后续一律用受理行 id 操作任务与状态
        Long requestId = request.getId();
        if (!WAITING_HUMAN.equals(request.getStatus())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "该执行不在等待人工状态");
        }
        if (taskId == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "taskId 不能为空");
        }
        AgentGraphHumanTask task = humanTaskMapper.selectById(taskId);
        if (task == null || !requestId.equals(task.getExecutionId())) {
            throw new BaseException(CommonErrorCode.NOT_FOUND, "人工任务不存在");
        }
        if (!"PENDING".equals(task.getStatus())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "该等待已处理，不能重复提交");
        }
        requireAssignee(task);
        if (decision == null || decision.isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "决策动作不能为空");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("decision", decision);
        result.put("values", values == null ? Map.of() : values);
        String resultJson = toJsonObject(result);
        if (humanTaskMapper.claimSubmitted(taskId, resultJson, requireUserId()) != 1) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "该等待已处理，不能重复提交");
        }
        humanTaskMapper.completeSiblings(requestId, task.getNodeId());
        if (requestMapper.transition(requestId, WAITING_HUMAN, RUNNING) != 1) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "执行状态已变化，请刷新后重试");
        }
        dispatch(requestId, LoginUserHolder.get());
        return get(requestId);
    }

    /** 处理人校验：超管 / 指定用户 / 指定角色 code（任务未指定处理人时超管可处理）。 */
    private void requireAssignee(AgentGraphHumanTask task) {
        if (loginContext.isSuperAdmin()) return;
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "缺少登录身份");
        }
        if (task.getAssigneeUserId() == null && task.getAssigneeRoleCode() == null) {
            throw new BaseException(CommonErrorCode.FORBIDDEN, "该等待未指定处理人，仅管理员可处理");
        }
        if (task.getAssigneeUserId() != null
                && task.getAssigneeUserId().equals(loginUser.getUserId())) {
            return;
        }
        if (task.getAssigneeRoleCode() != null && loginUser.getRoles() != null
                && loginUser.getRoles().contains(task.getAssigneeRoleCode())) {
            return;
        }
        throw new BaseException(CommonErrorCode.FORBIDDEN, "您不是该等待的处理人");
    }

    /** 待我处理列表：直接指派给我的 + 指派给我角色的 PENDING 任务。 */
    public List<AgentHumanTaskDTO> listMyHumanTasks() {
        LoginUser loginUser = LoginUserHolder.get();
        Long userId = requireUserId();
        List<AgentGraphHumanTask> tasks = humanTaskMapper.selectList(
                Wrappers.<AgentGraphHumanTask>lambdaQuery()
                        .eq(AgentGraphHumanTask::getStatus, "PENDING")
                        .and(wrapper -> {
                            wrapper.eq(AgentGraphHumanTask::getAssigneeUserId, userId);
                            if (loginUser != null && loginUser.getRoles() != null
                                    && !loginUser.getRoles().isEmpty()) {
                                wrapper.or().in(AgentGraphHumanTask::getAssigneeRoleCode,
                                        loginUser.getRoles());
                            }
                        })
                        .orderByDesc(AgentGraphHumanTask::getCreateTime)
                        .last("LIMIT 100"));
        return tasks.stream().map(this::toHumanTaskDto).toList();
    }

    /** 指定执行的人工任务列表（执行详情页展示等待/已处理）。 */
    public List<AgentHumanTaskDTO> listExecutionHumanTasks(Long executionId) {
        // W5：入参可为受理编号或执行编号，任务行始终按受理编号查询
        Long requestId = requireOwned(executionId).getId();
        return humanTaskMapper.selectList(Wrappers.<AgentGraphHumanTask>lambdaQuery()
                        .eq(AgentGraphHumanTask::getExecutionId, requestId)
                        .orderByAsc(AgentGraphHumanTask::getId))
                .stream().map(this::toHumanTaskDto).toList();
    }

    /**
     * 超时扫描（G3-3）：PENDING 任务按 payload.timeoutSeconds 判定；超时认领后
     * 以 TIMEOUT 决策重新派发（无超时边由解释器按 HUMAN_TIMEOUT 失败收尾）。
     */
    @Override
    public void scanHumanTaskTimeouts() {
        // 调度线程无登录身份：跨租户扫描时显式挂起租户拦截器，逐任务恢复所属租户上下文
        List<AgentGraphHumanTask> pending;
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            pending = humanTaskMapper.selectList(
                    Wrappers.<AgentGraphHumanTask>lambdaQuery()
                            .eq(AgentGraphHumanTask::getStatus, "PENDING")
                            .last("LIMIT 200"));
        }
        for (AgentGraphHumanTask task : pending) {
            LoginUser scanner = new LoginUser();
            scanner.setTenantId(task.getTenantId());
            scanner.setUserId(0L);
            LoginUserHolder.set(scanner);
            try {
                AgentGraphExecutionRequest request = requestMapper.selectById(task.getExecutionId());
                if (request == null || !WAITING_HUMAN.equals(request.getStatus())) continue;
                if (request.getCreateBy() != null) scanner.setUserId(request.getCreateBy());
                Integer timeoutSeconds = payloadTimeoutSeconds(task.getPayloadJson());
                if (timeoutSeconds == null || timeoutSeconds <= 0 || task.getCreateTime() == null) {
                    continue;
                }
                if (task.getCreateTime().plusSeconds(timeoutSeconds).isAfter(LocalDateTime.now())) {
                    continue;
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("decision", AgentGraphInterpreter.HUMAN_TIMEOUT_DECISION);
                result.put("values", Map.of());
                if (humanTaskMapper.claimTimeout(task.getId(), toJsonObject(result)) != 1) {
                    continue;
                }
                humanTaskMapper.timeoutSiblings(task.getExecutionId(), task.getNodeId());
                if (requestMapper.transition(task.getExecutionId(), WAITING_HUMAN, RUNNING) == 1) {
                    appendEvent(requestMapper.selectById(task.getExecutionId()),
                            "STATE", task.getNodeId(), "HUMAN_TIMEOUT");
                    dispatch(task.getExecutionId(), scanner);
                }
            } finally {
                LoginUserHolder.clear();
            }
        }
    }

    private Integer payloadTimeoutSeconds(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return null;
        try {
            Map<String, Object> parsed = JSON.readValue(payloadJson,
                    new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
                    });
            Object value = parsed.get("timeoutSeconds");
            if (value instanceof Number number) return number.intValue();
            if (value instanceof String text && !text.isBlank()) {
                try {
                    return Integer.parseInt(text.trim());
                } catch (NumberFormatException ignore) {
                    return null;
                }
            }
            return null;
        } catch (Exception malformed) {
            return null;
        }
    }

    private AgentHumanTaskDTO toHumanTaskDto(AgentGraphHumanTask task) {
        AgentHumanTaskDTO dto = new AgentHumanTaskDTO();
        dto.setId(task.getId());
        dto.setExecutionId(task.getExecutionId());
        // W5：任务行 executionId 是受理编号，回链受理行取结果执行 id，前端统一展示执行编号
        AgentGraphExecutionRequest linkedRequest = requestMapper.selectById(task.getExecutionId());
        dto.setResultExecutionId(linkedRequest == null ? null : linkedRequest.getResultExecutionId());
        dto.setNodeId(task.getNodeId());
        dto.setStatus(task.getStatus());
        dto.setAssigneeUserId(task.getAssigneeUserId());
        dto.setAssigneeRoleCode(task.getAssigneeRoleCode());
        dto.setResultJson(task.getResultJson());
        dto.setCreateTime(task.getCreateTime());
        dto.setSubmittedAt(task.getSubmittedAt());
        if (task.getPayloadJson() != null) {
            try {
                Map<String, Object> payload = JSON.readValue(task.getPayloadJson(),
                        new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
                        });
                dto.setInstructions(payload.get("instructions") == null
                        ? null : String.valueOf(payload.get("instructions")));
                dto.setFields(castMapList(payload.get("fields")));
                dto.setButtons(castMapList(payload.get("buttons")));
                Object timeout = payload.get("timeoutSeconds");
                if (timeout instanceof Number number) dto.setTimeoutSeconds(number.intValue());
            } catch (Exception ignored) {
                // payload 损坏时保留空结构，不阻断列表
            }
        }
        return dto;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castMapList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((key, value) -> {
                    if (key != null) copy.put(String.valueOf(key), value);
                });
                result.add(copy);
            }
        }
        return result;
    }

    /** 通用对象 JSON（暂停状态/任务定义/结果）。 */
    private String toJsonObject(Object value) {
        if (value == null) return null;
        try {
            return JSON.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "结构化数据序列化失败");
        }
    }

    private void finish(Long id, String status, String category, String message, Long resultId,
                        long started, String outputsJson) {
        if (requestMapper.finish(id, status, category, message, resultId,
                System.currentTimeMillis() - started, outputsJson) == 1) {
            // G3 返修：父执行取消时级联取消仍等待人工的子执行（行 + 待处理任务）
            if (CANCELLED.equals(status) && resultId != null) {
                cancelChildWaitingExecutions(resultId);
            }
            AgentGraphExecutionRequest terminal = requestMapper.selectById(id);
            appendEvent(terminal, "STATE", null, status);
            publishTerminalEvent(terminal);
        }
    }

    /**
     * 级联取消仍等待人工的子执行（G3 返修）：子执行行不在请求簿上，人工任务又挂在
     * 子执行行 id 上；父取消若只关请求簿与首层任务，子执行会永远停在 WAITING_HUMAN
     * 且其待办仍可提交。统一在此把等待中的子执行置 CANCELLED 并关闭其待处理任务。
     */
    private void cancelChildWaitingExecutions(Long resultExecutionId) {
        if (resultExecutionId == null) {
            return;
        }
        try {
            List<com.sw.ck.agent.entity.AgentGraphExecution> waitingChildren = executionMapper.selectList(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.sw.ck.agent.entity.AgentGraphExecution>lambdaQuery()
                            .eq(com.sw.ck.agent.entity.AgentGraphExecution::getParentExecutionId, resultExecutionId)
                            .eq(com.sw.ck.agent.entity.AgentGraphExecution::getStatus, WAITING_HUMAN));
            for (com.sw.ck.agent.entity.AgentGraphExecution child : waitingChildren) {
                humanTaskMapper.cancelPending(child.getId());
                child.setStatus(CANCELLED);
                executionMapper.updateById(child);
            }
        } catch (Exception cascadeFailure) {
            // 级联失败不回滚父取消（父已终态）；留日志排障
            log.warn("子执行级联取消失败: parentExecutionId={}, error={}",
                    resultExecutionId, cascadeFailure.getMessage());
        }
    }

    /** 终态落库成功后发布进程内事件（普通事件，无事务上下文；消费方自行幂等）。 */
    private void publishTerminalEvent(AgentGraphExecutionRequest terminal) {
        if (terminal == null || eventPublisher == null) {
            return;
        }
        try {
            eventPublisher.publishEvent(new com.sw.ck.agent.event.AgentGraphExecutionTerminalEvent(
                    terminal.getId(), terminal.getGraphDefId(), terminal.getTenantId(),
                    terminal.getCreateBy() == null ? null : String.valueOf(terminal.getCreateBy()),
                    terminal.getStatus(), terminal.getErrorCategory(), terminal.getErrorMessage()));
        } catch (Exception publishFailure) {
            // 终态已落库；联动方消费失败不影响执行结果本身
            log.warn("Agent 执行终态事件发布失败: executionId={}, error={}",
                    terminal.getId(), publishFailure.getMessage());
        }
    }

    private void appendEvent(AgentGraphExecutionRequest request, String type, String nodeId, String payload) {
        eventMapper.insertNext(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), request.getTenantId(),
                String.valueOf(request.getCreateBy()), request.getId(), type, nodeId, payload);
    }

    private AgentGraphExecutionEventDTO toEventDto(AgentGraphExecutionEvent event) {
        AgentGraphExecutionEventDTO dto = new AgentGraphExecutionEventDTO();
        dto.setSequence(event.getSequenceNo());
        dto.setType(event.getEventType());
        dto.setNodeId(event.getNodeId());
        dto.setPayload(event.getPayload());
        dto.setCreatedAt(event.getCreateTime());
        return dto;
    }

    private AgentGraphExecutionRequest requireOwned(Long id) {
        if (id == null) throw new BaseException(CommonErrorCode.PARAM_ERROR, "executionId 不能为空");
        AgentGraphExecutionRequest request = requestMapper.selectById(id);
        if (request == null) {
            // W5：读取/取消入口同时接受结果执行 id（详情/列表统一展示执行编号）
            request = requestMapper.selectOne(Wrappers.<AgentGraphExecutionRequest>lambdaQuery()
                    .eq(AgentGraphExecutionRequest::getResultExecutionId, id)
                    .orderByDesc(AgentGraphExecutionRequest::getId)
                    .last("LIMIT 1"));
        }
        if (request == null) throw new BaseException(CommonErrorCode.NOT_FOUND, "执行记录不存在");
        Long userId = requireUserId();
        if (!loginContext.isSuperAdmin() && !Objects.equals(request.getCreateBy(), userId)) {
            throw new BaseException(CommonErrorCode.NOT_FOUND, "执行记录不存在");
        }
        return request;
    }

    private Long requireUserId() {
        Long id = loginContext.getUserId();
        if (id == null) throw new BaseException(CommonErrorCode.PARAM_ERROR, "缺少登录身份");
        return id;
    }

    private AgentGraphExecutionLifecycleDTO toDto(AgentGraphExecutionRequest request) {
        AgentGraphExecutionLifecycleDTO dto = new AgentGraphExecutionLifecycleDTO();
        dto.setExecutionId(request.getId());
        dto.setGraphDefId(request.getGraphDefId());
        dto.setGraphDefVersion(request.getGraphDefVersion());
        dto.setStatus(request.getStatus());
        dto.setInput(request.getInput());
        dto.setErrorCategory(request.getErrorCategory());
        dto.setErrorMessage(request.getErrorMessage());
        dto.setLatencyMs(request.getLatencyMs());
        dto.setResultExecutionId(request.getResultExecutionId());
        dto.setOutputs(fromJson(request.getOutputsJson()));
        dto.setCreateTime(request.getCreateTime());
        dto.setStartedAt(request.getStartedAt());
        dto.setFinishedAt(request.getFinishedAt());
        dto.setCancelRequestedAt(request.getCancelRequestedAt());
        if (request.getResultExecutionId() != null) {
            try {
                dto.setOutput(executionService.getExecution(request.getResultExecutionId()).getOutput());
            } catch (BaseException ignored) {
                dto.setOutput(null);
            }
        }
        return dto;
    }

    private String normalizeKey(String key) {
        if (key == null || key.isBlank()) return null;
        String normalized = key.trim();
        if (normalized.length() > 100) throw new BaseException(CommonErrorCode.PARAM_ERROR, "幂等键过长");
        return normalized;
    }

    private String fingerprint(Long graphDefId, String input, String inputsJson) {
        try {
            String normalized = graphDefId + "\n" + (input == null ? "" : input)
                    + "\n" + (inputsJson == null ? "" : inputsJson);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Map → JSON（键排序保证幂等指纹稳定；空 map/null 返回 null）。 */
    private String toJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(new TreeMap<>(map));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "入参序列化失败");
        }
    }

    /** JSON → Map（空/null/损坏返回空 map，不阻断执行）。 */
    private Map<String, String> fromJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, String> parsed = JSON.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, String>>() {
                    });
            return parsed == null ? Map.of() : parsed;
        } catch (Exception malformed) {
            return Map.of();
        }
    }
}
