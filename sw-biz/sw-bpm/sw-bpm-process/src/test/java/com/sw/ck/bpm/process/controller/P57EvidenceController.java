package com.sw.ck.bpm.process.controller;

import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.common.response.R;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P57 隔离验收夹具。
 *
 * <p>仅在显式 {@code p57-evidence} profile 下装配，服务于真实 HTTP 验收的
 * 双租户普通用户和原始存储读回。它不进入默认 dev/prod 路由，也不绕过
 * Spring Security；夹具创建和清理由管理员权限保护。</p>
 */
@Profile("p57-evidence")
@RestController
@RequestMapping("/p57-evidence")
public class P57EvidenceController {

    private static final long TENANT_A = 57001L;
    private static final long TENANT_B = 57002L;
    private static final long ROLE_A = 57101L;
    private static final long ROLE_B = 57102L;
    private static final long USER_A = 57201L;
    private static final long USER_B = 57202L;
    private static final long USER_ROLE_A = 57301L;
    private static final long USER_ROLE_B = 57302L;
    private static final long ROLE_MENU_A = 57401L;
    private static final long ROLE_MENU_B = 57402L;
    private static final String USER_PASSWORD = "P57-evidence-user-20260902";

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final BpmProcessDefService processDefService;

    public P57EvidenceController(JdbcTemplate jdbcTemplate,
                                 PasswordEncoder passwordEncoder,
                                 BpmProcessDefService processDefService) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.processDefService = processDefService;
    }

    /**
     * 创建两个不同租户的普通用户，各自仅授予 workflow:def:view。
     * 数据使用固定 P57 前缀和固定 ID，便于审查期间准确清点和清理。
     */
    @PostMapping("/fixture/ordinary-users")
    @PreAuthorize("@ss.hasPermi('system:user:create')")
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> seedOrdinaryUsers() {
        cleanupRows();
        Long menuId = jdbcTemplate.queryForObject(
                "select id from sys_menu where permission = ? and deleted = 0",
                Long.class,
                "workflow:def:view");
        if (menuId == null) {
            throw new IllegalStateException("缺少 workflow:def:view 菜单权限，不能建立 P57 双租户夹具");
        }

        String passwordHash = passwordEncoder.encode(USER_PASSWORD);
        insertRole(ROLE_A, TENANT_A, "P57 租户 A 流程查看者", "p57_tenant_a_viewer");
        insertRole(ROLE_B, TENANT_B, "P57 租户 B 流程查看者", "p57_tenant_b_viewer");
        insertUser(USER_A, TENANT_A, "p57_tenant_a_user", "P57 租户 A 普通用户", passwordHash);
        insertUser(USER_B, TENANT_B, "p57_tenant_b_user", "P57 租户 B 普通用户", passwordHash);
        insertUserRole(USER_ROLE_A, TENANT_A, USER_A, ROLE_A);
        insertUserRole(USER_ROLE_B, TENANT_B, USER_B, ROLE_B);
        insertRoleMenu(ROLE_MENU_A, TENANT_A, ROLE_A, menuId);
        insertRoleMenu(ROLE_MENU_B, TENANT_B, ROLE_B, menuId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fixture", "p57-evidence-rbac");
        result.put("users", List.of(userDescriptor(USER_A, TENANT_A, "p57_tenant_a_user"),
                userDescriptor(USER_B, TENANT_B, "p57_tenant_b_user")));
        result.put("permission", "workflow:def:view");
        return R.ok(result);
    }

    /** 仅清理由本夹具固定 ID 标识的行。 */
    @DeleteMapping("/fixture/ordinary-users")
    @PreAuthorize("@ss.hasPermi('system:user:delete')")
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> cleanupOrdinaryUsers() {
        cleanupRows();
        return R.ok(Map.of("fixture", "p57-evidence-rbac", "cleaned", true));
    }

    /** 返回当前数据库原始 graph_json 与服务层读回值，并给出固定 P57 存储清点。 */
    @GetMapping("/storage/{id}")
    @PreAuthorize("@ss.hasPermi('workflow:def:view')")
    public R<Map<String, Object>> storage(@PathVariable Long id) {
        String raw = jdbcTemplate.queryForObject(
                "select graph_json from sw_bpm_process_def where id = ? and deleted = 0",
                String.class,
                id);
        BpmProcessDef reread = processDefService.getDef(id);
        String serviceValue = reread == null ? null : reread.getGraphJson();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("rawGraphJson", raw);
        result.put("serviceGraphJson", serviceValue);
        result.put("byteEqual", raw != null && raw.equals(serviceValue));
        result.put("p57ProcessDefRows", count("select count(*) from sw_bpm_process_def where form_key like 'p57_%' and deleted = 0"));
        result.put("p57BindingRows", count("select count(*) from sw_bpm_form_binding where form_key like 'p57_%' and deleted = 0"));
        result.put("p57FlowableDefinitionRows", count("select count(*) from act_re_procdef where key_ in (select process_key from sw_bpm_process_def where form_key like 'p57_%' and deleted = 0)"));
        result.put("externalBoundary", "本接口只清点 sw_bpm_process_def/sw_bpm_form_binding/Flowable ACT_RE_PROCDEF；不读取或改写业务表");
        return R.ok(result);
    }

    private Map<String, Object> userDescriptor(long userId, long tenantId, String username) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("userId", userId);
        user.put("tenantId", tenantId);
        user.put("username", username);
        user.put("permissions", List.of("workflow:def:view"));
        return user;
    }

    private void insertRole(long id, long tenantId, String name, String code) {
        jdbcTemplate.update("insert into sys_role (id, tenant_id, name, code, sort, status, data_scope, built_in, remark) "
                        + "values (?, ?, ?, ?, 0, 1, null, false, ?)",
                id, tenantId, name, code, "P57 isolated acceptance fixture");
    }

    private void insertUser(long id, long tenantId, String username, String realName, String passwordHash) {
        jdbcTemplate.update("insert into sys_user (id, tenant_id, username, password, real_name, dept_id, status, is_admin) "
                        + "values (?, ?, ?, ?, ?, 1, 0, 0)",
                id, tenantId, username, passwordHash, realName);
    }

    private void insertUserRole(long id, long tenantId, long userId, long roleId) {
        jdbcTemplate.update("insert into sys_user_role (id, tenant_id, user_id, role_id) values (?, ?, ?, ?)",
                id, tenantId, userId, roleId);
    }

    private void insertRoleMenu(long id, long tenantId, long roleId, long menuId) {
        jdbcTemplate.update("insert into sys_role_menu (id, tenant_id, role_id, menu_id) values (?, ?, ?, ?)",
                id, tenantId, roleId, menuId);
    }

    private void cleanupRows() {
        jdbcTemplate.update("delete from sys_role_menu where id in (?, ?)", ROLE_MENU_A, ROLE_MENU_B);
        jdbcTemplate.update("delete from sys_user_role where id in (?, ?)", USER_ROLE_A, USER_ROLE_B);
        jdbcTemplate.update("delete from sys_user where id in (?, ?)", USER_A, USER_B);
        jdbcTemplate.update("delete from sys_role where id in (?, ?)", ROLE_A, ROLE_B);
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }
}
