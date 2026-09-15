package com.sw.ck.form.controller;

import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.form.api.dto.FormDataQueryRequest;
import com.sw.ck.form.service.FormDataQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 表单数据查询入口。
 *
 * <p>端点路径：{@code POST /api/form/data/{formKey}/query}。
 * 鉴权走现有 Filter 链；租户/用户上下文取自 LoginUserHolder，端点不重复取 token。</p>
 */
@RestController
@RequestMapping("/form/data")
public class FormDataQueryController {

    private static final Logger log = LoggerFactory.getLogger(FormDataQueryController.class);

    private final FormDataQueryService formDataQueryService;

    public FormDataQueryController(FormDataQueryService formDataQueryService) {
        this.formDataQueryService = formDataQueryService;
    }

    /**
     * 分页查询表单数据。
     *
     * @param formKey 表单业务标识
     * @param request 查询请求（分页参数 + 过滤条件）
     * @return {@code R<PageResult<Map<String, Object>>>} 分页结果
     */
    @PostMapping("/{formKey}/query")
    @PreAuthorize("@ss.hasPermi('form:data:query')")
    public R<PageResult<Map<String, Object>>> queryData(@PathVariable("formKey") String formKey,
                                                         @RequestBody FormDataQueryRequest request) {
        log.info("Form data query: formKey={}, page={}, size={}, filters={}",
                formKey, request.getPageNum(), request.getPageSize(),
                request.getFilters() != null ? request.getFilters().size() : 0);

        PageResult<Map<String, Object>> result = formDataQueryService.queryFormData(formKey, request);
        return R.ok(result);
    }
}
