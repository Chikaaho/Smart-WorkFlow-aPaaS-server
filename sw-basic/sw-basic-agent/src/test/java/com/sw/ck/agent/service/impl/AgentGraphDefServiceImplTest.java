package com.sw.ck.agent.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.agent.dto.AgentGraphDefDTO;
import com.sw.ck.agent.dto.graph.GraphElement;
import com.sw.ck.agent.dto.graph.ProcessGraph;
import com.sw.ck.agent.entity.AgentGraphDef;
import com.sw.ck.agent.mapper.AgentGraphDefMapper;
import com.sw.ck.agent.service.AgentGraphDefService;
import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AgentGraphDefServiceImpl} 测试（M07 Step7 §11.1 表格 13 用例）。
 * <p>
 * 策略：{@code @SpringBootTest} + H2（TestConfig 组合装配，参照
 * {@code AgentModelConfigServiceImplTest} 先例）+ {@code @Transactional}（每用例回滚）。
 * </p>
 * <p>
 * 建表 DDL 与 V25 H2 迁移脚本逐列对齐（含 uk/index）；租户隔离经
 * {@link LoginUserHolder} 切换 tenant 验证（TenantLineInnerInterceptor 自动注入）。
 * </p>
 */
@SpringBootTest(
        classes = AgentGraphDefServiceImplTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.autoconfigure.exclude=com.sw.ck.common.config.mybatis.MybatisPlusConfig,"
                        + "com.sw.ck.common.config.redis.RedisConfig,"
                        + "com.sw.ck.security.config.SecurityAutoConfiguration,"
                        + "com.sw.ck.security.config.WebSecurityAutoConfiguration"
        }
)
@Transactional
@DisplayName("Agent 图定义 Service 测试")
class AgentGraphDefServiceImplTest {

    private static final Long TENANT_100 = 100L;
    private static final Long TENANT_200 = 200L;
    private static final Long USER_1 = 1L;

    @Autowired
    private AgentGraphDefService service;

    @Autowired
    private AgentGraphDefMapper mapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    // ==================== 建表（V25 H2 脚本 DDL） ====================

