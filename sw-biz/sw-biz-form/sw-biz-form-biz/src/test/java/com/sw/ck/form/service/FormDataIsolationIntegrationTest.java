package com.sw.ck.form.service;

import com.sw.ck.common.datascope.DataScopeFilter;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.form.api.dto.FormDataQueryRequest;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 表单数据跨租户 + 数据范围隔离集成测试（真实 H2 + 真实 {@link FormDataQueryService}）。
 * <p>
 * 覆盖 P32 R4 的跨租户维度：tenant A / tenant B 各自写入同结构动态宽表后，
 * 查询与导出路径（{@code queryFormData}）只能看到本租户数据。
 * </p>
 */
@SpringBootTest(
        classes = FormDataIsolationIntegrationTest.Cfg.class,
        properties = {
                "spring.autoconfigure.exclude=com.sw.ck.common.config.mybatis.MybatisPlusConfig,"
                        + "com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceAutoConfiguration,"
                        + "com.sw.ck.security.config.SecurityAutoConfiguration,"
                        + "com.sw.ck.security.config.WebSecurityAutoConfiguration"
        }
)
@DisplayName("表单数据租户隔离")
class FormDataIsolationIntegrationTest {

    private static final String TABLE = "sw_form_isolattest";

    @Configuration
    @EnableAutoConfiguration
    static class Cfg {
        @Bean
        FormDefService formDefService() {
            FormDefService mock = Mockito.mock(FormDefService.class);
            FormDefDTO dto = new FormDefDTO();
            dto.setId("form-isolation-1");
            dto.setFormKey("isol_test");
            dto.setStatus("PUBLISHED");
            dto.setPhysicalTableName(TABLE);
            when(mock.getFormDefByKey(anyString())).thenReturn(dto);
            when(mock.isCurrentUserVisible(anyString())).thenReturn(true);
            return mock;
        }

        @Bean
        com.sw.ck.form.mapper.FormDefMapper formDefMapper() {
            return Mockito.mock(com.sw.ck.form.mapper.FormDefMapper.class);
        }

        @Bean
        com.sw.ck.form.mapper.FormConfigMapper formConfigMapper() {
            com.sw.ck.form.mapper.FormConfigMapper mock = Mockito.mock(com.sw.ck.form.mapper.FormConfigMapper.class);
            com.sw.ck.form.entity.FormConfigEntity config = new com.sw.ck.form.entity.FormConfigEntity();
            config.setFormId("form-isolation-1");
            config.setDefinition("{\"fields\":[{\"name\":\"name\",\"label\":\"姓名\",\"type\":\"TEXT\"}]}");
            when(mock.selectList(Mockito.any())).thenReturn(List.of(config));
            return mock;
        }

        @Bean
        FormDataQueryService formDataQueryService(com.sw.ck.form.mapper.FormDefMapper fdm,
                                                  com.sw.ck.form.mapper.FormConfigMapper fcm,
                                                  JdbcTemplate jdbcTemplate,
                                                  com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                                  FormDefService formDefService) {
            return new FormDataQueryService(formDefService, fdm, fcm, jdbcTemplate, objectMapper);
        }

        @Bean
        com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }

        /** 满足 sw-security 启动自检：本测试不经过 HTTP 认证链，直接持有 LoginUser 上下文。 */
        @Bean
        com.sw.ck.security.spi.UserDetailsProvider userDetailsProvider() {
            return new com.sw.ck.security.spi.UserDetailsProvider() {
                @Override public LoginUser loadByUsername(String username) { return null; }
                @Override public LoginUser loadByUserId(Long userId) { return null; }
            };
        }
    }

    @Autowired
    FormDataQueryService queryService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void createTableAndData() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS \"" + TABLE + "\"");
        jdbcTemplate.execute("CREATE TABLE \"" + TABLE + "\" ("
                + "\"id\" VARCHAR(36) PRIMARY KEY, \"tenant_id\" BIGINT, \"deleted\" SMALLINT DEFAULT 0, "
                + "\"create_time\" TIMESTAMP DEFAULT CURRENT_TIMESTAMP, \"create_by\" BIGINT, "
                + "\"update_time\" TIMESTAMP DEFAULT CURRENT_TIMESTAMP, \"update_by\" BIGINT, \"version\" BIGINT DEFAULT 0, "
                + "\"name\" VARCHAR(1000))");
        // 租户 1 两条（user 11 / user 12），租户 2 一条
        insert("t1-a", 1L, 11L, "租户1-用户11");
        insert("t1-b", 1L, 12L, "租户1-用户12");
        insert("t2-a", 2L, 21L, "租户2-用户21");
    }

    private void insert(String id, Long tenantId, Long userId, String name) {
        jdbcTemplate.update("INSERT INTO \"" + TABLE + "\" (\"id\",\"tenant_id\",\"deleted\",\"create_by\",\"name\") "
                + "VALUES (?,?,0,?,?)", id, tenantId, userId, name);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS \"" + TABLE + "\"");
        LoginUserHolder.clear();
    }

    private void loginAs(Long tenantId, Long userId) {
        LoginUser u = new LoginUser();
        u.setUserId(userId);
        u.setTenantId(tenantId);
        LoginUserHolder.set(u);
    }

    private FormDataQueryRequest req(int pageSize) {
        FormDataQueryRequest r = new FormDataQueryRequest();
        r.setPageNum(1);
        r.setPageSize(pageSize);
        return r;
    }

    @Test
    @DisplayName("租户1用户查询/导出仅见租户1数据")
    void tenant1_shouldNotSeeTenant2() {
        loginAs(1L, 11L);
        PageResult<Map<String, Object>> r = queryService.queryFormData("isol_test", req(50));
        assertEquals(2L, r.getTotal());
        assertTrue(r.getRecords().stream().allMatch(m -> ((String) m.get("name")).startsWith("租户1")),
                "不得包含租户2记录: " + r.getRecords());

        // 导出路径（带数据范围）同样只见本租户
        PageResult<Map<String, Object>> scoped =
                queryService.queryFormData("isol_test", req(50), DataScopeFilter.none());
        assertEquals(2L, scoped.getTotal());
    }

    @Test
    @DisplayName("租户2用户仅见租户2数据")
    void tenant2_shouldNotSeeTenant1() {
        loginAs(2L, 21L);
        PageResult<Map<String, Object>> r = queryService.queryFormData("isol_test", req(50));
        assertEquals(1L, r.getTotal());
        assertEquals("租户2-用户21", r.getRecords().get(0).get("name"));
    }

    @Test
    @DisplayName("SELF 数据范围：仅见本人 create_by 记录")
    void selfScope_shouldOnlySeeOwnRows() {
        loginAs(1L, 11L);
        PageResult<Map<String, Object>> r =
                queryService.queryFormData("isol_test", req(50), DataScopeFilter.self(11L));
        assertEquals(1L, r.getTotal());
        assertEquals("租户1-用户11", r.getRecords().get(0).get("name"));
    }

    @Test
    @DisplayName("SELF 无 userId → 恒假（零行）")
    void selfScopeWithoutUserId_shouldReturnZero() {
        loginAs(1L, null);
        PageResult<Map<String, Object>> r =
                queryService.queryFormData("isol_test", req(50), DataScopeFilter.self(null));
        assertEquals(0L, r.getTotal());
    }
}
