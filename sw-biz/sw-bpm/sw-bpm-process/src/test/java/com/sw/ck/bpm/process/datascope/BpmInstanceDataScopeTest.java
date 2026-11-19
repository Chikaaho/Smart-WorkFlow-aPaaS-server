// package com.sw.ck.bpm.process.datascope;

// import com.sw.ck.bpm.process.entity.BpmInstance;
// import com.sw.ck.bpm.process.mapper.BpmInstanceMapper;
// import com.sw.ck.bpm.process.service.BpmInstanceService;
// import com.sw.ck.bpm.process.service.impl.BpmInstanceServiceImpl;
// import com.sw.ck.common.datascope.DataScopeType;
// import com.sw.ck.common.datascope.DeptScopeProvider;
// import com.sw.ck.common.page.PageParam;
// import com.sw.ck.common.page.PageResult;
// import com.sw.ck.common.security.LoginContextProvider;
// import com.sw.ck.security.holder.DataScope;
// import com.sw.ck.security.holder.LoginUser;
// import com.sw.ck.security.holder.LoginUserHolder;
// import org.junit.jupiter.api.AfterEach;
// import org.junit.jupiter.api.BeforeEach;
// import org.junit.jupiter.api.DisplayName;
// import org.junit.jupiter.api.Test;
// import org.mybatis.spring.mapper.MapperScannerConfigurer;
// import org.springframework.beans.factory.annotation.Autowired;
// import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
// import org.springframework.boot.test.context.SpringBootTest;
// import org.springframework.context.annotation.Bean;
// import org.springframework.context.annotation.Configuration;
// import org.springframework.jdbc.core.JdbcTemplate;
// import org.springframework.test.context.ActiveProfiles;

// import java.util.List;
// import java.util.Set;

// import static org.assertj.core.api.Assertions.assertThat;

// /**
//  * sw_bpm_instance 分页数据范围真过滤测试（验收标准 2）。
//  * <p>
//  * sw_bpm_instance 无 dept_id 列、归属用户列为 initiator_id，等效条件
//  * （initiator_id IN (SELECT id FROM sys_user WHERE dept_id IN (...))）在
//  * {@code BpmInstanceMapper#selectInstanceCount/selectInstanceList} 内实现；
//  * 本测试走真实拦截器链（租户 → 数据范围 → 乐观锁），覆盖
//  * ALL / SELF / DEPT / DEPT_AND_CHILD / CUSTOM（含空关联恒假）/ 超管短路。
//  * </p>
//  * <p>
//  * 用户：u2001/u2002 ∈ 部门 11，u2101 ∈ 111，u2102 ∈ 112，u2200 ∈ 12
//  * （子部门映射由测试 DeptScopeProvider 提供：11 → {111, 112}）。
//  * 实例：i1..i5 分别由 u2001/u2002/u2101/u2102/u2200 发起。
//  * </p>
//  */
// @SpringBootTest(
//         classes = BpmInstanceDataScopeTest.TestConfig.class,
//         webEnvironment = SpringBootTest.WebEnvironment.NONE,
//         properties = {
//                 "spring.datasource.url=jdbc:h2:mem:bpm_ds_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
//                 "spring.sql.init.schema-locations=classpath:db/schema-h2.sql,classpath:db/schema-datascope-addon-h2.sql"
//         }
// )
// @ActiveProfiles("test")
// @DisplayName("sw_bpm_instance 分页数据范围过滤测试")
// class BpmInstanceDataScopeTest {

//     @Autowired
//     private BpmInstanceMapper mapper;

//     @Autowired
//     private BpmInstanceService service;

//     @Autowired
//     private JdbcTemplate jdbcTemplate;

//     @BeforeEach
//     void setUp() {
//         jdbcTemplate.update("DELETE FROM sw_bpm_instance");
//         jdbcTemplate.update("DELETE FROM sys_user");
//         seedUser(2001L, 11L, "u2001");
//         seedUser(2002L, 11L, "u2002");
//         seedUser(2101L, 111L, "u2101");
//         seedUser(2102L, 112L, "u2102");
//         seedUser(2200L, 12L, "u2200");
//         seedInstance(1L, 2001L);
//         seedInstance(2L, 2002L);
//         seedInstance(3L, 2101L);
//         seedInstance(4L, 2102L);
//         seedInstance(5L, 2200L);
//     }

