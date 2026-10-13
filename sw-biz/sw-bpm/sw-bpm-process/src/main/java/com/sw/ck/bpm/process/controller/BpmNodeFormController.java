package com.sw.ck.bpm.process.controller;

import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.BpmTaskFormData;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.NodeFormDataService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.response.R;
import com.sw.ck.security.holder.LoginUserHolder;
import com.fasterxml.jackson.core.type.TypeReference;import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * P64 节点业务表单控制器（A01）。
 * <p>
 * 办理页读取绑定与数据（草稿/已提交）、保存草稿；最终提交经任务动作请求的
 * {@code nodeFormData} 与任务完成同事务生效；实例维度历史回看按任务/轮次列出。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/workflow")
public class BpmNodeFormController {

    private final BpmTaskFacade bpmTaskFacade;
    private final BpmInstanceService bpmInstanceService;
    private final NodeFormDataService nodeFormDataService;
    private final ObjectMapper objectMapper;

    public BpmNodeFormController(BpmTaskFacade bpmTaskFacade,
                                 BpmInstanceService bpmInstanceService,
                                 NodeFormDataService nodeFormDataService,
                                 ObjectMapper objectMapper) {
        this.bpmTaskFacade = bpmTaskFacade;
        this.bpmInstanceService = bpmInstanceService;
        this.nodeFormDataService = nodeFormDataService;
        this.objectMapper = objectMapper;
    }

