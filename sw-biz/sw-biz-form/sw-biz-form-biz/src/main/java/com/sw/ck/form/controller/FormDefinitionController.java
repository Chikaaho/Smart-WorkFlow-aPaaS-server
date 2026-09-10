package com.sw.ck.form.controller;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.form.api.dto.FormConfigSaveReq;
import com.sw.ck.form.api.dto.FormCreateReq;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.dto.FormSnapshotDTO;
import com.sw.ck.form.api.dto.FormSnapshotDetailDTO;
import com.sw.ck.form.api.dto.FormUpdateReq;
import com.sw.ck.form.api.dto.FormVisibilityUpdateReq;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.service.FormDefService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 表单定义管理 + 渲染接口。
 * <p>
 * 草稿和已发布都能取（前端设计器预览用草稿，填单用已发布）。
 * </p>
 */
@RestController
@RequestMapping("/form/def")
public class FormDefinitionController {

    private static final Logger log = LoggerFactory.getLogger(FormDefinitionController.class);

    private final FormDefService formDefService;

    public FormDefinitionController(FormDefService formDefService) {
        this.formDefService = formDefService;
    }

    // ==================== 草稿管理 ====================

    /**
     * 创建表单草稿。
     */
    @PreAuthorize("@ss.hasPermi('form:design:save')")
    @PostMapping
    public R<FormDefDTO> createDraft(@RequestBody FormCreateReq req) {
        log.info("Creating form draft: formKey={}, name={}", req.getFormKey(), req.getName());
        FormDefDTO result = formDefService.createDraft(
                req.getFormKey(), req.getName(),
                req.getLogicalTableName(), req.getDescription());
        return R.ok(result);
    }

    /**
     * 更新表单草稿。
     */
    @PreAuthorize("@ss.hasPermi('form:design:save')")
    @PutMapping("/{id}")
    public R<FormDefDTO> updateDraft(@PathVariable("id") String id, @RequestBody FormUpdateReq req) {
        log.info("Updating form draft: id={}", id);
        FormDefDTO result = formDefService.updateDraft(
                id, req.getName(), req.getLogicalTableName(), req.getDescription());
        return R.ok(result);
    }

    /**
     * 删除表单草稿（仅 DRAFT 状态允许，已发布表单禁止删除）。
     */
    @PreAuthorize("@ss.hasPermi('form:design:save')")
    @DeleteMapping("/{id}")
    public R<Void> deleteDraft(@PathVariable("id") String id) {
        log.info("Deleting form draft: id={}", id);
        formDefService.deleteDraft(id);
        return R.ok();
    }

    /**
     * 保存表单配置（definition JSON）。
     */
    @PreAuthorize("@ss.hasPermi('form:design:save')")
    @PostMapping("/{id}/config")
    public R<Void> saveConfig(@PathVariable("id") String id, @RequestBody FormConfigSaveReq req) {
        log.info("Saving form config: id={}", id);
        formDefService.saveConfig(id, req.getDefinition());
        return R.ok();
    }

    // ==================== 分页查询 ====================

    /**
     * 分页查询表单定义列表。
     * <p>
     * 只返元数据（id/formKey/name/status/时间），不返 definition JSON，
     * 避免列表体积过大。按 update_time 倒序排列（最近编辑的在前）。
     * 多租户/逻辑删除走 MyBatis-Plus 拦截器自动过滤。
     * </p>
     *
     * @param pageParam 分页参数（pageNum/pageSize）
     * @param keyword   可选，对 name 模糊搜索（LIKE），为空不加该条件
     * @return 分页结果，每行为 FormDefDTO
     */
    @PreAuthorize("@ss.hasPermi('form:design')")
    @GetMapping("/page")
    public R<PageResult<FormDefDTO>> pageFormDefs(PageParam pageParam,
                                                  @RequestParam(required = false) String keyword) {
        PageResult<FormDefDTO> result = formDefService.pageFormDefs(pageParam, keyword);
        return R.ok(result);
    }

