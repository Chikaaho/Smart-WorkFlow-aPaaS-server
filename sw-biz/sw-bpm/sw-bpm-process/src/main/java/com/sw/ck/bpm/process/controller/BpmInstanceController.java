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

    public BpmInstanceController(BpmInstanceService bpmInstanceService,
                                  BpmRuntimeFacade bpmRuntimeFacade,
                                  BpmProcessDefService bpmProcessDefService,
                                  UserQueryFacade userQueryFacade) {
        this.bpmInstanceService = bpmInstanceService;
        this.bpmRuntimeFacade = bpmRuntimeFacade;
        this.bpmProcessDefService = bpmProcessDefService;
        this.userQueryFacade = userQueryFacade;
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

        List<String> activeNodeIds = bpmRuntimeFacade.getActiveActivityIds(processInstanceId);
        List<BpmActivityDTO> flowTrace = bpmRuntimeFacade.queryHistoricActivities(processInstanceId);

        // 审批人展示名富化（可读身份回显；查询失败不阻断详情）
        Map<Long, String> assigneeNames = resolveUserNames(flowTrace.stream()
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
