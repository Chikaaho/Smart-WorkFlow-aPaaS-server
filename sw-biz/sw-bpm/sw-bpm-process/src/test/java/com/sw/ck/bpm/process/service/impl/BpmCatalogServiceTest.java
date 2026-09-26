package com.sw.ck.bpm.process.service.impl;

import com.sw.ck.bpm.process.dto.CatalogItemDTO;
import com.sw.ck.bpm.process.mapper.BpmProcessDefMapper;
import com.sw.ck.bpm.process.service.BpmCatalogService;
import com.sw.ck.bpm.process.service.BpmFormBindingService;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.form.FormDefinitionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事项目录服务测试（v0.0.2 流程中心，H2 集成）。
 * 覆盖验收修正 G2a：普通视角分类聚合数量——未分类（category_id=NULL）归入 key 0 统计，
 * 与「未分类」页签/卡片计数一致；categoryId=0 筛选仅返回未分类事项。
 */
@SpringBootTest(
        classes = BpmCatalogServiceTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:bpm_catalog_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "spring.sql.init.schema-locations=classpath:db/schema-process-def-h2.sql,classpath:db/schema-h2.sql"
        }
)
@ActiveProfiles("test")
@Transactional
@DisplayName("BpmCatalog 目录聚合测试")
class BpmCatalogServiceTest {

    @Autowired
    private BpmCatalogService catalogService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("聚合数量：未分类事项计入 key 0，分类事项计入各自分类")
    void portalCountsIncludeUncategorized() {
        insertDef(1L, "leave", "请假", 5L);
        insertDef(2L, "expense", "报销", null);
        insertBinding("form_leave", "leave");
        insertBinding("form_expense", "expense");

        Map<Long, Long> counts = catalogService.portalCategoryCounts();

        assertThat(counts).containsEntry(0L, 1L).containsEntry(5L, 1L);
    }

    @Test
    @DisplayName("普通视角列表：categoryId=0 筛选仅返回未分类事项")
    void portalItemsFilterUncategorized() {
        insertDef(1L, "leave", "请假", 5L);
        insertDef(2L, "expense", "报销", null);
        insertBinding("form_leave", "leave");
        insertBinding("form_expense", "expense");

        PageParam p = new PageParam();
        p.setPageNum(1);
        p.setPageSize(10);
        var page = catalogService.listPortalItems(null, 0L, p);

        assertThat(page.getRecords()).extracting(CatalogItemDTO::getItemKey).containsExactly("expense");
    }

    @Test
    @DisplayName("解除归属：assignCategory(null) 后 category_id 置空，分类可删除（G2a 回归）")
    void unassignCategoryClearsColumn() {
        insertDef(3L, "trip", "出差", 7L);
        insertBinding("form_trip", "trip");

        catalogService.assignCategory("trip", null);

        Long cid = jdbcTemplate.queryForObject(
                "SELECT category_id FROM sw_bpm_process_def WHERE process_key = 'trip'", Long.class);
        assertThat(cid).isNull();
    }

    // ==================== 夹具 ====================

    private void insertDef(Long id, String key, String name, Long categoryId) {
        jdbcTemplate.update("""
                        INSERT INTO sw_bpm_process_def (id, process_key, name, form_key, status, category_id)
                        VALUES (?, ?, ?, ?, 'PUBLISHED', ?)
                        """,
                id, key, name, "form_" + key, categoryId);
    }

    private void insertBinding(String formKey, String processDefKey) {
        jdbcTemplate.update("""
                        INSERT INTO sw_bpm_form_binding (id, tenant_id, form_key, process_def_key, active)
                        VALUES (?, 0, ?, ?, true)
                        """,
                System.nanoTime(), formKey, processDefKey);
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
        public BpmCatalogService bpmCatalogService(BpmProcessDefMapper processDefMapper,
                                                   com.sw.ck.bpm.process.mapper.BpmCategoryMapper categoryMapper,
                                                   BpmFormBindingService bindingService,
                                                   FormDefinitionService formDefinitionService) {
            return new BpmCatalogServiceImpl(processDefMapper, categoryMapper, bindingService,
                    formDefinitionService);
        }

        /** 表单服务桩：全部表单已发布且当前用户可发起（目录可见性聚合只测 bpm 侧语义）。 */
        @Bean
        public FormDefinitionService formDefinitionService() {
            return new FormDefinitionService() {
                @Override
                public java.util.Optional<String> getFormDefinition(String formKey) {
                    return java.util.Optional.empty();
                }

                @Override
                public java.util.Optional<Boolean> formExists(String formKey) {
                    return java.util.Optional.of(true);
                }

                @Override
                public java.util.Optional<FormDefDTO> getFormDef(String formKey) {
                    FormDefDTO dto = new FormDefDTO();
                    dto.setStatus("PUBLISHED");
                    return java.util.Optional.of(dto);
                }

                @Override
                public java.util.Optional<Boolean> canCurrentUserInitiate(String formKey) {
                    return java.util.Optional.of(true);
                }
            };
        }

        @Bean
        public static MapperScannerConfigurer mapperScannerConfigurer() {
            MapperScannerConfigurer configurer = new MapperScannerConfigurer();
            configurer.setBasePackage("com.sw.ck.bpm.process.mapper");
            return configurer;
        }
    }
}