    /**
     * 已发布表单候选（业务填报入口，仅需登录；供个人草稿/发起页选择表单）。
     * P4：普通用户无 form:design 权限，不能走 /page；此端点只暴露
     * formKey/name/formVersion 最小集，且仅 PUBLISHED。
     */
    @GetMapping("/published")
    public R<java.util.List<java.util.Map<String, Object>>> listPublishedForDraft() {
        com.sw.ck.common.page.PageParam pageParam = new com.sw.ck.common.page.PageParam();
        pageParam.setPageNum(1);
        pageParam.setPageSize(200);
        java.util.List<java.util.Map<String, Object>> candidates = formDefService.listPublishedForCurrentUser().stream()
                .map(d -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("formKey", d.getFormKey());
                    m.put("name", d.getName());
                    m.put("formVersion", d.getFormVersion());
                    return m;
                })
                .toList();
        return R.ok(candidates);
    }

    /** 更新业务发起可见范围；空 userIds 表示当前租户内全部用户。 */
    @PreAuthorize("@ss.hasPermi('form:design:save')")
    @PutMapping("/{id}/visibility")
    public R<Void> updateVisibility(@PathVariable("id") String id,
                                    @RequestBody FormVisibilityUpdateReq req) {
        formDefService.updateVisibility(id, req == null ? null : req.getUserIds());
        return R.ok();
    }

    // ==================== 发布 ====================

    /**
     * 发布表单草稿。
     * <p>
     * 字段定义从该表单已存的 config.definition 派生，不再接受外部 body 作为字段来源。
     * 保留 body 参数仅为 HTTP 兼容（前端可能仍发送），逻辑忽略。
     * </p>
     *
     * @param id 表单 ID
     */
    @PreAuthorize("@ss.hasPermi('form:design:publish')")
    @PostMapping("/{id}/publish")
    public R<Void> publish(@PathVariable("id") String id, @RequestBody(required = false) String body) {
        log.info("Publishing form: id={}", id);
        formDefService.publish(id);
        return R.ok();
    }

    /** 在同一物理表上发布新表单版本，保留历史快照与既有记录。 */
    @PreAuthorize("@ss.hasPermi('form:design:publish')")
    @PostMapping("/{id}/publish-version")
    public R<Void> publishNewVersion(@PathVariable("id") String id,
                                     @RequestBody FormConfigSaveReq req) {
        log.info("Publishing form version: id={}", id);
        formDefService.publishNewVersion(id, req == null ? null : req.getDefinition());
        return R.ok();
    }

    // ==================== I2 生命周期 / 列表配置 / 外部数据源 ====================

    /** 停用已发布表单（禁新绑定/填报/提交/发起；历史与既有实例按冻结快照可读）。 */
    @PreAuthorize("@ss.hasPermi('form:design:publish')")
    @PostMapping("/{id}/disable")
    public R<Void> disable(@PathVariable("id") String id,
                           @RequestBody(required = false) java.util.Map<String, String> body) {
        formDefService.disable(id, body == null ? null : body.get("reason"));
        return R.ok();
    }

    /** 重新启用已停用表单。 */
    @PreAuthorize("@ss.hasPermi('form:design:publish')")
    @PostMapping("/{id}/enable")
    public R<Void> enable(@PathVariable("id") String id,
                          @RequestBody(required = false) java.util.Map<String, String> body) {
        formDefService.enable(id, body == null ? null : body.get("reason"));
        return R.ok();
    }

    /** 保存表单列表展示配置（字段/顺序/筛选/默认排序/允许动作）。 */
    @PreAuthorize("@ss.hasPermi('form:design:save')")
    @PutMapping("/{id}/list-config")
    public R<Void> saveListConfig(@PathVariable("id") String id,
                                  @RequestBody java.util.Map<String, String> body) {
        formDefService.saveListConfig(id, body == null ? null : body.get("config"));
        return R.ok();
    }

    /** 读取表单列表展示配置；未配置返回 data=null（前端可用派生默认）。 */
    @GetMapping("/{id}/list-config")
    public R<String> getListConfig(@PathVariable("id") String id) {
        return R.ok(formDefService.getListConfig(id));
    }

    // ==================== 查询（渲染接口） ====================

    /**
     * 根据 ID 获取表单定义 DTO。
     */
    @PreAuthorize("@ss.hasPermi('form:design')")
    @GetMapping("/{id}")
    public R<FormDefDTO> getFormDef(@PathVariable("id") String id) {
        FormDefDTO dto = formDefService.getFormDef(id);
        if (dto == null) {
            return R.fail(FormErrorCode.FORM_NOT_FOUND.getCode(), FormErrorCode.FORM_NOT_FOUND.getMessage());
        }
        return R.ok(dto);
    }

    /**
     * 根据 formKey 获取表单定义 DTO。
     */
    @GetMapping("/by-key/{formKey}")
    public R<FormDefDTO> getFormDefByKey(@PathVariable("formKey") String formKey) {
        FormDefDTO dto = formDefService.getFormDefByKey(formKey);
        if (dto == null || !formDefService.isCurrentUserVisible(formKey)) {
            return R.fail(FormErrorCode.FORM_NOT_FOUND.getCode(), FormErrorCode.FORM_NOT_FOUND.getMessage());
        }
        return R.ok(dto);
    }

    /**
     * 渲染接口：根据 ID 获取表单 definition JSON。
     * <p>
     * 草稿和已发布均可获取。前端设计器预览用草稿，填单引擎用已发布。
     * </p>
     */
    @PreAuthorize("@ss.hasPermi('form:design')")
    @GetMapping("/{id}/definition")
    public R<String> getDefinition(@PathVariable("id") String id) {
        String definition = formDefService.getDefinitionById(id);
        if (definition == null) {
            return R.fail(FormErrorCode.CONFIG_NOT_FOUND.getCode(), FormErrorCode.CONFIG_NOT_FOUND.getMessage());
        }
        return R.ok(definition);
    }

    /**
     * 渲染接口：根据 formKey 获取表单 definition JSON。
     */
    @GetMapping("/by-key/{formKey}/definition")
    public R<String> getDefinitionByKey(@PathVariable("formKey") String formKey) {
        if (!formDefService.isCurrentUserVisible(formKey)) {
            return R.fail(FormErrorCode.FORM_NOT_FOUND.getCode(), FormErrorCode.FORM_NOT_FOUND.getMessage());
        }
        String definition = formDefService.getDefinition(formKey);
        if (definition == null) {
            return R.fail(FormErrorCode.CONFIG_NOT_FOUND.getCode(), FormErrorCode.CONFIG_NOT_FOUND.getMessage());
        }
        return R.ok(definition);
    }

    // ==================== 历史版本快照（只读） ====================

    /**
     * 查询表单历史版本快照列表（版本号倒序，只读）。
     * <p>
     * 只返版本元数据（formVersion + createTime），不返 definition JSON。
     * </p>
     *
     * @param id 表单 ID
     */
    @PreAuthorize("@ss.hasPermi('form:design')")
    @GetMapping("/{id}/snapshots")
    public R<List<FormSnapshotDTO>> listSnapshots(@PathVariable("id") String id) {
        return R.ok(formDefService.listSnapshots(id));
    }

    /**
     * 读取指定版本的快照详情（只读预览，含 definition JSON）。
     * <p>
     * 历史版本只读，不提供任何回写路径；版本不存在返 1301。
     * </p>
     *
     * @param id          表单 ID
     * @param formVersion 快照版本号
     */
    @PreAuthorize("@ss.hasPermi('form:design')")
    @GetMapping("/{id}/snapshots/{formVersion}")
    public R<FormSnapshotDetailDTO> getSnapshot(@PathVariable("id") String id,
                                                @PathVariable("formVersion") Integer formVersion) {
        return R.ok(formDefService.getSnapshot(id, formVersion));
    }
}
