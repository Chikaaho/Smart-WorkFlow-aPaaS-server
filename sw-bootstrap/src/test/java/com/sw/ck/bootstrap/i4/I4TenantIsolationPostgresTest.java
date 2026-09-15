package com.sw.ck.bootstrap.i4;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sw.ck.bpm.process.entity.BpmHandover;
import com.sw.ck.bpm.process.entity.DynamicBranchSnapshot;
import com.sw.ck.bpm.process.mapper.BpmHandoverMapper;
import com.sw.ck.bpm.process.mapper.DynamicBranchSnapshotMapper;
import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.openapi.biz.entity.OpenApiIdempotency;
import com.sw.ck.openapi.biz.mapper.OpenApiIdempotencyMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I4 等强度跨租户隔离集成（真实 PostgreSQL 路径 = zonky 内嵌 PG 17 + 全链迁移 V1—V84 +
 * TenantLineInnerInterceptor）。四象限覆盖 I4 新表：
 * sw_bpm_dynamic_branch / sw_bpm_handover / sw_openapi_idempotency。
 * t0→t0 正向读写、t88→t88 正向读写、t0→t88 零读零写、t88→t0 零读零写。
 */
@SpringJUnitConfig(I4TenantIsolationPostgresTest.I4Config.class)
class I4TenantIsolationPostgresTest {

    private static final Long T0 = 0L;
    private static final Long T88 = 88L;

    private static EmbeddedPostgres pg;

    @Autowired
    private DynamicBranchSnapshotMapper branchMapper;

    @Autowired
    private BpmHandoverMapper handoverMapper;

    @Autowired
    private OpenApiIdempotencyMapper idempotencyMapper;

    @Autowired
    private DataSource dataSource;

    @TestConfiguration
    @MapperScan(basePackageClasses = {
            DynamicBranchSnapshotMapper.class,
            BpmHandoverMapper.class,
            OpenApiIdempotencyMapper.class,
    })
    static class I4Config {

        @Bean(destroyMethod = "close")
        public DataSource dataSource() throws Exception {
            // 与 FlywayFullChainPostgresTest 同一 zonky 真实 PG 路径；全链迁移在 BeforeAll 执行
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(url);
            ds.setUsername("postgres");
            ds.setPassword("postgres");
            ds.setDriverClassName("org.postgresql.Driver");
            ds.setMaximumPoolSize(2);
            return ds;
        }

        @Bean
        public LoginContextProvider loginContextProvider() {
            return new LoginContextProvider() {
                @Override
                public Long getUserId() {
                    LoginUser user = LoginUserHolder.get();
                    return user == null ? null : user.getUserId();
                }

                @Override
                public Long getTenantId() {
                    LoginUser user = LoginUserHolder.get();
                    return user == null ? null : user.getTenantId();
                }

                @Override
                public Long getDeptId() {
                    return null;
                }

                @Override
                public com.sw.ck.common.datascope.DataScopeType getDataScopeType() {
                    return com.sw.ck.common.datascope.DataScopeType.SELF;
                }

                @Override
                public java.util.Set<Long> getCustomDeptIds() {
                    return java.util.Set.of();
                }

                @Override
                public boolean isSuperAdmin() {
                    return false;
                }
            };
        }

