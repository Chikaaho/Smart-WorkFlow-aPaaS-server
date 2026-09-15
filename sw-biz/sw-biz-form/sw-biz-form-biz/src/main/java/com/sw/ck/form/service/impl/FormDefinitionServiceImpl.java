package com.sw.ck.form.service.impl;

import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.form.FormDefinitionService;
import com.sw.ck.form.service.FormDefService;
import com.sw.ck.form.service.FormDataQueryService;
import com.sw.ck.form.service.FormFieldEnrichmentService;
import org.springframework.stereotype.Service;

/**
 * {@link FormDefinitionService} 实现。
 * <p>
 * 定义于 -api 模块，由 -biz 实现，工作流模块通过此接口获取表单定义。
 * </p>
 */
@Service
public class FormDefinitionServiceImpl implements FormDefinitionService {

    private final FormDefService formDefService;
    private final FormDataQueryService formDataQueryService;
    private final FormFieldEnrichmentService formFieldEnrichmentService;

    public FormDefinitionServiceImpl(FormDefService formDefService,
                                     FormDataQueryService formDataQueryService,
                                     FormFieldEnrichmentService formFieldEnrichmentService) {
        this.formDefService = formDefService;
        this.formDataQueryService = formDataQueryService;
        this.formFieldEnrichmentService = formFieldEnrichmentService;
    }

    @Override
    public String getFormDefinition(String formKey) {
        return formDefService.getDefinition(formKey);
    }

    @Override
    public String getFormDefinitionById(String formId) {
        return formDefService.getDefinitionById(formId);
    }

    @Override
    public boolean formExists(String formKey) {
        return formDefService.getFormDefByKey(formKey) != null;
    }

    @Override
    public FormDefDTO getFormDef(String formKey) {
        return formDefService.getFormDefByKey(formKey);
    }

    @Override
    public FormDefDTO getFormDefById(String formId) {
        return formDefService.getFormDef(formId);
    }

    @Override
    public boolean canCurrentUserInitiate(String formKey) {
        return formDefService.isCurrentUserVisible(formKey);
    }

    @Override
    public boolean canCurrentUserPerformAction(String formKey, String action) {
        FormDefDTO formDef = formDefService.getFormDefByKey(formKey);
        if (formDef == null || !formDefService.isCurrentUserVisible(formKey)) {
            return false;
        }
        if (!"view".equals(action) && !"PUBLISHED".equals(formDef.getStatus())) {
            return false;
        }
        return formFieldEnrichmentService == null
                || formFieldEnrichmentService.canCurrentUserPerformAction(formDef.getId(), action);
    }

    @Override
    public boolean canCurrentUserAccessRecord(String formKey, String recordId) {
        return formDataQueryService.canCurrentUserAccessRecord(formKey, recordId);
    }
}
