package com.sw.ck.form.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.form.api.dto.FormAppContentDTO;
import com.sw.ck.form.api.dto.FormAppDTO;
import com.sw.ck.form.service.FormAppService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 应用中心（用户端）：按可见范围展示应用，进入后展示已配置的目录与可用表单。
 * <p>
 * 仅需登录；应用可见性不授予后台管理能力，也不扩大数据读取范围——
 * 表单可用性同时满足应用范围与既有表单发布/可见性规则，数据查询继续叠加
 * {@code form:data:query} 与既有行权限。应用不可见或停用时按不存在处理
 * （不暴露受限对象名称或数量）。
 * </p>
 */
@RestController
@RequestMapping("/form/app/center")
public class FormAppCenterController {

    private final FormAppService formAppService;

    public FormAppCenterController(FormAppService formAppService) {
        this.formAppService = formAppService;
    }

    /** 当前用户可见的应用（启用 + 可见范围命中），计数只含当前用户可用表单。 */
    @GetMapping("/apps")
    public R<List<FormAppDTO>> apps() {
        return R.ok(formAppService.listVisibleAppsForCurrentUser());
    }

    /** 可见应用内容：目录（二级分类）与可用表单；未发布/停用/不可见表单不下发。 */
    @GetMapping("/apps/{id}")
    public R<FormAppContentDTO> content(@PathVariable("id") String id) {
        return R.ok(formAppService.getVisibleAppContent(id));
    }
}
