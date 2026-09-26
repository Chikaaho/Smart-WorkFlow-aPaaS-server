package com.sw.ck.form.service.impl;

import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.form.FormDefinitionService;
import com.sw.ck.form.service.FormDefService;
import com.sw.ck.form.service.FormDataQueryService;
import com.sw.ck.form.service.FormFieldEnrichmentService;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * {@link FormDefinitionService} 实现。
 * <p>
 * 定义于 -api 模块，由 -biz 实现，工作流模块通过此接口获取表单定义。
 * </p>
 * <p>
 * 结果语义：empty 只表达“判定目标缺失”（formKey/recordId 为空白）；
 * 表单不存在、未发布、未登录、无权等情况与合法判定结果一律以 present 的
 * true/false 或定义值表达。
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
    public Optional<String> getFormDefinition(String formKey) {
        return Optional.ofNullable(formDefService.getDefinition(formKey));
    }

    @Override
    public Optional<Boolean> formExists(String formKey) {
        return Optional.of(formDefService.getFormDefByKey(formKey) != null);
    }

    @Override
    public Optional<FormDefDTO> getFormDef(String formKey) {
        return Optional.ofNullable(formDefService.getFormDefByKey(formKey));
    }

    @Override
    public Optional<Boolean> canCurrentUserInitiate(String formKey) {
        if (formKey == null || formKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(formDefService.isCurrentUserVisible(formKey));
    }

    @Override
    public Optional<Boolean> canCurrentUserPerformAction(String formKey, String action) {
        if (formKey == null || formKey.isBlank()) {
            return Optional.empty();
        }
        FormDefDTO formDef = formDefService.getFormDefByKey(formKey);
        if (formDef == null || !formDefService.isCurrentUserVisible(formKey)) {
            return Optional.of(false);
        }
        if (!"view".equals(action) && !"PUBLISHED".equals(formDef.getStatus())) {
            return Optional.of(false);
        }
        return Optional.of(formFieldEnrichmentService == null
                || formFieldEnrichmentService.canCurrentUserPerformAction(formDef.getId(), action));
    }

    @Override
    public Optional<Boolean> canCurrentUserAccessRecord(String formKey, String recordId) {
        if (formKey == null || formKey.isBlank() || recordId == null || recordId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(formDataQueryService.canCurrentUserAccessRecord(formKey, recordId));
    }
}
