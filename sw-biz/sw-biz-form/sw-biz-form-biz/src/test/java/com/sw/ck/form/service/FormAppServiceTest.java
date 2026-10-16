package com.sw.ck.form.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.form.api.dto.FormAppContentDTO;
import com.sw.ck.form.api.dto.FormAppDTO;
import com.sw.ck.form.api.dto.FormAppDirDTO;
import com.sw.ck.form.api.dto.FormAppDirSaveReq;
import com.sw.ck.form.api.dto.FormAppMembershipReq;
import com.sw.ck.form.api.dto.FormAppSaveReq;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.dynamic.DynamicTableManager;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.entity.FormIdGenerator;
import com.sw.ck.form.mapper.FormAppDirMapper;
import com.sw.ck.form.mapper.FormAppMapper;
import com.sw.ck.form.mapper.FormAppScopeMapper;
import com.sw.ck.form.mapper.FormConfigMapper;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.form.mapper.FormSnapshotMapper;
import com.sw.ck.form.service.impl.FormAppServiceImpl;
import com.sw.ck.form.service.impl.FormDefServiceImpl;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
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
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 应用中心服务集成测试（H2 PostgreSQL 模式 + 真实租户拦截器）。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>应用 CRUD / 重名 / 可见范围（ALL、SPECIFIED、空集合=无人可见）；</li>
 *   <li>可见范围集合重配（移除后再加入不撞唯一键）；</li>
 *   <li>目录 CRUD / 重名 / 引用保护；应用删除引用保护；</li>
 *   <li>归属调整目标校验（停用应用、跨应用目录、审批意见表单拒绝）；</li>
 *   <li>用户端可见应用与内容过滤（未发布/不可见表单不下发、停用不可见、跨租户拒绝）；</li>
 *   <li>createDraft 带归属的校验（失败不创建）。</li>
 * </ul>
 */
