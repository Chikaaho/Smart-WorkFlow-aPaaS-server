package com.sw.ck.bpm.process.controller;

import com.sw.ck.bpm.api.dto.BpmActivityDTO;
import com.sw.ck.bpm.api.facade.BpmRuntimeFacade;
import com.sw.ck.bpm.process.dto.InstanceDetailDTO;
import com.sw.ck.bpm.process.dto.InstanceFilterDTO;
import com.sw.ck.bpm.process.dto.InstanceListItemDTO;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.bpm.process.service.ParticipantNameService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 流程实例监控控制器。
 * <p>
 * 提供流程实例的分页列表查询和详情（含流程图高亮数据 + 流转记录）。
 * 所有 Flowable 引擎操作经 {@link BpmRuntimeFacade} 完成，本 Controller 不直接依赖 Flowable 类型。
 * </p>
 *
 * <h3>端点</h3>
 * <ul>
 *   <li>{@code GET /workflow/instances} — 分页实例列表（支持状态/流程定义/发起人过滤）</li>
 *   <li>{@code GET /workflow/instances/{processInstanceId}} — 实例详情（含活跃节点 + 流转记录）</li>
 * </ul>
 *
 * <h3>防腐</h3>
 * 本 Controller 不 import 任何 Flowable 类型；所有引擎操作经 {@link BpmRuntimeFacade} 完成。
 */
@RestController
@RequestMapping("/workflow/instances")
public class BpmInstanceController {

    private static final Logger log = LoggerFactory.getLogger(BpmInstanceController.class);

    private final BpmInstanceService bpmInstanceService;
    private final BpmRuntimeFacade bpmRuntimeFacade;
    private final BpmProcessDefService bpmProcessDefService;
    private final UserQueryFacade userQueryFacade;
    private final ParticipantNameService participantNameService;
    private final com.sw.ck.security.support.PermissionService permissionService;
    private final com.sw.ck.bpm.process.mapper.CopyRecordMapper copyRecordMapper;

    public BpmInstanceController(BpmInstanceService bpmInstanceService,
                                  BpmRuntimeFacade bpmRuntimeFacade,
                                  BpmProcessDefService bpmProcessDefService,
                                  UserQueryFacade userQueryFacade,
                                  ParticipantNameService participantNameService,
                                  com.sw.ck.security.support.PermissionService permissionService,
                                  com.sw.ck.bpm.process.mapper.CopyRecordMapper copyRecordMapper) {
        this.bpmInstanceService = bpmInstanceService;
        this.bpmRuntimeFacade = bpmRuntimeFacade;
        this.bpmProcessDefService = bpmProcessDefService;
        this.userQueryFacade = userQueryFacade;
        this.participantNameService = participantNameService;
        this.permissionService = permissionService;
        this.copyRecordMapper = copyRecordMapper;
    }

    /**
     * 分页查询流程实例列表。
     * <p>
     * 支持可选过滤条件：状态（status）、流程定义 key（processDefKey）、发起人 ID（initiatorId）。
     * 所有过滤字段均为可选——不传或传空字符串表示不过滤。
     * 返回列表按创建时间倒序（最新实例在前），每条记录含 processName 富化。
     * 只查当前租户（MyBatis-Plus 拦截器自动注入 tenant_id，Service 层已处理）。
     * </p>
     *
     * @param pageParam 分页参数（pageNum 默认 1，pageSize 默认 10）
     * @param filter    过滤条件（所有字段可选）
     * @return 分页实例列表
     */
    @GetMapping
    @org.springframework.security.access.prepost.PreAuthorize("@ss.hasPermi('workflow:monitor:view')")
    public R<PageResult<InstanceListItemDTO>> listInstances(PageParam pageParam,
                                                             InstanceFilterDTO filter) {
        PageResult<BpmInstance> page = bpmInstanceService.pageInstances(pageParam, filter);

        List<InstanceListItemDTO> dtos = page.getRecords().stream()
                .map(this::toListItemDTO)
                .collect(Collectors.toList());

        PageResult<InstanceListItemDTO> result = new PageResult<>();
        result.setRecords(dtos);
        result.setTotal(page.getTotal());
        result.setPageNum(page.getPageNum());
        result.setPageSize(page.getPageSize());

        log.debug("实例列表查询: total={}, pageNum={}, pageSize={}",
                page.getTotal(), pageParam.getPageNum(), pageParam.getPageSize());
        return R.ok(result);
    }

