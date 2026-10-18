package com.sw.ck.system.datascopetest;

import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.security.holder.DataScope;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.service.SysUserService;
import com.sw.ck.system.service.UserPageQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = SysUserDataScopeTest.TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.datasource.url=jdbc:h2:mem:testdb_sysuserds_query;MODE=PostgreSQL",
                "spring.sql.init.schema-locations=classpath:db/schema-datascope-h2.sql",
                "spring.sql.init.data-locations=classpath:db/data-datascope-h2.sql",
                "sw.tenant.ignore-tables[0]=sys_menu"})
@ActiveProfiles("test")
class UserAssociationQueryIntegrationTest {
    @Autowired SysUserService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM sys_user_post"); jdbc.update("DELETE FROM sys_user_role"); jdbc.update("DELETE FROM sys_post");
        jdbc.update("DELETE FROM sys_role"); jdbc.update("DELETE FROM sys_user"); jdbc.update("DELETE FROM sys_dept");
        jdbc.update("INSERT INTO sys_dept (id,parent_id,name,code,status,tenant_id) VALUES (1,0,'总部','HQ',0,1),(2,1,'技术','TECH',0,1),(3,2,'后端','BE',0,1),(4,0,'其他租户部门','OTHER',0,2)");
        jdbc.update("INSERT INTO sys_role (id,name,code,status,tenant_id) VALUES (10,'普通角色','normal',1,1),(11,'停用角色','disabled',0,1),(12,'超级管理员','superadmin',1,1),(20,'他租户角色','other',1,2)");
        jdbc.update("INSERT INTO sys_post (id,code,name,status,tenant_id) VALUES (30,'DEV','开发',1,1),(31,'OLD','停用岗位',0,1),(40,'OTHER','他租户岗位',1,2)");
        jdbc.update("INSERT INTO sys_user (id,username,real_name,password,status,dept_id,tenant_id) VALUES (100,'alice','Alice','x',0,2,1),(101,'bob','Bob','x',0,3,1),(102,'other','Other','x',0,4,2),(103,'deleted','Deleted','x',0,2,1)");
        jdbc.update("UPDATE sys_user SET deleted=1 WHERE id=103");
        jdbc.update("INSERT INTO sys_user_role (id,user_id,role_id,tenant_id) VALUES (1000,100,10,1),(1001,101,10,1),(1002,102,20,2),(1003,100,11,1)");
        jdbc.update("INSERT INTO sys_user_post (id,user_id,post_id,tenant_id) VALUES (2000,100,30,1),(2001,101,30,1),(2002,102,40,2),(2003,100,31,1)");
        // 租户 1 管理员（dataScope=ALL，非超管）：本类断言关联查询与跨租户不泄漏，
        // 超管跨租户可见性由 SuperTenantVisibilityTest 覆盖，不在本夹具内开启。
        LoginUser login = new LoginUser(); login.setUserId(900L); login.setTenantId(1L); login.setSuperAdmin(false);
        login.setDataScope(DataScope.valueOf(DataScopeType.ALL.name())); LoginUserHolder.set(login);
    }

    @AfterEach void clear() { LoginUserHolder.clear(); }

    private PageResult<SysUser> query(UserPageQuery q) {
        PageParam p = new PageParam(); p.setPageNum(1); p.setPageSize(20); return service.page(p, q);
    }

    @Test
    void combinedFilters_areTenantSafeRecursiveAndDeduplicated() {
        UserPageQuery q = new UserPageQuery(); q.setKeyword("Alice"); q.setDeptId(1L); q.setPostId(30L); q.setRoleId(10L);
        PageResult<SysUser> result = query(q);
        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getRecords()).extracting(SysUser::getUsername).containsExactly("alice");
    }

    @Test
    void invalidAndDisabledAssociations_returnEmptyWithoutCrossTenantLeak() {
        UserPageQuery q = new UserPageQuery(); q.setPostId(40L); assertThat(query(q).getTotal()).isZero();
        q.setPostId(31L); assertThat(query(q).getTotal()).isZero();
        q.setRoleId(999L); assertThat(query(q).getRecords()).isEmpty();
    }

    @Test
    void invalidRole_rollsBackUserAndRelationsTogether() {
        SysUser user = new SysUser(); user.setUsername("rollback-user"); user.setRealName("rollback"); user.setStatus(0); user.setTenantId(1L);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createWithAssociations(user, "secret", java.util.List.of(999L), java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username='rollback-user'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role ur JOIN sys_user u ON u.id=ur.user_id WHERE u.username='rollback-user'", Integer.class)).isZero();
    }

    @Test
    void directBinding_superadminIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.updateRoleIds(100L, java.util.List.of(12L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role WHERE user_id=100 AND role_id=12 AND deleted=0", Integer.class)).isZero();
    }
}
