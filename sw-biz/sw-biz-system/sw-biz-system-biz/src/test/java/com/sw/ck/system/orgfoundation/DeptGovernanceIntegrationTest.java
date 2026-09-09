package com.sw.ck.system.orgfoundation;

import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.datascope.DeptScopeProvider;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.DataScope;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.datascope.DeptScopeProviderImpl;
import com.sw.ck.system.entity.SysDept;
import com.sw.ck.system.mapper.SysDeptMapper;
import com.sw.ck.system.service.SysDeptService;
import com.sw.ck.system.service.SysUserService;
import com.sw.ck.system.service.impl.SysDeptServiceImpl;
import com.sw.ck.system.service.impl.SysUserServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * I1 部门负责人与组织树治理集成测试（方向 §3.1）。
 * <p>
 * 负责人使用稳定用户标识且必须属于合法租户和有效组织范围（存在、未删除、
 * 正常状态、同租户）；parent 合法性覆盖不存在/自引用/移入自身子树（成环）；
 * 状态值合法且创建默认正常。
 * </p>
 * <p>
 * 固定种子：租户 1 部门 601（根）→602、603（停用）；租户 2 部门 701（根）。
 * 用户：801（租户 1 正常）、802（租户 1 停用）、803（租户 2 正常）。
 * </p>
 */
@SpringBootTest(
        classes = DeptGovernanceIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:testdb_i1_deptgov;MODE=PostgreSQL",
                "spring.sql.init.schema-locations=classpath:db/schema-datascope-h2.sql",
                "spring.sql.init.data-locations=classpath:db/data-datascope-h2.sql",
                "sw.tenant.ignore-tables[0]=sys_menu"
        }
)
@ActiveProfiles("test")
@DisplayName("I1 部门负责人与组织树治理集成测试")
class DeptGovernanceIntegrationTest {

    @Autowired
    private SysDeptService deptService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM sys_dept");
        jdbc.update("DELETE FROM sys_user");
        login(1L);
        seedDept(601L, 0L, "总部", 0, 1L, null);
        seedDept(602L, 601L, "研发部", 0, 1L, null);
        seedDept(603L, 601L, "停用部门", 1, 1L, null);
        seedDept(701L, 0L, "租户二总部", 0, 2L, null);
        seedUser(801L, 1L, 0);
        seedUser(802L, 1L, 1);
        seedUser(803L, 2L, 0);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    @Test
    @DisplayName("设置合法负责人并回显：详情返回 leaderId")
    void createWithValidLeader_shouldPersistLeaderId() {
        SysDept dept = baseDept(604L, 601L, "人事部");
        dept.setLeaderId(801L);
        deptService.create(dept);

        SysDept detail = deptService.getById(604L);
        assertThat(detail).isNotNull();
        assertThat(detail.getLeaderId()).isEqualTo(801L);
    }

    @Test
    @DisplayName("负责人不存在/停用/跨租户一律拒绝")
    void createWithInvalidLeader_shouldReject() {
        SysDept missing = baseDept(605L, 601L, "部门A");
        missing.setLeaderId(9999L);
        assertThatThrownBy(() -> deptService.create(missing))
                .isInstanceOfSatisfying(BaseException.class, e -> assertThat(e.getCode())
                        .isEqualTo(com.sw.ck.common.exception.CommonErrorCode.PARAM_ERROR.getCode()));

        SysDept disabled = baseDept(606L, 601L, "部门B");
        disabled.setLeaderId(802L);
        assertThatThrownBy(() -> deptService.create(disabled))
                .isInstanceOf(BaseException.class);

        SysDept crossTenant = baseDept(607L, 601L, "部门C");
        crossTenant.setLeaderId(803L);
        assertThatThrownBy(() -> deptService.create(crossTenant))
                .isInstanceOf(BaseException.class);
    }

    @Test
    @DisplayName("更新部门可指定负责人，校验口径一致")
    void updateLeader_shouldApplySameValidation() {
        SysDept update = new SysDept();
        update.setId(602L);
        update.setName("研发部");
        update.setParentId(601L);
        update.setSort(10);
        update.setStatus(0);
        update.setLeaderId(801L);
        deptService.update(update);
        assertThat(deptService.getById(602L).getLeaderId()).isEqualTo(801L);

        SysDept invalid = new SysDept();
        invalid.setId(602L);
        invalid.setParentId(601L);
        invalid.setLeaderId(802L);
        assertThatThrownBy(() -> deptService.update(invalid))
                .isInstanceOf(BaseException.class);
    }

