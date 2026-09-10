package com.sw.ck.form.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.security.holder.DataScope;
import com.sw.ck.security.holder.LoginUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * I2 字段权限评估单元测试：解析、role/user/dept 主体匹配、超管短路、编辑闸门。
 */
@DisplayName("字段权限服务·单元测试")
class FieldPermissionServiceTest {

    private final FieldPermissionService service = new FieldPermissionService(new ObjectMapper());

    private static final String DEFINITION = """
            {
              "fields": [{"name": "salary", "type": "NUMBER"}, {"name": "note", "type": "TEXT"}],
              "fieldPermissions": {
                "salary": {
                  "view": ["role:hr", "user:42", "dept:7"],
                  "edit": ["role:hr"]
                }
              }
            }
            """;

    private static final String ACTION_DEFINITION = """
            {
              "fields": [{"name": "amount", "type": "NUMBER"}],
              "actionPermissions": {
                "view": ["role:admin"],
                "flowStart": ["role:admin"]
              }
            }
            """;

    private LoginUser user(Long id, String role, Long deptId, boolean superAdmin) {
        LoginUser u = new LoginUser();
        u.setUserId(id);
        u.setTenantId(0L);
        u.setRoles(role == null ? List.of() : List.of(role));
        u.setDeptId(deptId);
        u.setSuperAdmin(superAdmin);
        u.setDataScope(DataScope.ALL);
        return u;
    }

    @Test
    @DisplayName("hr 角色可查看可编辑；他人不可见 salary；未配置字段不设限")
    void parse_and_check_ok() {
        var perms = service.parse(DEFINITION);
        assertThat(perms).containsKey("salary");

        assertThat(service.canView(user(1L, "hr", 9L, false), "salary", perms)).isTrue();
        assertThat(service.canEdit(user(1L, "hr", 9L, false), "salary", perms)).isTrue();
        assertThat(service.canEdit(user(42L, "emp", 9L, false), "salary", perms)).isFalse();
        assertThat(service.canView(user(42L, "emp", 7L, false), "salary", perms)).isTrue();
        assertThat(service.canView(user(43L, "emp", 8L, false), "salary", perms)).isFalse();
        // 未配置字段 note：任何人可看
        assertThat(service.canView(user(43L, "emp", 8L, false), "note", perms)).isTrue();
    }

    @Test
    @DisplayName("超管短路：无匹配主体也放行")
    void superAdmin_bypass() {
        var perms = service.parse(DEFINITION);
        LoginUser admin = user(99L, null, null, true);
        assertThat(service.canView(admin, "salary", perms)).isTrue();
        assertThat(service.canEdit(admin, "salary", perms)).isTrue();
    }

    @Test
    @DisplayName("编辑闸门：无 edit 权载荷携带受控字段 → FIELD_EDIT_DENIED")
    void assertEditablePayload_shouldReject() {
        var perms = service.parse(DEFINITION);
        LoginUser emp = user(42L, "emp", 9L, false);
        assertThatThrownBy(() -> service.assertEditablePayload(emp, perms,
                Map.of("note", "ok", "salary", 100)))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> assertThat(((BaseException) e).getCode())
                        .isEqualTo(com.sw.ck.form.api.exception.FormErrorCode.FIELD_EDIT_DENIED.getCode()));
        // 不含受控字段 → 放行
        service.assertEditablePayload(emp, perms, Map.of("note", "ok"));
    }

    @Test
    @DisplayName("viewDeniedFields 汇总无查看权字段；子字段键可独立配置")
    void viewDeniedFields_shouldCollect() {
        var perms = service.parse(DEFINITION);
        Set<String> denied = service.viewDeniedFields(user(43L, "emp", 8L, false), perms);
        assertThat(denied).containsExactly("salary");
    }

    @Test
    @DisplayName("顶层动作权限：撤销角色后 flowStart/view 均拒绝，超管放行")
    void actionPermissions_shouldMatchCurrentRoles() {
        LoginUser filler = user(43L, "filler", 8L, false);
        LoginUser admin = user(44L, "admin", 8L, false);
        LoginUser superAdmin = user(45L, null, 8L, true);

        assertThat(service.canAction(filler, "view", ACTION_DEFINITION)).isFalse();
        assertThat(service.canAction(filler, "flowStart", ACTION_DEFINITION)).isFalse();
        assertThat(service.canAction(admin, "view", ACTION_DEFINITION)).isTrue();
        assertThat(service.canAction(admin, "flowStart", ACTION_DEFINITION)).isTrue();
        assertThat(service.canAction(superAdmin, "flowStart", ACTION_DEFINITION)).isTrue();
        assertThat(service.canAction(filler, "edit", ACTION_DEFINITION)).isTrue();
    }
}
