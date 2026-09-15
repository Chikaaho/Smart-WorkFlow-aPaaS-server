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
        when(formService.getFormDef("iot_alert_form")).thenReturn(form);
        when(formService.getFormDefinition("iot_alert_form")).thenReturn("""
                {"fields":[
                  {"name":"ref_record","type":"REFERENCE","targetFormId":"target_form"},
                  {"name":"escalate","type":"BOOL"}
                ]}
                """);
        when(formService.canCurrentUserAccessRecord("target_form", "tenant-safe"))
                .thenReturn(true);
        when(formService.canCurrentUserAccessRecord("target_form", "tenant-other"))
                .thenReturn(false);

        ObjectProvider<FormDefinitionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(formService);
        BpmProcessDefService processDefService = mock(BpmProcessDefService.class);
        when(processDefService.findByProcessKey("p21-template")).thenReturn(process);

        IotFormContractCheckerImpl checker = new IotFormContractCheckerImpl(processDefService, provider);

        assertTrue(checker.checkMapping("p21-template", """
                [{"field":"ref_record","required":true,"fixed":"tenant-safe"},
                 {"field":"escalate","required":true,"fixed":true}]
                """).isEmpty());
        var errors = checker.checkMapping("p21-template", """
                [{"field":"ref_record","required":true,"fixed":"tenant-other"}]
                """);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("引用对象不存在或当前用户无权访问"));
    }
}