    @BeforeAll
    static void createTables(@Autowired JdbcTemplate jt) {
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_graph_def (
                    id           BIGINT NOT NULL PRIMARY KEY,
                    graph_key    VARCHAR(100) NOT NULL,
                    name         VARCHAR(200) NOT NULL,
                    def_version  INT NOT NULL DEFAULT 1,
                    status       VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
                    graph_json   CLOB,
                    create_time  TIMESTAMP,
                    create_by    VARCHAR(64),
                    update_time  TIMESTAMP,
                    update_by    VARCHAR(64),
                    deleted      SMALLINT NOT NULL DEFAULT 0,
                    tenant_id    BIGINT NOT NULL DEFAULT 0,
                    version      BIGINT NOT NULL DEFAULT 0
                )
                """);
        jt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_agent_graph_key ON sw_agent_graph_def (tenant_id, graph_key)");
        jt.execute("CREATE INDEX IF NOT EXISTS idx_sw_agent_graph_tenant_deleted ON sw_agent_graph_def (tenant_id, deleted)");
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM sw_agent_graph_def");
        setLoginUser(TENANT_100, USER_1);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    private void setLoginUser(Long tenantId, Long userId) {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(userId);
        loginUser.setTenantId(tenantId);
        loginUser.setUsername("user_" + userId);
        LoginUserHolder.set(loginUser);
    }

    // ==================== 用例 1：create 初始状态 ====================

    @Test
    @DisplayName("用例1: create 生成 agent_ 前缀 graphKey + 初始图(START→END 3 元素) + defVersion=1 + DRAFT")
    void create_shouldInitDraftGraph() {
        Long id = service.create("调度图-1");

        assertThat(id).isPositive();
        AgentGraphDef saved = mapper.selectById(id);
        assertThat(saved.getGraphKey()).startsWith("agent_");
        assertThat(saved.getName()).isEqualTo("调度图-1");
        assertThat(saved.getDefVersion()).isEqualTo(1);
        assertThat(saved.getStatus()).isEqualTo("DRAFT");

        ProcessGraph graph = parse(saved.getGraphJson());
        assertThat(graph.getGraphKey()).isEqualTo(saved.getGraphKey());
        assertThat(graph.getName()).isEqualTo("调度图-1");
        assertThat(graph.getVersion()).isEqualTo(1);
        assertThat(graph.getElements()).hasSize(3);
        assertThat(graph.getElements()).extracting(GraphElement::getKind)
                .containsExactly("node", "node", "edge");
        assertThat(graph.getElements()).extracting(GraphElement::getType)
                .containsExactly("START", "END", null);
    }

    // ==================== 用例 2：create name 空 ====================

    @Test
    @DisplayName("用例2: create name 为空白 → PARAM_ERROR，不落库")
    void create_blankName_shouldThrow() {
        assertThatThrownBy(() -> service.create("  "))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("图名称不能为空")
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.PARAM_ERROR.getCode()));
        assertThat(mapper.selectCount(null)).isZero();
    }

    // ==================== 用例 3：草稿保存覆盖 + 保持 DRAFT + 允许残图 ====================

    @Test
    @DisplayName("用例3: saveDraftGraph 全量覆盖 graph_json，status 保持 DRAFT，残图（仅 1 节点）可存")
    void saveDraftGraph_shouldOverwriteAndKeepDraft() {
        Long id = service.create("残图-测试");

        // 残图：仅 1 个 LLM 节点，无 START/END/边（草稿不校验，允许存）
        ProcessGraph draft = buildGraph("残图-测试");
        draft.setElements(java.util.List.of(GraphElement.builder()
                .id("node_llm_1").kind("node").type("LLM")
                .config(Map.of("modelConfigId", 42L)).build()));
        service.saveDraftGraph(id, draft);

        AgentGraphDef saved = mapper.selectById(id);
        assertThat(saved.getStatus()).as("草稿保存不得改变 DRAFT 状态").isEqualTo("DRAFT");
        ProcessGraph reread = parse(saved.getGraphJson());
        assertThat(reread.getElements()).hasSize(1);
        assertThat(reread.getElements().get(0).getType()).isEqualTo("LLM");
    }

    // ==================== 用例 4：saveDraftGraph 不存在 id ====================

    @Test
    @DisplayName("用例4: saveDraftGraph 不存在的 id → NOT_FOUND")
    void saveDraftGraph_unknownId_shouldThrow() {
        assertThatThrownBy(() -> service.saveDraftGraph(999999L, buildGraph("x")))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.NOT_FOUND.getCode()));
    }

    // ==================== 用例 5：config/style 不透明透传 ====================

    @Test
    @DisplayName("用例5: getGraph 回读 config/style 原样透传（不透明 Map，类型+值逐字段断言）")
    void getGraph_shouldRoundTripOpaqueConfigAndStyle() {
        Long id = service.create("透传-测试");

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("keyword", "退款");
        config.put("matchMode", "CONTAINS");
        config.put("maxRetries", 3);
        Map<String, Object> style = new LinkedHashMap<>();
        style.put("color", "#f00");
        style.put("width", 160);

        ProcessGraph draft = buildGraph("透传-测试");
        draft.setElements(java.util.List.of(
                GraphElement.builder()
                        .id("node_condition").kind("node").type("CONDITION")
                        .config(config).style(style)
                        .build()));
        service.saveDraftGraph(id, draft);

        ProcessGraph reread = service.getGraph(id);
        assertThat(reread.getElements()).hasSize(1);
        GraphElement element = reread.getElements().get(0);
        // 不透明字段原样透传：后端不得解析/改写其内部结构
        assertThat(element.getConfig()).containsEntry("keyword", "退款")
                .containsEntry("matchMode", "CONTAINS")
                .containsEntry("maxRetries", 3);
        assertThat(element.getStyle()).containsEntry("color", "#f00").containsEntry("width", 160);
    }

    // ==================== 用例 6：getGraph 不存在 id ====================

    @Test
    @DisplayName("用例6: getGraph 不存在的 id → NOT_FOUND")
    void getGraph_unknownId_shouldThrow() {
        assertThatThrownBy(() -> service.getGraph(999999L))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.NOT_FOUND.getCode()));
    }

    // ==================== 用例 7：分页 + 剥离大字段 ====================

    @Test
    @DisplayName("用例7: pageDefs 分页生效，DTO 不含 graphJson 大字段（编译期防线）")
    void pageDefs_shouldPaginateAndStripLargeField() {
        service.create("图-A");
        service.create("图-B");
        service.create("图-C");

        // 同毫秒创建的行 update_time 相同会使 ORDER BY 次序不稳定：
        // 显式拉开 update_time，使「update_time 倒序」断言确定化
        jdbcTemplate.update("UPDATE sw_agent_graph_def SET update_time = ? WHERE name = '图-A'",
                java.time.LocalDateTime.now().minusMinutes(2));
        jdbcTemplate.update("UPDATE sw_agent_graph_def SET update_time = ? WHERE name = '图-B'",
                java.time.LocalDateTime.now().minusMinutes(1));
        jdbcTemplate.update("UPDATE sw_agent_graph_def SET update_time = ? WHERE name = '图-C'",
                java.time.LocalDateTime.now());

        PageParam pageParam = new PageParam();
        pageParam.setPageNum(1);
        pageParam.setPageSize(2);
        PageResult<AgentGraphDefDTO> paged = service.pageDefs(pageParam);
        assertThat(paged.getRecords()).hasSize(2);
        assertThat(paged.getTotal()).isEqualTo(3);
        assertThat(paged.getPageSize()).isEqualTo(2);
        assertThat(paged.getRecords()).extracting(AgentGraphDefDTO::getName)
                .containsExactly("图-C", "图-B"); // update_time 倒序

        // DTO 类不得含 graphJson 字段（编译期防线，列表剥离大字段）
        boolean hasLargeField = Arrays.stream(AgentGraphDefDTO.class.getDeclaredFields())
                .anyMatch(f -> f.getName().equals("graphJson"));
        assertThat(hasLargeField).as("AgentGraphDefDTO 不应声明 graphJson 字段").isFalse();
    }

    // ==================== 用例 8：逻辑删除 ====================

    @Test
    @DisplayName("用例8: delete 逻辑删除（deleted=1），删除后 getGraph → NOT_FOUND")
    void delete_shouldSoftDelete() {
        Long id = service.create("删除-测试");

        service.delete(id);

        // @TableLogic 下 selectById 自动过滤 deleted=0 → 直接查库确认 deleted 标志位
        Integer deletedFlag = jdbcTemplate.queryForObject(
                "SELECT deleted FROM sw_agent_graph_def WHERE id = ?", Integer.class, id);
        assertThat(deletedFlag).as("逻辑删除后 deleted 应为 1").isEqualTo(1);
        assertThatThrownBy(() -> service.getGraph(id))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.NOT_FOUND.getCode()));
    }

    // ==================== 用例 9：首次发布 ====================

    @Test
    @DisplayName("用例9: publish 首次发布 defVersion 1→2、PUBLISHED、graphKey 不变")
    void publish_firstTime_shouldIncrementVersion() {
        Long id = service.create("发布-测试");
        AgentGraphDef before = mapper.selectById(id);
        String graphKey = before.getGraphKey();

        AgentGraphDefDTO resp = service.publish(id);

        assertThat(resp.getDefVersion()).isEqualTo(2);
        assertThat(resp.getStatus()).isEqualTo("PUBLISHED");
        assertThat(resp.getGraphKey()).isEqualTo(graphKey);
        AgentGraphDef after = mapper.selectById(id);
        assertThat(after.getDefVersion()).isEqualTo(2);
        assertThat(after.getStatus()).isEqualTo("PUBLISHED");
    }

    // ==================== 用例 10：重复发布 + key 冻结 ====================

    @Test
    @DisplayName("用例10: 重复发布 key 一致 → 版本再 +1；图内 graphKey 被篡改 → PARAM_ERROR 冻结")
    void publish_repeatAndFreezeCheck() {
        Long id = service.create("冻结-测试");
        String graphKey = mapper.selectById(id).getGraphKey();
        service.publish(id); // 1 → 2

        // 重复发布：图内 key 与实体一致 → 2 → 3
        ProcessGraph graph = service.getGraph(id);
        assertThat(graph.getGraphKey()).isEqualTo(graphKey);
        service.saveDraftGraph(id, graph);
        AgentGraphDefDTO second = service.publish(id);
        assertThat(second.getDefVersion()).isEqualTo(3);
        assertThat(second.getStatus()).isEqualTo("PUBLISHED");

        // 图内 graphKey 被篡改 → 冻结检查拦截
        ProcessGraph tampered = service.getGraph(id);
        tampered.setGraphKey("agent_hacked_key");
        service.saveDraftGraph(id, tampered);
        assertThatThrownBy(() -> service.publish(id))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("graphKey 已冻结")
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.PARAM_ERROR.getCode()));
    }

    // ==================== 用例 11：发布空图 ====================

    @Test
    @DisplayName("用例11: publish graph_json 为空/损坏 → PARAM_ERROR 图数据为空")
    void publish_emptyGraph_shouldThrow() {
        Long id = service.create("空图-测试");
        // 将 graph_json 置 null（模拟图数据丢失/损坏）
        mapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<AgentGraphDef>lambdaUpdate()
                .eq(AgentGraphDef::getId, id)
                .set(AgentGraphDef::getGraphJson, null));

        assertThatThrownBy(() -> service.publish(id))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("图数据为空")
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.PARAM_ERROR.getCode()));
    }

    // ==================== 用例 12：publish 不存在 id ====================

    @Test
    @DisplayName("用例12: publish 不存在的 id → NOT_FOUND")
    void publish_unknownId_shouldThrow() {
        assertThatThrownBy(() -> service.publish(999999L))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.NOT_FOUND.getCode()));
    }

    // ==================== 用例 13：跨租户隔离 ====================

    @Test
    @DisplayName("用例13: 租户 B 不可读/不可发布租户 A 的图（租户拦截器隔离）")
    void tenantIsolation_shouldHideOtherTenantGraph() {
        Long id = service.create("租户-测试");
        assertThat(service.getGraph(id)).isNotNull();

        // 切换到租户 200：getGraph/publish 均 NOT_FOUND
        setLoginUser(TENANT_200, USER_1);
        assertThatThrownBy(() -> service.getGraph(id))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.NOT_FOUND.getCode()));
        assertThatThrownBy(() -> service.publish(id))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.NOT_FOUND.getCode()));

        // 租户 B 自己的图不受影响
        Long idB = service.create("租户B-图");
        assertThat(service.getGraph(idB)).isNotNull();
    }

    // ==================== 内部辅助 ====================

    private ProcessGraph buildGraph(String name) {
        ProcessGraph graph = new ProcessGraph();
        graph.setName(name);
        graph.setVersion(1);
        return graph;
    }

    private ProcessGraph parse(String json) {
        try {
            return objectMapper.readValue(json, ProcessGraph.class);
        } catch (Exception e) {
            throw new AssertionError("graph_json 解析失败: " + e.getMessage(), e);
        }
    }

    // ==================== 组合测试配置 ====================

    @Configuration
    @MapperScan("com.sw.ck.agent.mapper")
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:agentgraphdef;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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
        public LoginContextProvider testLoginContextProvider() {
            return new LoginContextProvider() {
                @Override
                public Long getUserId() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getUserId() : null;
                }

                @Override
                public Long getTenantId() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getTenantId() : null;
                }

                @Override
                public Long getDeptId() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getDeptId() : null;
                }

                @Override
                public DataScopeType getDataScopeType() {
                    return DataScopeType.ALL;
                }

                @Override
                public Set<Long> getCustomDeptIds() {
                    return Set.of();
                }

                @Override
                public boolean isSuperAdmin() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null && user.isSuperAdmin();
                }
            };
        }

        @Bean
        public CommonMetaObjectHandler commonMetaObjectHandler(LoginContextProvider loginContextProvider) {
            return new CommonMetaObjectHandler(loginContextProvider);
        }

        @Bean
        public TenantProperties tenantProperties() {
            TenantProperties props = new TenantProperties();
            props.setEnabled(true);
            return props;
        }

        @Bean
        public TenantLineInnerInterceptor tenantLineInnerInterceptor(
                TenantProperties tenantProperties,
                LoginContextProvider loginContextProvider) {
            return new TenantLineInnerInterceptor(
                    new CommonTenantLineHandler(tenantProperties, loginContextProvider));
        }

        @Bean
        public MybatisPlusInterceptor mybatisPlusInterceptor(TenantLineInnerInterceptor tenantLineInnerInterceptor) {
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(tenantLineInnerInterceptor);
            interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
            interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
            return interceptor;
        }

        @Bean
        public org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                CommonMetaObjectHandler metaObjectHandler,
                MybatisPlusInterceptor interceptor) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setTypeAliasesPackage("com.sw.ck.agent.entity");
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
            globalConfig.setMetaObjectHandler(metaObjectHandler);
            factory.setGlobalConfig(globalConfig);
            factory.setPlugins(interceptor);
            return factory.getObject();
        }

        @Bean
        public ObjectMapper objectMapper() {
            // ProcessGraph 序列化仅涉及 Map/List/String（config/style 不透明），裸 ObjectMapper 即可
            return new ObjectMapper();
        }

        @Bean
        public AgentGraphDefService agentGraphDefService(ObjectMapper objectMapper) {
            return new AgentGraphDefServiceImpl(objectMapper);
        }
    }
}
