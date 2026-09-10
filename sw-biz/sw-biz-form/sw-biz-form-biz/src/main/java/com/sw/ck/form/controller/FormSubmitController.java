package com.sw.ck.form.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.form.service.FormSubmitService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 表单数据提交入口。
 * <p>
 * 由统一异常处理器 ({@link com.sw.ck.common.exception.GlobalExceptionHandler})
 * 将提交校验失败（1400-1499）自动转为 {@code R.fail} 返回。
 * </p>
 */
@RestController
@RequestMapping("/form/data")
public class FormSubmitController {

    private static final Logger log = LoggerFactory.getLogger(FormSubmitController.class);

    private final FormSubmitService formSubmitService;

    public FormSubmitController(FormSubmitService formSubmitService) {
        this.formSubmitService = formSubmitService;
    }

    /**
     * 提交表单数据。
     *
     * @param formKey 表单业务标识
     * @param data    提交数据（字段名 → 值）
     * @param request HTTP 请求（用于提取 IP / User-Agent）
     * @return {@code R<String>} 主表记录 ID
     */
    @PostMapping("/{formKey}")
    @PreAuthorize("@ss.hasPermi('form:data:submit')")
    public R<String> submitData(@PathVariable("formKey") String formKey,
                                 @RequestBody Map<String, Object> data,
                                 HttpServletRequest request) {
        String submitIp = request.getRemoteAddr();
        String userAgent = request.getHeader("User-Agent");
        log.info("Form submit endpoint: formKey={}, ip={}, ua={}", formKey, submitIp, userAgent);
        String recordId = formSubmitService.submitForm(formKey, data, submitIp, null, userAgent);
        return R.ok(recordId);
    }
}
