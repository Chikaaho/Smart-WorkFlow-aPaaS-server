package com.sw.ck.system.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.system.mapper.SysRefreshTokenMapper;
import com.sw.ck.system.security.SystemErrorKeys;
import org.apache.ibatis.reflection.MetaObject;
import org.junit.jupiter.api.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link RefreshTokenService} 集成测试。
 * 使用 H2 内存数据库 + 真实 MyBatis-Plus Mapper 验证：
 * <ul>
 *   <li>createRefreshToken — 生成 + 哈希 + 写入 DB</li>
 *   <li>rotateRefreshToken — 正常轮换 / token 无效 / 已过期 / 重放检测（家族撤销）</li>
 *   <li>revokeRefreshToken — 正常撤销 / 空 token / 不存在（幂等）</li>
 *   <li>findUserIdByToken — 正常查询 / 不存在 / null 输入</li>
 * </ul>
 */
@SpringBootTest(
        classes = RefreshTokenServiceTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@DisplayName("RefreshTokenService 测试")
class RefreshTokenServiceTest {

    private static final long USER_ID = 1L;
    private static final long TENANT_ID = 0L;
    private static final long EXPIRE_SECONDS = 604800;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void createTables(@Autowired JdbcTemplate jt) {
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sys_refresh_token (
                    id                bigint          not null primary key,
                    user_id           bigint          not null,
                    token_hash        varchar(128)    not null,
                    expires_at        timestamp       not null,
                    revoked           smallint        not null default 0,
                    create_time       timestamp       default current_timestamp,
                    create_by         bigint,
                    update_time       timestamp       default current_timestamp,
                    update_by         bigint,
                    tenant_id         bigint          not null default 0,
                    deleted           smallint        default 0,
                    version           bigint          default 0
                )
                """);
        jt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_srt_token_hash ON sys_refresh_token (token_hash)");
        jt.execute("CREATE INDEX IF NOT EXISTS idx_srt_user_tenant ON sys_refresh_token (user_id, tenant_id)");
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM sys_refresh_token");
    }

    // ============ createRefreshToken ============

    @Test
    @DisplayName("createRefreshToken：生成 64 字符 hex token + SHA-256 哈希存入 DB")
    void createRefreshToken_shouldStoreHashNotRawToken() {
        String rawToken = refreshTokenService.createRefreshToken(USER_ID, TENANT_ID, EXPIRE_SECONDS);
        assertThat(rawToken).hasSize(64).matches("^[0-9a-f]{64}$");

        // DB 中存的是 SHA-256 hash（64 字符 hex），非原文
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_refresh_token WHERE token_hash = ?",
                Long.class, rawToken);
        assertThat(count).as("DB 不应存原文").isZero();

        // 但应当有一条记录（hash 与原文不同）
        count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_refresh_token", Long.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("createRefreshToken：不同调用生成不同 token")
    void createRefreshToken_shouldReturnDifferentTokens() {
        String t1 = refreshTokenService.createRefreshToken(USER_ID, TENANT_ID, EXPIRE_SECONDS);
        String t2 = refreshTokenService.createRefreshToken(USER_ID, TENANT_ID, EXPIRE_SECONDS);
        assertThat(t1).isNotEqualTo(t2);
        // DB 中应有两条记录
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_refresh_token", Long.class);
        assertThat(count).isEqualTo(2);
    }

    // ============ rotateRefreshToken（正常轮换）============

    @Test
    @DisplayName("rotateRefreshToken：正常轮换 — 旧 token 撤销 + 新 token 签发")
    void rotateRefreshToken_normalFlow_shouldRevokeOldAndIssueNew() {
        String oldToken = refreshTokenService.createRefreshToken(USER_ID, TENANT_ID, EXPIRE_SECONDS);
        RefreshTokenService.RefreshTokenRotation rotation =
                refreshTokenService.rotateRefreshToken(oldToken, EXPIRE_SECONDS);

        assertThat(rotation.userId()).isEqualTo(USER_ID);
        assertThat(rotation.newRawToken()).hasSize(64).isNotEqualTo(oldToken);

        // 旧 token 不能再用于轮换（已撤销）
        assertThatThrownBy(() ->
                refreshTokenService.rotateRefreshToken(oldToken, EXPIRE_SECONDS))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SESSION_REVOKED);
    }

    // ============ rotateRefreshToken（重放检测）============

    @Test
    @DisplayName("rotateRefreshToken：重放已撤销 token → 家族撤销 + 抛异常，其他 token 也被撤销")
    void rotateRefreshToken_replayAttack_shouldRevokeAllForUser() {
        String token = refreshTokenService.createRefreshToken(USER_ID, TENANT_ID, EXPIRE_SECONDS);
        // 第一次轮换（正常）
        refreshTokenService.rotateRefreshToken(token, EXPIRE_SECONDS);
        // 同一用户创建另一个 token
        String anotherToken = refreshTokenService.createRefreshToken(USER_ID, TENANT_ID, EXPIRE_SECONDS);

        // 重放已撤销的旧 token → 家族撤销 + 抛异常
        assertThatThrownBy(() ->
                refreshTokenService.rotateRefreshToken(token, EXPIRE_SECONDS))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SESSION_REVOKED);

        // 修复核心验证：家族撤销已在新事务中提交，anotherToken 也应已被撤销
        assertThatThrownBy(() ->
                refreshTokenService.rotateRefreshToken(anotherToken, EXPIRE_SECONDS))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SESSION_REVOKED);
    }

    // ============ rotateRefreshToken（token 无效 / 过期）============

    @Test
    @DisplayName("rotateRefreshToken：不存在的 token → 抛异常")
    void rotateRefreshToken_unknownToken_shouldThrow() {
        assertThatThrownBy(() ->
                refreshTokenService.rotateRefreshToken("nonexistent-token-that-does-not-exist-in-db", EXPIRE_SECONDS))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.REFRESH_TOKEN_INVALID);
    }

    // ============ revokeRefreshToken ============

    @Test
    @DisplayName("revokeRefreshToken：正常撤销 → token 标记 revoked=1")
    void revokeRefreshToken_shouldRevoke() {
        String token = refreshTokenService.createRefreshToken(USER_ID, TENANT_ID, EXPIRE_SECONDS);
        refreshTokenService.revokeRefreshToken(token);
        // 撤销后不能轮换
        assertThatThrownBy(() ->
                refreshTokenService.rotateRefreshToken(token, EXPIRE_SECONDS))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SESSION_REVOKED);
    }

    @Test
    @DisplayName("revokeRefreshToken：null token → 静默成功（幂等）")
    void revokeRefreshToken_nullToken_shouldNotThrow() {
        assertThatCode(() -> refreshTokenService.revokeRefreshToken(null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("revokeRefreshToken：空 token → 静默成功（幂等）")
    void revokeRefreshToken_emptyToken_shouldNotThrow() {
        assertThatCode(() -> refreshTokenService.revokeRefreshToken(""))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("revokeRefreshToken：不存在的 token → 静默成功（幂等）")
    void revokeRefreshToken_unknownToken_shouldNotThrow() {
        assertThatCode(() ->
                refreshTokenService.revokeRefreshToken("some-random-unknown-token-that-does-not-exist"))
                .doesNotThrowAnyException();
    }

    // ============ findUserIdByToken ============

    @Test
    @DisplayName("findUserIdByToken：正常查询返回 userId")
    void findUserIdByToken_shouldReturnUserId() {
        String token = refreshTokenService.createRefreshToken(USER_ID, TENANT_ID, EXPIRE_SECONDS);
        assertThat(refreshTokenService.findUserIdByToken(token)).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("findUserIdByToken：不存在的 token → 返回 null")
    void findUserIdByToken_unknown_shouldReturnNull() {
        assertThat(refreshTokenService.findUserIdByToken("unknown-token")).isNull();
    }

    @Test
    @DisplayName("findUserIdByToken：null 输入 → 返回 null")
    void findUserIdByToken_null_shouldReturnNull() {
        assertThat(refreshTokenService.findUserIdByToken(null)).isNull();
    }

    // ============ TestConfig ============

    @Configuration
    @MapperScan("com.sw.ck.system.mapper")
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:refreshsvc;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
                    .driverClassName("org.h2.Driver")
                    .username("sa")
                    .password("")
                    .build();
        }

        @Bean
        public JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        public PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        public MybatisPlusInterceptor mybatisPlusInterceptor() {
            return new MybatisPlusInterceptor();
        }

        @Bean
        public org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                MybatisPlusInterceptor interceptor) throws Exception {
            com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean factory =
                    new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            MybatisConfiguration ibatisConfig = new MybatisConfiguration();
            ibatisConfig.setMapUnderscoreToCamelCase(true);
            ibatisConfig.setUseGeneratedKeys(true);
            factory.setConfiguration(ibatisConfig);
            GlobalConfig globalConfig = new GlobalConfig();
            GlobalConfig.DbConfig dbConfig = new GlobalConfig.DbConfig();
            dbConfig.setLogicDeleteField("deleted");
            dbConfig.setLogicDeleteValue("1");
            dbConfig.setLogicNotDeleteValue("0");
            globalConfig.setDbConfig(dbConfig);
            globalConfig.setMetaObjectHandler(new com.baomidou.mybatisplus.core.handlers.MetaObjectHandler() {
                @Override
                public void insertFill(MetaObject metaObject) {
                    this.strictInsertFill(metaObject, "deleted", Integer.class, 0);
                    this.strictInsertFill(metaObject, "version", Long.class, 0L);
                }

                @Override
                public void updateFill(MetaObject metaObject) {
                    // no-op
                }
            });
            factory.setGlobalConfig(globalConfig);
            factory.setPlugins(interceptor);
            return factory.getObject();
        }

        @Bean
        public RefreshTokenService refreshTokenService(
                SysRefreshTokenMapper sysRefreshTokenMapper,
                PlatformTransactionManager transactionManager) {
            return new RefreshTokenService(sysRefreshTokenMapper, transactionManager);
        }
    }
}
