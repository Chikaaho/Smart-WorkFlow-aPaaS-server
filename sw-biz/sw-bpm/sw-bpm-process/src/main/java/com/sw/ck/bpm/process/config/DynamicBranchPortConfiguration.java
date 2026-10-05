package com.sw.ck.bpm.process.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sw.ck.bpm.api.participant.DynamicBranchPort;
import com.sw.ck.bpm.api.result.MutationOutcome;
import com.sw.ck.bpm.process.entity.DynamicBranchSnapshot;
import com.sw.ck.bpm.process.mapper.DynamicBranchSnapshotMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * I4 动态并行分支冻结端口装配（bpm-process）。
 * <p>
 * 冻结幂等：同 (tenant, instance, node) 已有快照时直接返回既有有效分支，不重算不追加。
 * 去重规则可解释：部门 ID 升序遍历，同一负责人的多个部门合并为首条分支（dept_ids 逗号记录）；
 * 无效对象落 CANCELED 行并记录原因，不静默丢弃。
 * </p>
 */
@Configuration
public class DynamicBranchPortConfiguration {

    @Bean
    public DynamicBranchPort dynamicBranchPort(DynamicBranchSnapshotMapper mapper) {
        return new DynamicBranchPort() {
            @Override
            public Optional<List<FrozenBranch>> freeze(String tenantId, String processInstanceId, String nodeKey,
                                                       String sourceType, String sourceDesc, String mode,
                                                       List<BranchCandidate> candidates) {
                long tenant = parseTenant(tenantId);
                List<DynamicBranchSnapshot> existing = mapper.selectList(
                        new LambdaQueryWrapper<DynamicBranchSnapshot>()
                                .eq(DynamicBranchSnapshot::getTenantId, tenant)
                                .eq(DynamicBranchSnapshot::getProcessInstanceId, processInstanceId)
                                .eq(DynamicBranchSnapshot::getNodeKey, nodeKey)
                                .eq(DynamicBranchSnapshot::getDeleted, 0)
                                .orderByAsc(DynamicBranchSnapshot::getBranchIndex));
                if (!existing.isEmpty()) {
                    return Optional.of(frozenOf(existing)); // 冻结快照权威：不重算
                }
                List<BranchCandidate> ordered = new ArrayList<>(candidates == null
                        ? List.of() : candidates);
                ordered.sort(Comparator.comparing(c -> parseLongSafe(c.deptId())));

                // 同负责人合并（LinkedHashMap 保序：部门升序下首个出现位置即分支顺序）
                Map<String, List<String>> mergedByLeader = new LinkedHashMap<>();
                List<BranchCandidate> invalid = new ArrayList<>();
                for (BranchCandidate candidate : ordered) {
                    if (candidate.skipReason() != null || candidate.leaderId() == null) {
                        invalid.add(candidate);
                    } else {
                        mergedByLeader.computeIfAbsent(candidate.leaderId(), key -> new ArrayList<>())
                                .add(candidate.deptId());
                    }
                }

                List<FrozenBranch> result = new ArrayList<>();
                int index = 0;
                for (Map.Entry<String, List<String>> entry : mergedByLeader.entrySet()) {
                    DynamicBranchSnapshot row = baseRow(tenant, processInstanceId, nodeKey,
                            sourceType, sourceDesc, mode);
                    row.setBranchIndex(index);
                    row.setLeaderId(Long.valueOf(entry.getKey()));
                    row.setDeptIds(String.join(",", entry.getValue()));
                    row.setStatus("START");
                    mapper.insert(row);
                    result.add(new FrozenBranch(index, entry.getKey(), row.getDeptIds()));
                    index++;
                }
                for (BranchCandidate candidate : invalid) {
                    DynamicBranchSnapshot row = baseRow(tenant, processInstanceId, nodeKey,
                            sourceType, sourceDesc, mode);
                    row.setBranchIndex(index);
                    row.setDeptIds(candidate.deptId());
                    row.setStatus("CANCELED");
                    row.setCancelReason(candidate.skipReason() == null ? "INVALID" : candidate.skipReason());
                    mapper.insert(row);
                    index++;
                }
                return Optional.of(result);
            }

            @Override
            public Optional<List<FrozenBranchV2>> freezeRound(String tenantId, String processInstanceId,
                                                              String nodeKey, String executionId,
                                                              String sourceType, String sourceDesc,
                                                              String mode, String objectType,
                                                              List<BranchCandidateV2> candidates) {
                long tenant = parseTenant(tenantId);
                // 1) 同 execution 恢复/重试：复用既有轮次快照（幂等，不重算不追加）
                List<DynamicBranchSnapshot> sameExecution = mapper.selectList(
                        new LambdaQueryWrapper<DynamicBranchSnapshot>()
                                .eq(DynamicBranchSnapshot::getTenantId, tenant)
                                .eq(DynamicBranchSnapshot::getProcessInstanceId, processInstanceId)
                                .eq(DynamicBranchSnapshot::getNodeKey, nodeKey)
                                .eq(DynamicBranchSnapshot::getSemanticVersion, 2)
                                .eq(DynamicBranchSnapshot::getExecutionId, executionId)
                                .eq(DynamicBranchSnapshot::getDeleted, 0)
                                .orderByAsc(DynamicBranchSnapshot::getBranchIndex));
                if (!sameExecution.isEmpty()) {
                    return Optional.of(frozenV2Of(sameExecution));
                }
                // 2) 新轮次：round = 历史最大 + 1；旧轮次未完成分支收口（旧任务不可串办）
                List<DynamicBranchSnapshot> history = mapper.selectList(
                        new LambdaQueryWrapper<DynamicBranchSnapshot>()
                                .eq(DynamicBranchSnapshot::getTenantId, tenant)
                                .eq(DynamicBranchSnapshot::getProcessInstanceId, processInstanceId)
                                .eq(DynamicBranchSnapshot::getNodeKey, nodeKey)
                                .eq(DynamicBranchSnapshot::getSemanticVersion, 2)
                                .eq(DynamicBranchSnapshot::getDeleted, 0));
                long nextRound = history.stream()
                        .mapToLong(row -> row.getRoundNo() == null ? 0L : row.getRoundNo())
                        .max().orElse(-1L) + 1;
                if (!history.isEmpty()) {
                    mapper.update(null, new LambdaUpdateWrapper<DynamicBranchSnapshot>()
                            .eq(DynamicBranchSnapshot::getTenantId, tenant)
                            .eq(DynamicBranchSnapshot::getProcessInstanceId, processInstanceId)
                            .eq(DynamicBranchSnapshot::getNodeKey, nodeKey)
                            .eq(DynamicBranchSnapshot::getSemanticVersion, 2)
                            .eq(DynamicBranchSnapshot::getStatus, "START")
                            .eq(DynamicBranchSnapshot::getDeleted, 0)
                            .set(DynamicBranchSnapshot::getStatus, "CANCELED")
                            .set(DynamicBranchSnapshot::getCancelReason, "SUPERSEDED_BY_ROUND")
                            .set(DynamicBranchSnapshot::getUpdateTime, java.time.LocalDateTime.now()));
                }
                // 3) 逐对象落行：同负责人不同部门保持独立分支；来源位置整组保留
                List<BranchCandidateV2> ordered = new ArrayList<>(candidates == null ? List.of() : candidates);
                ordered.sort(java.util.Comparator.comparingLong(c -> parseLongSafe(c.objectId())));
                List<FrozenBranchV2> result = new ArrayList<>();
                int index = 0;
                for (BranchCandidateV2 candidate : ordered) {
                    DynamicBranchSnapshot row = baseRow(tenant, processInstanceId, nodeKey,
                            sourceType, sourceDesc, mode);
                    row.setBranchIndex(index);
                    row.setSemanticVersion(2);
                    row.setObjectType(objectType);
                    row.setRoundNo(nextRound);
                    row.setExecutionId(executionId);
                    if (candidate.skipReason() != null || candidate.assigneeId() == null) {
                        row.setObjectId(candidate.objectId());
                        row.setStatus("CANCELED");
                        row.setCancelReason(candidate.skipReason() == null ? "INVALID" : candidate.skipReason());
                        mapper.insert(row);
                    } else {
                        row.setObjectId(candidate.objectId());
                        row.setLeaderId(Long.valueOf(candidate.assigneeId()));
                        row.setSourceRefs(candidate.sourceRefsJson());
                        row.setStatus("START");
                        mapper.insert(row);
                        result.add(new FrozenBranchV2(index, candidate.assigneeId(),
                                objectType, candidate.objectId(), candidate.sourceRefsJson()));
                    }
                    index++;
                }
                return Optional.of(result);
            }

            private List<FrozenBranchV2> frozenV2Of(List<DynamicBranchSnapshot> rows) {
                List<FrozenBranchV2> result = new ArrayList<>();
                for (DynamicBranchSnapshot row : rows) {
                    if ("CANCELED".equals(row.getStatus()) || row.getLeaderId() == null) {
                        continue;
                    }
                    result.add(new FrozenBranchV2(row.getBranchIndex() == null ? 0 : row.getBranchIndex(),
                            String.valueOf(row.getLeaderId()),
                            row.getObjectType() == null ? "DEPT" : row.getObjectType(),
                            row.getObjectId(),
                            row.getSourceRefs()));
                }
                return result;
            }

            @Override
            public Optional<MutationOutcome> recordAction(String tenantId, String processInstanceId, String nodeKey,
                                                          String leaderId, String taskId, String action, String reason) {
                long tenant = parseTenant(tenantId);
                // P63 v2：同负责人多分支按任务绑定行区分；任务首次回调时绑定未关联的最早分支
                DynamicBranchSnapshot current = mapper.selectOne(
                        new LambdaQueryWrapper<DynamicBranchSnapshot>()
                                .eq(DynamicBranchSnapshot::getTenantId, tenant)
                                .eq(DynamicBranchSnapshot::getProcessInstanceId, processInstanceId)
                                .eq(DynamicBranchSnapshot::getNodeKey, nodeKey)
                                .eq(DynamicBranchSnapshot::getSemanticVersion, 2)
                                .eq(DynamicBranchSnapshot::getTaskId, taskId)
                                .eq(DynamicBranchSnapshot::getDeleted, 0)
                                .orderByDesc(DynamicBranchSnapshot::getRoundNo)
                                .last("LIMIT 1"));
                if (current == null) {
                    DynamicBranchSnapshot unbound = mapper.selectOne(
                            new LambdaQueryWrapper<DynamicBranchSnapshot>()
                                    .eq(DynamicBranchSnapshot::getTenantId, tenant)
                                    .eq(DynamicBranchSnapshot::getProcessInstanceId, processInstanceId)
                                    .eq(DynamicBranchSnapshot::getNodeKey, nodeKey)
                                    .eq(DynamicBranchSnapshot::getSemanticVersion, 2)
                                    .eq(DynamicBranchSnapshot::getLeaderId, Long.valueOf(leaderId))
                                    .isNull(DynamicBranchSnapshot::getTaskId)
                                    .ne(DynamicBranchSnapshot::getStatus, "CANCELED")
                                    .eq(DynamicBranchSnapshot::getDeleted, 0)
                                    .orderByDesc(DynamicBranchSnapshot::getRoundNo)
                                    .orderByAsc(DynamicBranchSnapshot::getBranchIndex)
                                    .last("LIMIT 1"));
                    if (unbound != null) {
                        DynamicBranchSnapshot bind = new DynamicBranchSnapshot();
                        bind.setId(unbound.getId());
                        bind.setTaskId(taskId);
                        bind.setUpdateTime(java.time.LocalDateTime.now());
                        mapper.updateById(bind);
                        unbound.setTaskId(taskId);
                        current = unbound;
                    }
                }
                if (current == null) {
                    current = mapper.selectOne(
                            new LambdaQueryWrapper<DynamicBranchSnapshot>()
                                    .eq(DynamicBranchSnapshot::getTenantId, tenant)
                                    .eq(DynamicBranchSnapshot::getProcessInstanceId, processInstanceId)
                                    .eq(DynamicBranchSnapshot::getNodeKey, nodeKey)
                                    .eq(DynamicBranchSnapshot::getLeaderId, Long.valueOf(leaderId))
                                    .eq(DynamicBranchSnapshot::getDeleted, 0)
                                    .orderByAsc(DynamicBranchSnapshot::getBranchIndex)
                                    .last("LIMIT 1"));
                }
                if (current == null) {
                    // 冻结前回调（防御）：该分支尚未冻结，不凭空造分支
                    return Optional.empty();
                }
                DynamicBranchSnapshot patch = new DynamicBranchSnapshot();
                patch.setId(current.getId());
                if (taskId != null && (current.getTaskId() == null || current.getTaskId().isBlank())) {
                    patch.setTaskId(taskId);
                }
                if ("START".equals(action)) {
                    if ("CANCELED".equals(current.getStatus())) {
                        return Optional.of(MutationOutcome.ALREADY_APPLIED); // 取消态不被 START 覆写
                    }
                    patch.setStatus("START");
                } else if ("APPROVE".equals(action) || "DISAPPROVE".equals(action)) {
                    patch.setStatus(action);
                } else if ("CANCEL".equals(action)) {
                    if ("APPROVE".equals(current.getStatus()) || "DISAPPROVE".equals(current.getStatus())) {
                        return Optional.of(MutationOutcome.ALREADY_APPLIED); // 已终态动作的分支不改写
                    }
                    patch.setStatus("CANCELED");
                    patch.setCancelReason(reason);
                } else {
                    // 未受支持的动作：不写入任何变更，幂等语义下无第二次效果
                    return Optional.of(MutationOutcome.ALREADY_APPLIED);
                }
                patch.setUpdateTime(LocalDateTime.now());
                mapper.updateById(patch);
                return Optional.of(MutationOutcome.APPLIED);
            }

            @Override
            public Optional<MutationOutcome> closeRemaining(String tenantId, String processInstanceId, String nodeKey,
                                                            String reason) {
                long tenant = parseTenant(tenantId);
                // P63 v2：负向结算只关闭最新轮次未完成分支（旧轮次行已在轮次开启时收口）
                DynamicBranchSnapshot latest = mapper.selectOne(
                        new LambdaQueryWrapper<DynamicBranchSnapshot>()
                                .eq(DynamicBranchSnapshot::getTenantId, tenant)
                                .eq(DynamicBranchSnapshot::getProcessInstanceId, processInstanceId)
                                .eq(DynamicBranchSnapshot::getNodeKey, nodeKey)
                                .eq(DynamicBranchSnapshot::getSemanticVersion, 2)
                                .eq(DynamicBranchSnapshot::getDeleted, 0)
                                .orderByDesc(DynamicBranchSnapshot::getRoundNo)
                                .last("LIMIT 1"));
                int updated;
                if (latest != null && latest.getRoundNo() != null) {
                    updated = mapper.update(null, new LambdaUpdateWrapper<DynamicBranchSnapshot>()
                            .eq(DynamicBranchSnapshot::getTenantId, tenant)
                            .eq(DynamicBranchSnapshot::getProcessInstanceId, processInstanceId)
                            .eq(DynamicBranchSnapshot::getNodeKey, nodeKey)
                            .eq(DynamicBranchSnapshot::getSemanticVersion, 2)
                            .eq(DynamicBranchSnapshot::getRoundNo, latest.getRoundNo())
                            .eq(DynamicBranchSnapshot::getStatus, "START")
                            .eq(DynamicBranchSnapshot::getDeleted, 0)
                            .set(DynamicBranchSnapshot::getStatus, "CANCELED")
                            .set(DynamicBranchSnapshot::getCancelReason, reason)
                            .set(DynamicBranchSnapshot::getUpdateTime, java.time.LocalDateTime.now()));
                    return Optional.of(updated > 0
                            ? MutationOutcome.APPLIED : MutationOutcome.ALREADY_APPLIED);
                }
                updated = mapper.update(null, new LambdaUpdateWrapper<DynamicBranchSnapshot>()
                        .eq(DynamicBranchSnapshot::getTenantId, tenant)
                        .eq(DynamicBranchSnapshot::getProcessInstanceId, processInstanceId)
                        .eq(nodeKey != null && !nodeKey.isBlank(),
                                DynamicBranchSnapshot::getNodeKey, nodeKey)
                        .eq(DynamicBranchSnapshot::getStatus, "START")
                        .eq(DynamicBranchSnapshot::getDeleted, 0)
                        .set(DynamicBranchSnapshot::getStatus, "CANCELED")
                        .set(DynamicBranchSnapshot::getCancelReason, reason)
                        .set(DynamicBranchSnapshot::getUpdateTime, LocalDateTime.now()));
                return Optional.of(updated > 0
                        ? MutationOutcome.APPLIED : MutationOutcome.ALREADY_APPLIED);
            }

            private List<FrozenBranch> frozenOf(List<DynamicBranchSnapshot> existing) {
                List<FrozenBranch> result = new ArrayList<>();
                Map<Long, Boolean> seen = new LinkedHashMap<>();
                for (DynamicBranchSnapshot row : existing) {
                    if (row.getLeaderId() == null || "CANCELED".equals(row.getStatus())) {
                        continue;
                    }
                    if (seen.putIfAbsent(row.getLeaderId(), Boolean.TRUE) != null) {
                        continue;
                    }
                    result.add(new FrozenBranch(row.getBranchIndex() == null ? 0 : row.getBranchIndex(),
                            String.valueOf(row.getLeaderId()), row.getDeptIds()));
                }
                return result;
            }

            private DynamicBranchSnapshot baseRow(long tenant, String processInstanceId, String nodeKey,
                                                  String sourceType, String sourceValue, String mode) {
                DynamicBranchSnapshot row = new DynamicBranchSnapshot();
                row.setTenantId(tenant);
                row.setProcessInstanceId(processInstanceId);
                row.setNodeKey(nodeKey);
                row.setSourceType(sourceType);
                row.setSourceValue(sourceValue);
                row.setConvergeMode(mode);
                row.setCreateTime(LocalDateTime.now());
                row.setUpdateTime(LocalDateTime.now());
                row.setDeleted(0);
                return row;
            }

            private long parseTenant(String tenantId) {
                if (tenantId == null) {
                    // 租户变量在流程启动时已强制非空；缺失属异常路径，fail closed 不落租户 0
                    throw new IllegalArgumentException("动态分支端口缺少租户上下文");
                }
                return Long.parseLong(tenantId);
            }

            private long parseLongSafe(String value) {
                try {
                    return Long.parseLong(value);
                } catch (NumberFormatException e) {
                    return Long.MAX_VALUE;
                }
            }
        };
    }
}
