package com.sw.ck.bpm.process.controller;

import com.sw.ck.bpm.process.entity.BpmInstanceIntervention;
import com.sw.ck.bpm.process.service.BpmMonitorService;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 流程运营监控/干预/基础分析（I4 §3.3）。管理端专用权限；数据范围同时约束汇总与明细。
 */
@Slf4j
@RestController
@RequestMapping("/workflow/monitor")
public class BpmMonitorController {

    private final BpmMonitorService monitorService;

    public BpmMonitorController(BpmMonitorService monitorService) {
        this.monitorService = monitorService;
    }

    @Data
    public static class InterveneRequest {
        private String action;
        private String reason;
        private Long toAssignee;
        /** 可选迁移任务集合：仅 TRANSFER 且提供时迁移所选子集，未选任务保持原办理人。 */
        private java.util.List<String> taskIds;
    }

    /** 七类条件检索：defKey / instanceId / initiator / status / nodeKey / assignee / 时间范围。 */
    @PreAuthorize("@ss.hasPermi('workflow:monitor:view')")
    @GetMapping("/instances")
    public R<PageResult<BpmMonitorService.InstanceMonitorView>> instances(
            PageParam pageParam,
            @RequestParam(required = false) String processDefKey,
            @RequestParam(required = false) String processInstanceId,
            @RequestParam(required = false) Long initiatorId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String nodeKey,
            @RequestParam(required = false) Long assignee,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime timeFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime timeTo) {
        return R.ok(monitorService.monitorInstances(pageParam, new BpmMonitorService.MonitorQuery(
                processDefKey, processInstanceId, initiatorId, status, nodeKey, assignee,
                timeFrom, timeTo)));
    }

    @PreAuthorize("@ss.hasPermi('workflow:monitor:manage')")
    @PostMapping("/instances/{processInstanceId}/intervene")
    public R<BpmInstanceIntervention> intervene(@PathVariable String processInstanceId,
                                                @RequestBody InterveneRequest request) {
        BpmInstanceIntervention record = monitorService.intervene(processInstanceId,
                request.getAction(), request.getReason(), request.getToAssignee(), request.getTaskIds());
        log.info("实例干预接口已执行: instance={}, action={}", processInstanceId, request.getAction());
        return R.ok(record);
    }

    @PreAuthorize("@ss.hasPermi('workflow:monitor:view')")
    @GetMapping("/instances/{processInstanceId}/interventions")
    public R<List<BpmInstanceIntervention>> interventions(@PathVariable String processInstanceId) {
        return R.ok(monitorService.interventions(processInstanceId));
    }

    /** 动态并行分支冻结快照勾稽（I4 §3.1 运营回读）：逐条展示冻结来源部门/负责人/状态/取消原因。 */
    @PreAuthorize("@ss.hasPermi('workflow:monitor:view')")
    @GetMapping("/instances/{processInstanceId}/deadlines")
    public R<List<Map<String, Object>>> deadlines(@PathVariable String processInstanceId) {
        return R.ok(monitorService.deadlines(processInstanceId));
    }

    @PreAuthorize("@ss.hasPermi('workflow:monitor:view')")
    @GetMapping("/instances/{processInstanceId}/branches")
    public R<List<com.sw.ck.bpm.process.entity.DynamicBranchSnapshot>> branches(
            @PathVariable String processInstanceId) {
        return R.ok(monitorService.branches(processInstanceId));
    }

    @PreAuthorize("@ss.hasPermi('workflow:monitor:view')")
    @GetMapping("/analytics/summary")
    public R<Map<String, Object>> analyticsSummary(
            @RequestParam(required = false) String processDefKey,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime timeFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime timeTo) {
        return R.ok(monitorService.analyticsSummary(processDefKey, timeFrom, timeTo));
    }
}
