package com.sw.ck.agent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.agent.dto.AgentGraphExecutionAcceptedDTO;
import com.sw.ck.agent.dto.AgentGraphExecutionLifecycleDTO;
import com.sw.ck.agent.dto.AgentGraphExecutionEventDTO;
import com.sw.ck.agent.dto.AgentGraphExecuteRespDTO;
import com.sw.ck.agent.entity.AgentGraphDef;
import com.sw.ck.agent.entity.AgentGraphExecutionRequest;
import com.sw.ck.agent.entity.AgentGraphExecutionEvent;
import com.sw.ck.agent.mapper.AgentGraphDefMapper;
import com.sw.ck.agent.mapper.AgentGraphExecutionEventMapper;
import com.sw.ck.agent.mapper.AgentGraphExecutionRequestMapper;
import com.sw.ck.agent.orchestration.AgentExecutionEventContext;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
public class AgentGraphExecutionLifecycleServiceImpl implements AgentGraphExecutionLifecycleService {
    static final String QUEUED = "QUEUED";
    static final String RUNNING = "RUNNING";
    static final String CANCEL_REQUESTED = "CANCEL_REQUESTED";
    static final String SUCCESS = "SUCCESS";
    static final String FAILED = "FAILED";
    static final String CANCELLED = "CANCELLED";
    static final String INTERRUPTED = "INTERRUPTED";

    private final AgentGraphExecutionRequestMapper requestMapper;
    private final AgentGraphExecutionEventMapper eventMapper;
    private final AgentGraphDefMapper graphDefMapper;
    private final AgentGraphExecutionService executionService;
    private final LoginContextProvider loginContext;
    private final TransactionTemplate transactionTemplate;
    private final ThreadPoolExecutor executor;
    private final ConcurrentHashMap<Long, Thread> runningThreads = new ConcurrentHashMap<>();

    public AgentGraphExecutionLifecycleServiceImpl(
            AgentGraphExecutionRequestMapper requestMapper,
            AgentGraphExecutionEventMapper eventMapper,
            AgentGraphDefMapper graphDefMapper,
            AgentGraphExecutionService executionService,
            LoginContextProvider loginContext,
            PlatformTransactionManager transactionManager,
            @Value("${sw.agent.execution.workers:4}") int workers,
            @Value("${sw.agent.execution.queue-capacity:64}") int queueCapacity) {
        this.requestMapper = requestMapper;
        this.eventMapper = eventMapper;
        this.graphDefMapper = graphDefMapper;
        this.executionService = executionService;
        this.loginContext = loginContext;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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
    public AgentGraphExecutionAcceptedDTO submit(Long graphDefId, String input, String idempotencyKey) {
        if (graphDefId == null || input == null || input.isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "graphDefId 和 input 不能为空");
        }
        String key = normalizeKey(idempotencyKey);
        String fingerprint = fingerprint(graphDefId, input);
        Long userId = requireUserId();
        LoginUser loginUser = LoginUserHolder.get();

        AgentGraphExecutionRequest accepted = transactionTemplate.execute(status -> {
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
            request.setInput(input);
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
        requireOwned(executionId);
        return eventMapper.selectList(Wrappers.<AgentGraphExecutionEvent>lambdaQuery()
                        .eq(AgentGraphExecutionEvent::getExecutionId, executionId)
                        .gt(AgentGraphExecutionEvent::getSequenceNo, Math.max(0, afterSequence))
                        .orderByAsc(AgentGraphExecutionEvent::getSequenceNo)
                        .last("LIMIT 500"))
                .stream().map(this::toEventDto).toList();
    }

    @Override
    public AgentGraphExecutionLifecycleDTO cancel(Long executionId) {
        AgentGraphExecutionRequest current = requireOwned(executionId);
        if (QUEUED.equals(current.getStatus())) {
            if (requestMapper.cancelQueued(executionId) == 1) {
                appendEvent(requestMapper.selectById(executionId), "STATE", null, CANCELLED);
            }
        } else if (RUNNING.equals(current.getStatus())) {
            if (requestMapper.requestRunningCancellation(executionId) == 1) {
                appendEvent(requestMapper.selectById(executionId), "STATE", null, CANCEL_REQUESTED);
                Thread thread = runningThreads.get(executionId);
                if (thread != null) thread.interrupt();
            }
        }
        return toDto(requireOwned(executionId));
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
        if (requestMapper.transition(executionId, QUEUED, RUNNING) != 1) return;
        Thread current = Thread.currentThread();
        runningThreads.put(executionId, current);
        if (loginUser != null) LoginUserHolder.set(loginUser);
        AgentGraphExecutionRequest request = requestMapper.selectById(executionId);
        request.setStartedAt(LocalDateTime.now());
        requestMapper.updateById(request);
        long started = System.currentTimeMillis();
        try {
            AgentExecutionEventContext.set((type, payload) -> appendEvent(request, type, null, payload));
            appendEvent(request, "STATE", null, RUNNING);
            AgentGraphExecuteRespDTO result = executionService.execute(request.getGraphDefId(), request.getInput());
            AgentGraphExecutionRequest latest = requestMapper.selectById(executionId);
            if (Thread.currentThread().isInterrupted() || CANCEL_REQUESTED.equals(latest.getStatus())) {
                finish(executionId, CANCELLED, null, "执行已取消", result.getExecutionId(), started);
            } else if (result.isSuccess()) {
                finish(executionId, SUCCESS, null, null, result.getExecutionId(), started);
            } else {
                finish(executionId, FAILED, "GRAPH_EXECUTION_FAILED", result.getErrorMessage(),
                        result.getExecutionId(), started);
            }
        } catch (Exception error) {
            AgentGraphExecutionRequest latest = requestMapper.selectById(executionId);
            if (Thread.currentThread().isInterrupted() || CANCEL_REQUESTED.equals(latest.getStatus())) {
                finish(executionId, CANCELLED, null, "执行已取消", null, started);
            } else {
                finish(executionId, FAILED, "EXECUTION_ERROR", "图执行失败，请查看节点记录", null, started);
            }
        } finally {
            AgentExecutionEventContext.clear();
            runningThreads.remove(executionId);
            LoginUserHolder.clear();
        }
    }

    private void finish(Long id, String status, String category, String message, Long resultId, long started) {
        if (requestMapper.finish(id, status, category, message, resultId,
                System.currentTimeMillis() - started) == 1) {
            AgentGraphExecutionRequest terminal = requestMapper.selectById(id);
            appendEvent(terminal, "STATE", null, status);
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

    private String fingerprint(Long graphDefId, String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((graphDefId + "\n" + input).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
