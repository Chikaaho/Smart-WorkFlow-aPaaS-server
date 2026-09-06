package com.sw.ck.form.service;

import com.sw.ck.form.api.facade.FormDataSubmitFacade;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * {@link FormDataSubmitFacade} 默认实现：委托 {@link FormSubmitService}
 * 同一校验/落库/受理路径，不另写第二套提交逻辑。
 */
@Service
public class FormDataSubmitFacadeImpl implements FormDataSubmitFacade {

    private final FormSubmitService formSubmitService;

    public FormDataSubmitFacadeImpl(FormSubmitService formSubmitService) {
        this.formSubmitService = formSubmitService;
    }

    @Override
    public String submit(String formKey, Map<String, Object> submittedData, String idempotencyKey) {
        return formSubmitService.submitForm(formKey, submittedData, null, null, null, idempotencyKey);
    }

    @Override
    public String submit(String formKey, Map<String, Object> submittedData,
                         String idempotencyKey, String dispatchChannel) {
        return formSubmitService.submitForm(formKey, submittedData, null, null, null,
                idempotencyKey, dispatchChannel);
    }

    @Override
    public String submit(String formKey, Map<String, Object> submittedData,
                         String idempotencyKey, String dispatchChannel,
                         String processDefKey) {
        return formSubmitService.submitForm(formKey, submittedData, null, null, null,
                idempotencyKey, dispatchChannel, processDefKey);
    }

    @Override
    public void validateSubmission(String formKey, Map<String, Object> submittedData) {
        formSubmitService.validateSubmission(formKey, submittedData);
    }
}
