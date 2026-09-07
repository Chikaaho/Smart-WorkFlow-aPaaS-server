package com.sw.ck.bpm.process.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.CopyRecord;
import com.sw.ck.bpm.process.mapper.CopyRecordMapper;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.response.R;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.storage.api.StorageFacade;
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
import java.util.Map;

/**
 * 表单附件/图片端点（v0.0.2 OA，P2 表单子集）。
 * <p>
 * 上传：登录用户可用（上传产物在关联前为孤立文件，关联与查看另受对象权限约束）。
 * 下载：按记录对象权限放行——实例发起人、当前待办人（含候选）、抄送接收人、
 * 超管或持有 {@code form:data:list} 的数据管理者；其余一律拒绝。
 * 附件字段值本身仅存 storageKey，不携带授权语义。
 * </p>
 */
@RestController
@RequestMapping("/workflow/attachments")
public class WorkflowAttachmentController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowAttachmentController.class);

    private final StorageFacade storageFacade;
    private final BpmInstanceService bpmInstanceService;
    private final BpmTaskFacade bpmTaskFacade;
    private final CopyRecordMapper copyRecordMapper;

    public WorkflowAttachmentController(StorageFacade storageFacade,
                                        BpmInstanceService bpmInstanceService,
                                        BpmTaskFacade bpmTaskFacade,
                                        CopyRecordMapper copyRecordMapper) {
        this.storageFacade = storageFacade;
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
        StorageUploadResult result = storageFacade.upload(file.getInputStream(),
                file.getOriginalFilename(), file.getContentType());
        return R.ok(Map.of(
                "storageKey", result.getStorageKey(),
                "storageName", result.getStorageName() == null ? "" : result.getStorageName(),
                "fileSize", result.getFileSize() == null ? 0L : result.getFileSize()));
    }

    /**
     * 按对象权限下载附件/图片。
     *
     * @param recordId   表单记录 id（= 流程 businessKey）
     * @param storageKey 存储键（来自字段值，服务端只按 key 取流，不信任路径信息）
     */
    @GetMapping("/{recordId}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable String recordId,
                                                        @RequestParam("storageKey") String storageKey,
                                                        @RequestParam(value = "name", required = false) String name)
            throws IOException {
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED.getCode(), "未登录");
        }
        if (storageKey == null || storageKey.isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "storageKey 不能为空");
        }
        if (!hasRecordAccess(loginUser, recordId)) {
            log.warn("附件下载越权拒绝: recordId={}, user={}", recordId, loginUser.getUserId());
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "无权访问该记录附件");
        }
        var stream = storageFacade.download(storageKey);
        String fileName = (name == null || name.isBlank()) ? storageKey : name;
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename*=UTF-8''"
                        + java.net.URLEncoder.encode(fileName, java.nio.charset.StandardCharsets.UTF_8))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(stream));
    }

    // ==================== 对象权限 ====================

    private boolean hasRecordAccess(LoginUser user, String recordId) {
        if (user.isSuperAdmin()) {
            return true;
        }
        if (user.getPermissions() != null && user.getPermissions().contains("form:data:list")) {
            return true;
        }
        BpmInstance instance = bpmInstanceService.lambdaQuery()
                .eq(BpmInstance::getBusinessKey, recordId)
                .last("LIMIT 1")
                .one();
        if (instance == null) {
            return false;
        }
        String uid = String.valueOf(user.getUserId());
        if (user.getUserId().equals(instance.getInitiatorId())) {
            return true;
        }
        boolean taskParticipant = bpmTaskFacade
                .queryByProcessInstance(instance.getProcessInstanceId()).stream()
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