    /**
     * 查询流程实例详情。
     * <p>
     * 返回实例基本信息 + 当前活跃节点列表（流程图绿色高亮用）+
     * 全部历史活动节点（流转时间线用）。
     * 活跃节点和流转记录均可能为空列表（已结束 / 刚启动）。
     * </p>
     *
     * @param processInstanceId Flowable 流程实例 ID
     * @return 实例详情（含活跃节点 + 流转记录）
     * @throws BaseException 实例不存在时抛出（code=404）
     */
    @GetMapping("/{processInstanceId}")
    public R<InstanceDetailDTO> instanceDetail(@PathVariable String processInstanceId) {
        BpmInstance instance = bpmInstanceService.findByProcessInstanceId(processInstanceId)
                .orElseThrow(() -> new BaseException(
                        CommonErrorCode.NOT_FOUND.getCode(), "流程实例不存在"));
        assertInstanceReadable(instance, processInstanceId);

        List<String> activeNodeIds = bpmRuntimeFacade.getActiveActivityIds(processInstanceId);
        List<BpmActivityDTO> flowTrace = bpmRuntimeFacade.queryHistoricActivities(processInstanceId);

        // 候选模式任务在 Flowable 历史中无 assignee（引擎层 approver 兜底会误填为发起人），
        // 权威参与人以节点进入时冻结的快照为准：快照命中即覆盖（I1 G5b）。
        // 任务级快照优先：动态并行多分支共用同一 node_key，节点级覆盖会把多条分支
        // 改写为同一办理人（I4 G1a），taskId 命中才回退节点级。
        Map<String, Long> nodeAssignees = participantNameService.resolveNodeAssignees(processInstanceId);
        Map<String, Long> taskAssignees = participantNameService.resolveTaskAssignees(processInstanceId);
        flowTrace.forEach(a -> {
            if ("userTask".equals(a.getActivityType())) {
                Long pid = a.getTaskId() != null ? taskAssignees.get(a.getTaskId()) : null;
                if (pid == null) {
                    pid = nodeAssignees.get(a.getActivityId());
                }
                if (pid != null) {
                    a.setAssignee(String.valueOf(pid));
                }
            }
        });

        // 审批人展示名富化（快照冻结名优先，可读身份不随后续改名重写；查询失败不阻断详情）
        Map<Long, String> assigneeNames = participantNameService.resolveDisplayNames(processInstanceId,
                flowTrace.stream()
                .map(BpmActivityDTO::getAssignee)
                .filter(a -> a != null && a.matches("\\d+"))
                .map(Long::valueOf)
                .collect(Collectors.toSet()));
        flowTrace.forEach(a -> {
            if (a.getAssignee() != null && a.getAssignee().matches("\\d+")) {
                a.setAssigneeName(assigneeNames.get(Long.valueOf(a.getAssignee())));
            }
        });

        InstanceDetailDTO dto = toDetailDTO(instance, activeNodeIds, flowTrace,
                bpmRuntimeFacade.getProcessVariables(processInstanceId).get("formData"));

        log.debug("实例详情查询: processInstanceId={}, activeNodes={}, flowTraceSize={}",
                processInstanceId, activeNodeIds.size(), flowTrace.size());
        return R.ok(dto);
    }

    // ==================== 内部方法 ====================

