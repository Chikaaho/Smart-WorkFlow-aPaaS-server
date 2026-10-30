package com.sw.ck.form.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.form.api.dto.FormAppContentDTO;
import com.sw.ck.form.api.dto.FormAppDTO;
import com.sw.ck.form.api.dto.FormAppDirDTO;
import com.sw.ck.form.api.dto.FormAppDirSaveReq;
import com.sw.ck.form.api.dto.FormAppMembershipReq;
import com.sw.ck.form.api.dto.FormAppSaveReq;
import com.sw.ck.form.dynamic.FixedTableSql;
import com.sw.ck.form.service.FormAppService;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 应用管理（后台）：应用 / 目录 / 表单归属维护。
 * <p>
 * 应用管理为后台权限（{@code form:app:manage}），用户端可见范围不授予本组能力；
 * 服务端逐操作校验。进入应用管理页后以应用上下文组织目录与表单；
 * 表单归属调整是显式操作，失败保持原归属。
 * </p>
 */
@RestController
@RequestMapping("/form/app/manage")
public class FormAppManageController {

    private static final Logger log = LoggerFactory.getLogger(FormAppManageController.class);

    private final FormAppService formAppService;
    private final JdbcTemplate jdbcTemplate;

    public FormAppManageController(FormAppService formAppService, JdbcTemplate jdbcTemplate) {
        this.formAppService = formAppService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 应用列表（含停用与承接应用），keyword 对名称模糊过滤。
     * <p>owner-20261030-u020-q40-list-contract：下发真实归属 tenantId/tenantName；
     * tenantId 过滤参数仅超管生效，普通调用方传参被忽略（隔离仍由既有拦截器保证）。</p>
     */
    @PreAuthorize("@ss.hasPermi('form:app:manage')")
    @GetMapping("/list")
    public R<List<FormAppDTO>> list(@RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) Long tenantId) {
        LoginUser user = LoginUserHolder.get();
        boolean superAdmin = user != null && user.isSuperAdmin();
        List<FormAppDTO> apps = formAppService.listApps(keyword, superAdmin ? tenantId : null);
        Map<Long, String> tenantNames = tenantNameMap();
        for (FormAppDTO app : apps) {
            if (app.getTenantId() != null) {
                app.setTenantName(tenantNames.get(app.getTenantId()));
            }
        }
        return R.ok(apps);
    }

    /** 租户 id→名称映射（复用既有启用租户选项来源；读失败为空映射，名称留空）。 */
    private Map<Long, String> tenantNameMap() {
        try {
            Map<Long, String> names = new java.util.HashMap<>();
            for (Map<String, Object> row : FixedTableSql.listEnabledTenantOptions(jdbcTemplate)) {
                Object id = row.get("id");
                Object name = row.get("name");
                if (id instanceof Number number && name != null) {
                    names.put(number.longValue(), String.valueOf(name));
                }
            }
            return names;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 新建应用（名称/图标/颜色/排序/可见范围）。 */
    @PreAuthorize("@ss.hasPermi('form:app:manage')")
    @PostMapping
    public R<FormAppDTO> create(@RequestBody FormAppSaveReq req) {
        log.info("Creating form app: name={}", req == null ? null : req.getName());
        return R.ok(formAppService.createApp(req));
    }

    /** 更新应用（可见范围集合整体重配）。 */
    @PreAuthorize("@ss.hasPermi('form:app:manage')")
    @PutMapping("/{id}")
    public R<FormAppDTO> update(@PathVariable("id") String id, @RequestBody FormAppSaveReq req) {
        log.info("Updating form app: id={}", id);
        return R.ok(formAppService.updateApp(id, req));
    }

    /** 应用详情（含计数与指定用户集合）。 */
    @PreAuthorize("@ss.hasPermi('form:app:manage')")
    @GetMapping("/{id}")
    public R<FormAppDTO> get(@PathVariable("id") String id) {
        return R.ok(formAppService.getApp(id));
    }

    /** 删除应用（有目录或表单引用时拒绝）。 */
    @PreAuthorize("@ss.hasPermi('form:app:manage')")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable("id") String id) {
        log.info("Deleting form app: id={}", id);
        formAppService.deleteApp(id);
        return R.ok();
    }

    /** 停用应用（阻止新增入口，保留数据与历史）。 */
    @PreAuthorize("@ss.hasPermi('form:app:manage')")
    @PostMapping("/{id}/disable")
    public R<Void> disable(@PathVariable("id") String id) {
        log.info("Disabling form app: id={}", id);
        formAppService.disableApp(id);
        return R.ok();
    }

    /** 恢复应用。 */
    @PreAuthorize("@ss.hasPermi('form:app:manage')")
    @PostMapping("/{id}/enable")
    public R<Void> enable(@PathVariable("id") String id) {
        log.info("Enabling form app: id={}", id);
        formAppService.enableApp(id);
        return R.ok();
    }

    /** 应用内容（管理视角）：目录 + 目录内表单 + 应用内直接归属表单。 */
    @PreAuthorize("@ss.hasPermi('form:app:manage')")
    /**
     * 租户选项（0.1.7 #1：可见范围 TENANT 维度的候选集）。
     * 只回 id 与名称，供选择器展示；读失败返回空列表（选择器呈现为空但可保存其它维度）。
     */
    @GetMapping("/tenants")
    public R<List<Map<String, Object>>> listTenantOptions() {
        try {
            return R.ok(FixedTableSql.listEnabledTenantOptions(jdbcTemplate));
        } catch (Exception e) {
            return R.ok(List.of());
        }
    }

    @GetMapping("/{id}/content")
    public R<FormAppContentDTO> content(@PathVariable("id") String id) {
        return R.ok(formAppService.getAppContent(id));
    }

    /** 新建目录（应用内二级分类）。 */
    @PreAuthorize("@ss.hasPermi('form:app:manage')")
    @PostMapping("/{id}/dirs")
    public R<FormAppDirDTO> createDir(@PathVariable("id") String id,
                                      @RequestBody FormAppDirSaveReq req) {
        log.info("Creating app dir: appId={}, name={}", id, req == null ? null : req.getName());
        return R.ok(formAppService.createDir(id, req));
    }

    /** 更新目录名称/排序。 */
    @PreAuthorize("@ss.hasPermi('form:app:manage')")
    @PutMapping("/dirs/{dirId}")
    public R<FormAppDirDTO> updateDir(@PathVariable("dirId") String dirId,
                                      @RequestBody FormAppDirSaveReq req) {
        log.info("Updating app dir: id={}", dirId);
        return R.ok(formAppService.updateDir(dirId, req));
    }

    /** 删除目录（目录下有表单时拒绝）。 */
    @PreAuthorize("@ss.hasPermi('form:app:manage')")
    @DeleteMapping("/dirs/{dirId}")
    public R<Void> deleteDir(@PathVariable("dirId") String dirId) {
        log.info("Deleting app dir: id={}", dirId);
        formAppService.deleteDir(dirId);
        return R.ok();
    }

    /** 表单归属调整（显式操作；目标须为租户内启用应用）。 */
    @PreAuthorize("@ss.hasPermi('form:app:manage')")
    @PutMapping("/forms/{formId}/membership")
    public R<Void> assignForm(@PathVariable("formId") String formId,
                              @RequestBody FormAppMembershipReq req) {
        log.info("Assigning form membership: formId={}, appId={}, dirId={}",
                formId, req == null ? null : req.getAppId(), req == null ? null : req.getDirId());
        formAppService.assignForm(formId, req);
        return R.ok();
    }
}
