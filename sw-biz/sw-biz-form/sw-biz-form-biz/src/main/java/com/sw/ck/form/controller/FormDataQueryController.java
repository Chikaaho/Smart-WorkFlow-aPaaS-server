package com.sw.ck.form.controller;

import org.springframework.beans.factory.annotation.Autowired;

import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.form.api.dto.FormDataQueryRequest;
import com.sw.ck.form.vo.FormDataRecordVO;
import com.sw.ck.form.service.FormDataQueryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import lombok.extern.log4j.Log4j2;
/**
 * 表单数据查询入口。
 *
 * <p>端点路径：{@code POST /api/form/data/{formKey}/query}。
 * 鉴权走现有 Filter 链；租户/用户上下文取自 LoginUserHolder，端点不重复取 token。</p>
 */
@RestController
@RequestMapping("/form/data")
@Log4j2
public class FormDataQueryController {

    @Autowired
    private FormDataQueryService formDataQueryService;

    /**
     * 分页查询表单数据。
     *
     * @param formKey 表单业务标识
     * @param request 查询请求（分页参数 + 过滤条件）
     * @return {@code R<PageResult<FormDataRecordVO>>} 分页结果（动态字段）
     */
    @PostMapping("/{formKey}/query")
    @PreAuthorize("@ss.hasPermi('form:data:query')")
    public R<PageResult<FormDataRecordVO>> queryData(@PathVariable("formKey") String formKey,
                                                       @RequestBody FormDataQueryRequest request) {
        log.info("Form data query: formKey={}, page={}, size={}, filters={}",
                formKey, request.getPageNum(), request.getPageSize(),
                request.getFilters() != null ? request.getFilters().size() : 0);

        PageResult<Map<String, Object>> source = formDataQueryService.queryFormData(formKey, request);
        PageResult<FormDataRecordVO> result = new PageResult<>();
        result.setRecords(source.getRecords().stream().map(FormDataRecordVO::new).toList());
        result.setTotal(source.getTotal());
        result.setPageNum(source.getPageNum());
        result.setPageSize(source.getPageSize());
        return R.ok(result);
    }
}
