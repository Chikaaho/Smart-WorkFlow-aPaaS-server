package com.sw.ck.form.controller;

import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.form.dto.workspace.WorkspaceAggregateReq;
import com.sw.ck.form.dto.workspace.WorkspaceRowsReq;
import com.sw.ck.form.service.FormDataQueryService;
import com.sw.ck.form.service.FormDefService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作台数据组件受控查询入口。
 *
 * <p>不复用表单设计/数据管理权限，也不授予额外数据权：登录后只能看到
 * 当前账号可见的已发布表单；字段/记录权限、租户与数据范围由查询服务强制。</p>
 */
@RestController
@RequestMapping("/form/workspace/analytics")
public class FormWorkspaceAnalyticsController {

    private static final Logger log = LoggerFactory.getLogger(FormWorkspaceAnalyticsController.class);

    private final FormDataQueryService formDataQueryService;
    private final FormDefService formDefService;

    public FormWorkspaceAnalyticsController(FormDataQueryService formDataQueryService,
                                            FormDefService formDefService) {
        this.formDataQueryService = formDataQueryService;
        this.formDefService = formDefService;
    }

    /** 当前账号可关联的已发布表单（最小集：formKey/name/formVersion）。 */
    @GetMapping("/sources")
    public R<List<Map<String, Object>>> sources() {
        List<Map<String, Object>> list = formDefService.listPublishedForCurrentUser().stream()
                .map(def -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("formKey", def.getFormKey());
                    item.put("name", def.getName());
                    item.put("formVersion", def.getFormVersion());
                    return item;
                })
                .toList();
        return R.ok(list);
    }

    /** 指定表单当前账号可见的标量字段目录。 */
    @GetMapping("/forms/{formKey}/fields")
    public R<Map<String, Object>> fields(@PathVariable("formKey") String formKey) {
        return R.ok(formDataQueryService.workspaceFields(formKey));
    }

    /** 分组聚合。 */
    @PostMapping("/aggregate")
    public R<Map<String, Object>> aggregate(@RequestBody WorkspaceAggregateReq req) {
        if (req == null || req.getFormKey() == null || req.getFormKey().isBlank()) {
            return R.fail(1500, "聚合查询缺少表单标识");
        }
        log.info("Workspace aggregate: formKey={}, groupBy={}, metrics={}",
                req.getFormKey(), req.getGroupBy(),
                req.getMetrics() == null ? 0 : req.getMetrics().size());
        return R.ok(formDataQueryService.aggregateWorkspaceData(req.getFormKey(), req));
    }

    /** 数据列表。 */
    @PostMapping("/rows")
    public R<PageResult<Map<String, Object>>> rows(@RequestBody WorkspaceRowsReq req) {
        if (req == null || req.getFormKey() == null || req.getFormKey().isBlank()) {
            return R.fail(1500, "列表查询缺少表单标识");
        }
        log.info("Workspace rows: formKey={}, columns={}, page={}",
                req.getFormKey(), req.getColumns() == null ? 0 : req.getColumns().size(),
                req.getPageNum());
        return R.ok(formDataQueryService.queryWorkspaceRows(req.getFormKey(), req));
    }
}
