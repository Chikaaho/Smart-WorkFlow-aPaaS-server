package com.sw.ck.bootstrap;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sw.ck.bpm.process.dto.ApprovalHistoryItemDTO;
import com.sw.ck.bpm.process.entity.ApprovalActionRecord;
import com.sw.ck.bpm.process.service.ApprovalHistoryEnricher;
import com.sw.ck.bpm.process.service.TaskActionService;
import com.sw.ck.system.mapper.SysDeptMapper;
import com.sw.ck.system.mapper.SysUserMapper;
import com.sw.ck.system.service.impl.DeptFacadeImpl;
import com.sw.ck.system.service.impl.UserFacadeImpl;
import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * W-c（0.4.1）：任务详情审批历史的意见字段与处理人部门，在真实 PostgreSQL 上验证。
 * <p>
 * 覆盖三个场景：有意见表单的会签行（字段视图 + 部门名）、无意见表单的普通行
 * （不虚构字段，部门照常解析）、处理人无部门（部门为空，由前端显示占位符）。
 * 真实 PG 路径验证新增 SQL（{@code sys_user.dept_id} 批量查询、{@code sys_dept}
 * 名称批量查询）的类型与关联；租户过滤由既有多租户拦截器承担，本测试不重复覆盖。
 * </p>
 */
@SpringJUnitConfig(ApprovalHistoryDeptPostgresTest.PgConfig.class)
class ApprovalHistoryDeptPostgresTest {

    private static final String USER = "postgres";
    private static final String PASSWORD = "postgres";

    private static EmbeddedPostgres pg;
    private static String url;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private UserFacadeImpl userFacade;

    @Autowired
    private DeptFacadeImpl deptFacade;

    @TestConfiguration
    @MapperScan(basePackageClasses = {SysUserMapper.class, SysDeptMapper.class})
    static class PgConfig {

        @Bean(destroyMethod = "close")
        public DataSource dataSource() throws Exception {
            pg = EmbeddedPostgres.builder().start();
            url = pg.getJdbcUrl(USER, "postgres");
            String[] locations = {
                    "classpath:db/migration/{vendor}",
                    "classpath:db/migration/bpm/{vendor}",
                    "classpath:db/migration/notify/{vendor}",
                    "classpath:db/migration/form/{vendor}",
                    "classpath:db/migration/storage/{vendor}",
                    "classpath:db/migration/job/{vendor}",
                    "classpath:db/migration/agent/{vendor}",
                    "classpath:db/migration/iot/{vendor}",
                    "classpath:db/migration/openapi/{vendor}",
                    "classpath:db/migration/system/{vendor}"
            };
            Flyway.configure()
                    .dataSource(url, USER, PASSWORD)
                    .locations(Arrays.stream(locations)
                            .map(location -> location.replace("{vendor}", "postgresql"))
                            .toArray(String[]::new))
                    .load()
                    .migrate();
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(url);
            ds.setUsername(USER);
            ds.setPassword(PASSWORD);
            return ds;
        }

        @Bean
        public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            GlobalConfig globalConfig = new GlobalConfig();
            globalConfig.setBanner(false);
            factory.setGlobalConfig(globalConfig);
            return factory.getObject();
        }

        @Bean
        public UserFacadeImpl userFacade(SysUserMapper userMapper, SysDeptMapper deptMapper) {
            return new UserFacadeImpl(userMapper, deptMapper);
        }

        @Bean
        public DeptFacadeImpl deptFacade(SysDeptMapper deptMapper) {
            return new DeptFacadeImpl(deptMapper);
        }
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (pg != null) {
            pg.close();
        }
    }

    private void seed() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO sys_dept (id, name, parent_id, sort, status, deleted, tenant_id, version) "
                + "VALUES (9001, '安全保卫部', 0, 0, 0, 0, 1, 0)");
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, sex, status, dept_id, is_admin, deleted, tenant_id, version) "
                + "VALUES (9001, 'w_c_dept_user', 'x', '张伟', 0, 0, 9001, 0, 0, 1, 0)");
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, sex, status, dept_id, is_admin, deleted, tenant_id, version) "
                + "VALUES (9002, 'w_c_nodept_user', 'x', '李强', 0, 0, NULL, 0, 0, 1, 0)");
    }

    @Test
    @DisplayName("真实 PG：会签行字段视图 + 部门名；普通行无字段但有部门；无部门处理人为空")
    void enrich_shouldResolveOpinionFieldsAndDeptOnPostgres() {
        seed();
        TaskActionService taskActionService = mock(TaskActionService.class);
        when(taskActionService.resolveUserNames(any())).thenReturn(Map.of(9001L, "张伟"));

        ApprovalHistoryItemDTO signItem = new ApprovalHistoryItemDTO();
        signItem.setTaskId("t-sign");
        signItem.setAssignee("9001");
        signItem.setOpinionData(Map.of("fact", "属实", "handler", "9001"));

        ApprovalHistoryItemDTO plainItem = new ApprovalHistoryItemDTO();
        plainItem.setTaskId("t-plain");
        plainItem.setAssignee("9001");

        ApprovalHistoryItemDTO noDeptItem = new ApprovalHistoryItemDTO();
        noDeptItem.setTaskId("t-nodept");
        noDeptItem.setAssignee("9002");

        ApprovalActionRecord action = new ApprovalActionRecord();
        action.setTaskId("t-sign");
        action.setOpinionFormSnapshot("{\"opinionForm\":{\"fields\":["
                + "{\"key\":\"fact\",\"type\":\"RADIO\",\"label\":\"事实核实结论\"},"
                + "{\"key\":\"handler\",\"type\":\"USER\",\"label\":\"现场处置人\"}]}}");

        List<ApprovalHistoryItemDTO> history = List.of(signItem, plainItem, noDeptItem);
        ApprovalHistoryEnricher.enrich(history, Map.of("t-sign", action),
                new com.fasterxml.jackson.databind.ObjectMapper(), taskActionService,
                userFacade, deptFacade);

        assertNotNull(signItem.getOpinionFields());
        assertEquals(2, signItem.getOpinionFields().size());
        assertEquals("事实核实结论", signItem.getOpinionFields().get(0).getLabel());
        assertEquals("属实", signItem.getOpinionFields().get(0).getDisplayValue());
        assertEquals("张伟", signItem.getOpinionFields().get(1).getDisplayValue());
        assertEquals("安全保卫部", signItem.getAssigneeDept());

        assertNull(plainItem.getOpinionFields(), "无意见快照的普通行不生成字段视图");
        assertEquals("安全保卫部", plainItem.getAssigneeDept());

        assertNull(noDeptItem.getAssigneeDept(), "处理人无部门时部门为空");
    }
}
