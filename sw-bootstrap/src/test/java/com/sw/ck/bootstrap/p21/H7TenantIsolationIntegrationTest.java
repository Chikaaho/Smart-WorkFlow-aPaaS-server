package com.sw.ck.bootstrap.p21;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.iot.entity.IotConnection;
import com.sw.ck.iot.mapper.IotConnectionMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.zaxxer.hikari.HikariDataSource;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * H7 双租户四象限隔离集成运行（真实 PG smart_workflow 库 + TenantLineInnerInterceptor）。
 * <p>
 * 通过 LoginUserHolder 注入 tenant0 / tenant88 两个上下文，验证：
 * t0→t0 正向读写、t88→t88 正向读写、t0→t88 零读零写、t88→t0 零读零写。
 * 仅操作本轮 h7- 前缀对象并在结束时清理。
 * </p>
 */
@SpringJUnitConfig(H7TenantIsolationIntegrationTest.H7Config.class)
class H7TenantIsolationIntegrationTest {

    private static final Long T0 = 0L;
    private static final Long T88 = 88L;
    private static final String CODE_T0 = "h7-tenant0-conn";
    private static final String CODE_T88 = "h7-tenant88-conn";

    @Autowired
    private IotConnectionMapper connectionMapper;

    @Autowired
    private DataSource dataSource;

    @TestConfiguration
    @MapperScan(basePackageClasses = IotConnectionMapper.class)
    static class H7Config {

        @Bean(destroyMethod = "close")
        public DataSource dataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl("jdbc:postgresql://localhost:5432/smart_workflow");
            ds.setUsername("chikan");
            ds.setPassword("123456");
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

            com.baomidou.mybatisplus.core.MybatisConfiguration configuration =
                    new com.baomidou.mybatisplus.core.MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);

