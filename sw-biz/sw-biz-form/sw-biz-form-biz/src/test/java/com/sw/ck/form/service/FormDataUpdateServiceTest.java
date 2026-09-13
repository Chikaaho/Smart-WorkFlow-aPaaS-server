package com.sw.ck.form.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.dto.FormDataUpdateRequest;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.dto.SubTableRowAction;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.dynamic.DynamicTableManager;
import com.sw.ck.form.entity.*;
import com.sw.ck.form.mapper.FormConfigMapper;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.form.mapper.FormSnapshotMapper;
import com.sw.ck.form.mapper.FormTraceMapper;
import com.sw.ck.form.service.impl.FormDefServiceImpl;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.api.dict.DictFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = FormDataUpdateServiceTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("表单记录更新服务·集成测试")
class FormDataUpdateServiceTest {

    @Autowired private FormDefService formDefService;
    @Autowired private FormSubmitService formSubmitService;
    @Autowired private FormDataUpdateService formDataUpdateService;
    @Autowired private FormDataQueryService formDataQueryService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private FormDefMapper formDefMapper;
    @Autowired private ObjectMapper objectMapper;

    private final List<String> createdTables = new ArrayList<>();
    private final List<String> createdFormIds = new ArrayList<>();
    private static final Long TEST_USER_ID = 1L;
    private static final Long TEST_TENANT_ID = 100L;

    @BeforeEach
    void setUp() {
        // I5：租户归属改由登录态填充；测试种子统一落在租户 0 上下文
        if (com.sw.ck.security.holder.LoginUserHolder.get() == null) {
            com.sw.ck.security.holder.LoginUser i5TenantZeroSetup = new com.sw.ck.security.holder.LoginUser();
            i5TenantZeroSetup.setUserId(0L);
            i5TenantZeroSetup.setTenantId(0L);
            com.sw.ck.security.holder.LoginUserHolder.set(i5TenantZeroSetup);
        }
        createMetadataTables();
        LoginUser u = new LoginUser();
        u.setUserId(TEST_USER_ID);
        u.setTenantId(TEST_TENANT_ID);
        u.setUsername("test_user");
        LoginUserHolder.set(u);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
        for (String t : createdTables) {
            try { jdbcTemplate.execute("DROP TABLE \"" + t + "\" CASCADE"); } catch (Exception ignored) {}
        }
        createdTables.clear();
        for (String fid : createdFormIds) {
            try {
                jdbcTemplate.update("DELETE FROM sw_form_trace WHERE form_id = ?", fid);
                jdbcTemplate.update("DELETE FROM sw_form_snapshot WHERE form_id = ?", fid);
                jdbcTemplate.update("DELETE FROM sw_form_config WHERE form_id = ?", fid);
                jdbcTemplate.update("DELETE FROM sw_form_def WHERE \"id\" = ?", fid);
            } catch (Exception ignored) {}
        }
        createdFormIds.clear();
    }

    // ==================== helper ====================

    private String publishForm(String formKey, String name, String definitionJson) {
        FormDefDTO d = formDefService.createDraft(formKey, name, null, null);
        createdFormIds.add(d.getId());
        formDefService.saveConfig(d.getId(), definitionJson);
        formDefService.publish(d.getId());
        FormDefEntity e = formDefMapper.selectById(d.getId());
        createdTables.add(e.getPhysicalTableName());
        return e.getPhysicalTableName();
    }