        @Bean
        public DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        public SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                                   LoginContextProvider loginContextProvider) {
            TenantProperties tenantProperties = new TenantProperties();
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(
                    new CommonTenantLineHandler(tenantProperties, loginContextProvider)));
            interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);

            GlobalConfig globalConfig = new GlobalConfig();
            GlobalConfig.DbConfig dbConfig = new GlobalConfig.DbConfig();
            dbConfig.setLogicDeleteField("deleted");
            dbConfig.setLogicDeleteValue("1");
            dbConfig.setLogicNotDeleteValue("0");
            globalConfig.setDbConfig(dbConfig);

            // 生产同构：BaseEntity 审计列/租户列 insert 填充经 GlobalConfig（tenant_id 物理落列）
            globalConfig.setMetaObjectHandler(
                    new com.sw.ck.common.config.mybatis.CommonMetaObjectHandler(loginContextProvider));
            MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            factoryBean.setConfiguration(configuration);
            factoryBean.setPlugins(interceptor);
            factoryBean.setGlobalConfig(globalConfig);
            try {
                return factoryBean.getObject();
            } catch (Exception e) {
                throw new IllegalStateException("I4 sqlSessionFactory 初始化失败", e);
            }
        }
    }

    private static String url;

    @BeforeAll
    static void startPostgresAndMigrate() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        url = pg.getJdbcUrl("postgres", "postgres");
        List<String> locations = List.of(
                "classpath:db/migration/postgresql",
                "classpath:db/migration/bpm/postgresql",
                "classpath:db/migration/notify/postgresql",
                "classpath:db/migration/form/postgresql",
                "classpath:db/migration/storage/postgresql",
                "classpath:db/migration/job/postgresql",
                "classpath:db/migration/agent/postgresql",
                "classpath:db/migration/iot/postgresql",
                "classpath:db/migration/openapi/postgresql");
        org.flywaydb.core.Flyway.configure()
                .dataSource(url, "postgres", "postgres")
                .locations(locations.toArray(new String[0]))
                .load()
                .migrate();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (pg != null) {
            pg.close();
        }
        LoginUserHolder.clear();
    }

    private void loginAs(Long tenantId, Long userId) {
        LoginUser user = new LoginUser();
        user.setUserId(userId);
        user.setTenantId(tenantId);
        LoginUserHolder.set(user);
    }

    @Test
    void dynamicBranch_fourQuadrantTenantIsolation() {
        String piT0 = "pi-i4-t0-" + UUID.randomUUID().toString().substring(0, 8);
        // t0 写入
        loginAs(T0, 1L);
        DynamicBranchSnapshot row0 = branch("piT0", piT0, 1L);
        assertEquals(1, branchMapper.insert(row0));
        assertEquals(1, branchMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DynamicBranchSnapshot>()
                        .eq(DynamicBranchSnapshot::getProcessInstanceId, piT0)));
        // t88 视角零读
        loginAs(T88, 2L);
        assertEquals(0, branchMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DynamicBranchSnapshot>()
                        .eq(DynamicBranchSnapshot::getProcessInstanceId, piT0)));
        // t88 写入自己的，互不可见
        String piT88 = "pi-i4-t88-" + UUID.randomUUID().toString().substring(0, 8);
        DynamicBranchSnapshot row88 = branch("piT88", piT88, 2L);
        row88.setTenantId(T88);
        assertEquals(1, branchMapper.insert(row88));
        assertEquals(1, branchMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DynamicBranchSnapshot>()
                        .eq(DynamicBranchSnapshot::getProcessInstanceId, piT88)));
        loginAs(T0, 1L);
        assertEquals(0, branchMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DynamicBranchSnapshot>()
                        .eq(DynamicBranchSnapshot::getProcessInstanceId, piT88)));
        // 租户列落库正确（跨拦截器直查；t0 行由拦截器填充，t88 行显式 set 被 MetaObjectHandler 覆盖为上下文租户）
        assertTenantColumn("sw_bpm_dynamic_branch", piT0, T0);
        assertTenantColumn("sw_bpm_dynamic_branch", piT88, T88);
    }

    @Test
    void handoverAndIdempotency_tenantScoped() {
        loginAs(T0, 1L);
        BpmHandover handover0 = new BpmHandover();
        handover0.setFromUserId(10L);
        handover0.setToUserId(20L);
        handover0.setIncludeProxyRules(false);
        handover0.setStatus("PROCESSING");
        handover0.setOperatorId(1L);
        handover0.setExecutedAt(LocalDateTime.now());
        handover0.setCreateTime(LocalDateTime.now());
        handover0.setUpdateTime(LocalDateTime.now());
        handover0.setDeleted(0);
        assertEquals(1, handoverMapper.insert(handover0));
        assertEquals(1, handoverMapper.selectCount(null));

        OpenApiIdempotency idem0 = new OpenApiIdempotency();
        idem0.setAppId("app-t0");
        idem0.setIdemKey("key-t0");
        idem0.setResultRef("ref-t0");
        idem0.setCreateTime(LocalDateTime.now());
        idem0.setUpdateTime(LocalDateTime.now());
        idem0.setDeleted(0);
        assertEquals(1, idempotencyMapper.insert(idem0));
        assertEquals(1, idempotencyMapper.selectCount(null));

        loginAs(T88, 2L);
        // t88 视角：t0 数据零读
        assertEquals(0, handoverMapper.selectCount(null));
        assertEquals(0, idempotencyMapper.selectCount(null));
        // t88 写入同名幂等键不冲突（app 维度 + 拦截器租户注入隔离）
        OpenApiIdempotency idem88 = new OpenApiIdempotency();
        idem88.setAppId("app-t88");
        idem88.setIdemKey("key-t88");
        idem88.setResultRef("ref-t88");
        idem88.setCreateTime(LocalDateTime.now());
        idem88.setUpdateTime(LocalDateTime.now());
        idem88.setDeleted(0);
        assertEquals(1, idempotencyMapper.insert(idem88));
        assertEquals(1, idempotencyMapper.selectCount(null));
    }

    private DynamicBranchSnapshot branch(String nodeKey, String pi, Long leaderId) {
        DynamicBranchSnapshot row = new DynamicBranchSnapshot();
        row.setProcessInstanceId(pi);
        row.setNodeKey(nodeKey);
        row.setBranchIndex(0);
        row.setSourceType("VARIABLE");
        row.setSourceValue("deptList");
        row.setDeptIds("1");
        row.setLeaderId(leaderId);
        row.setConvergeMode("ALL");
        row.setStatus("START");
        row.setCreateTime(LocalDateTime.now());
        row.setUpdateTime(LocalDateTime.now());
        row.setDeleted(0);
        return row;
    }

    /** 绕过拦截器直查租户列，确认物理写入值与上下文一致（零串写的物理证据）。 */
    private void assertTenantColumn(String table, String processInstanceId, Long expectedTenant) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT tenant_id FROM " + table + " WHERE process_instance_id = ?")) {
            ps.setString(1, processInstanceId);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next(), table + " 应有行: " + processInstanceId);
                assertEquals(expectedTenant, rs.getLong(1), table + " 租户列物理值应一致");
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
