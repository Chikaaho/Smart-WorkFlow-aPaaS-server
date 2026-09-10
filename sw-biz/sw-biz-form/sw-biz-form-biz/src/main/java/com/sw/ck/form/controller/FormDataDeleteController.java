package com.sw.ck.form.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.form.service.FormDataDeleteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 表单数据删除入口。
 *
 * <p>端点路径：{@code DELETE /api/form/data/{formKey}/{recordId}}。
 * 鉴权走现有 Filter 链；租户/用户上下文取自 LoginUserHolder，端点不重复取 token。</p>
 */
@RestController
@RequestMapping("/form/data")
public class FormDataDeleteController {

    private static final Logger log = LoggerFactory.getLogger(FormDataDeleteController.class);

    private final FormDataDeleteService formDataDeleteService;

    public FormDataDeleteController(FormDataDeleteService formDataDeleteService) {
        this.formDataDeleteService = formDataDeleteService;
    }

    /**
     * 软删除一条表单记录（含 RESTRICT 反查 + CASCADE 子表连带）。
     *
     * @param formKey  表单业务标识
     * @param recordId 主表记录 UUID
     * @return {@code R<Void>}
     */
    @DeleteMapping("/{formKey}/{recordId}")
    @PreAuthorize("@ss.hasPermi('form:data:delete')")
    public R<Void> deleteData(@PathVariable("formKey") String formKey,
                               @PathVariable("recordId") String recordId) {
        log.info("Form data delete: formKey={}, recordId={}", formKey, recordId);
        formDataDeleteService.deleteRecord(formKey, recordId);
        return R.ok();
    }
}