    private String parseSubTableName(String json, String field) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> m = objectMapper.readValue(json, Map.class);
            return m.get(field);
        } catch (Exception ex) { throw new RuntimeException(ex); }
    }

    // ==================== 1: record not found ====================

    @Test
    @DisplayName("更新不存在的记录 → RECORD_NOT_FOUND(1507)")
    void notFound() {
        publishForm("upd_nf", "nf", "{\"fields\":[{\"name\":\"x\",\"type\":\"TEXT\"}]}");
        FormDataUpdateRequest req = new FormDataUpdateRequest();
        req.setData(Map.of("x", "y"));
        req.setVersion(0L);
        assertThatThrownBy(() ->
                formDataUpdateService.updateRecord("upd_nf", "nonexist-0000-0000-000000000000", req))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(FormErrorCode.RECORD_NOT_FOUND.getCode()));
    }

    // ==================== 2: version conflict ====================

    @Test
    @DisplayName("version 不匹配 → VERSION_CONFLICT(1508)")
    void versionConflict() {
        publishForm("upd_vc", "vc", "{\"fields\":[{\"name\":\"cnt\",\"type\":\"NUMBER\"}]}");
        String rid = formSubmitService.submitForm("upd_vc", Map.of("cnt", 100), null, null, null);

        FormDataUpdateRequest req = new FormDataUpdateRequest();
        req.setData(Map.of("cnt", 200));
        req.setVersion(1L); // wrong

        assertThatThrownBy(() -> formDataUpdateService.updateRecord("upd_vc", rid, req))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(FormErrorCode.VERSION_CONFLICT.getCode()));

        // verify data untouched
        Map<String, Object> row = formDataQueryService.getRecordDetail("upd_vc", rid);
        assertThat(((Number) row.get("cnt")).intValue()).isEqualTo(100);
        assertThat(((Number) row.get("version")).longValue()).isEqualTo(0L);
    }

    // ==================== 3: main table update success ====================

    @Test
    @DisplayName("主表整量更新 → 所有列更新 + version+1")
    void updateSuccess() {
        String tn = publishForm("upd_ok", "ok",
                "{\"fields\":[{\"name\":\"t\",\"type\":\"TEXT\",\"required\":true},{\"name\":\"s\",\"type\":\"NUMBER\"},{\"name\":\"a\",\"type\":\"BOOL\"}]}");
        String rid = formSubmitService.submitForm("upd_ok",
                Map.of("t", "old", "s", 50, "a", false), null, null, null);

        FormDataUpdateRequest req = new FormDataUpdateRequest();
        req.setData(Map.of("t", "new", "s", 99, "a", true));
        req.setVersion(0L);
        formDataUpdateService.updateRecord("upd_ok", rid, req);

        Map<String, Object> row = formDataQueryService.getRecordDetail("upd_ok", rid);
        assertThat(row.get("t")).isEqualTo("new");
        assertThat(((Number) row.get("s")).intValue()).isEqualTo(99);
        assertThat(((Number) row.get("a")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("version")).longValue()).isEqualTo(1L);
    }

    // ==================== 4: sub-table ADD ====================

    @Test
    @DisplayName("子表行 ADD → INSERT 新行 + parent_record_id")
    void subAdd() {
        publishForm("upd_sa", "sa",
                "{\"fields\":[{\"name\":\"t\",\"type\":\"TEXT\"},{\"name\":\"items\",\"type\":\"TABLE\",\"subFields\":[{\"name\":\"n\",\"type\":\"TEXT\"},{\"name\":\"q\",\"type\":\"NUMBER\"}]}]}");
        FormDefEntity e = formDefMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FormDefEntity>()
                        .eq(FormDefEntity::getFormKey, "upd_sa"));
        String subTn = parseSubTableName(e.getSubTableMapping(), "items");
        createdTables.add(subTn);

        String rid = formSubmitService.submitForm("upd_sa", Map.of("t", "main"), null, null, null);

        FormDataUpdateRequest req = new FormDataUpdateRequest();
        req.setData(Map.of("t", "main"));
        req.setVersion(0L);
        SubTableRowAction a = new SubTableRowAction();
        a.setAction("ADD");
        a.setData(Map.of("n", "item1", "q", 5));
        Map<String, List<SubTableRowAction>> s = new LinkedHashMap<>();
        s.put("items", List.of(a));
        req.setSubTableRows(s);
        formDataUpdateService.updateRecord("upd_sa", rid, req);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM \"" + subTn + "\" WHERE \"parent_record_id\" = ? AND \"deleted\" = 0", rid);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("n")).isEqualTo("item1");
        assertThat(((Number) rows.get(0).get("q")).intValue()).isEqualTo(5);
    }

    // ==================== 5: sub-table UPDATE ====================

    @Test
    @DisplayName("子表行 UPDATE → 字段更新")
    void subUpdate() {
        publishForm("upd_su", "su",
                "{\"fields\":[{\"name\":\"t\",\"type\":\"TEXT\"},{\"name\":\"items\",\"type\":\"TABLE\",\"subFields\":[{\"name\":\"n\",\"type\":\"TEXT\"}]}]}");
        FormDefEntity e = formDefMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FormDefEntity>()
                        .eq(FormDefEntity::getFormKey, "upd_su"));
        String subTn = parseSubTableName(e.getSubTableMapping(), "items");
        createdTables.add(subTn);

        String rid = formSubmitService.submitForm("upd_su",
                Map.of("t", "main", "items", List.of(Map.of("n", "old"))), null, null, null);

        List<Map<String, Object>> rs = jdbcTemplate.queryForList(
                "SELECT \"id\" FROM \"" + subTn + "\" WHERE \"parent_record_id\" = ? AND \"deleted\" = 0", rid);
        String sid = (String) rs.get(0).get("id");

        FormDataUpdateRequest req = new FormDataUpdateRequest();
        req.setData(Map.of("t", "main"));
        req.setVersion(0L);
        SubTableRowAction a = new SubTableRowAction();
        a.setAction("UPDATE"); a.setId(sid); a.setData(Map.of("n", "new"));
        Map<String, List<SubTableRowAction>> s = new LinkedHashMap<>();
        s.put("items", List.of(a));
        req.setSubTableRows(s);
        formDataUpdateService.updateRecord("upd_su", rid, req);

        String val = jdbcTemplate.queryForObject(
                "SELECT \"n\" FROM \"" + subTn + "\" WHERE \"id\" = ? AND \"deleted\" = 0", String.class, sid);
        assertThat(val).isEqualTo("new");
    }

    // ==================== 6: sub-table DELETE ====================

    @Test
    @DisplayName("子表行 DELETE → 软删")
    void subDelete() {
        publishForm("upd_sd", "sd",
                "{\"fields\":[{\"name\":\"t\",\"type\":\"TEXT\"},{\"name\":\"items\",\"type\":\"TABLE\",\"subFields\":[{\"name\":\"n\",\"type\":\"TEXT\"}]}]}");
        FormDefEntity e = formDefMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FormDefEntity>()
                        .eq(FormDefEntity::getFormKey, "upd_sd"));
        String subTn = parseSubTableName(e.getSubTableMapping(), "items");
        createdTables.add(subTn);

        String rid = formSubmitService.submitForm("upd_sd",
                Map.of("t", "main", "items", List.of(Map.of("n", "del"))), null, null, null);

        List<Map<String, Object>> rs = jdbcTemplate.queryForList(
                "SELECT \"id\" FROM \"" + subTn + "\" WHERE \"parent_record_id\" = ? AND \"deleted\" = 0", rid);
        String sid = (String) rs.get(0).get("id");

        FormDataUpdateRequest req = new FormDataUpdateRequest();
        req.setData(Map.of("t", "main"));
        req.setVersion(0L);
        SubTableRowAction a = new SubTableRowAction();
        a.setAction("DELETE"); a.setId(sid);
        Map<String, List<SubTableRowAction>> s = new LinkedHashMap<>();
        s.put("items", List.of(a));
        req.setSubTableRows(s);
        formDataUpdateService.updateRecord("upd_sd", rid, req);

        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"" + subTn + "\" WHERE \"id\" = ? AND \"deleted\" = 1", Integer.class, sid);
        assertThat(cnt).isEqualTo(1);
    }

    // ==================== 7: cross-record tampering blocked ====================

    @Test
    @DisplayName("子表行 UPDATE parent_record_id 不匹配 → 不影响数据")
    void subUpdateWrongParent() {
        publishForm("upd_xp", "xp",
                "{\"fields\":[{\"name\":\"t\",\"type\":\"TEXT\"},{\"name\":\"items\",\"type\":\"TABLE\",\"subFields\":[{\"name\":\"n\",\"type\":\"TEXT\"}]}]}");
        FormDefEntity e = formDefMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FormDefEntity>()
                        .eq(FormDefEntity::getFormKey, "upd_xp"));
        String subTn = parseSubTableName(e.getSubTableMapping(), "items");
        createdTables.add(subTn);

        String r1 = formSubmitService.submitForm("upd_xp",
                Map.of("t", "r1", "items", List.of(Map.of("n", "sub1"))), null, null, null);
        String r2 = formSubmitService.submitForm("upd_xp",
                Map.of("t", "r2", "items", List.of(Map.of("n", "sub2"))), null, null, null);

        List<Map<String, Object>> rs = jdbcTemplate.queryForList(
                "SELECT \"id\" FROM \"" + subTn + "\" WHERE \"parent_record_id\" = ? AND \"deleted\" = 0", r2);
        String sub2Id = (String) rs.get(0).get("id");

        // Try to update sub2 via r1's update request (cross-record tampering)
        FormDataUpdateRequest req = new FormDataUpdateRequest();
        req.setData(Map.of("t", "r1"));
        req.setVersion(0L);
        SubTableRowAction a = new SubTableRowAction();
        a.setAction("UPDATE"); a.setId(sub2Id); a.setData(Map.of("n", "hacked"));
        Map<String, List<SubTableRowAction>> s = new LinkedHashMap<>();
        s.put("items", List.of(a));
        req.setSubTableRows(s);
        formDataUpdateService.updateRecord("upd_xp", r1, req);

        // Verify sub2 untouched
        String val = jdbcTemplate.queryForObject(
                "SELECT \"n\" FROM \"" + subTn + "\" WHERE \"id\" = ? AND \"deleted\" = 0", String.class, sub2Id);
        assertThat(val).isEqualTo("sub2");
    }

    // ==================== 8: detail with sub-table rows ====================

    @Test
    @DisplayName("查详情 → 返回主记录 + version + 子表行")
    void detailWithSubRows() {
        publishForm("det_sr", "dsr",
                "{\"fields\":[{\"name\":\"t\",\"type\":\"TEXT\"},{\"name\":\"items\",\"type\":\"TABLE\",\"subFields\":[{\"name\":\"n\",\"type\":\"TEXT\"}]}]}");
        FormDefEntity e = formDefMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FormDefEntity>()
                        .eq(FormDefEntity::getFormKey, "det_sr"));
        createdTables.add(parseSubTableName(e.getSubTableMapping(), "items"));

        String rid = formSubmitService.submitForm("det_sr",
                Map.of("t", "detail", "items", List.of(Map.of("n", "a"), Map.of("n", "b"))),
                null, null, null);

        Map<String, Object> detail = formDataQueryService.getRecordDetail("det_sr", rid);
        assertThat(detail).containsKeys("id", "t", "version", "items");
        assertThat(detail.get("t")).isEqualTo("detail");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) detail.get("items");
        assertThat(items).hasSize(2);
    }

    // ==================== 9: detail not found ====================

    @Test
    @DisplayName("查详情记录不存在 → RECORD_NOT_FOUND(1507)")
    void detailNotFound() {
        publishForm("det_nf", "dnf", "{\"fields\":[{\"name\":\"x\",\"type\":\"TEXT\"}]}");
        assertThatThrownBy(() ->
                formDataQueryService.getRecordDetail("det_nf", "nonexist-0000-0000-000000000000"))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(FormErrorCode.RECORD_NOT_FOUND.getCode()));
    }

    // ==================== metadata tables ====================

    private void createMetadataTables() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS sw_form_def ("
                + "id VARCHAR(36) PRIMARY KEY, form_key VARCHAR(100) NOT NULL UNIQUE,"
                + "name VARCHAR(200) NOT NULL, logical_table_name VARCHAR(100),"
                + "status VARCHAR(20) NOT NULL DEFAULT 'DRAFT', physical_table_name VARCHAR(100),"
                + "form_version INT NOT NULL DEFAULT 1, description VARCHAR(500), visibility_scope TEXT,"
                + "sub_table_mapping TEXT,"
                + "tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT NOT NULL DEFAULT 0,"
                + "create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, create_by BIGINT,"
                + "update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_by BIGINT,"
                + "version BIGINT NOT NULL DEFAULT 0)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS sw_form_config ("
                + "id VARCHAR(36) PRIMARY KEY, form_id VARCHAR(36) NOT NULL,"
                + "table_name VARCHAR(200), parent_table VARCHAR(200),"
                + "definition CLOB NOT NULL,"
                + "tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT NOT NULL DEFAULT 0,"
                + "create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, create_by BIGINT,"
                + "update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_by BIGINT,"
                + "version BIGINT NOT NULL DEFAULT 0)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS sw_form_snapshot ("
                + "id VARCHAR(36) PRIMARY KEY, form_id VARCHAR(36) NOT NULL,"
                + "form_version INT NOT NULL, definition CLOB NOT NULL,"
                + "tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT NOT NULL DEFAULT 0,"
                + "create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, create_by BIGINT,"
                + "update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_by BIGINT,"
                + "version BIGINT NOT NULL DEFAULT 0)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS sw_form_trace ("
                + "id VARCHAR(36) PRIMARY KEY, form_id VARCHAR(36) NOT NULL,"
                + "record_id VARCHAR(36) NOT NULL, submit_user_id BIGINT NOT NULL,"
                + "submit_ip VARCHAR(200), submit_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "device_fingerprint VARCHAR(200), user_agent VARCHAR(500),"
                + "tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT NOT NULL DEFAULT 0,"
                + "create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, create_by BIGINT,"
                + "update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_by BIGINT,"
                + "version BIGINT NOT NULL DEFAULT 0)");
    }

    // ==================== TestConfig ====================

    @Configuration
    @MapperScan("com.sw.ck.form.mapper")
    static class TestConfig {
        @Bean public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:updatetest;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
                    .driverClassName("org.h2.Driver").username("sa").password("").build();
        }
        @Bean public JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean public PlatformTransactionManager txMgr(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }
        @Bean
        public org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            MybatisSqlSessionFactoryBean f = new MybatisSqlSessionFactoryBean();
            f.setDataSource(ds); f.setTypeAliasesPackage("com.sw.ck.form.entity");
            MybatisConfiguration c = new MybatisConfiguration(); c.setMapUnderscoreToCamelCase(true);
            f.setConfiguration(c);
            GlobalConfig gc = new GlobalConfig();
            GlobalConfig.DbConfig db = new GlobalConfig.DbConfig();
            db.setLogicDeleteField("deleted"); db.setLogicDeleteValue("1"); db.setLogicNotDeleteValue("0");
            gc.setDbConfig(db); f.setGlobalConfig(gc);
            // I5：租户归属改由 MetaObjectHandler 从登录态填充；测试上下文注册同一填充器
            com.sw.ck.common.config.mybatis.CommonMetaObjectHandler i5MetaObjectHandler =
                    new com.sw.ck.common.config.mybatis.CommonMetaObjectHandler(new com.sw.ck.common.security.LoginContextProvider() {
                        @Override public Long getUserId() {
                            com.sw.ck.security.holder.LoginUser u = com.sw.ck.security.holder.LoginUserHolder.get();
                            return u != null ? u.getUserId() : null;
                        }
                        @Override public Long getTenantId() {
                            com.sw.ck.security.holder.LoginUser u = com.sw.ck.security.holder.LoginUserHolder.get();
                            return u != null ? u.getTenantId() : null;
                        }
                        @Override public Long getDeptId() { return null; }
                        @Override public com.sw.ck.common.datascope.DataScopeType getDataScopeType() {
                            return com.sw.ck.common.datascope.DataScopeType.ALL;
                        }
                        @Override public java.util.Set<Long> getCustomDeptIds() { return java.util.Set.of(); }
                        @Override public boolean isSuperAdmin() { return false; }
                    });
            i5MetaObjectHandler.setFormIdFiller(meta -> {
                Object original = meta.getOriginalObject();
                if (original instanceof com.sw.ck.form.entity.FormBaseEntity f2 && f2.getId() == null) {
                    f2.setId(new com.sw.ck.form.entity.FormIdGenerator().generate());
                }
            });
            gc.setMetaObjectHandler(i5MetaObjectHandler);
            MybatisPlusInterceptor i = new MybatisPlusInterceptor();
            i.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
            f.setPlugins(i);
            return f.getObject();
        }
        @Bean public DynamicTableManager dtm(JdbcTemplate jt) { return new DynamicTableManager(jt); }
        @Bean public ObjectMapper om() { return new ObjectMapper(); }
        @Bean public FormDefService fds(FormDefMapper fdm, FormConfigMapper fcm, FormSnapshotMapper fsm,
                                         DynamicTableManager dtm, ObjectMapper om) {
            return new FormDefServiceImpl(fdm, fcm, fsm, dtm, new FormIdGenerator(), om);
        }
        @Bean public DictFacade df() {
            return new DictFacade() {
                public boolean isValidCode(String t, String c) { return true; }
                public List<com.sw.ck.system.api.dict.DictItemDTO> listByType(String t) { return List.of(); }
                public String resolveLabel(String t, String c) { return null; }
            };
        }
        @Bean public DomainEventPublisher dep(org.springframework.context.ApplicationEventPublisher d) {
            return new DomainEventPublisher(d);
        }
        @Bean public FormFieldValidator ffv(FormConfigMapper fcm, ObjectMapper om) {
            return new FormFieldValidator(fcm, om);
        }
        @Bean public FormSubmitService fss(FormDefMapper fdm, FormTraceMapper ftm, DynamicTableManager dtm,
                                            ObjectMapper om, JdbcTemplate jt, DictFacade df,
                                            DomainEventPublisher ep, FormFieldValidator ffv) {
            return new FormSubmitService(fdm, ftm, dtm, new FormIdGenerator(), om, jt, df, ep,
                    java.util.Optional.empty(), ffv,
                    new org.springframework.beans.factory.ObjectProvider<com.sw.ck.form.api.port.FlowStartPort>() {
                @Override public com.sw.ck.form.api.port.FlowStartPort getIfAvailable() { return null; }
            });
        }
        @Bean public FormDataUpdateService fdus(FormDefService fds, FormDefMapper fdm, FormConfigMapper fcm,
                                                 JdbcTemplate jt, ObjectMapper om, DictFacade df,
                                                 FormFieldValidator ffv) {
            return new FormDataUpdateService(fds, fdm, fcm, jt, om, new FormIdGenerator(), df, ffv);
        }
        @Bean public FormDataQueryService fdqs(FormDefService fds, FormDefMapper fdm, FormConfigMapper fcm,
                                                JdbcTemplate jt, ObjectMapper om) {
            return new FormDataQueryService(fds, fdm, fcm, jt, om);
        }
    }
}