    /**
     * 实例详情对象权限（I4 §3.3/§4：数据范围作用于明细，跨用户零串读）。
     * <p>
     * 允许读取：超级管理员；持有 workflow:monitor:view 的运营身份；
     * 发起人本人；该实例的参与人（历史/活跃任务办理人）；抄送接收人。
     * 其余身份一律拒绝，前端隐藏不替代服务端拒绝。
     * </p>
     */
    private void assertInstanceReadable(BpmInstance instance, String processInstanceId) {
        var loginUser = com.sw.ck.security.holder.LoginUserHolder.get();
        if (loginUser == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED.getCode(), "未认证");
        }
        if (loginUser.isSuperAdmin()) {
            return;
        }
        if (permissionService.hasPermi("workflow:monitor:view")) {
            return;
        }
        if (instance.getInitiatorId() != null
                && instance.getInitiatorId().equals(loginUser.getUserId())) {
            return;
        }
        try {
            boolean participant = bpmRuntimeFacade.queryHistoricActivities(processInstanceId).stream()
                    .anyMatch(a -> "userTask".equals(a.getActivityType())
                            && loginUser.getUserId() != null
                            && loginUser.getUserId().toString().equals(a.getAssignee()));
            if (participant) {
                return;
            }
        } catch (Exception e) {
            log.warn("实例参与人读取判定失败，按无参与处理: processInstanceId={}, {}",
                    processInstanceId, e.getMessage());
        }
        Long copyCount = copyRecordMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.sw.ck.bpm.process.entity.CopyRecord>()
                        .eq(com.sw.ck.bpm.process.entity.CopyRecord::getProcessInstanceId, processInstanceId)
                        .eq(com.sw.ck.bpm.process.entity.CopyRecord::getRecipientId,
                                String.valueOf(loginUser.getUserId())));
        if (copyCount != null && copyCount > 0) {
            return;
        }
        log.warn("实例详情越权拒绝: processInstanceId={}, currentUser={}",
                processInstanceId, loginUser.getUserId());
        throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "无权查看该流程实例");
    }

    /**
     * 将 BpmInstance 实体裁剪为列表项 DTO，并富化 processName。
     * <p>
     * processName 通过 processDefKey 查 BpmProcessDefService 获取。
     * 若流程定义已删除导致查不到，processName 置为 null（不阻断列表查询）。
     * </p>
     */
    /**
     * 按 ID 批量解析用户展示名；查不到的 ID 返回 null，不阻断查询。
     */
    private Map<Long, String> resolveUserNames(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        try {
            return userQueryFacade.getUserDisplayNames(ids);
        } catch (Exception e) {
            log.warn("用户展示名批量查询失败，回退为 null: {}", e.getMessage());
            return Map.of();
        }
    }

    private InstanceListItemDTO toListItemDTO(BpmInstance entity) {
        InstanceListItemDTO dto = new InstanceListItemDTO();
        dto.setId(entity.getId());
        dto.setProcessInstanceId(entity.getProcessInstanceId());
        dto.setProcessDefKey(entity.getProcessDefKey());
        dto.setBusinessKey(entity.getBusinessKey());
        dto.setFormKey(entity.getFormKey());
        dto.setInitiatorId(entity.getInitiatorId());
        dto.setInitiatorName(resolveUserNames(
                entity.getInitiatorId() == null ? List.of() : List.of(entity.getInitiatorId()))
                .get(entity.getInitiatorId()));
        dto.setStatus(entity.getStatus());
        dto.setCreateTime(entity.getCreateTime());

        // processName 富化
        if (entity.getProcessDefKey() != null) {
            BpmProcessDef processDef = bpmProcessDefService.findByProcessKey(entity.getProcessDefKey());
            if (processDef != null) {
                dto.setProcessName(processDef.getName());
            }
        }

        return dto;
    }

    /**
     * 构建实例详情 DTO。
     * <p>
     * 继承 {@link #toListItemDTO(BpmInstance)} 的字段裁剪 + processName 富化，
     * 再追加 activeNodeIds 和 flowTrace。
     * </p>
     */
    private InstanceDetailDTO toDetailDTO(BpmInstance instance,
                                           List<String> activeNodeIds,
                                           List<BpmActivityDTO> flowTrace,
                                           Object formDataVariable) {
        InstanceDetailDTO dto = new InstanceDetailDTO();
        if (formDataVariable instanceof Map<?, ?> formDataMap) {
            java.util.Map<String, Object> formData = new java.util.LinkedHashMap<>();
            formDataMap.forEach((k, v) -> formData.put(String.valueOf(k), v));
            dto.setFormData(formData);
        }
        // 继承 InstanceListItemDTO 的字段
        dto.setId(instance.getId());
        dto.setProcessInstanceId(instance.getProcessInstanceId());
        dto.setProcessDefKey(instance.getProcessDefKey());
        dto.setBusinessKey(instance.getBusinessKey());
        dto.setFormKey(instance.getFormKey());
        dto.setInitiatorId(instance.getInitiatorId());
        dto.setInitiatorName(resolveUserNames(
                instance.getInitiatorId() == null ? List.of() : List.of(instance.getInitiatorId()))
                .get(instance.getInitiatorId()));
        dto.setStatus(instance.getStatus());
        dto.setCreateTime(instance.getCreateTime());

        // processName 富化
        if (instance.getProcessDefKey() != null) {
            BpmProcessDef processDef = bpmProcessDefService.findByProcessKey(instance.getProcessDefKey());
            if (processDef != null) {
                dto.setProcessName(processDef.getName());
            }
        }

        // 监控特有字段
        dto.setActiveNodeIds(activeNodeIds);
        dto.setFlowTrace(flowTrace);

        return dto;
    }
}
