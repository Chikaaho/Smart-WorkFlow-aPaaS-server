package com.sw.ck.bpm.process.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sw.ck.bpm.process.entity.ParticipantSnapshot;
import com.sw.ck.bpm.process.mapper.ParticipantSnapshotMapper;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 参与人展示名解析（I1 历史身份不被改写）。
 * <p>
 * 优先使用节点进入时冻结的快照姓名（{@code sw_bpm_participant_snapshot.participant_name}），
 * 快照缺失（历史存量数据、发起人等）再回落实时查询。用户改名/停用后，
 * 已冻结轮次的可读身份保持不变。
 * </p>
 */
@Service
public class ParticipantNameService {

    private static final Logger log = LoggerFactory.getLogger(ParticipantNameService.class);

    private final ParticipantSnapshotMapper snapshotMapper;
    private final UserQueryFacade userQueryFacade;

    public ParticipantNameService(ParticipantSnapshotMapper snapshotMapper, UserQueryFacade userQueryFacade) {
        this.snapshotMapper = snapshotMapper;
        this.userQueryFacade = userQueryFacade;
    }

    /**
     * 解析流程实例内参与人的展示名：快照优先，缺失回落实时查询。
     * 查询失败不阻断调用方（返回实时结果或空 Map）。
     */
    public Map<Long, String> resolveDisplayNames(String processInstanceId, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = new HashMap<>();
        Set<Long> missing = new LinkedHashSet<>(ids);
        try {
            List<ParticipantSnapshot> snapshots = snapshotMapper.selectList(
                    new LambdaQueryWrapper<ParticipantSnapshot>()
                            .eq(ParticipantSnapshot::getProcessInstanceId, processInstanceId)
                            .isNotNull(ParticipantSnapshot::getParticipantName));
            for (ParticipantSnapshot snapshot : snapshots) {
                Long pid = parseId(snapshot.getParticipantId());
                if (pid != null && snapshot.getParticipantName() != null
                        && !snapshot.getParticipantName().isBlank()) {
                    // 同一参与人多轮快照取首个非空冻结名（轮次间姓名不应变化）
                    result.putIfAbsent(pid, snapshot.getParticipantName());
                    missing.remove(pid);
                }
            }
        } catch (Exception e) {
            log.warn("参与人快照姓名查询失败，整批回落实时查询: {}", e.getMessage());
        }
        if (!missing.isEmpty()) {
            try {
                // empty = ids 为 null（此处 missing 非空，契约不产生）：无可合并展示名
                userQueryFacade.getUserDisplayNames(missing)
                        .ifPresent(names -> names.forEach(result::putIfAbsent));
            } catch (Exception e) {
                log.warn("用户展示名实时查询失败，回退为空: {}", e.getMessage());
            }
        }
        return result;
    }

    private Long parseId(String value) {
        if (value == null || !value.matches("\\d+")) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解析流程实例内各节点（node_key = 活动 ID）的权威参与人（I1 G5b）。
     * <p>
     * 候选模式任务在 Flowable 历史中无 ASSIGNEE_，展示链不得回退为发起人；
     * 权威身份以节点进入时冻结的快照为准。同节点多行按状态优先级取一：
     * HANDLED（实际办理）&gt; PENDING（待办理）&gt; INVALIDATED（竞态失效）。
     * 查询失败返回空 Map，不阻断调用方。
     * </p>
     */
    public Map<String, Long> resolveNodeAssignees(String processInstanceId) {
        if (processInstanceId == null || processInstanceId.isBlank()) {
            return Map.of();
        }
        try {
            List<ParticipantSnapshot> snapshots = snapshotMapper.selectList(
                    new LambdaQueryWrapper<ParticipantSnapshot>()
                            .eq(ParticipantSnapshot::getProcessInstanceId, processInstanceId));
            Map<String, Long> result = new HashMap<>();
            for (ParticipantSnapshot snapshot : snapshots) {
                Long pid = parseId(snapshot.getParticipantId());
                if (pid == null || snapshot.getNodeKey() == null) {
                    continue;
                }
                int rank = rankOf(snapshot.getParticipantStatus());
                Long current = result.get(snapshot.getNodeKey());
                if (current == null || rank > current) {
                    result.put(snapshot.getNodeKey(), pid);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("节点参与人快照查询失败，回退为空: {}", e.getMessage());
            return Map.of();
        }
    }

    private int rankOf(String status) {
        if ("HANDLED".equalsIgnoreCase(status)) {
            return 3;
        }
        if ("PENDING".equalsIgnoreCase(status)) {
            return 2;
        }
        return 1;
    }

    /**
     * 解析流程实例内各任务的权威参与人（I4 G1a）。
     * <p>
     * 动态并行多分支任务共用同一 node_key，节点级解析会把多条分支覆盖为同一人；
     * 任务级以快照的 task_id 为键逐一对应，保证办理身份与轨迹逐条相等。
     * 同任务多轮快照按状态优先级取一（HANDLED &gt; PENDING &gt; INVALIDATED）。
     * 查询失败返回空 Map，不阻断调用方。
     * </p>
     */
    public Map<String, Long> resolveTaskAssignees(String processInstanceId) {
        if (processInstanceId == null || processInstanceId.isBlank()) {
            return Map.of();
        }
        try {
            List<ParticipantSnapshot> snapshots = snapshotMapper.selectList(
                    new LambdaQueryWrapper<ParticipantSnapshot>()
                            .eq(ParticipantSnapshot::getProcessInstanceId, processInstanceId)
                            .isNotNull(ParticipantSnapshot::getTaskId));
            Map<String, Long> result = new HashMap<>();
            Map<String, Integer> ranks = new HashMap<>();
            for (ParticipantSnapshot snapshot : snapshots) {
                Long pid = parseId(snapshot.getParticipantId());
                if (pid == null || snapshot.getTaskId() == null || snapshot.getTaskId().isBlank()) {
                    continue;
                }
                int rank = rankOf(snapshot.getParticipantStatus());
                Integer current = ranks.get(snapshot.getTaskId());
                if (current == null || rank > current) {
                    result.put(snapshot.getTaskId(), pid);
                    ranks.put(snapshot.getTaskId(), rank);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("任务参与人快照查询失败，回退为空: {}", e.getMessage());
            return Map.of();
        }
    }
}
