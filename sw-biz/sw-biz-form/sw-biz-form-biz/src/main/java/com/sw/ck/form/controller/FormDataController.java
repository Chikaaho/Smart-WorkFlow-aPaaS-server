package com.sw.ck.form.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.form.api.dto.FormDataUpdateRequest;
import com.sw.ck.form.service.FormDataQueryService;
import com.sw.ck.form.service.FormDataUpdateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 表单数据详情与更新入口。
 *
 * <p>端点路径：
 * <ul>
 *   <li>{@code GET /api/form/data/{formKey}/{recordId}} — 查单条记录详情（含子表行）</li>
 *   <li>{@code PUT /api/form/data/{formKey}/{recordId}} — 更新记录（乐观锁 + 子表分流）</li>
 * </ul>
 * 鉴权走现有 Filter 链；租户/用户上下文取自 LoginUserHolder，端点不重复取 token。
 * </p>
 */
@RestController
@RequestMapping("/form/data")
public class FormDataController {

    private static final Logger log = LoggerFactory.getLogger(FormDataController.class);

    private final FormDataQueryService formDataQueryService;
    private final FormDataUpdateService formDataUpdateService;

    public FormDataController(FormDataQueryService formDataQueryService,
                              FormDataUpdateService formDataUpdateService) {
        this.formDataQueryService = formDataQueryService;
        this.formDataUpdateService = formDataUpdateService;
    }

    /**
     * 查询单条记录详情（含子表行，供编辑回显）。
     *
     * @param formKey  表单业务标识
     * @param recordId 主表记录 UUID
     * @return {@code R<Map>} 主记录字段 + 各子表行列表
     */
    @GetMapping("/{formKey}/{recordId}")
    @PreAuthorize("@ss.hasPermi('form:data:query')")
    public R<Map<String, Object>> getDetail(@PathVariable("formKey") String formKey,
                                            @PathVariable("recordId") String recordId) {
        log.info("Form data detail: formKey={}, recordId={}", formKey, recordId);
        Map<String, Object> detail = formDataQueryService.getRecordDetail(formKey, recordId);
        return R.ok(detail);
    }

    /**
     * 更新表单记录（主表整量 + 子表按变动状态分流 + 乐观锁）。
     *
     * @param formKey  表单业务标识
     * @param recordId 主表记录 UUID
     * @param request  更新请求（主表数据 + version + 子表行变动）
     * @return {@code R<Void>}
     */
    @PutMapping("/{formKey}/{recordId}")
    @PreAuthorize("@ss.hasPermi('form:data:edit')")
    public R<Void> updateData(@PathVariable("formKey") String formKey,
                              @PathVariable("recordId") String recordId,
                              @RequestBody FormDataUpdateRequest request) {
        log.info("Form data update: formKey={}, recordId={}, version={}, subTableFields={}",
                formKey, recordId, request.getVersion(),
                request.getSubTableRows() != null ? request.getSubTableRows().size() : 0);
        formDataUpdateService.updateRecord(formKey, recordId, request);
        return R.ok();
    }
}
