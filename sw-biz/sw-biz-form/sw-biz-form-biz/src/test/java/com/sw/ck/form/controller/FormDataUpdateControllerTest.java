package com.sw.ck.form.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.GlobalExceptionHandler;
import com.sw.ck.form.api.dto.FormDataUpdateRequest;
import com.sw.ck.form.service.FormDataDeleteService;
import com.sw.ck.form.service.FormDataQueryService;
import com.sw.ck.form.service.FormDataUpdateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P32 S4：表单数据编辑控制器回归（v5 路由修复后的 PUT /form/data/{formKey}/{recordId}）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("表单数据编辑控制器回归")
class FormDataUpdateControllerTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private FormDataQueryService formDataQueryService;

    @Mock
    private FormDataUpdateService formDataUpdateService;

    @Mock
    private FormDataDeleteService formDataDeleteService;

    @InjectMocks
    private FormDataController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(null))
                .build();
    }

    @Test
    @DisplayName("PUT /form/data/{formKey}/{recordId} → 编辑委托成功，R.ok")
    void updateData_happyPath() throws Exception {
        doNothing().when(formDataUpdateService).updateRecord(eq("upd_form"), eq("rec-1"), any());

        FormDataUpdateRequest request = new FormDataUpdateRequest();
        request.setData(java.util.Map.of("city", "杭州-已编辑"));
        request.setVersion(0L);

        mockMvc.perform(put("/form/data/{formKey}/{recordId}", "upd_form", "rec-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(formDataUpdateService).updateRecord(eq("upd_form"), eq("rec-1"), any());
    }
}