    /**
     * 读取任务节点业务表单：绑定 + definition + 当前数据（草稿或已提交）。
     * <p>
     * 越权边界（与 {@code TaskActionService} 办理校验同语义，fail closed）：
     * 任务办理人（assignee/candidate）或实例发起人可读；写入（草稿）仅限办理人。
     * </p>
     */
    @GetMapping("/tasks/{taskId}/node-form")
    public R<Map<String, Object>> getTaskNodeForm(@PathVariable String taskId) {
        BpmTaskDTO task = bpmTaskFacade.getTask(taskId)
                .orElseThrow(() -> new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "任务不存在"));
        BpmInstance instance = bpmInstanceService.findByProcessInstanceId(task.getProcessInstanceId())
                .orElseThrow(() -> new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "流程实例不存在"));
        assertNodeFormReadable(task, instance);
        Map<String, Object> result = new LinkedHashMap<>();
        Optional<NodeFormDataService.NodeFormBinding> binding = nodeFormDataService.resolveBinding(
                task.getProcessDefinitionKey(), instance.getDefVersion(), task.getTaskDefinitionKey());
        if (binding.isEmpty()) {
            result.put("bound", false);
            return R.ok(result);
        }
        result.put("bound", true);
        result.put("formKey", binding.get().formKey());
        result.put("formName", binding.get().formName());
        Long tenantId = LoginUserHolder.get() == null ? null : LoginUserHolder.get().getTenantId();
        BpmTaskFormData data = tenantId == null ? null
                : nodeFormDataService.findByTaskId(tenantId, taskId).orElse(null);
        // 任务级绑定版本冻结（审查02 P1-04b；复审05 修正绑定口径）：任务数据行已建立时以该行
        // 绑定版本渲染/校验；未建立任务行时按发布冻结图记录的绑定版本（任务创建即已绑定，
        // 表单再发布不漂移）；冻结图无记录的历史图回退当前已发布版本（旧无绑定兼容）。
        Long boundVersion = data != null && data.getFormVersion() != null
                ? data.getFormVersion()
                : NodeFormDataService.parseBindingVersion(binding.get().formVersion());
        result.put("formVersion", boundVersion == null ? null : String.valueOf(boundVersion));
        // 绑定版本快照缺失：可诊断拒绝，不按最新定义静默渲染（复审03 P1-04b）
        Optional<String> definition = nodeFormDataService
                .loadBoundDefinition(binding.get().formKey(), boundVersion);
        if (definition.isEmpty()) {
            throw new BaseException(com.sw.ck.bpm.api.exception.BpmErrorCode.NODE_FORM_NOT_BOUND.getCode(),
                    boundVersion == null
                            ? "节点表单当前定义不可用: " + binding.get().formKey()
                            : "任务节点表单绑定版本快照缺失: " + binding.get().formKey() + "@v" + boundVersion
                            + "（不按最新定义静默渲染，请管理员修复快照）");
        }
        // 契约形状：definition 为结构化对象（fields 供办理页渲染），不是 JSON 字符串
        try {
            result.put("definition", objectMapper.readValue(definition.get(),
                    new TypeReference<Map<String, Object>>() { }));
        } catch (Exception e) {
            throw new BaseException(com.sw.ck.bpm.api.exception.BpmErrorCode.NODE_FORM_NOT_BOUND.getCode(),
                    "节点表单定义解析失败: " + binding.get().formKey() + "@v" + boundVersion
                            + "（" + e.getMessage() + "）");
        }
        if (data != null) {
            result.put("status", data.getStatus());
            result.put("data", nodeFormDataService.parseData(data.getDataText()));
            result.put("roundNo", data.getRoundNo());
        } else {
            result.put("status", "EMPTY");
            result.put("data", Map.of());
        }
        return R.ok(result);
    }

    /**
     * 保存草稿（DRAFT；已最终提交的任务拒绝改写；仅任务办理人可写）。
     */
    @PostMapping("/tasks/{taskId}/node-form/draft")
    public R<Long> saveDraft(@PathVariable String taskId,
                             @RequestBody Map<String, Object> body) {
        BpmTaskDTO task = bpmTaskFacade.getTask(taskId)
                .orElseThrow(() -> new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "任务不存在"));
        BpmInstance instance = bpmInstanceService.findByProcessInstanceId(task.getProcessInstanceId())
                .orElseThrow(() -> new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "流程实例不存在"));
        assertNodeFormWritable(task);
        Optional<NodeFormDataService.NodeFormBinding> binding = nodeFormDataService.resolveBinding(
                task.getProcessDefinitionKey(), instance.getDefVersion(), task.getTaskDefinitionKey());
        if (binding.isEmpty()) {
            throw new BaseException(com.sw.ck.bpm.api.exception.BpmErrorCode.NODE_FORM_NOT_BOUND);
        }
        Map<String, Object> data = body == null ? Map.of()
                : body.get("data") instanceof Map<?, ?> map
                ? objectMapper.convertValue(map, new TypeReference<Map<String, Object>>() { })
                : objectMapper.convertValue(body, new TypeReference<Map<String, Object>>() { });
        Long id = nodeFormDataService.saveDraft(instance.getProcessInstanceId(),
                instance.getProcessDefKey(), task.getTaskDefinitionKey(), taskId,
                binding.get().formKey(), NodeFormDataService.parseBindingVersion(binding.get().formVersion()), data);
        return R.ok(id);
    }

    /**
     * 实例维度节点表单数据历史回看（含各任务/轮次数据、状态与提交人）。
     */
    @GetMapping("/instances/{instanceId}/node-form-data")
    public R<List<Map<String, Object>>> listInstanceNodeFormData(@PathVariable String instanceId) {
        Long tenantId = LoginUserHolder.get() == null ? null : LoginUserHolder.get().getTenantId();
        if (tenantId == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED);
        }
        List<BpmTaskFormData> rows = nodeFormDataService.listByInstance(tenantId, instanceId);
        return R.ok(rows.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("processInstanceId", row.getProcessInstanceId());
            item.put("nodeKey", row.getNodeKey());
            item.put("taskId", row.getTaskId());
            item.put("roundNo", row.getRoundNo());
            item.put("formKey", row.getFormKey());
            item.put("formVersion", row.getFormVersion());
            item.put("status", row.getStatus());
            item.put("data", nodeFormDataService.parseData(row.getDataText()));
        item.put("submittedBy", row.getSubmittedBy());
        item.put("submitTime", row.getSubmitTime());
        return item;
    }).toList());
    }

    // ==================== 越权边界 ====================

    /** 当前登录用户 ID（未登录= null）。 */
    private Long currentUserId() {
        return LoginUserHolder.get() == null ? null : LoginUserHolder.get().getUserId();
    }

    /**
     * 读取边界：任务办理人（assignee/candidate）或实例发起人；无法判定时 fail closed。
     */
    private void assertNodeFormReadable(BpmTaskDTO task, BpmInstance instance) {
        Long userId = currentUserId();
        if (userId == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED);
        }
        boolean assigned = String.valueOf(userId).equals(task.getAssignee());
        boolean canHandle = bpmTaskFacade.canHandle(task.getTaskId(), String.valueOf(userId))
                .orElse(Boolean.FALSE);
        boolean initiator = instance.getInitiatorId() != null && userId.equals(instance.getInitiatorId());
        if (!assigned && !canHandle && !initiator) {
            log.warn("节点表单读取越权拒绝: taskId={}, assignee={}, currentUserId={}",
                    task.getTaskId(), task.getAssignee(), userId);
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "无权查看该任务节点表单");
        }
    }

    /**
     * 写入边界（草稿/最终提交）：仅任务办理人；无法判定时 fail closed（与办理校验同语义）。
     */
    private void assertNodeFormWritable(BpmTaskDTO task) {
        Long userId = currentUserId();
        if (userId == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED);
        }
        boolean assigned = String.valueOf(userId).equals(task.getAssignee());
        boolean canHandle = bpmTaskFacade.canHandle(task.getTaskId(), String.valueOf(userId))
                .orElse(Boolean.FALSE);
        if (!assigned && !canHandle) {
            log.warn("节点表单写入越权拒绝: taskId={}, assignee={}, currentUserId={}",
                    task.getTaskId(), task.getAssignee(), userId);
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "无权填写该任务节点表单");
        }
    }
}
