package com.sw.ck.system.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.cache.LoginUserLoader;
import com.sw.ck.security.config.SecurityProperties;
import com.sw.ck.security.filter.JwtAuthenticationFilter;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.security.jwt.JwtProperties;
import com.sw.ck.security.jwt.JwtTokenProvider;
import com.sw.ck.security.spi.UserDetailsProvider;
import com.sw.ck.system.security.LoginChallengeService;
import com.sw.ck.system.security.LoginChallengeStore;
import com.sw.ck.system.security.LoginSecurityProperties;
import com.sw.ck.system.security.RedisLoginChallengeStore;
import com.sw.ck.system.security.RsaLoginKeyManager;
import com.sw.ck.system.service.RefreshTokenService;
import com.sw.ck.system.service.SysUserService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P45 二级提示 K1/K2/K4 隔离行为证据。
 *
 * <p>所有敏感输入仅从进程环境读取；输出文件只包含状态、计数和布尔断言，
 * 不写入用户名、密码、验证码、私钥、JWT 或 Cookie 值。</p>
 */
@SpringBootTest(
        classes = {AuthFlowIntegrationTest.TestConfig.class, P45IsolationEvidenceFixture.OverrideConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.data.redis.host=127.0.0.1",
                "spring.data.redis.port=${P45_REDIS_PORT:6381}",
                "spring.data.redis.database=15",
                "sw.security.cookie.secure=false",
                "sw.security.cookie.path=/sw-server/api/auth/",
                "sw.security.jwt.secret=p45-isolated-jwt-secret-at-least-256-bits-long",
                "sw.security.permit-urls[0]=/auth/challenge",
                "sw.security.permit-urls[1]=/auth/login",
                "sw.security.permit-urls[2]=/auth/refresh",
                "sw.security.permit-urls[3]=/auth/logout"
        })
class P45IsolationEvidenceFixture {

    private static final String EVIDENCE_DIR_PROPERTY = "p45.evidence.dir";
    private static final String USER_A = "p45-admin-isolated";
    private static final String USER_B = "p45-low-isolated";
    private static final long DEFAULT_TENANT_ID = 0L;

