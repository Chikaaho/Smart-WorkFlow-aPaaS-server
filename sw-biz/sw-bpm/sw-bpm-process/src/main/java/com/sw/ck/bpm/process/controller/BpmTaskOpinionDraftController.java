package com.sw.ck.bpm.process.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.entity.BpmTaskOpinionDraft;
import com.sw.ck.bpm.process.mapper.BpmTaskOpinionDraftMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.response.R;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 审批意见暂存：绑定任务、轮次、办理人和意见表单，仅本人可读写。 */
@RestController
@RequestMapping("/workflow/tasks/{taskId}/opinion-draft")
public class BpmTaskOpinionDraftController {
    private final BpmTaskFacade taskFacade;
    private final BpmTaskOpinionDraftMapper mapper;
    private final ObjectMapper objectMapper;

    public BpmTaskOpinionDraftController(BpmTaskFacade taskFacade,
                                         BpmTaskOpinionDraftMapper mapper,
                                         ObjectMapper objectMapper) {
        this.taskFacade = taskFacade;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public R<Map<String, Object>> get(@PathVariable String taskId) {
        LoginUser actor = requireActor();
        BpmTaskDTO task = requireOwnedRunningTask(taskId, actor);
        BpmTaskOpinionDraft draft = find(taskId, actor);
        return R.ok(draft == null ? null : view(task, draft));
    }

    @Transactional
    @PutMapping
    @PreAuthorize("isAuthenticated()")
    public R<Map<String, Object>> save(@PathVariable String taskId,
                                       @RequestBody OpinionDraftRequest request) {
        LoginUser actor = requireActor();
        BpmTaskDTO task = requireOwnedRunningTask(taskId, actor);
        if (request == null || request.comment() != null && request.comment().length() > 20_000) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "审批意见暂存内容过长");
        }
        String opinionJson = toJson(request.opinionData() == null ? Map.of() : request.opinionData());
        if (opinionJson.length() > 200_000) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "审批意见数据过大");
        }
        BpmTaskOpinionDraft draft = find(taskId, actor);
        if (draft == null) {
            draft = new BpmTaskOpinionDraft();
            draft.setTaskId(taskId);
            draft.setProcessInstanceId(task.getProcessInstanceId());
            draft.setNodeKey(task.getTaskDefinitionKey());
            draft.setRoundNo(1);
            draft.setAssigneeId(actor.getUserId());
            draft.setTenantId(actor.getTenantId());
            draft.setOpinionData(opinionJson);
            draft.setOpinionFormId(request.opinionFormId());
            draft.setOpinionFormVersion(request.opinionFormVersion());
            draft.setComment(request.comment());
            mapper.insert(draft);
        } else {
            draft.setNodeKey(task.getTaskDefinitionKey());
            draft.setOpinionData(opinionJson);
            draft.setOpinionFormId(request.opinionFormId());
            draft.setOpinionFormVersion(request.opinionFormVersion());
            draft.setComment(request.comment());
            draft.setUpdateTime(LocalDateTime.now());
            mapper.updateById(draft);
        }
        return R.ok(view(task, draft));
    }

    @Transactional
    @DeleteMapping
    @PreAuthorize("isAuthenticated()")
    public R<Void> delete(@PathVariable String taskId) {
        LoginUser actor = requireActor();
        requireOwnedRunningTask(taskId, actor);
        BpmTaskOpinionDraft draft = find(taskId, actor);
        if (draft == null) return R.ok();
        mapper.deleteById(draft.getId());
        return R.ok();
    }

    private LoginUser requireActor() {
        LoginUser actor = LoginUserHolder.get();
        if (actor == null || actor.getUserId() == null || actor.getTenantId() == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED);
        }
        return actor;
    }

    private BpmTaskDTO requireOwnedRunningTask(String taskId, LoginUser actor) {
        BpmTaskDTO task = taskFacade.getTask(taskId).orElseThrow(
                () -> new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "任务不存在"));
        if (task.getEndTime() != null) {
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "任务已结束，意见暂存不可改写");
        }
        if (task.getTenantId() != null && !task.getTenantId().isBlank()
                && !task.getTenantId().equals(String.valueOf(actor.getTenantId()))) {
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "意见暂存任务不属于当前租户");
        }
        if (task.getAssignee() == null
                || !task.getAssignee().equals(String.valueOf(actor.getUserId()))) {
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "仅当前办理人可访问意见暂存");
        }
        return task;
    }

    private BpmTaskOpinionDraft find(String taskId, LoginUser actor) {
        return mapper.selectOne(Wrappers.<BpmTaskOpinionDraft>lambdaQuery()
                .eq(BpmTaskOpinionDraft::getTaskId, taskId)
                .eq(BpmTaskOpinionDraft::getAssigneeId, actor.getUserId())
                .eq(BpmTaskOpinionDraft::getTenantId, actor.getTenantId())
                .last("LIMIT 1"));
    }

    private Map<String, Object> view(BpmTaskDTO task, BpmTaskOpinionDraft draft) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", draft.getTaskId());
        out.put("processInstanceId", task.getProcessInstanceId());
        out.put("nodeKey", draft.getNodeKey());
        out.put("roundNo", draft.getRoundNo());
        out.put("assigneeId", String.valueOf(draft.getAssigneeId()));
        out.put("opinionFormId", draft.getOpinionFormId());
        out.put("opinionFormVersion", draft.getOpinionFormVersion());
        out.put("comment", draft.getComment());
        try {
            out.put("opinionData", objectMapper.readValue(
                    draft.getOpinionData() == null ? "{}" : draft.getOpinionData(),
                    new TypeReference<Map<String, Object>>() {}));
        } catch (Exception ignored) {
            out.put("opinionData", Map.of());
        }
        out.put("updateTime", draft.getUpdateTime());
        return out;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "审批意见数据格式无效");
        }
    }

    public record OpinionDraftRequest(String opinionFormId, String opinionFormVersion,
                                      String comment, Map<String, Object> opinionData) {}
}
