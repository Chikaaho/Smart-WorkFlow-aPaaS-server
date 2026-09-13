package com.sw.ck.bpm.process.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sw.ck.bpm.api.participant.DynamicBranchPort;
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
            public List<FrozenBranch> freeze(String tenantId, String processInstanceId, String nodeKey,
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
                    return frozenOf(existing); // 冻结快照权威：不重算
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
                return result;
            }

            @Override
            public void recordAction(String tenantId, String processInstanceId, String nodeKey,
                                     String leaderId, String taskId, String action, String reason) {
                long tenant = parseTenant(tenantId);
                DynamicBranchSnapshot current = mapper.selectOne(
                        new LambdaQueryWrapper<DynamicBranchSnapshot>()
                                .eq(DynamicBranchSnapshot::getTenantId, tenant)
                                .eq(DynamicBranchSnapshot::getProcessInstanceId, processInstanceId)
                                .eq(DynamicBranchSnapshot::getNodeKey, nodeKey)
                                .eq(DynamicBranchSnapshot::getLeaderId, Long.valueOf(leaderId))
                                .eq(DynamicBranchSnapshot::getDeleted, 0)
                                .orderByAsc(DynamicBranchSnapshot::getBranchIndex)
                                .last("LIMIT 1"));
                if (current == null) {
                    return; // 冻结前回调（防御）：不凭空造分支
                }
                DynamicBranchSnapshot patch = new DynamicBranchSnapshot();
                patch.setId(current.getId());
                if (taskId != null && (current.getTaskId() == null || current.getTaskId().isBlank())) {
                    patch.setTaskId(taskId);
                }
                if ("START".equals(action)) {
                    if ("CANCELED".equals(current.getStatus())) {
                        return; // 取消态不被 START 覆写
                    }
                    patch.setStatus("START");
                } else if ("APPROVE".equals(action) || "DISAPPROVE".equals(action)) {
                    patch.setStatus(action);
                } else if ("CANCEL".equals(action)) {
                    if ("APPROVE".equals(current.getStatus()) || "DISAPPROVE".equals(current.getStatus())) {
                        return; // 已终态动作的分支不改写
                    }
                    patch.setStatus("CANCELED");
                    patch.setCancelReason(reason);
                } else {
                    return;
                }
                patch.setUpdateTime(LocalDateTime.now());
                mapper.updateById(patch);
            }

            @Override
            public void closeRemaining(String tenantId, String processInstanceId, String nodeKey,
                                       String reason) {
                long tenant = parseTenant(tenantId);
                mapper.update(null, new LambdaUpdateWrapper<DynamicBranchSnapshot>()
                        .eq(DynamicBranchSnapshot::getTenantId, tenant)
                        .eq(DynamicBranchSnapshot::getProcessInstanceId, processInstanceId)
                        .eq(nodeKey != null && !nodeKey.isBlank(),
                                DynamicBranchSnapshot::getNodeKey, nodeKey)
                        .eq(DynamicBranchSnapshot::getStatus, "START")
                        .eq(DynamicBranchSnapshot::getDeleted, 0)
                        .set(DynamicBranchSnapshot::getStatus, "CANCELED")
                        .set(DynamicBranchSnapshot::getCancelReason, reason)
                        .set(DynamicBranchSnapshot::getUpdateTime, LocalDateTime.now()));
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
                try {
                    return tenantId == null ? 0L : Long.parseLong(tenantId);
                } catch (NumberFormatException e) {
                    return 0L;
                }
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