@SpringBootTest(classes = FormAppServiceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("应用中心·应用/目录/归属/可见性集成测试")
class FormAppServiceTest {

    private static final long TENANT_0 = 0L;
    private static final long TENANT_100 = 100L;
    private static final long USER_ADMIN = 1L;
    private static final long USER_MEMBER = 5L;
    private static final long USER_OTHER = 8L;

    @Autowired
    private FormAppService formAppService;

    @Autowired
    private FormDefService formDefService;

    @Autowired
    private FormAppMapper appMapper;

    @Autowired
    private FormAppDirMapper dirMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS sw_form_app_user");
        jdbcTemplate.execute("DROP TABLE IF EXISTS sw_form_app_scope");
        jdbcTemplate.execute("DROP TABLE IF EXISTS sw_form_app_dir");
        jdbcTemplate.execute("DROP TABLE IF EXISTS sw_form_app");
        jdbcTemplate.execute("DROP TABLE IF EXISTS sw_form_def");
        jdbcTemplate.execute("DROP TABLE IF EXISTS sw_form_config");
        jdbcTemplate.execute("""
                CREATE TABLE sw_form_app (
                    id                  varchar(36)  not null primary key,
                    tenant_id           bigint       not null default 0,
                    name                varchar(100) not null,
                    icon                varchar(64),
                    color               varchar(32),
                    sort_no             integer      not null default 0,
                    visible_scope_type  varchar(20)  not null default 'SPECIFIED',
                    status              varchar(20)  not null default 'ENABLED',
                    source              varchar(20)  not null default 'MANUAL',
                    legacy_category_id  bigint,
                    deleted             smallint     not null default 0,
                    create_time         timestamp    not null default current_timestamp,
                    create_by           bigint,
                    update_time         timestamp    not null default current_timestamp,
                    update_by           bigint,
                    version             bigint       not null default 0
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE sw_form_app_dir (
                    id          varchar(36)  not null primary key,
                    tenant_id   bigint       not null default 0,
                    app_id      varchar(36)  not null,
                    name        varchar(100) not null,
                    sort_no     integer      not null default 0,
                    deleted     smallint     not null default 0,
                    create_time timestamp    not null default current_timestamp,
                    create_by   bigint,
                    update_time timestamp    not null default current_timestamp,
                    update_by   bigint,
                    version     bigint       not null default 0
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE sw_form_app_scope (
                    id          varchar(36) not null primary key,
                    tenant_id   bigint      not null default 0,
                    app_id      varchar(36) not null,
                    scope_type  varchar(20) not null,
                    scope_id    varchar(64) not null,
                    deleted     smallint    not null default 0,
                    create_time timestamp   not null default current_timestamp,
                    create_by   bigint,
                    update_time timestamp   not null default current_timestamp,
                    update_by   bigint,
                    version     bigint      not null default 0
                )
                """);
        jdbcTemplate.execute("CREATE INDEX idx_sw_form_app_scope_app "
                + "ON sw_form_app_scope (tenant_id, app_id, deleted)");
        jdbcTemplate.execute("""
                CREATE TABLE sw_form_def (
                    id                   varchar(36)  not null primary key,
                    form_type            varchar(20)  not null default 'BUSINESS',
                    form_key             varchar(100) not null,
                    name                 varchar(200) not null,
                    logical_table_name   varchar(100),
                    physical_table_name  varchar(100),
                    status               varchar(20)  not null default 'DRAFT',
                    form_version         integer      not null default 1,
                    description          varchar(500),
                    visibility_scope     clob,
                    sub_table_mapping    clob,
                    app_id               varchar(36),
                    dir_id               varchar(36),
                    create_time          timestamp    not null default current_timestamp,
                    create_by            bigint,
                    update_time          timestamp    not null default current_timestamp,
                    update_by            bigint,
                    deleted              smallint     not null default 0,
                    tenant_id            bigint       not null default 0,
                    version              bigint       not null default 0
                )
                """);
        jdbcTemplate.execute("CREATE UNIQUE INDEX uk_test_form_key "
                + "ON sw_form_def (tenant_id, form_key, deleted)");
        jdbcTemplate.execute("""
                CREATE TABLE sw_form_config (
                    id                   varchar(36) not null primary key,
                    form_id              varchar(36) not null,
                    definition           clob,
                    table_name           varchar(200),
                    parent_table         varchar(200),
                    create_time          timestamp   not null default current_timestamp,
                    create_by            bigint,
                    update_time          timestamp   not null default current_timestamp,
                    update_by            bigint,
                    deleted              smallint    not null default 0,
                    tenant_id            bigint      not null default 0,
                    version              bigint      not null default 0
                )
                """);
        LoginUserHolder.clear();
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    private void loginAs(Long tenantId, Long userId) {
        LoginUser user = new LoginUser();
        user.setUserId(userId);
        user.setTenantId(tenantId);
        LoginUserHolder.set(user);
    }

    private String insertApp(String id, long tenantId, String name, String scope, String status, String source) {
        jdbcTemplate.update("INSERT INTO sw_form_app (id, tenant_id, name, sort_no, visible_scope_type, status, source, deleted, version) "
                + "VALUES (?, ?, ?, 0, ?, ?, ?, 0, 0)", id, tenantId, name, scope, status, source);
        return id;
    }

    private String insertForm(String id, long tenantId, String formKey, String status, String appId, String dirId,
                              String visibilityScope) {
        jdbcTemplate.update("INSERT INTO sw_form_def (id, tenant_id, form_key, name, status, form_type, app_id, dir_id, "
                + "visibility_scope, deleted, version) VALUES (?, ?, ?, ?, ?, 'BUSINESS', ?, ?, ?, 0, 0)",
                id, tenantId, formKey, "表单-" + formKey, status, appId, dirId, visibilityScope);
        return id;
    }

    private FormAppSaveReq req(String name, String scope, List<Long> userIds) {
        FormAppSaveReq req = new FormAppSaveReq();
        req.setName(name);
        req.setVisibleScopeType(scope);
        req.setUserIds(userIds);
        return req;
    }

    private FormAppDirSaveReq dirReq(String name) {
        FormAppDirSaveReq req = new FormAppDirSaveReq();
        req.setName(name);
        return req;
    }

    // ==================== 应用 CRUD / 可见范围 ====================

    @Test
    @DisplayName("创建应用：名称必填、重名拒绝、SPECIFIED 空集合=无人可见、ALL 为显式选择")
    void createApp_nameAndScopeSemantics() {
        loginAs(TENANT_0, USER_ADMIN);
        FormAppDTO created = formAppService.createApp(req(" 报销应用 ", "SPECIFIED", List.of(USER_ADMIN, USER_ADMIN)));
        assertThat(created.getName()).isEqualTo("报销应用");
        assertThat(created.getVisibleScopeType()).isEqualTo("SPECIFIED");
        assertThat(created.getVisibleUserIds()).containsExactly(USER_ADMIN);
        assertThat(created.getStatus()).isEqualTo("ENABLED");
        assertThat(created.getSource()).isEqualTo("MANUAL");

        // 重名拒绝（trim 后同名）
        assertThatThrownBy(() -> formAppService.createApp(req("报销应用", "ALL", null)))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("code", FormErrorCode.APP_NAME_DUPLICATE.getCode());

        // SPECIFIED 空集合 = 无人可见（不得误作全员授权）
        FormAppDTO empty = formAppService.createApp(req("空集合应用", "SPECIFIED", List.of()));
        assertThat(empty.getVisibleUserIds()).isEmpty();

        // 缺省范围按 SPECIFIED（安全值）
        FormAppDTO defaultScope = formAppService.createApp(req("缺省范围应用", null, null));
        assertThat(defaultScope.getVisibleScopeType()).isEqualTo("SPECIFIED");

        assertThatThrownBy(() -> formAppService.createApp(req(" ", "ALL", null)))
                .isInstanceOf(BaseException.class);
    }

    @Test
    @DisplayName("更新可见范围：集合整体重配，移除后再加入不撞唯一键；切到 ALL 清空集合")
    void updateApp_scopeRebindWithoutUniqueCollision() {
        loginAs(TENANT_0, USER_ADMIN);
        FormAppDTO app = formAppService.createApp(req("范围应用", "SPECIFIED", List.of(USER_MEMBER)));

        // 移除全部用户
        formAppService.updateApp(app.getId(), req("范围应用", "SPECIFIED", List.of()));
        assertThat(formAppService.getApp(app.getId()).getVisibleUserIds()).isEmpty();

        // 再加入同一用户：历史软删行不占用唯一键
        formAppService.updateApp(app.getId(), req("范围应用", "SPECIFIED", List.of(USER_MEMBER, USER_OTHER)));
        assertThat(formAppService.getApp(app.getId()).getVisibleUserIds())
                .containsExactly(USER_MEMBER, USER_OTHER);

        // 切到 ALL：集合清空（全部用户必须显式选择，不保留隐性残留）
        formAppService.updateApp(app.getId(), req("范围应用", "ALL", null));
        FormAppDTO all = formAppService.getApp(app.getId());
        assertThat(all.getVisibleScopeType()).isEqualTo("ALL");
        assertThat(all.getVisibleUserIds()).isNull();
        Integer relationRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_form_app_scope WHERE app_id = ?", Integer.class, app.getId());
        assertThat(relationRows).isZero();
    }

    @Test
    @DisplayName("目录：同应用内重名拒绝；有表单时拒删并报引用数；空目录可删")
    void dirLifecycleAndReferenceProtection() {
        loginAs(TENANT_0, USER_ADMIN);
        FormAppDTO app = formAppService.createApp(req("目录应用", "ALL", null));

        FormAppDirDTO dir = formAppService.createDir(app.getId(), dirReq("合同"));
        assertThat(dir.getAppId()).isEqualTo(app.getId());

        assertThatThrownBy(() -> formAppService.createDir(app.getId(), dirReq("合同")))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("code", FormErrorCode.APP_DIR_NAME_DUPLICATE.getCode());

        // 目录下有表单 → 拒删
        insertForm("form-in-dir", TENANT_0, "form_in_dir", "PUBLISHED", app.getId(), dir.getId(), null);
        assertThatThrownBy(() -> formAppService.deleteDir(dir.getId()))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("code", FormErrorCode.APP_DIR_DELETE_RESTRICTED.getCode());

        // 移出表单后可删
        jdbcTemplate.update("UPDATE sw_form_def SET dir_id = NULL WHERE id = 'form-in-dir'");
        formAppService.deleteDir(dir.getId());
        assertThat(dirMapper.selectById(dir.getId())).isNull();
    }

    @Test
    @DisplayName("应用删除：有目录或表单引用时拒绝；空应用可删")
    void deleteApp_referenceProtection() {
        loginAs(TENANT_0, USER_ADMIN);
        FormAppDTO withDir = formAppService.createApp(req("有目录应用", "ALL", null));
        formAppService.createDir(withDir.getId(), dirReq("目录"));
        assertThatThrownBy(() -> formAppService.deleteApp(withDir.getId()))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("code", FormErrorCode.APP_DELETE_RESTRICTED.getCode());

        FormAppDTO withForm = formAppService.createApp(req("有表单应用", "ALL", null));
        insertForm("form-in-app", TENANT_0, "form_in_app", "PUBLISHED", withForm.getId(), null, null);
        assertThatThrownBy(() -> formAppService.deleteApp(withForm.getId()))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("code", FormErrorCode.APP_DELETE_RESTRICTED.getCode());

        FormAppDTO empty = formAppService.createApp(req("空应用", "ALL", null));
        formAppService.deleteApp(empty.getId());
        assertThat(appMapper.selectById(empty.getId())).isNull();
    }

    @Test
    @DisplayName("归属调整：有效目标成功、停用应用拒绝、跨应用目录拒绝、审批意见表单拒绝")
    void assignForm_targetValidation() {
        loginAs(TENANT_0, USER_ADMIN);
        FormAppDTO appA = formAppService.createApp(req("应用A", "ALL", null));
        FormAppDTO appB = formAppService.createApp(req("应用B", "ALL", null));
        FormAppDirDTO dirA = formAppService.createDir(appA.getId(), dirReq("目录A"));
        FormAppDirDTO dirB = formAppService.createDir(appB.getId(), dirReq("目录B"));
        insertForm("form-move", TENANT_0, "form_move", "PUBLISHED", appA.getId(), null, null);

        // 有效目标：移到 B 的目录
        FormAppMembershipReq move = new FormAppMembershipReq();
        move.setAppId(appB.getId());
        move.setDirId(dirB.getId());
        formAppService.assignForm("form-move", move);
        FormDefEntity moved = formDefMapper.selectById("form-move");
        assertThat(moved.getAppId()).isEqualTo(appB.getId());
        assertThat(moved.getDirId()).isEqualTo(dirB.getId());

        // 目录不属于目标应用 → 拒绝，原归属保持（失败不改变）
        FormAppMembershipReq crossDir = new FormAppMembershipReq();
        crossDir.setAppId(appB.getId());
        crossDir.setDirId(dirA.getId());
        assertThatThrownBy(() -> formAppService.assignForm("form-move", crossDir))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("code", FormErrorCode.APP_DIR_NOT_FOUND.getCode());
        assertThat(formDefMapper.selectById("form-move").getAppId()).isEqualTo(appB.getId());

        // 停用应用不能接收归属
        formAppService.disableApp(appA.getId());
        FormAppMembershipReq toDisabled = new FormAppMembershipReq();
        toDisabled.setAppId(appA.getId());
        assertThatThrownBy(() -> formAppService.assignForm("form-move", toDisabled))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("code", FormErrorCode.APP_DISABLED.getCode());

        // 审批意见表单不参与应用归属
        jdbcTemplate.update("INSERT INTO sw_form_def (id, tenant_id, form_key, name, status, form_type, deleted, version) "
                + "VALUES ('form-approval', 0, 'form_approval', '审批意见表单', 'PUBLISHED', 'APPROVAL_COMMENT', 0, 0)");
        FormAppMembershipReq approval = new FormAppMembershipReq();
        approval.setAppId(appB.getId());
        assertThatThrownBy(() -> formAppService.assignForm("form-approval", approval))
                .isInstanceOf(BaseException.class);
    }

    @Test
    @DisplayName("停用：阻止新建目录与归属调整；管理端仍可见，用户端不可见；恢复后重新可见")
    void disableAndEnable_boundaries() {
        loginAs(TENANT_0, USER_ADMIN);
        FormAppDTO app = formAppService.createApp(req("停用应用", "ALL", null));

        formAppService.disableApp(app.getId());
        assertThat(formAppService.getApp(app.getId()).getStatus()).isEqualTo("DISABLED");
        assertThatThrownBy(() -> formAppService.createDir(app.getId(), dirReq("新目录")))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("code", FormErrorCode.APP_DISABLED.getCode());
        // 管理端列表仍含停用应用
        assertThat(formAppService.listApps(null)).extracting(FormAppDTO::getId).contains(app.getId());
        // 用户端不可见
        assertThat(formAppService.listVisibleAppsForCurrentUser()).extracting(FormAppDTO::getId)
                .doesNotContain(app.getId());

        formAppService.enableApp(app.getId());
        assertThat(formAppService.listVisibleAppsForCurrentUser()).extracting(FormAppDTO::getId)
                .contains(app.getId());
    }

    // ==================== 用户端可见性 ====================

    @Test
    @DisplayName("用户端应用列表：ALL 全员可见、SPECIFIED 命中可见、空集合无人可见、跨租户零串读")
    void visibleApps_scopeSemantics() {
        insertApp("app-all", TENANT_0, "全员应用", "ALL", "ENABLED", "MANUAL");
        insertApp("app-member", TENANT_0, "指定用户应用", "SPECIFIED", "ENABLED", "MANUAL");
        jdbcTemplate.update("INSERT INTO sw_form_app_scope (id, tenant_id, app_id, scope_type, scope_id, deleted, version) "
                + "VALUES ('au-1', 0, 'app-member', 'USER', ?, 0, 0)", String.valueOf(USER_MEMBER));
        insertApp("app-empty", TENANT_0, "空集合应用", "SPECIFIED", "ENABLED", "MANUAL");
        insertApp("app-tenant100", TENANT_100, "他租户应用", "ALL", "ENABLED", "MANUAL");

        loginAs(TENANT_0, USER_MEMBER);
        assertThat(formAppService.listVisibleAppsForCurrentUser()).extracting(FormAppDTO::getId)
                .containsExactlyInAnyOrder("app-all", "app-member");

        loginAs(TENANT_0, USER_OTHER);
        assertThat(formAppService.listVisibleAppsForCurrentUser()).extracting(FormAppDTO::getId)
                .containsExactly("app-all");

        // 跨租户：租户 0 用户看不到租户 100 的应用，反之亦然
        loginAs(TENANT_100, USER_MEMBER);
        assertThat(formAppService.listVisibleAppsForCurrentUser()).extracting(FormAppDTO::getId)
                .containsExactly("app-tenant100");
    }

    @Test
    @DisplayName("用户端内容：未发布/不可见表单不下发、可用计数只算可见表单；应用不可见按不存在处理")
    void visibleContent_filtersFormsAndHidesApp() {
        insertApp("app-1", TENANT_0, "内容应用", "ALL", "ENABLED", "MANUAL");
        jdbcTemplate.update("INSERT INTO sw_form_app_dir (id, tenant_id, app_id, name, sort_no, deleted, version) "
                + "VALUES ('dir-1', 0, 'app-1', '目录一', 0, 0, 0)");
        insertForm("f-pub", TENANT_0, "form_pub", "PUBLISHED", "app-1", null, null);
        insertForm("f-pub-dir", TENANT_0, "form_pub_dir", "PUBLISHED", "app-1", "dir-1", null);
        insertForm("f-draft", TENANT_0, "form_draft", "DRAFT", "app-1", null, null);
        insertForm("f-disabled", TENANT_0, "form_disabled", "DISABLED", "app-1", null, null);
        insertForm("f-restricted", TENANT_0, "form_restricted", "PUBLISHED", "app-1", null, "{\"userIds\":[8]}");

        loginAs(TENANT_0, USER_MEMBER);
        FormAppContentDTO content = formAppService.getVisibleAppContent("app-1");
        assertThat(content.getApp().getFormCount()).isEqualTo(2L);
        assertThat(content.getDirectForms()).extracting(f -> f.getFormKey())
                .containsExactly("form_pub");
        assertThat(content.getDirs()).hasSize(1);
        assertThat(content.getDirs().get(0).getForms()).extracting(f -> f.getFormKey())
                .containsExactly("form_pub_dir");

        // 列表计数同为可用表单数（不暴露受限表单数量）
        assertThat(formAppService.listVisibleAppsForCurrentUser())
                .filteredOn(app -> app.getId().equals("app-1"))
                .extracting(FormAppDTO::getFormCount)
                .containsExactly(2L);

        // 应用不可见 → 按不存在处理（不暴露对象）
        insertApp("app-hidden", TENANT_0, "隐藏应用", "SPECIFIED", "ENABLED", "MANUAL");
        assertThatThrownBy(() -> formAppService.getVisibleAppContent("app-hidden"))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("code", FormErrorCode.APP_NOT_FOUND.getCode());

        // 停用应用用户端按不存在处理
        insertApp("app-off", TENANT_0, "停用应用", "ALL", "DISABLED", "MANUAL");
        assertThatThrownBy(() -> formAppService.getVisibleAppContent("app-off"))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("code", FormErrorCode.APP_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("管理端内容：全量业务表单（含草稿/停用/不可见），按目录与直接归属分组")
    void manageContent_fullBusinessForms() {
        loginAs(TENANT_0, USER_ADMIN);
        insertApp("app-m", TENANT_0, "管理内容应用", "ALL", "ENABLED", "MANUAL");
        jdbcTemplate.update("INSERT INTO sw_form_app_dir (id, tenant_id, app_id, name, sort_no, deleted, version) "
                + "VALUES ('dir-m', 0, 'app-m', '目录', 0, 0, 0)");
        insertForm("f-1", TENANT_0, "form_1", "PUBLISHED", "app-m", null, null);
        insertForm("f-2", TENANT_0, "form_2", "DRAFT", "app-m", "dir-m", null);

        FormAppContentDTO content = formAppService.getAppContent("app-m");
        assertThat(content.getApp().getFormCount()).isEqualTo(2L);
        assertThat(content.getDirectForms()).hasSize(1);
        assertThat(content.getDirs().get(0).getForms()).extracting(f -> f.getStatus())
                .containsExactly("DRAFT");
    }

    // ==================== createDraft 归属校验 ====================

    @Test
    @DisplayName("createDraft 带归属：有效目标落库；无效应用/停用应用/跨应用目录/不存在表单均失败且不创建")
    void createDraft_withMembershipValidation() {
        loginAs(TENANT_0, USER_ADMIN);
        FormAppDTO app = formAppService.createApp(req("草稿归属应用", "ALL", null));
        FormAppDirDTO dir = formAppService.createDir(app.getId(), dirReq("草稿目录"));
        FormAppDTO other = formAppService.createApp(req("其他应用", "ALL", null));
        FormAppDirDTO otherDir = formAppService.createDir(other.getId(), dirReq("其他目录"));

        var dto = formDefService.createDraft("draft_ok", "草稿表单", null, null, "BUSINESS",
                app.getId(), dir.getId());
        assertThat(dto.getAppId()).isEqualTo(app.getId());
        assertThat(dto.getDirId()).isEqualTo(dir.getId());

        assertThatThrownBy(() -> formDefService.createDraft("draft_bad1", "坏草稿1", null, null, "BUSINESS",
                "nope", null))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("code", FormErrorCode.APP_NOT_FOUND.getCode());

        assertThatThrownBy(() -> formDefService.createDraft("draft_bad2", "坏草稿2", null, null, "BUSINESS",
                app.getId(), otherDir.getId()))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("code", FormErrorCode.APP_DIR_NOT_FOUND.getCode());

        formAppService.disableApp(app.getId());
        assertThatThrownBy(() -> formDefService.createDraft("draft_bad3", "坏草稿3", null, null, "BUSINESS",
                app.getId(), null))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("code", FormErrorCode.APP_DISABLED.getCode());

        // 失败路径均未创建表单
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_form_def WHERE form_key IN ('draft_bad1','draft_bad2','draft_bad3')",
                Integer.class);
        assertThat(count).isZero();

        // 审批意见表单忽略归属参数
        var approval = formDefService.createDraft("draft_approval", "审批意见", null, null,
                "APPROVAL_COMMENT", app.getId(), null);
        assertThat(approval.getAppId()).isNull();
    }

    @Autowired
    private FormDefMapper formDefMapper;

    // ==================== 测试装配 ====================

    @Configuration
    @MapperScan(basePackages = "com.sw.ck.form.mapper")
    static class Config {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:form_app_center;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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
        public org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setTypeAliasesPackage("com.sw.ck.form.entity");

            MybatisConfiguration ibatisConfig = new MybatisConfiguration();
            ibatisConfig.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(ibatisConfig);

            GlobalConfig globalConfig = new GlobalConfig();
            GlobalConfig.DbConfig dbConfig = new GlobalConfig.DbConfig();
            dbConfig.setLogicDeleteField("deleted");
            dbConfig.setLogicDeleteValue("1");
            dbConfig.setLogicNotDeleteValue("0");
            globalConfig.setDbConfig(dbConfig);

            LoginContextProvider loginContextProvider = new LoginContextProvider() {
                @Override public Long getUserId() {
                    LoginUser u = LoginUserHolder.get();
                    return u != null ? u.getUserId() : null;
                }
                @Override public Long getTenantId() {
                    LoginUser u = LoginUserHolder.get();
                    return u != null ? u.getTenantId() : null;
                }
                @Override public Long getDeptId() {
                    return null;
                }
                @Override public com.sw.ck.common.datascope.DataScopeType getDataScopeType() {
                    return com.sw.ck.common.datascope.DataScopeType.ALL;
                }
                @Override public java.util.Set<Long> getCustomDeptIds() {
                    return java.util.Set.of();
                }
                @Override public boolean isSuperAdmin() {
                    return false;
                }
            };
            CommonMetaObjectHandler metaObjectHandler = new CommonMetaObjectHandler(loginContextProvider);
            metaObjectHandler.setFormIdFiller(meta -> {
                Object original = meta.getOriginalObject();
                if (original instanceof com.sw.ck.form.entity.FormBaseEntity f && f.getId() == null) {
                    f.setId(new FormIdGenerator().generate());
                }
            });
            globalConfig.setMetaObjectHandler(metaObjectHandler);
            factory.setGlobalConfig(globalConfig);

            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(
                    new CommonTenantLineHandler(new TenantProperties(), loginContextProvider)));
            interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
            interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
            factory.setPlugins(interceptor);

            return factory.getObject();
        }

        @Bean
        public DynamicTableManager dynamicTableManager(JdbcTemplate jdbcTemplate) {
            return new DynamicTableManager(jdbcTemplate);
        }

        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        public FormAppMembershipValidator formAppMembershipValidator(FormAppMapper appMapper,
                                                                     FormAppDirMapper dirMapper) {
            return new FormAppMembershipValidator(appMapper, dirMapper);
        }

        @Bean
        public FormDefService formDefService(FormDefMapper formDefMapper,
                                             FormConfigMapper formConfigMapper,
                                             FormSnapshotMapper formSnapshotMapper,
                                             DynamicTableManager dynamicTableManager,
                                             FormIdGenerator formIdGenerator,
                                             ObjectMapper objectMapper,
                                             FormAppMembershipValidator membershipValidator) {
            // 真实路径使用带归属校验的主构造（与 Spring 装配同一签名）
            return new FormDefServiceImpl(formDefMapper, formConfigMapper, formSnapshotMapper,
                    dynamicTableManager, formIdGenerator, objectMapper,
                    new FormVisibilityRules(objectMapper), new FormulaEngine(),
                    new FieldPermissionService(objectMapper), null, null, null, null, membershipValidator);
        }

        @Bean
        public FormIdGenerator formIdGenerator() {
            return new FormIdGenerator();
        }

        @Bean
        public FormAppService formAppService(FormAppMapper appMapper,
                                             FormAppDirMapper dirMapper,
                                             FormAppScopeMapper appScopeMapper,
                                             FormDefMapper formDefMapper,
                                             FormDefService formDefService,
                                             FormAppMembershipValidator membershipValidator,
                                             FormAppScopeMatcher scopeMatcher,
                                             FormIdGenerator formIdGenerator) {
            return new FormAppServiceImpl(appMapper, dirMapper, appScopeMapper, formDefMapper,
                    formDefService, membershipValidator, scopeMatcher, formIdGenerator);
        }
    }
}
