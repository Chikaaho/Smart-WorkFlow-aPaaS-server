package com.sw.ck.bpm.process.controller;

import com.sw.ck.bpm.process.dto.ApprovalHistoryItemDTO;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.service.ApprovalHistoryAssembler;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.FormPrintPdfService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.response.R;
import com.sw.ck.form.api.print.FormPrintFacade;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 服务端打印 PDF（1.0.0 F2-3）：表单详情、任务详情、「我发起的」三处共用端点。
 * <p>
 * 表单记录内容只经 {@link com.sw.ck.form.api.print.FormPrintFacade} 的权限过滤链
 * （字段级隐藏照样生效，F2-4）；审批历史仅在调用方能访问该实例时并入
 * （发起人本人 / 实例参与人 / 超管）。版本解析见 facade（F2-5，页脚注明回退）。
 * </p>
 */
@RestController
@RequestMapping("/workflow/print")
public class FormPrintController {

    private static final Logger log = LoggerFactory.getLogger(FormPrintController.class);

    private final FormPrintPdfService formPrintPdfService;
    private final FormPrintFacade formPrintFacade;
    private final BpmInstanceService bpmInstanceService;
    private final ApprovalHistoryAssembler approvalHistoryAssembler;
    private final com.sw.ck.bpm.api.facade.BpmTaskFacade bpmTaskFacade;

    public FormPrintController(FormPrintPdfService formPrintPdfService,
                               FormPrintFacade formPrintFacade,
                               BpmInstanceService bpmInstanceService,
                               ApprovalHistoryAssembler approvalHistoryAssembler,
                               com.sw.ck.bpm.api.facade.BpmTaskFacade bpmTaskFacade) {
        this.formPrintPdfService = formPrintPdfService;
        this.formPrintFacade = formPrintFacade;
        this.bpmInstanceService = bpmInstanceService;
        this.approvalHistoryAssembler = approvalHistoryAssembler;
        this.bpmTaskFacade = bpmTaskFacade;
    }

    /**
     * 浏览器打印上下文（F2-5，S1）：与 PDF 共用提交时版本定位。
     * <p>
     * 返回实际用于打印的定义 JSON（提交时快照或当前定义）、版本号与版本定位结果；
     * 浏览器按同一份定义渲染字段与 printTemplate，定位不到时由前端页脚注明。
     * 记录读取仍走 FormPrintFacade 的权限过滤链。
     * </p>
     */
    @GetMapping("/context")
    public R<PrintContextResponse> context(@RequestParam("formKey") String formKey,
                                           @RequestParam("recordId") String recordId,
                                           @RequestParam(value = "processInstanceId", required = false)
                                           String processInstanceId) {
        LoginUser current = LoginUserHolder.get();
        if (current == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
        }
        BpmInstance instance = null;
        if (processInstanceId != null && !processInstanceId.isBlank()) {
            instance = bpmInstanceService.findByProcessInstanceId(processInstanceId).orElse(null);
            if (instance == null) {
                throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "流程实例不存在");
            }
            requireInstanceAccessible(instance, current);
        }
        FormPrintFacade.FormPrintData data = formPrintFacade
                .recordDetail(formKey, recordId, instance == null ? null : instance.getCreateTime())
                .orElseThrow(() -> new BaseException(CommonErrorCode.NOT_FOUND.getCode(),
                        "打印数据不可用或无权读取"));
        return R.ok(new PrintContextResponse(data.formName(), data.printDefinitionJson(),
                data.printVersion(), data.versionPinned()));
    }

    /** 浏览器打印上下文响应（不携带记录业务值）。 */
    public record PrintContextResponse(String formName, String definition,
                                       Integer formVersion, boolean versionPinned) {
    }

    /**
     * 下载打印 PDF。
     *
     * @param formKey          表单业务标识（必填）
     * @param recordId         表单记录 UUID（必填）
     * @param processInstanceId 流程实例标识（可选；提供时并入审批历史，并校验实例可访问）
     */
    @GetMapping("/pdf")
    public ResponseEntity<byte[]> pdf(@RequestParam("formKey") String formKey,
                                      @RequestParam("recordId") String recordId,
                                      @RequestParam(value = "processInstanceId", required = false)
                                      String processInstanceId) {
        LoginUser current = LoginUserHolder.get();
        if (current == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
        }

        BpmInstance instance = null;
        List<ApprovalHistoryItemDTO> history = null;
        if (processInstanceId != null && !processInstanceId.isBlank()) {
            instance = bpmInstanceService.findByProcessInstanceId(processInstanceId).orElse(null);
            if (instance == null) {
                throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "流程实例不存在");
            }
            requireInstanceAccessible(instance, current);
            history = approvalHistoryAssembler.build(instance);
        }

        byte[] bytes = formPrintPdfService.render(formKey, recordId,
                instance == null ? null : instance.getCreateTime(), history);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=print.pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(bytes);
    }

    /** 实例可访问：发起人本人 / 活动或历史任务参与人 / 超管；否则 403（不泄露差异）。 */
    private void requireInstanceAccessible(BpmInstance instance, LoginUser current) {
        if (current.isSuperAdmin()) {
            return;
        }
        if (current.getUserId() != null && current.getUserId().equals(instance.getInitiatorId())) {
            return;
        }
        boolean participant = bpmTaskFacade.queryByProcessInstance(instance.getProcessInstanceId())
                .orElse(List.of()).stream()
                .anyMatch(task -> current.getUsername() != null
                        && current.getUsername().equals(task.getAssignee()));
        if (!participant) {
            log.warn("打印 PDF 实例访问被拒绝: instanceId={}, user={}",
                    instance.getId(), current.getUserId());
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "无权查看该流程实例");
        }
    }
}
