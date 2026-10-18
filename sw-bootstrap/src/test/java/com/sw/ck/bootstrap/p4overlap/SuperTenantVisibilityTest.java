package com.sw.ck.bootstrap.p4overlap;

import com.sw.ck.bpm.process.entity.BpmDraft;
import com.sw.ck.bpm.process.service.BpmDraftService;
import com.sw.ck.common.datascope.DeptScopeProvider;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超管租户（系统超管，全系统唯一身份）跨租户可见性：超管可读/可写全部租户数据，
 * 同处租户 0 的普通用户与其它租户用户一律保持隔离。
 * <p>
 * 真实服务 + 真实 MyBatis-Plus 租户拦截器（{@code CommonTenantLineHandler}）+ 真实 H2：
 * 租户 1 的草稿对超管可见、可改；对租户 0 普通用户与租户 2 用户仍不可见、不可改。
 * </p>
 */
@SpringBootTest(classes = SuperTenantVisibilityTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("超管租户跨租户可见；普通用户（含租户 0）隔离不削弱")
class SuperTenantVisibilityTest {

    @Autowired
    private BpmDraftService draftService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @org.springframework.context.annotation.Configuration
    @Import(OverlapH2TestConfig.class)
    static class Config {

        @Bean
        public BpmDraftService bpmDraftService() {
            return new com.sw.ck.bpm.process.service.impl.BpmDraftServiceImpl();
        }

        @Bean
        public DeptScopeProvider deptScopeProvider() {
            return new DeptScopeProvider() {
                @Override
                public java.util.List<Long> listChildDeptIds(Long deptId) {
                    return java.util.List.of();
                }
            };
        }
    }

    private Long tenant1DraftId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM sw_bpm_command");
        jdbcTemplate.update("DELETE FROM sw_bpm_instance");
        jdbcTemplate.update("DELETE FROM sw_bpm_approval_action");
        jdbcTemplate.update("DELETE FROM sw_bpm_draft");

        // 租户 1 用户 31 的个人草稿（真实服务落库，租户拦截器注入 tenant_id=1）
        login(31L, 1L, false);
        BpmDraft draft = new BpmDraft();
        draft.setTitle("租户1用户草稿");
        draft.setFormKey("p4_oa_biz_form_20260905b");
        draft.setFormVersion(2L);
        draft.setStatus("EDITING");
        draft.setPayload("{\"applicant\":\"用户31\"}");
        draft.setSubmitSeq(0);
        draftService.save(draft);
        tenant1DraftId = draft.getId();
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    private void login(long userId, long tenantId, boolean superAdmin) {
        LoginUser user = new LoginUser();
        user.setUserId(userId);
        user.setTenantId(tenantId);
        user.setSuperAdmin(superAdmin);
        LoginUserHolder.set(user);
    }

    @Test
    @DisplayName("超管：跨租户 getById 可见、列表可见、跨租户更新生效")
    void superAdminSeesAndManagesAllTenants() {
        login(1L, 0L, true);

        BpmDraft foreign = draftService.getById(tenant1DraftId);
        assertThat(foreign).as("超管跨租户读取必须可见").isNotNull();
        assertThat(foreign.getTitle()).isEqualTo("租户1用户草稿");

        assertThat(draftService.lambdaQuery().list()).as("超管列表包含其它租户草稿")
                .extracting(BpmDraft::getId).contains(tenant1DraftId);

        BpmDraft tampered = new BpmDraft();
        tampered.setId(tenant1DraftId);
        tampered.setTitle("超管跨租户修正");
        assertThat(draftService.updateById(tampered)).as("超管跨租户更新生效").isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select title from sw_bpm_draft where id = ?", String.class, tenant1DraftId))
                .isEqualTo("超管跨租户修正");
    }

    @Test
    @DisplayName("租户 0 普通用户：跨租户不可见、不可改（隔离不因超管能力放宽）")
    void tenantZeroNormalUserStaysIsolated() {
        login(2L, 0L, false);

        assertThat(draftService.getById(tenant1DraftId))
                .as("租户 0 普通用户不得读回租户 1 草稿").isNull();

        BpmDraft tampered = new BpmDraft();
        tampered.setId(tenant1DraftId);
        tampered.setTitle("普通用户越权篡改");
        assertThat(draftService.updateById(tampered)).as("租户 0 普通用户跨租户更新不得生效").isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "select title from sw_bpm_draft where id = ?", String.class, tenant1DraftId))
                .as("库内内容未被改动").isEqualTo("租户1用户草稿");
    }

    @Test
    @DisplayName("租户 2 普通用户：跨租户不可见（既有 G6b 语义保持）")
    void otherTenantUserStaysIsolated() {
        login(32L, 2L, false);

        assertThat(draftService.getById(tenant1DraftId)).isNull();
        assertThat(draftService.lambdaQuery().list()).extracting(BpmDraft::getId)
                .doesNotContain(tenant1DraftId);
    }
}
