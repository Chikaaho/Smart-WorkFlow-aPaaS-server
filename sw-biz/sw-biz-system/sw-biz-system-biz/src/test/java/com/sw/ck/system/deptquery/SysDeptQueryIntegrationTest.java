package com.sw.ck.system.deptquery;

import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.datascope.DeptScopeProvider;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.DataScope;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.datascope.DeptScopeProviderImpl;
import com.sw.ck.system.entity.SysDept;
import com.sw.ck.system.mapper.SysDeptMapper;
import com.sw.ck.system.mapper.SysDeptScopedMapper;
import com.sw.ck.system.mapper.SysPostMapper;
import com.sw.ck.system.mapper.SysRoleMapper;
import com.sw.ck.system.mapper.SysUserMapper;
import com.sw.ck.system.mapper.SysUserPostMapper;
import com.sw.ck.system.mapper.SysUserRoleMapper;
import com.sw.ck.system.service.DeptQuery;
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

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 部门名称/状态条件查询 + 祖先补全闭环集成测试。
 * <p>
 * 走真实拦截器链（租户 → 数据范围 → 乐观锁 → 分页）与真实 Service/Mapper：
 * 无条件兼容回归、名称/状态/组合筛选、空结果、非法状态显式报错、祖先路径补全、
 * 共享祖先去重、兄弟未命中分支不混入，以及隔离边界（两租户互不可见、
 * 逻辑删除部门与已删祖先不出现、受限可见范围下祖先补全与筛选走同一授权查询通道）。
 * </p>
 * <p>
 * 固定种子（租户 1，create_by=900）：总部(1)→研发部(2)→后端组(3)、前端组(4)；
 * 总部→市场部(5)、停用部门(6)；落单团队(201) 的父部门 已删除研发部(200) 已逻辑删除。
 * 租户 2：总部(100)→研发部(101)（与租户 1 同名，验证租户隔离）。
 * </p>
 */
@SpringBootTest(
        classes = SysDeptQueryIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:testdb_deptquery;MODE=PostgreSQL",
                "spring.sql.init.schema-locations=classpath:db/schema-datascope-h2.sql",
                "spring.sql.init.data-locations=classpath:db/data-datascope-h2.sql",
                // sys_menu 为全局表（无 tenant_id 列），排除租户拦截器
                "sw.tenant.ignore-tables[0]=sys_menu"
        }
)
@ActiveProfiles("test")
@DisplayName("部门名称/状态条件查询与祖先补全集成测试")
class SysDeptQueryIntegrationTest {

    @Autowired
    private SysDeptService deptService;

    @Autowired
    private SysDeptScopedMapper scopedMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        // 物理清空，避免跨用例干扰（逻辑删除行会占用主键）
        jdbc.update("DELETE FROM sys_dept");
        // 默认登录：租户 1 管理员（数据范围 ALL；非超管——超管跨租户可见性由 SuperTenantVisibilityTest 覆盖）
        login(1L, DataScopeType.ALL, 900L, 1L);
        // ===== 租户 1 =====
        seed(1L, 0L, "总部", 1, 0, 900L, 1, 0);
        seed(2L, 1L, "研发部", 10, 0, 900L, 1, 0);
        seed(3L, 2L, "后端组", 20, 0, 900L, 1, 0);
        seed(4L, 2L, "前端组", 30, 0, 900L, 1, 0);
        seed(5L, 1L, "市场部", 40, 0, 900L, 1, 0);
        seed(6L, 1L, "停用部门", 50, 1, 900L, 1, 0);
        seed(200L, 1L, "已删除研发部", 60, 0, 900L, 1, 1);
        // 名称刻意避开 部/组 等其它用例关键字，防止名称包含匹配交叉命中
        seed(201L, 200L, "落单团队", 70, 0, 900L, 1, 0);
        // ===== 租户 2 =====
        seed(100L, 0L, "总部", 1, 0, 900L, 2, 0);
        seed(101L, 100L, "研发部", 10, 0, 900L, 2, 0);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    // ==================== 工具方法 ====================

