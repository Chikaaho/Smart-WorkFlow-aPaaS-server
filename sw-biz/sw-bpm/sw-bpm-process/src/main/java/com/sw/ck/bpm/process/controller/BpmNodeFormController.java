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
     */
    @GetMapping("/tasks/{taskId}/node-form")
    public R<Map<String, Object>> getTaskNodeForm(@PathVariable String taskId) {
        BpmTaskDTO task = bpmTaskFacade.getTask(taskId)
                .orElseThrow(() -> new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "任务不存在"));
        BpmInstance instance = bpmInstanceService.findByProcessInstanceId(task.getProcessInstanceId())
                .orElseThrow(() -> new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "流程实例不存在"));
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
        result.put("formVersion", binding.get().formVersion());
        result.put("definition", nodeFormDataService.loadFormDefinition(binding.get().formKey()));
        Long tenantId = LoginUserHolder.get() == null ? null : LoginUserHolder.get().getTenantId();
        BpmTaskFormData data = tenantId == null ? null
                : nodeFormDataService.findByTaskId(tenantId, taskId).orElse(null);
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
     * 保存草稿（DRAFT；已最终提交的任务拒绝改写）。
     */
    @PostMapping("/tasks/{taskId}/node-form/draft")
    public R<Long> saveDraft(@PathVariable String taskId,
                             @RequestBody Map<String, Object> body) {
        BpmTaskDTO task = bpmTaskFacade.getTask(taskId)
                .orElseThrow(() -> new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "任务不存在"));
        BpmInstance instance = bpmInstanceService.findByProcessInstanceId(task.getProcessInstanceId())
                .orElseThrow(() -> new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "流程实例不存在"));
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
                binding.get().formKey(), data);
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
}
