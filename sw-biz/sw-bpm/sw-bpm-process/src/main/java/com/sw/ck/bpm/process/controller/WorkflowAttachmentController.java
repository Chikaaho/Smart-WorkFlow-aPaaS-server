package com.sw.ck.bpm.process.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.process.entity.ApprovalActionRecord;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.CopyRecord;
import com.sw.ck.bpm.process.mapper.ApprovalActionRecordMapper;
import com.sw.ck.bpm.process.mapper.CopyRecordMapper;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.response.R;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.storage.api.StorageFacade;
import com.sw.ck.form.api.form.FormDefinitionService;
import com.sw.ck.storage.api.StorageUploadResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 表单附件/图片端点（v0.0.2 OA，P2 表单子集）。
 * <p>
 * 上传：登录用户可用（上传产物在关联前为孤立文件，关联与查看另受对象权限约束）。
 * 下载：按记录对象权限放行——实例发起人、当前待办人（含候选）、抄送接收人、
 * 超管或持有 {@code form:data:list} 的数据管理者；其余一律拒绝。
 * 记录可读后仍需通过表单字段查看权与文件引用核验，storageKey本身不携带授权语义。
 * </p>
 */
@RestController
@RequestMapping("/workflow/attachments")
public class WorkflowAttachmentController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowAttachmentController.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /** 意见数据里附件字段的键名口径（与前端展示口径一致）。 */
    private static final Pattern OPINION_ATTACHMENT_KEY =
            Pattern.compile("^(attachments?|files?|附件|附录)", Pattern.CASE_INSENSITIVE);

    private final StorageFacade storageFacade;
    private final ApprovalActionRecordMapper approvalActionRecordMapper;
    private final FormDefinitionService formDefinitionService;
    private final BpmInstanceService bpmInstanceService;
    private final BpmTaskFacade bpmTaskFacade;
    private final CopyRecordMapper copyRecordMapper;

    public WorkflowAttachmentController(StorageFacade storageFacade,
                                        ApprovalActionRecordMapper approvalActionRecordMapper,
                                        FormDefinitionService formDefinitionService,
                                        BpmInstanceService bpmInstanceService,
                                        BpmTaskFacade bpmTaskFacade,
                                        CopyRecordMapper copyRecordMapper) {
        this.storageFacade = storageFacade;
        this.approvalActionRecordMapper = approvalActionRecordMapper;
        this.formDefinitionService = formDefinitionService;
        this.bpmInstanceService = bpmInstanceService;
        this.bpmTaskFacade = bpmTaskFacade;
        this.copyRecordMapper = copyRecordMapper;
    }

    /** 上传附件/图片（登录用户），返回 storageKey 供表单字段关联。 */
    @PostMapping("/upload")
    public R<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "上传文件不能为空");
        }
        // upload 当前契约恒 present（上传失败抛异常，不以上空表达失败）
        StorageUploadResult result = storageFacade.upload(file.getInputStream(),
                        file.getOriginalFilename(), file.getContentType())
                .orElseThrow(() -> new IllegalStateException("文件上传未返回结果"));
        return R.ok(Map.of(
                "storageKey", result.getStorageKey(),
                "storageName", result.getStorageName() == null ? "" : result.getStorageName(),
                "fileSize", result.getFileSize() == null ? 0L : result.getFileSize()));
    }

    /**
     * 按对象权限下载附件/图片。
     *
     * @param recordId   表单记录 id（= 流程 businessKey）
     * @param storageKey 存储键（服务端核实该文件由当前可见字段实际引用）
     * @param formKey    无流程实例记录时的表单标识；有实例时按实例归属解析并拒绝冲突值
     */
    @GetMapping("/{recordId}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable String recordId,
                                                        @RequestParam("storageKey") String storageKey,
                                                        @RequestParam(value = "name", required = false) String name,
                                                        @RequestParam(value = "formKey", required = false) String formKey)
            throws IOException {
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED.getCode(), "未登录");
        }
        if (storageKey == null || storageKey.isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "storageKey 不能为空");
        }
        BpmInstance instance = bpmInstanceService.lambdaQuery()
                .eq(BpmInstance::getBusinessKey, recordId)
                .last("LIMIT 1")
                .one();
        String resolvedFormKey = instance == null ? formKey : instance.getFormKey();
        boolean formMatches = formKey == null || formKey.isBlank()
                || formKey.equals(resolvedFormKey);
        if (!hasRecordAccess(loginUser, instance) || !formMatches
                || resolvedFormKey == null || resolvedFormKey.isBlank()
                || !formDefinitionService.canCurrentUserAccessAttachment(
                        resolvedFormKey, recordId, storageKey).orElse(false)) {
            log.warn("附件下载越权拒绝: recordId={}, user={}", recordId, loginUser.getUserId());
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "无权访问该记录附件");
        }
        // empty = 该 storageKey 无存储记录或已逻辑删除（原 getFileOrThrow 的 NOT_FOUND 缺失路径）：
        // 对外保持原有 404 语义，不改变下载接口行为
        var stream = storageFacade.download(storageKey)
                .orElseThrow(() -> new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "附件不存在"));
        String fileName = (name == null || name.isBlank()) ? storageKey : name;
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename*=UTF-8''"
                        + java.net.URLEncoder.encode(fileName, java.nio.charset.StandardCharsets.UTF_8))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(stream));
    }

    /**
     * 意见附件受控下载（direction-ui-completion-020）。
     *
     * <p>授权语义：调用人必须可访问该审批记录所属流程实例（发起人/当前参与人/抄送/超管/数据管理者），
     * 且 storageKey 必须由该记录冻结意见数据中附件字段实际引用；不接受文件名/显示值拼地址，
     * 也不放行任意 storageKey。旧记录没有可解析引用时返回 403/404，不伪造恢复。</p>
     */
    @GetMapping("/opinion/{actionRecordId}/download")
    public ResponseEntity<InputStreamResource> downloadOpinionAttachment(
            @PathVariable String actionRecordId,
            @RequestParam("storageKey") String storageKey,
            @RequestParam(value = "name", required = false) String name) throws IOException {
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED.getCode(), "未登录");
        }
        if (storageKey == null || storageKey.isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "storageKey 不能为空");
        }
        long recordId;
        try {
            recordId = Long.parseLong(actionRecordId);
        } catch (NumberFormatException e) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "审批记录标识不合法");
        }
        ApprovalActionRecord record = approvalActionRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "审批记录不存在");
        }
        BpmInstance instance = record.getProcessInstanceId() == null ? null
                : bpmInstanceService.lambdaQuery()
                        .eq(BpmInstance::getProcessInstanceId, record.getProcessInstanceId())
                        .last("LIMIT 1")
                        .one();
        if (!hasRecordAccess(loginUser, instance)
                || !opinionReferencesStorageKey(record.getOpinionData(), storageKey)) {
            log.warn("意见附件下载越权拒绝: recordId={}, user={}", actionRecordId, loginUser.getUserId());
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "无权访问该意见附件");
        }
        var stream = storageFacade.download(storageKey)
                .orElseThrow(() -> new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "附件不存在"));
        String fileName = (name == null || name.isBlank()) ? storageKey : name;
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename*=UTF-8''"
                        + java.net.URLEncoder.encode(fileName, java.nio.charset.StandardCharsets.UTF_8))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(stream));
    }

    /** 冻结意见数据中的附件字段是否真实引用该 storageKey（字符串或 {storageKey,name} 结构）。 */
    private boolean opinionReferencesStorageKey(String opinionData, String storageKey) {
        if (opinionData == null || opinionData.isBlank()) return false;
        try {
            JsonNode root = OBJECT_MAPPER.readTree(opinionData);
            if (root == null || !root.isObject()) return false;
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (!OPINION_ATTACHMENT_KEY.matcher(entry.getKey()).find()) continue;
                if (nodeReferencesStorageKey(entry.getValue(), storageKey)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean nodeReferencesStorageKey(JsonNode node, String storageKey) {
        if (node == null) return false;
        if (node.isTextual()) return storageKey.equals(node.textValue());
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (nodeReferencesStorageKey(item, storageKey)) return true;
            }
            return false;
        }
        if (node.isObject()) {
            JsonNode key = node.get("storageKey");
            return key != null && key.isTextual() && storageKey.equals(key.textValue());
        }
        return false;
    }

    // ==================== 对象权限 ====================

    private boolean hasRecordAccess(LoginUser user, BpmInstance instance) {
        if (user.isSuperAdmin()) {
            return true;
        }
        if (user.getPermissions() != null && user.getPermissions().contains("form:data:list")) {
            return true;
        }
        if (instance == null) {
            return false;
        }
        String uid = String.valueOf(user.getUserId());
        if (user.getUserId().equals(instance.getInitiatorId())) {
            return true;
        }
        // empty = 实例标识缺失：无活动任务可判定参与（原空列表口径），fail closed 继续判定
        boolean taskParticipant = bpmTaskFacade
                .queryByProcessInstance(instance.getProcessInstanceId())
                .orElse(List.of()).stream()
                .anyMatch(task -> uid.equals(task.getAssignee())
                        || (task.getCandidateUserIds() != null
                            && task.getCandidateUserIds().contains(uid)));
        if (taskParticipant) {
            return true;
        }
        CopyRecord copy = copyRecordMapper.selectOne(Wrappers.<CopyRecord>lambdaQuery()
                .eq(CopyRecord::getProcessInstanceId, instance.getProcessInstanceId())
                .eq(CopyRecord::getRecipientId, uid)
                .last("LIMIT 1"));
        return copy != null;
    }
}
