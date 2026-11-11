package com.sw.ck.system.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import jakarta.annotation.Resource;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sw.ck.system.entity.SysAuditLog;
import com.sw.ck.system.mapper.SysAuditLogMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计日志保留清理（B8）：真实 H2 表上验证「过期行删除、未过期行保留」，
 * 以及清理按批次有界推进（batch=1 时多轮删除后停止）。
 */
@SpringBootTest(classes = AuditLogPurgeIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("审计日志保留清理")
class AuditLogPurgeIntegrationTest {

    @Resource
    private AuditLogService auditLogService;
    @Resource
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS sys_audit_log");
        jdbcTemplate.execute("""
                CREATE TABLE sys_audit_log (
                    id              BIGINT PRIMARY KEY,
                    create_time     TIMESTAMP,
                    tenant_id       BIGINT DEFAULT 0,
                    log_type        VARCHAR(20),
                    username        VARCHAR(64),
                    user_id         BIGINT,
                    result          VARCHAR(20),
                    fail_reason     VARCHAR(255),
                    module          VARCHAR(50),
                    action          VARCHAR(50),
                    target          VARCHAR(255),
                    ip              VARCHAR(64),
                    user_agent      VARCHAR(255),
                    exception_type  VARCHAR(200),
                    summary         VARCHAR(1000),
                    trace_id        VARCHAR(64)
                )
                """);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS sys_audit_log");
    }

    @Test
    @DisplayName("90 天前的记录被分批删除，未过期记录保留")
    void purgeDeletesOnlyExpiredRowsInBatches() {
        LocalDateTime now = LocalDateTime.now();
        insert(1L, now.minusDays(120), "LOGIN");
        insert(2L, now.minusDays(100), "OPERATION");
        insert(3L, now.minusDays(10), "LOGIN");
        insert(4L, now.minusDays(1), "EXCEPTION");

        int deleted = auditLogService.purgeBefore(now.minusDays(90), 1);

        assertThat(deleted).isEqualTo(2);
        List<Map<String, Object>> remaining = jdbcTemplate.queryForList(
                "SELECT id FROM sys_audit_log ORDER BY id");
        assertThat(remaining).extracting(row -> ((Number) row.get("id")).longValue())
                .containsExactly(3L, 4L);
    }

    private void insert(long id, LocalDateTime createdAt, String logType) {
        jdbcTemplate.update("""
                INSERT INTO sys_audit_log (id, create_time, tenant_id, log_type, result)
                VALUES (?, ?, 0, ?, 'SUCCESS')
                """, id, createdAt, logType);
    }

    @Configuration
    @MapperScan("com.sw.ck.system.mapper")
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:auditpurge;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
                    .username("sa")
                    .password("")
                    .build();
        }

        @Bean
        public JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            GlobalConfig globalConfig = new GlobalConfig();
            globalConfig.setBanner(false);
            factory.setGlobalConfig(globalConfig);
            MybatisConfiguration configuration = new MybatisConfiguration();
            factory.setConfiguration(configuration);
            return factory.getObject();
        }

        @Bean
        public AuditLogService auditLogService(SysAuditLogMapper mapper) {
            return new AuditLogService(mapper);
        }
    }
}