//     @AfterEach
//     void tearDown() {
//         LoginUserHolder.clear();
//     }

//     private void seedUser(Long id, Long deptId, String username) {
//         jdbcTemplate.update("""
//                         INSERT INTO sys_user (id, username, password, status, dept_id, tenant_id)
//                         VALUES (?, ?, 'x', 0, ?, 0)
//                         """,
//                 id, username, deptId);
//     }

//     private void seedInstance(Long id, Long initiatorId) {
//         jdbcTemplate.update("""
//                         INSERT INTO sw_bpm_instance
//                         (id, process_instance_id, process_def_key, business_key, form_key,
//                          initiator_id, status, create_by, tenant_id)
//                         VALUES (?, ?, 'def_' || ?, 'bk_' || ?, 'form_' || ?, ?, 'RUNNING', ?, 0)
//                         """,
//                 id, "pi-" + id, id, id, id, initiatorId, initiatorId);
//     }

//     private void loginAs(DataScopeType scopeType, Long userId, Long deptId, Set<Long> customDeptIds,
//                          boolean superAdmin) {
//         LoginUser user = new LoginUser();
//         user.setUserId(userId);
//         user.setTenantId(0L);
//         user.setDeptId(deptId);
//         user.setDataScope(DataScope.valueOf(scopeType.name()));
//         user.setCustomDeptIds(customDeptIds);
//         user.setSuperAdmin(superAdmin);
//         LoginUserHolder.set(user);
//     }

//     private PageResult<BpmInstance> pageAll() {
//         PageParam param = new PageParam();
//         param.setPageNum(1);
//         param.setPageSize(50);
//         return service.pageInstances(param, null);
//     }

//     private List<Long> ids(PageResult<BpmInstance> result) {
//         return result.getRecords().stream().map(BpmInstance::getId).toList();
//     }

//     // ==================== 用例 ====================

//     @Test
//     @DisplayName("ALL：返回全量 5 实例")
//     void all_shouldReturnAllInstances() {
//         loginAs(DataScopeType.ALL, 2001L, 11L, Set.of(), false);

//         PageResult<BpmInstance> result = pageAll();

//         assertThat(result.getTotal()).isEqualTo(5L);
//         assertThat(ids(result)).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
//     }

//     @Test
//     @DisplayName("超管：短路全部范围限制，返回全量")
//     void superAdmin_shouldReturnAllInstances() {
//         loginAs(DataScopeType.SELF, 2001L, 11L, Set.of(), true);

//         PageResult<BpmInstance> result = pageAll();

//         assertThat(result.getTotal()).isEqualTo(5L);
//     }

//     @Test
//     @DisplayName("SELF：仅本人（2001）发起的实例 i1")
//     void self_shouldReturnOnlyOwnInstances() {
//         loginAs(DataScopeType.SELF, 2001L, 11L, Set.of(), false);

//         PageResult<BpmInstance> result = pageAll();

//         assertThat(result.getTotal()).isEqualTo(1L);
//         assertThat(ids(result)).containsExactly(1L);
//     }

//     @Test
//     @DisplayName("DEPT：仅本部门（11）用户发起的实例 i1/i2")
//     void dept_shouldReturnOnlySameDeptUsersInstances() {
//         loginAs(DataScopeType.DEPT, 2001L, 11L, Set.of(), false);

//         PageResult<BpmInstance> result = pageAll();

//         assertThat(result.getTotal()).isEqualTo(2L);
//         assertThat(ids(result)).containsExactlyInAnyOrder(1L, 2L);
//     }

//     @Test
//     @DisplayName("DEPT_AND_CHILD：本部门及子部门（11+111+112）发起人 → i1..i4")
//     void deptAndChild_shouldIncludeChildDeptUsersInstances() {
//         loginAs(DataScopeType.DEPT_AND_CHILD, 2001L, 11L, Set.of(), false);