    @Test
    @DisplayName("父部门不存在/指向自身/移入自身子树（成环）均拒绝")
    void parentValidation_shouldRejectIllegalTargets() {
        SysDept missingParent = baseDept(608L, 9999L, "部门D");
        assertThatThrownBy(() -> deptService.create(missingParent))
                .isInstanceOf(BaseException.class);

        SysDept selfParent = baseDept(602L, 602L, "研发部");
        assertThatThrownBy(() -> deptService.update(selfParent))
                .isInstanceOf(BaseException.class);

        // 601 是 602 的父节点：把 601 挂到 602 之下即成环
        SysDept cycle = baseDept(601L, 602L, "总部");
        assertThatThrownBy(() -> deptService.update(cycle))
                .isInstanceOf(BaseException.class);
    }

    @Test
    @DisplayName("合法移动：子树间平移成功且树保持完整")
    void validMove_shouldSucceed() {
        SysDept move = baseDept(603L, 602L, "停用部门");
        move.setStatus(1);
        deptService.update(move);
        assertThat(deptService.getById(603L).getParentId()).isEqualTo(602L);
        assertThat(deptService.listTree()).extracting(SysDept::getId)
                .containsExactlyInAnyOrder(601L, 602L, 603L);
    }

    @Test
    @DisplayName("状态合法值校验：非法值拒绝，缺省创建默认正常")
    void statusValidation_shouldApply() {
        SysDept invalid = baseDept(609L, 601L, "部门E");
        invalid.setStatus(5);
        assertThatThrownBy(() -> deptService.create(invalid))
                .isInstanceOf(BaseException.class);

        SysDept defaulted = baseDept(610L, 601L, "部门F");
        deptService.create(defaulted);
        assertThat(deptService.getById(610L).getStatus()).isZero();
    }

    // ==================== 测试上下文配置 ====================

    private SysDept baseDept(Long id, Long parentId, String name) {
        SysDept dept = new SysDept();
        dept.setId(id);
        dept.setParentId(parentId);
        dept.setName(name);
        dept.setCode("D" + id);
        dept.setSort(10);
        return dept;
    }

    private void seedDept(Long id, Long parentId, String name, int status, Long tenantId, Long leaderId) {
        jdbc.update("INSERT INTO sys_dept (id, parent_id, name, code, sort, status, tenant_id, deleted, leader_id) "
                        + "VALUES (?, ?, ?, ?, 10, ?, ?, 0, ?)",
                id, parentId, name, "D" + id, status, tenantId, leaderId);
    }

    private void seedUser(Long id, Long tenantId, int status) {
        jdbc.update("INSERT INTO sys_user (id, username, password, status, tenant_id, deleted) "
                        + "VALUES (?, ?, 'x', ?, ?, 0)",
                id, "u" + id, status, tenantId);
    }

    private void login(Long tenantId) {
        LoginUser user = new LoginUser();
        user.setUserId(900L);
        user.setTenantId(tenantId);
        user.setDataScope(DataScope.ALL);
        LoginUserHolder.set(user);
    }

    @Configuration
    @EnableAutoConfiguration
    static class TestConfig {

        @Bean
        public static MapperScannerConfigurer mapperScannerConfigurer() {
            MapperScannerConfigurer configurer = new MapperScannerConfigurer();
            configurer.setBasePackage("com.sw.ck.system.mapper");
            return configurer;
        }

        @Bean
        public DeptScopeProvider deptScopeProvider(@Lazy SysDeptMapper sysDeptMapper) {
            return new DeptScopeProviderImpl(sysDeptMapper);
        }

        @Bean
        public SysDeptService sysDeptService(SysUserService sysUserService) {
            return new SysDeptServiceImpl(sysUserService);
        }

        @Bean
        public SysUserService sysUserService(PasswordEncoder passwordEncoder, com.sw.ck.system.mapper.SysUserRoleMapper userRoleMapper,
                                             com.sw.ck.system.mapper.SysUserPostMapper userPostMapper, com.sw.ck.system.mapper.SysRoleMapper roleMapper,
                                             com.sw.ck.system.mapper.SysPostMapper postMapper) {
            return new SysUserServiceImpl(passwordEncoder, userRoleMapper, userPostMapper, roleMapper, postMapper);
        }

        @Bean
        public PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(10);
        }

        @Bean
        public LoginContextProvider testLoginContextProvider() {
            return new LoginContextProvider() {
                @Override
                public Long getUserId() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getUserId() : null;
                }

                @Override
                public Long getTenantId() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getTenantId() : null;
                }

                @Override
                public Long getDeptId() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getDeptId() : null;
                }

                @Override
                public DataScopeType getDataScopeType() {
                    LoginUser user = LoginUserHolder.get();
                    if (user == null || user.getDataScope() == null) {
                        return DataScopeType.ALL;
                    }
                    return DataScopeType.valueOf(user.getDataScope().name());
                }

                @Override
                public java.util.Set<Long> getCustomDeptIds() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null && user.getCustomDeptIds() != null
                            ? user.getCustomDeptIds() : java.util.Set.of();
                }

                @Override
                public boolean isSuperAdmin() {
                    return LoginUserHolder.get() != null && LoginUserHolder.get().isSuperAdmin();
                }
            };
        }
    }
}
