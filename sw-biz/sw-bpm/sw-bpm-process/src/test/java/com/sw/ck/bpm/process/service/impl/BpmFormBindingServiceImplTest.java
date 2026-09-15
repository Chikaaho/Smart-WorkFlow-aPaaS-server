package com.sw.ck.bpm.process.service.impl;

import com.sw.ck.bpm.process.entity.BpmFormBinding;
import com.sw.ck.bpm.process.mapper.BpmFormBindingMapper;
import com.sw.ck.bpm.process.service.BpmFormBindingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * sw_bpm_form_binding 唯一绑定语义测试（H2）。
 * <p>
 * H2 侧用生成列 {@code active_key} + 唯一索引 {@code uk_sw_bpm_binding_active}
 * 等价实现 PG partial unique index 的「同租户同 form_key 仅一条 active=true」约束：
 * active=true 时 active_key='tenant_id:form_key'（非空 → 唯一生效），
 * active=false 时 active_key=NULL（H2 唯一索引允许多个 NULL 共存 → 历史记录可共存）。
 * 本测试验证该约束的正反例（DB 级，JdbcTemplate 显式 tenant_id），
 * 以及 {@link BpmFormBindingService#findActiveByFormKey} 与
 * {@code BpmDeployRunner#bindFormToProcess} 查-插幂等模式（Service 级）。
 * </p>
 */
@SpringBootTest(
        classes = BpmFormBindingServiceImplTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        // 独立内存库：避免与 BpmInstanceServiceImplTest 共用 bpm_svc_test 导致建表冲突
        properties = "spring.datasource.url=jdbc:h2:mem:bpm_binding_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
)
@ActiveProfiles("test")
@Transactional
@DisplayName("BpmFormBinding 绑定语义测试")
class BpmFormBindingServiceImplTest {

    @Autowired
    private BpmFormBindingMapper mapper;

    @Autowired
    private BpmFormBindingService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 断言唯一约束冲突：Spring 译为 DuplicateKeyException，根因 SQLState=23505。 */
    private void assertUniqueViolation(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(DuplicateKeyException.class)
                .satisfies(e -> {
                    Throwable cause = ((DuplicateKeyException) e).getMostSpecificCause();
                    assertThat(cause).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) cause).getSQLState()).isEqualTo("23505");
                });
    }

    private void insertBinding(Long id, Long tenantId, String formKey, String processDefKey, boolean active) {
        jdbcTemplate.update("""
                        INSERT INTO sw_bpm_form_binding (id, tenant_id, form_key, process_def_key, active)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                id, tenantId, formKey, processDefKey, active);
    }

    // ==================== DB 级约束：正例 ====================

    @Test
    @DisplayName("正例：插入 active=true 绑定成功")
    void activeTrue_insertShouldSucceed() {
        insertBinding(1L, 1L, "form_a", "def_a", true);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_form_binding WHERE id = 1 AND active = true", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("正例：同租户同 form_key 第二条 active=true 被唯一约束拒绝")
    void duplicateActive_sameTenantAndFormKey_shouldFail() {
        insertBinding(1L, 1L, "form_a", "def_a", true);

        assertUniqueViolation(() -> insertBinding(2L, 1L, "form_a", "def_b", true));
    }

    @Test
    @DisplayName("正例：同租户同 form_key 多条 active=false 历史记录可共存")
    void multipleInactive_sameTenantAndFormKey_shouldCoexist() {
        insertBinding(1L, 1L, "form_a", "def_a", false);
        insertBinding(2L, 1L, "form_a", "def_b", false);
        insertBinding(3L, 1L, "form_a", "def_c", false);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_form_binding WHERE tenant_id = 1 AND form_key = 'form_a'", Integer.class);
        assertThat(count).isEqualTo(3);
    }

    @Test
    @DisplayName("正例：不同 tenant_id 同 form_key 各一条 active=true 可共存（租户隔离）")
    void activeBindings_differentTenants_shouldCoexist() {
        insertBinding(1L, 1L, "form_a", "def_a", true);
        insertBinding(2L, 2L, "form_a", "def_a", true);
        insertBinding(3L, 3L, "form_a", "def_a", true);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_form_binding WHERE active = true AND form_key = 'form_a'", Integer.class);
        assertThat(count).isEqualTo(3);
    }

    @Test
    @DisplayName("正例：先停用 active=true→false，再插入新 active=true 成功（启停切换）")
    void deactivateThenInsertNewActive_shouldSucceed() {
        insertBinding(1L, 1L, "form_a", "def_a", true);
        jdbcTemplate.update("UPDATE sw_bpm_form_binding SET active = false WHERE id = 1");

        insertBinding(2L, 1L, "form_a", "def_b", true);

        Integer activeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_form_binding WHERE tenant_id = 1 AND form_key = 'form_a' AND active = true",
                Integer.class);
        assertThat(activeCount).isEqualTo(1);
    }

    // ==================== DB 级约束：反例 ====================

    @Test
    @DisplayName("反例：已有 active=true 时，把另一条 active=false 更新为 true 被拒绝")
    void updateInactiveToActive_withExistingActive_shouldFail() {
        insertBinding(1L, 1L, "form_a", "def_a", false);
        insertBinding(2L, 1L, "form_a", "def_b", true);

        assertUniqueViolation(() -> jdbcTemplate.update(
                "UPDATE sw_bpm_form_binding SET active = true WHERE id = 1"));
    }

    // ==================== Service 级语义 ====================

    @Test
    @DisplayName("正例：findActiveByFormKey 只返回 active=true 记录")
    void findActiveByFormKey_shouldReturnOnlyActive() {
        BpmFormBinding active = new BpmFormBinding();
        active.setId(1L);
        active.setFormKey("form_svc");
        active.setProcessDefKey("def_a");
        active.setActive(true);
        mapper.insert(active);

        BpmFormBinding inactive = new BpmFormBinding();
        inactive.setId(2L);
        inactive.setFormKey("form_svc");
        inactive.setProcessDefKey("def_old");
        inactive.setActive(false);
        mapper.insert(inactive);

        List<BpmFormBinding> result = service.findActiveByFormKey("form_svc");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getActive()).isTrue();
        assertThat(result.get(0).getProcessDefKey()).isEqualTo("def_a");
    }

    @Test
    @DisplayName("正例：bindFormToProcess 查-插幂等模式 —— 首次插入成功、查得后重复插入被唯一约束拦截")
    void runnerIdempotencyPattern_findThenInsert() {
        // 与 BpmDeployRunner#bindFormToProcess 相同模式：先查后插
        BpmFormBinding binding = new BpmFormBinding();
        binding.setId(1L);
        binding.setFormKey("IT_APPLICATION");
        binding.setProcessDefKey("skeleton_approval");
        binding.setActive(true);
        service.save(binding);

        // 第二次调用 run 时的前置查询：非空 → runner 会跳过
        List<BpmFormBinding> existing = service.findActiveByFormKey("IT_APPLICATION");
        assertThat(existing).hasSize(1);

        // 若绕过查-插直接重复插入（新 id、同 formKey、active=true）→ 唯一约束兜底拒绝
        BpmFormBinding dup = new BpmFormBinding();
        dup.setId(2L);
        dup.setFormKey("IT_APPLICATION");
        dup.setProcessDefKey("skeleton_approval");
        dup.setActive(true);
        assertUniqueViolation(() -> service.save(dup));
    }

    // ==================== 测试上下文配置 ====================

    @Configuration
    @EnableAutoConfiguration
    static class TestConfig {

        // I5 fail-closed：测试种子与断言均在租户 0 语境；本测试上下文提供固定
        // 租户 0 的登录上下文（生产由 SecurityLoginContextProvider 从认证态读取）
        @Bean
        public com.sw.ck.common.security.LoginContextProvider i5TenantZeroLoginContextProvider() {
            return new com.sw.ck.common.security.LoginContextProvider() {
                @Override public Long getUserId() { return 0L; }
                @Override public Long getTenantId() { return 0L; }
                @Override public Long getDeptId() { return null; }
                @Override public com.sw.ck.common.datascope.DataScopeType getDataScopeType() {
                    return com.sw.ck.common.datascope.DataScopeType.ALL;
                }
                @Override public java.util.Set<Long> getCustomDeptIds() { return java.util.Set.of(); }
                @Override public boolean isSuperAdmin() { return false; }
            };
        }


        @Bean
        public BpmFormBindingService bpmFormBindingService() {
            return new BpmFormBindingServiceImpl();
        }

        @Bean
        public static MapperScannerConfigurer mapperScannerConfigurer() {
            MapperScannerConfigurer configurer = new MapperScannerConfigurer();
            configurer.setBasePackage("com.sw.ck.bpm.process.mapper");
            return configurer;
        }
    }
}
