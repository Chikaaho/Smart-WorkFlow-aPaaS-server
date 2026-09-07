package com.sw.ck.form.service.impl;

import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.form.FormDefinitionService;
import com.sw.ck.form.service.FormDefService;
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

    public FormDefinitionServiceImpl(FormDefService formDefService) {
        this.formDefService = formDefService;
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
}
