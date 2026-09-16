package com.sw.ck.form.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.GlobalExceptionHandler;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.service.FormSubmitService;
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

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link FormSubmitController} 单元测试。
 * <p>
 * 验证 REST 路径 / R 包装 / 异常处理器集成。
 * 提交校验逻辑由 {@link FormSubmitService} 覆盖，本测试仅验证 HTTP 层编排。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("表单提交控制器")
class FormSubmitControllerTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private FormSubmitService formSubmitService;

    @InjectMocks
    private FormSubmitController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(null))
                .build();
    }

    // ==================== Happy path ====================

    @Test
    @DisplayName("POST /form/data/{formKey} → 提交成功 → R.ok(recordId)")
    void submitData_happyPath_shouldReturnRecordId() throws Exception {
        String formKey = "test_form";
        Map<String, Object> requestBody = Map.of("name", "张三", "age", 25);
        String expectedRecordId = "rec-001";

        when(formSubmitService.submitForm(eq(formKey), any(), any(), any(), any()))
                .thenReturn(expectedRecordId);

        mockMvc.perform(post("/form/data/{formKey}", formKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(expectedRecordId));
    }

    // ==================== 校验失败传播 ====================

    @Test
    @DisplayName("POST 必填字段缺失 → Service 抛 BaseException(1401) → R.fail(1401)")
    void submitData_requiredFieldMissing_shouldReturn1401() throws Exception {
        String formKey = "test_form";
        Map<String, Object> requestBody = Map.of();

        when(formSubmitService.submitForm(eq(formKey), any(), any(), any(), any()))
                .thenThrow(new BaseException(FormErrorCode.SUBMIT_FIELD_REQUIRED, "必填字段 'name' 缺失"));

        mockMvc.perform(post("/form/data/{formKey}", formKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(FormErrorCode.SUBMIT_FIELD_REQUIRED.getCode()))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("POST 字段类型不匹配 → Service 抛 BaseException(1402) → R.fail(1402)")
    void submitData_typeMismatch_shouldReturn1402() throws Exception {
        String formKey = "test_form";
        Map<String, Object> requestBody = Map.of("age", "not-a-number");

        when(formSubmitService.submitForm(eq(formKey), any(), any(), any(), any()))
                .thenThrow(new BaseException(FormErrorCode.SUBMIT_FIELD_TYPE_MISMATCH, "字段 'age' 需要数字类型"));

        mockMvc.perform(post("/form/data/{formKey}", formKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(FormErrorCode.SUBMIT_FIELD_TYPE_MISMATCH.getCode()))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("POST 未知字段 → Service 抛 BaseException(1400) → R.fail(1400)")
    void submitData_unknownField_shouldReturn1400() throws Exception {
        String formKey = "test_form";
        Map<String, Object> requestBody = Map.of("unknown", "val");

        when(formSubmitService.submitForm(eq(formKey), any(), any(), any(), any()))
                .thenThrow(new BaseException(FormErrorCode.SUBMIT_FIELD_UNKNOWN, "提交了未定义的字段 'unknown'"));

        mockMvc.perform(post("/form/data/{formKey}", formKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(FormErrorCode.SUBMIT_FIELD_UNKNOWN.getCode()))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
