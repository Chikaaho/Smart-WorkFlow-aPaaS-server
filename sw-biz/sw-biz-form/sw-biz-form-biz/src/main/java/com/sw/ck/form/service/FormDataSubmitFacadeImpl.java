package com.sw.ck.form.service;

import com.sw.ck.form.api.facade.FormDataSubmitFacade;
import com.sw.ck.form.api.facade.SubmissionValidationOutcome;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * {@link FormDataSubmitFacade} 默认实现：委托 {@link FormSubmitService}
 * 同一校验/落库/受理路径，不另写第二套提交逻辑。
 * <p>
 * 提交失败（未登录、表单不存在/未发布、字段校验失败、落库失败）继续抛原有业务异常，
 * 不以上空表达失败；成功提交恒以 present 返回 recordId。
 * </p>
 */
@Service
public class FormDataSubmitFacadeImpl implements FormDataSubmitFacade {

    private final FormSubmitService formSubmitService;

    public FormDataSubmitFacadeImpl(FormSubmitService formSubmitService) {
        this.formSubmitService = formSubmitService;
    }

    @Override
    public Optional<String> submit(String formKey, Map<String, Object> submittedData, String idempotencyKey) {
        return Optional.of(formSubmitService.submitForm(formKey, submittedData, null, null, null, idempotencyKey));
    }

    @Override
    public Optional<String> submit(String formKey, Map<String, Object> submittedData,
                                   String idempotencyKey, String dispatchChannel) {
        return Optional.of(formSubmitService.submitForm(formKey, submittedData, null, null, null,
                idempotencyKey, dispatchChannel));
    }

    @Override
    public Optional<String> submit(String formKey, Map<String, Object> submittedData,
                                   String idempotencyKey, String dispatchChannel,
                                   String processDefKey) {
        return Optional.of(formSubmitService.submitForm(formKey, submittedData, null, null, null,
                idempotencyKey, dispatchChannel, processDefKey));
    }

    @Override
    public Optional<String> submitOnBehalfOf(String formKey, Map<String, Object> submittedData,
                                             String idempotencyKey, Long recordOwnerUserId) {
        // P64 派发接缝 A06（复审05 P2-04a）：记录归属人=被分配办理人（系统列 create_by/update_by）；
        // 校验/可见性/审计仍按调用方上下文（命令消费侧还原的发起人）
        return Optional.of(formSubmitService.submitForm(formKey, submittedData, null, null, null,
                idempotencyKey, null, null, recordOwnerUserId));
    }

    @Override
    public Optional<SubmissionValidationOutcome> validateSubmission(String formKey,
                                                                    Map<String, Object> submittedData) {
        formSubmitService.validateSubmission(formKey, submittedData);
        return Optional.of(SubmissionValidationOutcome.VALID);
    }
}