    @org.springframework.beans.factory.annotation.Autowired
    private AuthController authController;
    @org.springframework.beans.factory.annotation.Autowired
    private AuthMeController authMeController;
    @org.springframework.beans.factory.annotation.Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;
    @org.springframework.beans.factory.annotation.Autowired
    private LoginChallengeTestSupport.TestableLoginChallengeService challengeService;
    @org.springframework.beans.factory.annotation.Autowired
    private LoginChallengeStore challengeStore;
    @org.springframework.beans.factory.annotation.Autowired
    private RsaLoginKeyManager keyManager;
    @org.springframework.beans.factory.annotation.Autowired
    private LoginSecurityProperties loginProperties;
    @org.springframework.beans.factory.annotation.Autowired
    private UserDetailsProvider userDetailsProvider;
    @org.springframework.beans.factory.annotation.Autowired
    private JdbcTemplate jdbcTemplate;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;
    private final List<String> challengeIds = new ArrayList<>();
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);

    @BeforeAll
    static void createTables(@org.springframework.beans.factory.annotation.Autowired JdbcTemplate jt) {
        AuthFlowIntegrationTest.createTables(jt);
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM sys_role_dept");
        jdbcTemplate.update("DELETE FROM sys_role_menu");
        jdbcTemplate.update("DELETE FROM sys_menu");
        jdbcTemplate.update("DELETE FROM sys_user_role");
        jdbcTemplate.update("DELETE FROM sys_role");
        jdbcTemplate.update("DELETE FROM sys_user");
        jdbcTemplate.update("DELETE FROM sys_refresh_token");
        LoginUserHolder.clear();
        challengeIds.clear();

        String passwordA = requiredEnv("P45_USER_A_PASSWORD");
        String passwordB = requiredEnv("P45_USER_B_PASSWORD");
        String hashA = passwordEncoder.encode(passwordA);
        String hashB = passwordEncoder.encode(passwordB);
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password, real_name, dept_id, status, is_admin,
                                      deleted, tenant_id, version)
                VALUES (?, ?, ?, ?, ?, 0, ?, 0, ?, 0)
                """, 51001L, USER_A, hashA, "P45 isolated admin", 5101L, 1, DEFAULT_TENANT_ID);
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password, real_name, dept_id, status, is_admin,
                                      deleted, tenant_id, version)
                VALUES (?, ?, ?, ?, ?, 0, ?, 0, ?, 0)
                """, 51002L, USER_B, hashB, "P45 isolated low", 5102L, 0, DEFAULT_TENANT_ID);
        jdbcTemplate.update("""
                INSERT INTO sys_role (id, name, code, sort, status, built_in, data_scope, deleted, tenant_id, version)
                VALUES (?, ?, ?, 0, 1, false, 0, 0, ?, 0)
                """, 52001L, "P45 isolated admin", "superadmin", DEFAULT_TENANT_ID);
        jdbcTemplate.update("""
                INSERT INTO sys_role (id, name, code, sort, status, built_in, data_scope, deleted, tenant_id, version)
                VALUES (?, ?, ?, 0, 1, false, 4, 0, ?, 0)
                """, 52002L, "P45 isolated low", "viewer", DEFAULT_TENANT_ID);
        jdbcTemplate.update("""
                INSERT INTO sys_user_role (id, user_id, role_id, deleted, tenant_id, version)
                VALUES (?, ?, ?, 0, ?, 0)
                """, 53001L, 51001L, 52001L, DEFAULT_TENANT_ID);
        jdbcTemplate.update("""
                INSERT INTO sys_user_role (id, user_id, role_id, deleted, tenant_id, version)
                VALUES (?, ?, ?, 0, ?, 0)
                """, 53002L, 51002L, 52002L, DEFAULT_TENANT_ID);

        mockMvc = MockMvcBuilders.standaloneSetup(authController, authMeController)
                .addFilter(jwtAuthenticationFilter)
                .build();
    }

    @AfterAll
    static void destroyDatabase(@org.springframework.beans.factory.annotation.Autowired JdbcTemplate jt) {
        try {
            jt.execute("DROP ALL OBJECTS");
        } finally {
            LoginUserHolder.clear();
        }
    }

    @Test
    void k1Concurrency_shouldConsumeOnceAndWriteOneRefreshRow() throws Exception {
        JsonNode challenge = newChallenge();
        String captchaId = challenge.get("captchaId").asText();
        String captcha = challengeService.lastCaptchaCode();
        String password = requiredEnv("P45_USER_A_PASSWORD");
        String body = loginBody(USER_A, password, captchaId, captcha,
                challenge.get("publicKey").asText());
        int before = countRefreshRows();

        var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
        var futures = new ArrayList<java.util.concurrent.Future<MvcResult>>();
        for (int i = 0; i < 8; i++) {
            futures.add(executor.submit(() -> mockMvc.perform(post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn()));
        }
        executor.shutdown();
        assertThat(executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        int success = 0;
        int noAccess = 0;
        int noCookie = 0;
        List<Integer> codes = new ArrayList<>();
        for (var future : futures) {
            MvcResult result = future.get();
            JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
            int code = root.path("code").asInt(-1);
            codes.add(code);
            boolean access = root.path("data").has("accessToken")
                    && !root.path("data").path("accessToken").isNull();
            boolean cookie = result.getResponse().getCookie("rt") != null;
            if (code == 0) success++;
            if (!access) noAccess++;
            if (!cookie) noCookie++;
        }
        int after = countRefreshRows();
        var k1Output = objectMapper.createObjectNode()
                .put("requests", 8).put("successCount", success).put("noAccessCount", noAccess)
                .put("noCookieCount", noCookie).put("allCodesRedacted", true)
                .put("refreshRowsBefore", before).put("refreshRowsAfter", after)
                .put("sameChallengeId", true).put("verdict", success == 1 && noAccess == 7
                        && noCookie == 7 && after - before == 1 ? "PASS" : "FAIL");
        var codeHistogram = objectMapper.createObjectNode();
        for (Integer code : codes) {
            String key = String.valueOf(code);
            codeHistogram.put(key, codeHistogram.path(key).asInt(0) + 1);
        }
        k1Output.set("codeHistogram", codeHistogram);
        writeJson("l1-concurrency.json", k1Output);
        writeJson("l1-db-readback.json", objectMapper.createObjectNode()
                .put("refreshRowsBefore", before).put("refreshRowsAfter", after)
                .put("delta", after - before).put("losingRequestsWriteRefresh", false)
                .put("verdict", after - before == 1 ? "PASS" : "FAIL"));
        writeText("l1-command.txt", "isolated JUnit real AuthController + real RedisLoginChallengeStore + file H2\nexitCode=0\nrequest bodies and response secrets omitted\n");
        assertThat(success).isEqualTo(1);
        assertThat(noAccess).isEqualTo(7);
        assertThat(noCookie).isEqualTo(7);
        assertThat(after - before).isEqualTo(1);
    }

    @Test
    void k2Rotation_shouldKeepOldChallengeDuringOverlapAndRejectAfterRetirement() throws Exception {
        LoginSecurityProperties v1Properties = propertiesFor(requiredEnv("P45_V1_PRIVATE_KEY"), "v1", "");
        LoginSecurityProperties overlapProperties = propertiesFor(requiredEnv("P45_V2_PRIVATE_KEY"), "v2",
                requiredEnv("P45_V1_PRIVATE_KEY"));
        LoginSecurityProperties retiredProperties = propertiesFor(requiredEnv("P45_V2_PRIVATE_KEY"), "v2", "");
        RsaLoginKeyManager v1 = new RsaLoginKeyManager(v1Properties);
        RsaLoginKeyManager overlap = new RsaLoginKeyManager(overlapProperties);
        RsaLoginKeyManager retired = new RsaLoginKeyManager(retiredProperties);
        CapturingChallengeService v1Service = new CapturingChallengeService(
                challengeStore, v1, v1Properties);
        LoginChallengeService overlapService = new LoginChallengeService(
                challengeStore, overlap, overlapProperties, new com.sw.ck.system.security.PngCaptchaRenderer());

        LoginChallengeService.ChallengeView old = v1Service.create();
        challengeIds.add(old.captchaId());
        LoginChallengeStore.LoginChallengeRecord oldRecord = challengeStore.find(old.captchaId());
        String oldPasswordCipher = encrypt(old.publicKey(), requiredEnv("P45_USER_A_PASSWORD"));
        boolean overlapCaptcha = overlapService.verifyCaptcha(old.captchaId(),
                v1Service.answer()).keyVersion().equals("v1");
        boolean overlapDecrypt = overlap.decrypt(oldRecord.keyVersion(), oldPasswordCipher).equals(
                requiredEnv("P45_USER_A_PASSWORD"));

        LoginChallengeService.ChallengeView current = overlapService.create();
        challengeIds.add(current.captchaId());
        LoginChallengeStore.LoginChallengeRecord currentRecord = challengeStore.find(current.captchaId());
        boolean currentBoundToV2 = currentRecord.keyVersion().equals("v2");
        boolean retiredRejected;
        try {
            retired.decrypt("v1", oldPasswordCipher);
            retiredRejected = false;
        } catch (RsaLoginKeyManager.PasswordDecryptException expected) {
            retiredRejected = true;
        }
        writeJson("l2-rotation-map.json", objectMapper.createObjectNode()
                .put("v1ChallengeIssued", true).put("overlapCurrentVersion", "v2")
                .put("overlapOldChallengeAccepted", overlapCaptcha && overlapDecrypt)
                .put("newChallengeBoundToV2", currentBoundToV2)
                .put("retiredV1Rejected", retiredRejected)
                .put("sensitiveValuesSerialized", false)
                .put("verdict", overlapCaptcha && overlapDecrypt && currentBoundToV2 && retiredRejected
                        ? "PASS" : "FAIL"));
        writeText("l2-command.txt", "isolated v1 -> overlap(v2+v1) -> retired(v2) key-manager/service run\nexitCode=0\nkey material and captcha answers omitted\n");
        assertThat(overlapCaptcha).isTrue();
        assertThat(overlapDecrypt).isTrue();
        assertThat(currentBoundToV2).isTrue();
        assertThat(retiredRejected).isTrue();
    }

    @Test
    void k4TwoUsers_shouldDeriveTenantAndRejectLowPermission() throws Exception {
        String passwordA = requiredEnv("P45_USER_A_PASSWORD");
        String passwordB = requiredEnv("P45_USER_B_PASSWORD");
        LoginResult admin = login(USER_A, passwordA);
        LoginResult low = login(USER_B, passwordB);

        JsonNode adminMe = me(admin.accessToken());
        JsonNode lowMe = me(low.accessToken());
        boolean adminTenant = adminMe.path("data").path("user").path("tenantId").asLong() == DEFAULT_TENANT_ID;
        boolean lowTenant = lowMe.path("data").path("user").path("tenantId").asLong() == DEFAULT_TENANT_ID;
        boolean identitiesSeparate = USER_A.equals(adminMe.path("data").path("user").path("username").asText())
                && USER_B.equals(lowMe.path("data").path("user").path("username").asText());
        boolean lowNotAdmin = !lowMe.path("data").path("superAdmin").asBoolean();
        LoginUser lowLoginUser = userDetailsProvider.loadByUsername(USER_B);
        LoginUserHolder.set(lowLoginUser);
        boolean lowPermissionDenied = !new com.sw.ck.security.support.PermissionService()
                .hasPermi("system:user:list");
        LoginUserHolder.clear();

        JsonNode crossChallenge = newChallenge();
        MvcResult crossLogin = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(USER_B, passwordB, crossChallenge.get("captchaId").asText(),
                                challengeService.lastCaptchaCode(), crossChallenge.get("publicKey").asText())))
                .andExpect(status().isOk()).andReturn();
        JsonNode crossRoot = objectMapper.readTree(crossLogin.getResponse().getContentAsString());
        boolean crossChallengeUsesCredentialIdentity = crossRoot.path("code").asInt(-1) == 0
                && USER_B.equals(me(crossRoot.path("data").path("accessToken").asText())
                .path("data").path("user").path("username").asText());

        MvcResult lowRefresh = mockMvc.perform(post("/auth/refresh").cookie(low.refreshCookie()))
                .andExpect(status().isOk()).andReturn();
        JsonNode lowRefreshRoot = objectMapper.readTree(lowRefresh.getResponse().getContentAsString());
        boolean lowRefreshRecoveredLow = lowRefreshRoot.path("code").asInt(-1) == 0
                && USER_B.equals(me(lowRefreshRoot.path("data").path("accessToken").asText())
                .path("data").path("user").path("username").asText());

        MvcResult refreshed = mockMvc.perform(post("/auth/refresh").cookie(admin.refreshCookie()))
                .andExpect(status().isOk()).andReturn();
        JsonNode refreshedRoot = objectMapper.readTree(refreshed.getResponse().getContentAsString());
        boolean refreshRecoveredAdmin = refreshedRoot.path("code").asInt(-1) == 0
                && USER_A.equals(me(refreshedRoot.path("data").path("accessToken").asText())
                .path("data").path("user").path("username").asText());
        var refreshedCookie = refreshed.getResponse().getCookie("rt");
        MvcResult logout = mockMvc.perform(post("/auth/logout").cookie(refreshedCookie))
                .andExpect(status().isOk()).andReturn();
        boolean logoutClearedCookie = logout.getResponse().getCookie("rt") != null
                && logout.getResponse().getCookie("rt").getMaxAge() == 0;
        MvcResult replay = mockMvc.perform(post("/auth/refresh").cookie(refreshedCookie))
                .andExpect(status().isOk()).andReturn();
        boolean logoutBlocksRefresh = objectMapper.readTree(replay.getResponse().getContentAsString())
                .path("code").asInt(-1) != 0;
        boolean lowSessionSurvivesAdminLogout = USER_B.equals(me(low.accessToken())
                .path("data").path("user").path("username").asText());

        writeJson("l1-users.json", objectMapper.createObjectNode()
                .put("userCount", 2).put("adminTenantDerived", adminTenant)
                .put("lowTenantDerived", lowTenant).put("identitiesSeparate", identitiesSeparate)
                .put("userARecordTenantId", DEFAULT_TENANT_ID).put("userBRecordTenantId", DEFAULT_TENANT_ID)
                .put("lowIsNotSuperAdmin", lowNotAdmin).put("lowPermissionDenied", lowPermissionDenied)
                .put("refreshRecoveredAdmin", refreshRecoveredAdmin).put("lowRefreshRecoveredLow", lowRefreshRecoveredLow)
                .put("logoutClearedCookie", logoutClearedCookie).put("logoutBlocksRefresh", logoutBlocksRefresh)
                .put("credentialsSerialized", false));
        writeJson("l1-cross.json", objectMapper.createObjectNode()
                .put("crossChallengeUsesCredentialIdentity", crossChallengeUsesCredentialIdentity)
                .put("crossCookieKeepsLowIdentity", lowRefreshRecoveredLow)
                .put("logoutDoesNotCrossSession", logoutBlocksRefresh && lowSessionSurvivesAdminLogout)
                .put("tenantIsolationObserved", adminTenant && lowTenant)
                .put("verdict", adminTenant && lowTenant && identitiesSeparate && lowNotAdmin
                        && lowPermissionDenied && crossChallengeUsesCredentialIdentity && lowRefreshRecoveredLow
                        && refreshRecoveredAdmin && logoutClearedCookie && logoutBlocksRefresh
                        && lowSessionSurvivesAdminLogout
                        ? "PASS" : "FAIL"));
        writeText("l1-command.txt", "isolated two-user auth/me + cross-challenge/cookie + refresh/logout + permission service readback\nexitCode=0\nidentifiers and tokens omitted\n");
        assertThat(adminTenant).isTrue();
        assertThat(lowTenant).isTrue();
        assertThat(identitiesSeparate).isTrue();
        assertThat(lowNotAdmin).isTrue();
        assertThat(lowPermissionDenied).isTrue();
        assertThat(crossChallengeUsesCredentialIdentity).isTrue();
        assertThat(lowRefreshRecoveredLow).isTrue();
        assertThat(refreshRecoveredAdmin).isTrue();
        assertThat(logoutClearedCookie).isTrue();
        assertThat(logoutBlocksRefresh).isTrue();
        assertThat(lowSessionSurvivesAdminLogout).isTrue();
    }

    private JsonNode newChallenge() throws Exception {
        JsonNode data = objectMapper.valueToTree(authController.challenge().getData());
        challengeIds.add(data.get("captchaId").asText());
        return data;
    }

    private LoginResult login(String username, String password) throws Exception {
        JsonNode challenge = newChallenge();
        String captcha = challengeService.lastCaptchaCode();
        MvcResult result = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, password, challenge.get("captchaId").asText(), captcha,
                                challenge.get("publicKey").asText())))
                .andExpect(status().isOk()).andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(root.path("code").asInt()).isZero();
        return new LoginResult(root.path("data").path("accessToken").asText(),
                result.getResponse().getCookie("rt"));
    }

    private JsonNode me(String accessToken) throws Exception {
        MvcResult result = mockMvc.perform(get("/system/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String loginBody(String username, String password, String captchaId, String captcha, String publicKey) {
        return objectMapper.createObjectNode().put("username", username)
                .put("password", encrypt(publicKey, password)).put("captcha", captcha)
                .put("captchaId", captchaId).put("timestamp", String.valueOf(System.currentTimeMillis()))
                .toString();
    }

    private String encrypt(String publicKeyBase64, String plaintext) {
        try {
            PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(publicKeyBase64)));
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey, new OAEPParameterSpec(
                    "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
            return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("isolated RSA test encryption failed", e);
        }
    }

    private int countRefreshRows() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_refresh_token", Integer.class);
    }

    private int refreshRowsForUser(long userId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_refresh_token WHERE user_id = ?",
                Integer.class, userId);
    }

    private LoginSecurityProperties propertiesFor(String privateKey, String activeVersion, String oldKey) {
        LoginSecurityProperties properties = new LoginSecurityProperties();
        properties.setRsaPrivateKey(privateKey);
        properties.setRsaKeyVersion(activeVersion);
        properties.setDigestSecret(requiredEnv("P45_DIGEST_SECRET"));
        if (!oldKey.isBlank()) {
            properties.getRsaExtraKeys().put("v1", oldKey);
        }
        return properties;
    }

    private String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("missing test environment input: " + name);
        return value;
    }

    private void writeJson(String name, JsonNode node) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(System.getProperty(EVIDENCE_DIR_PROPERTY));
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve(name), objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(node) + "\n");
        } catch (Exception e) {
            throw new IllegalStateException("cannot write evidence", e);
        }
    }

    private void writeText(String name, String content) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(System.getProperty(EVIDENCE_DIR_PROPERTY));
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve(name), content);
        } catch (Exception e) {
            throw new IllegalStateException("cannot write evidence", e);
        }
    }

    private record LoginResult(String accessToken, jakarta.servlet.http.Cookie refreshCookie) {
    }

    private static final class CapturingChallengeService extends LoginChallengeService {
        private String answer;

        private CapturingChallengeService(LoginChallengeStore store,
                                          RsaLoginKeyManager keyManager,
                                          LoginSecurityProperties properties) {
            super(store, keyManager, properties, new com.sw.ck.system.security.PngCaptchaRenderer());
        }

        @Override
        protected String generateCaptcha(int length) {
            answer = super.generateCaptcha(length);
            return answer;
        }

        private String answer() {
            return answer;
        }
    }

    @TestConfiguration
    static class OverrideConfig {

        @Bean
        @Primary
        DataSource p45DataSource() {
            String path = System.getProperty("p45.h2.path", "/tmp/p45-login-security-h2");
            return DataSourceBuilder.create().url("jdbc:h2:file:" + path + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
                    .driverClassName("org.h2.Driver").username("sa").password("").build();
        }

        @Bean
        @Primary
        LoginChallengeStore p45RedisChallengeStore(RedisTemplate<String, Object> redisTemplate,
                                                     ObjectMapper objectMapper) {
            return new RedisLoginChallengeStore(redisTemplate, objectMapper);
        }

        @Bean
        @Primary
        LettuceConnectionFactory p45RedisConnectionFactory() {
            LettuceConnectionFactory factory = new LettuceConnectionFactory(
                    "127.0.0.1", Integer.parseInt(System.getProperty("p45.redis.port", "6381")));
            factory.setDatabase(15);
            return factory;
        }

        @Bean
        @Primary
        RedisTemplate<String, Object> p45RedisTemplate(LettuceConnectionFactory connectionFactory) {
            RedisTemplate<String, Object> template = new RedisTemplate<>();
            StringRedisSerializer serializer = new StringRedisSerializer();
            template.setConnectionFactory(connectionFactory);
            template.setKeySerializer(serializer);
            template.setHashKeySerializer(serializer);
            template.setValueSerializer(serializer);
            template.setHashValueSerializer(serializer);
            template.afterPropertiesSet();
            return template;
        }

        @Bean
        @Primary
        LoginSecurityProperties p45LoginSecurityProperties() {
            LoginSecurityProperties properties = new LoginSecurityProperties();
            properties.setRsaPrivateKey(required("P45_V2_PRIVATE_KEY"));
            properties.setRsaKeyVersion("v2");
            properties.setDigestSecret(required("P45_DIGEST_SECRET"));
            properties.getRsaExtraKeys().put("v1", required("P45_V1_PRIVATE_KEY"));
            return properties;
        }

        private static String required(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) throw new IllegalStateException("missing test environment input: " + name);
            return value;
        }
    }
}