    /**
     * 登录身份固定为「租户内管理员」：dataScope=ALL、superAdmin=false。
     * 本类断言租户边界与数据范围行为，不使用超管旁路；超管的跨租户可见性由
     * {@code com.sw.ck.bootstrap.p4overlap.SuperTenantVisibilityTest} 单独覆盖。
     */
    private void login(Long tenantId, DataScopeType scopeType, Long userId, Long deptId) {
        LoginUser user = new LoginUser();
        user.setTenantId(tenantId);
        user.setSuperAdmin(false);
        user.setUserId(userId);
        user.setDeptId(deptId);
        user.setDataScope(DataScope.valueOf(scopeType.name()));
        LoginUserHolder.set(user);
    }

    private void seed(long id, long parentId, String name, int sort, int status, long createBy, long tenantId, int deleted) {
        jdbc.update("""
                        INSERT INTO sys_dept (id, parent_id, name, code, sort, status, create_by, tenant_id, deleted)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id, parentId, name, "D" + id, sort, status, createBy, tenantId, deleted);
    }

    private DeptQuery query(String name, Integer status) {
        DeptQuery q = new DeptQuery();
        q.setName(name);
        q.setStatus(status);
        return q;
    }

    private List<Long> ids(List<SysDept> depts) {
        return depts.stream().map(SysDept::getId).toList();
    }

    private List<Long> listTreeIds(DeptQuery query) {
        return ids(deptService.listTree(query));
    }

    // ==================== 无条件兼容 ====================

    @Test
    @DisplayName("无条件查询 = 现状行为：全量、sort 升序、无租户 2 / 已删除数据")
    void noCondition_returnsFullTenantTree_sameAsLegacy() {
        DeptQuery empty = new DeptQuery();

        List<SysDept> legacy = deptService.listTree();
        List<SysDept> filtered = deptService.listTree(empty);

        assertThat(ids(filtered)).as("无条件条件查询与历史全量查询完全一致").isEqualTo(ids(legacy));
        assertThat(ids(filtered))
                .as("sort 升序且仅含租户 1 未删除数据（租户 2 的 100/101、已删除 200 不得混入）")
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 201L);
    }

    // ==================== 名称筛选 ====================

    @Test
    @DisplayName("名称包含匹配：命中节点 + 祖先补全，兄弟未命中分支不混入")
    void nameFilter_containsMatch_returnsHitsWithAncestors() {
        assertThat(listTreeIds(query("研发", null)))
                .as("研发部(2) 命中，补全祖先总部(1)；后端组/前端组/市场部/停用部门等未命中分支不得混入")
                .containsExactly(1L, 2L);
        assertThat(listTreeIds(query("组", null)))
                .as("后端组(3)/前端组(4) 命中，共享祖先研发部(2)/总部(1)")
                .containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    @DisplayName("纯空格名称视为未填写：返回全量，与无条件一致")
    void nameFilter_blankName_equalsNoCondition() {
        assertThat(listTreeIds(query("   ", null))).isEqualTo(ids(deptService.listTree()));
        assertThat(listTreeIds(query("", null))).isEqualTo(ids(deptService.listTree()));
    }

    @Test
    @DisplayName("名称不命中：返回空数组，不报错")
    void nameFilter_noMatch_returnsEmpty() {
        assertThat(listTreeIds(query("不存在的部门", null))).isEmpty();
    }

    // ==================== 状态筛选 ====================

    @Test
    @DisplayName("状态 0：仅正常部门")
    void statusFilter_normal_returnsNormalOnly() {
        assertThat(listTreeIds(query(null, 0)))
                .as("停用部门(6) 被排除")
                .containsExactly(1L, 2L, 3L, 4L, 5L, 201L);
    }

    @Test
    @DisplayName("状态 1：仅停用部门 + 祖先")
    void statusFilter_disabled_returnsDisabledWithAncestors() {
        assertThat(listTreeIds(query(null, 1)))
                .as("停用部门(6) 命中，补全祖先总部(1)")
                .containsExactly(1L, 6L);
    }

    // ==================== 名称 + 状态组合 ====================

    @Test
    @DisplayName("名称+状态组合 AND：命中交集，不命中返回空")
    void combinedNameAndStatus_applyBoth() {
        assertThat(listTreeIds(query("研发", 0))).containsExactly(1L, 2L);
        assertThat(listTreeIds(query("研发", 1))).isEmpty();
        assertThat(listTreeIds(query("停用", 1))).containsExactly(1L, 6L);
    }

    // ==================== 祖先路径 / 去重 / 结构 ====================

    @Test
    @DisplayName("深层命中：返回完整祖先链（后端组→研发部→总部）")
    void deepHit_returnsCompleteAncestorChain() {
        assertThat(listTreeIds(query("后端组", null))).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("命中根节点：无多余祖先")
    void rootHit_returnsOnlyRoot() {
        assertThat(listTreeIds(query("总部", null))).containsExactly(1L);
    }

    @Test
    @DisplayName("兄弟未命中分支不混入：后端组命中时前端组不得出现")
    void siblingUnhitBranch_notMixedIn() {
        List<SysDept> result = deptService.listTree(query("后端组", null));
        assertThat(ids(result)).containsExactly(1L, 2L, 3L);
        assertThat(result.stream().map(SysDept::getName))
                .as("前端组/市场部/停用部门等未命中分支不得混入")
                .doesNotContain("前端组", "市场部", "停用部门");
    }

    @Test
    @DisplayName("多个命中共享祖先：去重，不重复返回")
    void sharedAncestor_deduplicated() {
        List<SysDept> result = deptService.listTree(query("组", null));
        assertThat(ids(result)).containsExactly(1L, 2L, 3L, 4L);
        assertThat(result.stream().map(SysDept::getId).distinct().count())
                .as("无重复节点").isEqualTo(result.size());
    }

    // ==================== 非法状态 ====================

    @Test
    @DisplayName("非法状态值：显式 PARAM_ERROR，绝不静默退化为全量")
    void illegalStatus_rejectedWithParamError_notFullData() {
        assertThatThrownBy(() -> deptService.listTree(query(null, 2)))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> assertThat(((BaseException) e).getCode())
                        .as("应为 PARAM_ERROR(400)")
                        .isEqualTo(CommonErrorCode.PARAM_ERROR.getCode()));
        assertThatThrownBy(() -> deptService.listTree(query(null, -1)))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> assertThat(((BaseException) e).getCode())
                        .isEqualTo(CommonErrorCode.PARAM_ERROR.getCode()));
        // 非法状态被拒绝后，合法调用仍正常（无状态污染）
        assertThat(listTreeIds(query(null, 0))).contains(1L, 2L);
    }

    // ==================== 隔离边界：租户 / 逻辑删除 / 受限可见范围 ====================

    @Test
    @DisplayName("两租户同名部门互不可见：各自只返回本租户树")
    void tenantIsolation_sameNameDeptNotVisibleAcrossTenants() {
        login(1L, DataScopeType.ALL, 900L, 1L);
        assertThat(listTreeIds(query("研发部", null))).containsExactly(1L, 2L);

        login(2L, DataScopeType.ALL, 900L, 1L);
        assertThat(listTreeIds(query("研发部", null)))
                .as("租户 2 只见自己的总部(100)+研发部(101)")
                .containsExactly(100L, 101L);
    }

    @Test
    @DisplayName("祖先指向他租户部门：祖先补全不借机越权，链在此截断")
    void crossTenantAncestor_notLeaked() {
        // 租户 1 的部门 8 其 parent_id 指向租户 2 的总部(100)：祖先补全经同一查询通道，
        // 租户拦截器会过滤掉 100，链自然截断，不得暴露另一租户任何节点
        seed(8L, 100L, "跨境部门", 80, 0, 900L, 1, 0);
        login(1L, DataScopeType.ALL, 900L, 1L);

        assertThat(listTreeIds(query("跨境部门", null)))
                .as("仅命中自身；租户 2 的 100/101 不得因父链补全混入")
                .containsExactly(8L);
    }

    @Test
    @DisplayName("已逻辑删除部门：不命中；其子部门的祖先链中不出现")
    void logicDeletedDept_notHitAndDeletedAncestorHidden() {
        assertThat(listTreeIds(query("已删除研发部", null)))
                .as("逻辑删除行(200)不参与命中")
                .isEmpty();
        assertThat(listTreeIds(query("落单团队", null)))
                .as("落单团队(201) 命中，但其已删除父部门(200)不参与祖先补全")
                .containsExactly(201L);
    }

    @Test
    @DisplayName("受限可见范围：部门查询通道对数据范围拦截器可见，祖先补全无旁路")
    void scopedChannel_selfScope_restrictsDeptQuery() {
        // 他人（create_by=901）创建的部门：SELF 范围下不可见
        seed(7L, 1L, "他人部门", 90, 0, 901L, 1, 0);
        login(1L, DataScopeType.SELF, 900L, 1L);

        List<SysDept> scoped = scopedMapper.selectScoped();

        assertThat(scoped.stream().map(SysDept::getId).collect(java.util.stream.Collectors.toSet()))
                .as("SELF 范围仅 create_by=900：他人部门(7)被数据范围拦截；"
                        + "租户 2 的 100/101 被租户拦截；已删除 200 被逻辑删除拦截——"
                        + "证明走同一授权查询通道时三层约束全部生效")
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 201L);
    }

    // ==================== CRUD 回归 ====================

    @Test
    @DisplayName("既有 CRUD 无回归：新增/编辑/删除后树查询同步反映")
    void crud_regression() {
        SysDept created = new SysDept();
        created.setName("新增部门");
        created.setCode("NEW");
        created.setParentId(1L);
        created.setSort(99);
        created.setStatus(0);
        Long newId = deptService.create(created);

        assertThat(ids(deptService.listTree())).contains(newId);

        SysDept toUpdate = new SysDept();
        toUpdate.setId(newId);
        toUpdate.setName("新增部门改名");
        toUpdate.setCode("NEW");
        toUpdate.setParentId(1L);
        toUpdate.setSort(99);
        toUpdate.setStatus(0);
        deptService.update(toUpdate);

        List<SysDept> afterUpdate = deptService.listTree();
        assertThat(afterUpdate.stream()
                .filter(d -> d.getId().equals(newId))
                .map(SysDept::getName)).containsExactly("新增部门改名");

        deptService.delete(newId);
        assertThat(ids(deptService.listTree())).doesNotContain(newId);
        assertThat(ids(deptService.listTree())).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 201L);
    }

    // ==================== 测试上下文配置 ====================
    // 独立包 + 显式 Bean 声明（同 SysUserDataScopeTest 的既有约束，不做 @ComponentScan）。

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
            // @Lazy 对齐生产装配：DeptScopeProviderImpl 处于拦截器链依赖图中
            return new DeptScopeProviderImpl(sysDeptMapper);
        }

        @Bean
        public SysDeptService sysDeptService(SysUserService sysUserService) {
            return new SysDeptServiceImpl(sysUserService);
        }

        @Bean
        public SysUserService sysUserService(PasswordEncoder passwordEncoder, SysUserRoleMapper userRoleMapper,
                                             SysUserPostMapper userPostMapper, SysRoleMapper roleMapper,
                                             SysPostMapper postMapper) {
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
                public Set<Long> getCustomDeptIds() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null && user.getCustomDeptIds() != null
                            ? user.getCustomDeptIds() : Set.of();
                }

                @Override
                public boolean isSuperAdmin() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null && user.isSuperAdmin();
                }
            };
        }
    }
}
