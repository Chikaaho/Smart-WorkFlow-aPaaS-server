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
    public Optional<SubmissionValidationOutcome> validateSubmission(String formKey,
                                                                    Map<String, Object> submittedData) {
        formSubmitService.validateSubmission(formKey, submittedData);
        return Optional.of(SubmissionValidationOutcome.VALID);
    }
}
