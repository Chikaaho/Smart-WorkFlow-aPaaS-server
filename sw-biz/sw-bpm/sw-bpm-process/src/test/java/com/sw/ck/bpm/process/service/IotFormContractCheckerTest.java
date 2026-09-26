package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.form.FormDefinitionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IotFormContractCheckerTest {

    @Test
    void referenceRequiresTargetRecordVisibility() {
        BpmProcessDef process = new BpmProcessDef();
        process.setStatus("PUBLISHED");
        process.setIotAccessEnabled(true);
        process.setFormKey("iot_alert_form");

        FormDefinitionService formService = mock(FormDefinitionService.class);
        FormDefDTO form = FormDefDTO.builder()
                .formKey("iot_alert_form")
                .status("PUBLISHED")
                .build();
        when(formService.getFormDef("iot_alert_form")).thenReturn(java.util.Optional.of(form));
        when(formService.getFormDefinition("iot_alert_form")).thenReturn(java.util.Optional.of("""
                {"fields":[
                  {"name":"ref_record","type":"REFERENCE","targetFormId":"target_form"},
                  {"name":"escalate","type":"BOOL"}
                ]}
                """));
        when(formService.canCurrentUserAccessRecord("target_form", "tenant-safe"))
                .thenReturn(java.util.Optional.of(true));
        when(formService.canCurrentUserAccessRecord("target_form", "tenant-other"))
                .thenReturn(java.util.Optional.of(false));

        ObjectProvider<FormDefinitionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(formService);
        BpmProcessDefService processDefService = mock(BpmProcessDefService.class);
        when(processDefService.findByProcessKey("p21-template")).thenReturn(process);

        IotFormContractCheckerImpl checker = new IotFormContractCheckerImpl(processDefService, provider);

        // 两层语义：present + 空列表 = 校验通过（有值的结果，不得用 empty 代替）
        java.util.Optional<java.util.List<String>> passing = checker.checkMapping("p21-template", """
                [{"field":"ref_record","required":true,"fixed":"tenant-safe"},
                 {"field":"escalate","required":true,"fixed":true}]
                """);
        assertTrue(passing.isPresent(), "校验合法执行必须 present，而不是 empty");
        assertTrue(passing.orElseThrow().isEmpty(), "通过时错误列表必须为空列表");

        // present + 非空列表 = 校验发现错误
        java.util.Optional<java.util.List<String>> failing = checker.checkMapping("p21-template", """
                [{"field":"ref_record","required":true,"fixed":"tenant-other"}]
                """);
        assertTrue(failing.isPresent(), "校验合法执行必须 present，而不是 empty");
        java.util.List<String> errors = failing.orElseThrow();
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("引用对象不存在或当前用户无权访问"));

        // empty = 无法裁决（流程模板 key 空白），调用方必须 fail closed 而不是当作通过
        assertTrue(checker.checkMapping("  ", """
                [{"field":"escalate","required":true,"fixed":true}]
                """).isEmpty(), "无法裁决必须是 empty，且不得与'通过'混同");
    }
}