            com.baomidou.mybatisplus.core.config.GlobalConfig globalConfig =
                    new com.baomidou.mybatisplus.core.config.GlobalConfig();
            com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig dbConfig =
                    new com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig();
            dbConfig.setLogicDeleteField("deleted");
            dbConfig.setLogicDeleteValue("1");
            dbConfig.setLogicNotDeleteValue("0");
            globalConfig.setDbConfig(dbConfig);

            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setGlobalConfig(globalConfig);
            factory.setPlugins(interceptor);
            try {
                return factory.getObject();
            } catch (Exception e) {
                throw new IllegalStateException("初始化 SqlSessionFactory 失败", e);
            }
        }
    }

    private static LoginUser context(Long tenantId) {
        LoginUser user = new LoginUser();
        user.setUserId(1L);
        user.setTenantId(tenantId);
        return user;
    }

    private IotConnection newConn(Long tenantId, String code) {
        IotConnection conn = new IotConnection();
        conn.setCode(code);
        conn.setCreateTime(java.time.LocalDateTime.now());
        conn.setUpdateTime(java.time.LocalDateTime.now());
        conn.setDeleted(0);
        conn.setVersion(0L);
        conn.setName("H7 " + code);
        conn.setConnType("MQTT");
        conn.setEnabled(1);
        conn.setHost("10.7.7.7");
        conn.setPort(1883);
        conn.setTenantId(tenantId);
        return conn;
    }

    @BeforeAll
    static void cleanupPreviousRuns(@org.springframework.beans.factory.annotation.Autowired DataSource dataSource) {
        // 本测试依赖本机常驻 PostgreSQL（localhost:5432）。环境不可用时按外部环境事实
        // 跳过（assumeTrue），等强度跨租户行为由 I4TenantIsolationPostgresTest（zonky
        // 内嵌 PG + 全链迁移 + TenantLine 拦截器四象限）持续承载，不降低隔离验证强度。
        if (!postgresReachable()) {
            assumeTrue(false, "本机 PostgreSQL(localhost:5432) 不可用：H7 跳过，等强度由 I4TenantIsolationPostgresTest 承载");
        }
        // 逻辑删除行仍占用 (tenant_id, code) 唯一索引：物理清理历史运行对象
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("delete from sw_iot_connection where code like 'h7-%'");
        } catch (Exception e) {
            throw new IllegalStateException("H7 物理清理失败", e);
        }
    }

    private static boolean postgresReachable() {
        try (var socket = new java.net.Socket("localhost", 5432)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void fourQuadrantIsolation() {
        try {
            // t0→t0 正向写 + 读
            LoginUserHolder.set(context(T0));
            IotConnection c0 = newConn(T0, CODE_T0);
            int ownWriteT0 = connectionMapper.insert(c0);
            assertEquals(1, ownWriteT0);
            System.out.println("{\"kind\":\"write\",\"requestTenant\":0,\"objectTenant\":0,\"objectId\":" + c0.getId() + ",\"code\":\"" + CODE_T0 + "\",\"affectedRows\":" + ownWriteT0 + "}");
            IotConnection readT0 = connectionMapper.selectById(c0.getId());
            assertNotNull(readT0, "t0 应能读到自己的连接");
            assertEquals(T0, readT0.getTenantId());

            // t88→t88 正向写 + 读
            LoginUserHolder.set(context(T88));
            IotConnection c88 = newConn(T88, CODE_T88);
            int ownWriteT88 = connectionMapper.insert(c88);
            assertEquals(1, ownWriteT88);
            System.out.println("{\"kind\":\"write\",\"requestTenant\":88,\"objectTenant\":88,\"objectId\":" + c88.getId() + ",\"code\":\"" + CODE_T88 + "\",\"affectedRows\":" + ownWriteT88 + "}");
            IotConnection readT88 = connectionMapper.selectById(c88.getId());
            assertNotNull(readT88, "t88 应能读到自己的连接");
            assertEquals(T88, readT88.getTenantId());

            // t0→t88 零读
            LoginUserHolder.set(context(T0));
            assertNull(connectionMapper.selectById(c88.getId()), "t0 不得读到 t88 对象");
            // t0→t88 零写
            IotConnection patchT88 = new IotConnection();
            patchT88.setId(c88.getId());
            patchT88.setName("HACKED-BY-T0");
            int crossWriteT0 = connectionMapper.updateById(patchT88);
            assertEquals(0, crossWriteT0, "t0 不得改写 t88 对象");
            System.out.println("{\"kind\":\"write\",\"requestTenant\":0,\"objectTenant\":88,\"objectId\":" + c88.getId() + ",\"code\":\"" + CODE_T88 + "\",\"affectedRows\":" + crossWriteT0 + "}");

            // t88→t0 零读
            LoginUserHolder.set(context(T88));
            assertNull(connectionMapper.selectById(c0.getId()), "t88 不得读到 t0 对象");
            // t88→t0 零写
            IotConnection patchT0 = new IotConnection();
            patchT0.setId(c0.getId());
            patchT0.setName("HACKED-BY-T88");
            int crossWriteT88 = connectionMapper.updateById(patchT0);
            assertEquals(0, crossWriteT88, "t88 不得改写 t0 对象");
            System.out.println("{\"kind\":\"write\",\"requestTenant\":88,\"objectTenant\":0,\"objectId\":" + c0.getId() + ",\"code\":\"" + CODE_T0 + "\",\"affectedRows\":" + crossWriteT88 + "}");

            // 各自列表只见自身
            LoginUserHolder.set(context(T0));
            List<IotConnection> t0List = connectionMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IotConnection>()
                            .eq(IotConnection::getCode, CODE_T0));
            assertEquals(1, t0List.size(), "t0 列表应见自身 h7 对象");
            System.out.println("{\"kind\":\"list\",\"requestTenant\":0,\"codes\":[\"" + t0List.get(0).getCode() + "\"],\"count\":" + t0List.size() + "}");
            LoginUserHolder.set(context(T88));
            List<IotConnection> t88List = connectionMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IotConnection>()
                            .eq(IotConnection::getCode, CODE_T88));
            assertEquals(1, t88List.size(), "t88 列表应见自身 h7 对象");
            System.out.println("{\"kind\":\"list\",\"requestTenant\":88,\"codes\":[\"" + t88List.get(0).getCode() + "\"],\"count\":" + t88List.size() + "}");

            // 恢复被改前名称断言（写隔离留痕）
            assertEquals("H7 " + CODE_T88, readT88.getName());
            assertEquals("H7 " + CODE_T0, readT0.getName());

            // 用不经过 MyBatis 的原始 SQL 回读四象限，形成可复核的读/写证据。
            assertRawQuadrant("t0->t0", T0, c0.getId(), "H7 " + CODE_T0, true);
            assertRawQuadrant("t0->t88", T0, c88.getId(), "H7 " + CODE_T88, false);
            assertRawQuadrant("t88->t88", T88, c88.getId(), "H7 " + CODE_T88, true);
            assertRawQuadrant("t88->t0", T88, c0.getId(), "H7 " + CODE_T0, false);
        } finally {
            // 清理本轮对象（各租户上下文删各自对象）
            LoginUserHolder.set(context(T0));
            connectionMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IotConnection>()
                    .eq(IotConnection::getCode, CODE_T0));
            LoginUserHolder.set(context(T88));
            connectionMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IotConnection>()
                    .eq(IotConnection::getCode, CODE_T88));
            LoginUserHolder.clear();
        }
    }

    private void assertRawQuadrant(String quadrant, Long requestTenant, Long objectId,
                                   String expectedName, boolean shouldRead) {
        String readSql = "SELECT count(*) FROM sw_iot_connection "
                + "WHERE tenant_id = ? AND id = ? AND deleted = 0";
        String writeSql = "SELECT name FROM sw_iot_connection "
                + "WHERE tenant_id = ? AND id = ? AND deleted = 0";
        try (var conn = dataSource.getConnection();
             var read = conn.prepareStatement(readSql);
             var write = conn.prepareStatement(writeSql)) {
            read.setLong(1, requestTenant);
            read.setLong(2, objectId);
            long visibleRows;
            try (var rs = read.executeQuery()) {
                rs.next();
                visibleRows = rs.getLong(1);
            }
            assertEquals(shouldRead ? 1L : 0L, visibleRows, quadrant + " 原始 SQL 读隔离");

            write.setLong(1, shouldRead ? requestTenant : (requestTenant == T0 ? T88 : T0));
            write.setLong(2, objectId);
            String actualName;
            try (var rs = write.executeQuery()) {
                assertTrue(rs.next(), quadrant + " 原始 SQL 写后对象应存在");
                actualName = rs.getString(1);
            }
            assertEquals(expectedName, actualName, quadrant + " 原始 SQL 写隔离");
            System.out.println("[H7-SQL] quadrant=" + quadrant
                    + " readSql=SELECT_count_by_tenant_id_and_id"
                    + " requestTenant=" + requestTenant
                    + " objectId=" + objectId
                    + " readExpected=" + (shouldRead ? 1 : 0)
                    + " readActual=" + visibleRows
                    + " writeSql=SELECT_name_by_object_tenant"
                    + " writeExpectedName=" + expectedName
                    + " writeActualName=" + actualName
                    + " exit=0");
        } catch (Exception e) {
            fail(quadrant + " 原始 SQL 验证失败: " + e.getMessage());
        }
    }
}