//         PageResult<BpmInstance> result = pageAll();

//         assertThat(result.getTotal()).isEqualTo(4L);
//         assertThat(ids(result)).containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
//     }

//     @Test
//     @DisplayName("CUSTOM：仅关联部门（111+12）用户发起的实例 i3/i5")
//     void custom_shouldReturnOnlyCustomDeptUsersInstances() {
//         loginAs(DataScopeType.CUSTOM, 2001L, 11L, Set.of(111L, 12L), false);

//         PageResult<BpmInstance> result = pageAll();

//         assertThat(result.getTotal()).isEqualTo(2L);
//         assertThat(ids(result)).containsExactlyInAnyOrder(3L, 5L);
//     }

//     @Test
//     @DisplayName("CUSTOM：未配置任何部门 → 恒假返回 0 行")
//     void custom_withEmptyDeptIds_shouldReturnEmpty() {
//         loginAs(DataScopeType.CUSTOM, 2001L, 11L, Set.of(), false);

//         PageResult<BpmInstance> result = pageAll();

//         assertThat(result.getTotal()).isZero();
//         assertThat(result.getRecords()).isEmpty();
//     }

//     @Test
//     @DisplayName("DEPT：取不到部门 → 恒假返回 0 行")
//     void dept_withoutDeptId_shouldReturnEmpty() {
//         loginAs(DataScopeType.DEPT, 2001L, null, Set.of(), false);

//         PageResult<BpmInstance> result = pageAll();

//         assertThat(result.getTotal()).isZero();
//         assertThat(result.getRecords()).isEmpty();
//     }

//     // ==================== 测试上下文配置 ====================

//     @Configuration
//     @EnableAutoConfiguration
//     static class TestConfig {

//         @Bean
//         public BpmInstanceService bpmInstanceService(
//                 LoginContextProvider loginContextProvider,
//                 DeptScopeProvider deptScopeProvider) {
//             return new BpmInstanceServiceImpl(loginContextProvider, deptScopeProvider);
//         }

//         @Bean
//         public static MapperScannerConfigurer mapperScannerConfigurer() {
//             MapperScannerConfigurer configurer = new MapperScannerConfigurer();
//             configurer.setBasePackage("com.sw.ck.bpm.process.mapper");
//             return configurer;
//         }

//         @Bean
//         public LoginContextProvider testLoginContextProvider() {
//             return new LoginContextProvider() {
//                 @Override
//                 public Long getUserId() {
//                     LoginUser user = LoginUserHolder.get();
//                     return user != null ? user.getUserId() : null;
//                 }

//                 @Override
//                 public Long getTenantId() {
//                     LoginUser user = LoginUserHolder.get();
//                     return user != null ? user.getTenantId() : null;
//                 }

//                 @Override
//                 public Long getDeptId() {
//                     LoginUser user = LoginUserHolder.get();
//                     return user != null ? user.getDeptId() : null;
//                 }

//                 @Override
//                 public DataScopeType getDataScopeType() {
//                     LoginUser user = LoginUserHolder.get();
//                     if (user == null || user.getDataScope() == null) {
//                         return DataScopeType.ALL;
//                     }
//                     return DataScopeType.valueOf(user.getDataScope().name());
//                 }

//                 @Override
//                 public Set<Long> getCustomDeptIds() {
//                     LoginUser user = LoginUserHolder.get();
//                     return user != null && user.getCustomDeptIds() != null
//                             ? user.getCustomDeptIds() : Set.of();
//                 }

//                 @Override
//                 public boolean isSuperAdmin() {
//                     LoginUser user = LoginUserHolder.get();
//                     return user != null && user.isSuperAdmin();
//                 }
//             };
//         }

//         @Bean
//         public DeptScopeProvider testDeptScopeProvider() {
//             // 与 sys_user 测试同构的部门树映射：11 → {111, 112}
//             return deptId -> {
//                 if (deptId == null) {
//                     return List.of();
//                 }
//                 if (deptId == 11L) {
//                     return List.of(111L, 112L);
//                 }
//                 return List.of();
//             };
//         }
//     }
// }
