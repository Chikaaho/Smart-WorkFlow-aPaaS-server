package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.GraphValidationError;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.nodefunc.NodeFunctionContext;
import com.sw.ck.bpm.api.nodefunc.NodeFunctionResult;
import com.sw.ck.bpm.api.nodefunc.ParticipantFunction;
import com.sw.ck.common.exception.BaseException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G13a：在隔离注册表/测试配置注册受控故障函数，运行八类负向
 * （超时/异常/非法格式/超限输出/跨租户用户/失败策略/重复调用/审计完整性）。
 * 不增加生产脚本入口：故障实现仅存在于本测试上下文。
 */
@SpringBootTest(
        classes = NodeFunctionNegativeContractTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:g13a_nodefn;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "spring.sql.init.schema-locations=classpath:db/g13a-schema-h2.sql"
        })
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NodeFunctionNegativeContractTest {

    private static final AtomicInteger OK_CALLS = new AtomicInteger();

    @Autowired
    private NodeFunctionService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void seedRegistry() {
        jdbcTemplate.update("""
                INSERT INTO sw_bpm_node_function
                (id, func_key, func_version, func_type, impl_bean, config, allowed_nodes,
                 timeout_ms, failure_strategy, enabled, tenant_id, deleted, version)
                VALUES (?,?,?,?,?,?,'[]',?,?,1,?,0,0)
                """, 71001L, "func_slow_v1", 1, "RESOLVE_PARTICIPANTS", "g13aSlow",
                "{\"input\":\"context\"}", 1000L, "BLOCK", 0L);
        jdbcTemplate.update("""
                INSERT INTO sw_bpm_node_function
                (id, func_key, func_version, func_type, impl_bean, config, allowed_nodes,
                 timeout_ms, failure_strategy, enabled, tenant_id, deleted, version)
                VALUES (?,?,?,?,?,?,'[]',?,?,1,?,0,0)
                """, 71002L, "func_bomb_v1", 1, "RESOLVE_PARTICIPANTS", "g13aBomb",
                "{\"input\":\"context\"}", 5000L, "BLOCK", 0L);
        jdbcTemplate.update("""
                INSERT INTO sw_bpm_node_function
                (id, func_key, func_version, func_type, impl_bean, config, allowed_nodes,
                 timeout_ms, failure_strategy, enabled, tenant_id, deleted, version)
                VALUES (?,?,?,?,?,?,'[]',?,?,1,?,0,0)
                """, 71003L, "func_badfmt_v1", 1, "RESOLVE_PARTICIPANTS", "g13aBadFmt",
                "{\"input\":\"context\"}", 5000L, "BLOCK", 0L);
        jdbcTemplate.update("""
                INSERT INTO sw_bpm_node_function
                (id, func_key, func_version, func_type, impl_bean, config, allowed_nodes,
                 timeout_ms, failure_strategy, enabled, tenant_id, deleted, version)
                VALUES (?,?,?,?,?,?,'[]',?,?,1,?,0,0)
                """, 71004L, "func_over_v1", 1, "RESOLVE_PARTICIPANTS", "g13aOver",
                "{\"input\":\"context\"}", 5000L, "BLOCK", 0L);
        jdbcTemplate.update("""
                INSERT INTO sw_bpm_node_function
                (id, func_key, func_version, func_type, impl_bean, config, allowed_nodes,
                 timeout_ms, failure_strategy, enabled, tenant_id, deleted, version)
                VALUES (?,?,?,?,?,?,'[]',?,?,1,?,0,0)
                """, 71005L, "func_fb_v1", 1, "RESOLVE_PARTICIPANTS", "g13aFallback",
                "{\"input\":\"context\"}", 5000L, "FALLBACK", 0L);
        jdbcTemplate.update("""
                INSERT INTO sw_bpm_node_function
                (id, func_key, func_version, func_type, impl_bean, config, allowed_nodes,
                 timeout_ms, failure_strategy, enabled, tenant_id, deleted, version)
                VALUES (?,?,?,?,?,?,'[]',?,?,1,?,0,0)
                """, 71006L, "func_ok_v1", 1, "RESOLVE_PARTICIPANTS", "g13aOk",
                "{\"input\":\"context\"}", 5000L, "BLOCK", 0L);
        jdbcTemplate.update("""
                INSERT INTO sw_bpm_node_function
                (id, func_key, func_version, func_type, impl_bean, config, allowed_nodes,
                 timeout_ms, failure_strategy, enabled, tenant_id, deleted, version)
                VALUES (?,?,?,?,?,?,'[]',?,?,1,?,0,0)
                """, 71007L, "func_cross_v1", 1, "RESOLVE_PARTICIPANTS", "g13aCross",
                "{\"input\":\"context\"}", 5000L, "BLOCK", 0L);
        jdbcTemplate.update("""
                INSERT INTO sw_bpm_node_function
                (id, func_key, func_version, func_type, impl_bean, config, allowed_nodes,
                 timeout_ms, failure_strategy, enabled, tenant_id, deleted, version)
                VALUES (?,?,?,?,?,?,'[]',?,?,1,?,0,0)
                """, 71008L, "func_secret_v1", 1, "RESOLVE_PARTICIPANTS", "g13aOk",
                "{\"input\":\"context\"}", 5000L, "BLOCK", 777L);
    }

    private static Map<String, Object> configOf(String key) {
        return Map.of("functions", List.of(Map.of("key", key, "version", 1)));
    }

    private static NodeFunctionContext ctx(Long tenantId, String instanceId) {
        return NodeFunctionContext.builder()
                .tenantId(tenantId)
                .processInstanceId(instanceId)
                .nodeKey("node_a")
                .nodeIdempotentKey("task-" + instanceId)
                .initiatorUserId(8801L)
                .variables(Map.of("submitter", "8801"))
                .build();
    }

    private static BaseException failOf(Throwable t) {
        return (BaseException) t;
    }

    @Test
    void a1_timeoutBreaksExecutionAndWritesAudit() {
        assertThatThrownBy(() -> service.resolveParticipants(1L, "g13a-i1", "node_a",
                "task-g13a-i1", configOf("func_slow_v1"), Map.of("submitter", "8801")))
                .isInstanceOfSatisfying(BaseException.class, e ->
                        assertThat(e.getCode()).isEqualTo(2415));
        assertThat(auditOf("g13a-i1", "func_slow_v1")).contains("outcome=FAILED", "error_code=2415");
    }

    @Test
    void a2_runtimeExceptionRejectedWithFrameworkStrategy() {
        assertThatThrownBy(() -> service.resolveParticipants(1L, "g13a-i2", "node_a",
                "task-g13a-i2", configOf("func_bomb_v1"), Map.of("submitter", "8801")))
                .isInstanceOfSatisfying(BaseException.class, e ->
                        assertThat(e.getCode()).isEqualTo(2414));
        assertThat(auditOf("g13a-i2", "func_bomb_v1")).contains("outcome=FAILED", "error_code=2414");
    }

    @Test
    void a3_illegalFormatOutputRejected() {
        assertThatThrownBy(() -> service.resolveParticipants(1L, "g13a-i3", "node_a",
                "task-g13a-i3", configOf("func_badfmt_v1"), Map.of("submitter", "8801")))
                .isInstanceOfSatisfying(BaseException.class, e ->
                        assertThat(e.getCode()).isEqualTo(2413));
        assertThat(auditOf("g13a-i3", "func_badfmt_v1")).contains("outcome=FAILED", "error_code=2413");
    }

    @Test
    void a4_overLimitOutputRejected() {
        assertThatThrownBy(() -> service.resolveParticipants(1L, "g13a-i4", "node_a",
                "task-g13a-i4", configOf("func_over_v1"), Map.of("submitter", "8801")))
                .isInstanceOfSatisfying(BaseException.class, e ->
                        assertThat(e.getCode()).isEqualTo(2413));
        assertThat(auditOf("g13a-i4", "func_over_v1")).contains("outcome=FAILED", "error_code=2413");
    }

    @Test
    void a5_crossTenantParticipantRejected() {
        // 租户 1 的用户权威只含 8801/8802；函数返回 999（他租户）必须拒绝
        assertThatThrownBy(() -> service.resolveParticipants(1L, "g13a-i5", "node_a",
                "task-g13a-i5", configOf("func_cross_v1"), Map.of("submitter", "8801")))
                .isInstanceOfSatisfying(BaseException.class, e ->
                        assertThat(e.getCode()).isEqualTo(2413));
        assertThat(auditOf("g13a-i5", "func_cross_v1")).contains("outcome=FAILED", "error_code=2413");
    }

    @Test
    void a6_fallbackStrategyReturnsNullWithoutRollback() {
        List<String> result = service.resolveParticipants(1L, "g13a-i6", "node_a",
                "task-g13a-i6", configOf("func_fb_v1"), Map.of("submitter", "8801"));
        assertThat(result).isNull();
        assertThat(auditOf("g13a-i6", "func_fb_v1")).contains("outcome=FAILED");
    }

    @Test
    void a7_repeatInvocationRecordsBothCalls() {
        List<String> first = service.resolveParticipants(1L, "g13a-i7", "node_a",
                "task-g13a-i7", configOf("func_ok_v1"), Map.of("submitter", "8801"));
        List<String> second = service.resolveParticipants(1L, "g13a-i7", "node_a",
                "task-g13a-i7", configOf("func_ok_v1"), Map.of("submitter", "8801"));
        assertThat(first).isEqualTo(second);
        Integer calls = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sw_bpm_node_function_audit "
                        + "WHERE process_instance_id='g13a-i7' AND outcome='SUCCEEDED'",
                Integer.class);
        assertThat(calls).isEqualTo(2);
        assertThat(OK_CALLS.get()).isEqualTo(2);
    }

    @Test
    void a8_auditRowsCarryCompleteIdentity() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT func_key, func_version, func_type, process_instance_id, node_key, "
                        + "idempotent_key, actor_id, outcome, error_code, duration_ms, tenant_id "
                        + "FROM sw_bpm_node_function_audit WHERE process_instance_id LIKE 'g13a-%'");
        assertThat(rows.size()).isGreaterThanOrEqualTo(7);
        for (Map<String, Object> row : rows) {
            assertThat(String.valueOf(row.get("func_key"))).isNotBlank();
            assertThat(row.get("func_version")).isNotNull();
            assertThat(String.valueOf(row.get("func_type"))).isNotBlank();
            assertThat(String.valueOf(row.get("process_instance_id"))).isNotBlank();
            assertThat(String.valueOf(row.get("node_key"))).isNotBlank();
            assertThat(String.valueOf(row.get("idempotent_key"))).isNotBlank();
            assertThat(row.get("actor_id")).isNotNull();
            assertThat(String.valueOf(row.get("outcome"))).isIn("SUCCEEDED", "FAILED");
            assertThat(row.get("duration_ms")).isNotNull();
        }
    }

    @Test
    void a9_publishRejectsCrossTenantFunctionReference() {
        ProcessGraph graph = new ProcessGraph();
        graph.setElements(List.of(node("n1", "func_secret_v1"), node("n2", "func_ok_v1")));
        List<GraphValidationError> errors = service.validateForPublish(graph, 1L);
        assertThat(errors).extracting(GraphValidationError::getErrorCode)
                .containsExactly(BpmErrorCode.NODE_FUNCTION_NOT_FOUND.getCode());

        ProcessGraph okGraph = new ProcessGraph();
        okGraph.setElements(List.of(node("n2", "func_ok_v1")));
        List<GraphValidationError> okErrors = service.validateForPublish(okGraph, 1L);
        assertThat(okErrors).isEmpty();
    }

    private GraphElement node(String id, String funcKey) {
        GraphElement element = new GraphElement();
        element.setId(id);
        element.setKind("node");
        element.setType("APPROVE");
        element.setConfig(new java.util.HashMap<>(Map.of(
                "functions", List.of(Map.of("key", funcKey, "version", 1)))));
        return element;
    }

    private String auditOf(String instanceId, String funcKey) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT outcome, error_code FROM sw_bpm_node_function_audit "
                        + "WHERE process_instance_id=? AND func_key=?", instanceId, funcKey);
        return "outcome=" + row.get("outcome") + ",error_code=" + row.get("error_code");
    }

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
        NodeFunctionService nodeFunctionService(
                com.sw.ck.bpm.process.mapper.BpmNodeFunctionMapper mapper,
                org.springframework.context.ApplicationContext applicationContext,
                com.sw.ck.system.api.user.UserQueryFacade userFacade) {
            org.springframework.beans.factory.ObjectProvider<com.sw.ck.system.api.user.UserQueryFacade> provider =
                    Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
            Mockito.when(provider.getIfAvailable()).thenReturn(userFacade);
            return new NodeFunctionService(mapper, applicationContext, provider);
        }

        @Bean
        com.sw.ck.system.api.user.UserQueryFacade g13aUserAuthority() {
            com.sw.ck.system.api.user.UserQueryFacade facade =
                    Mockito.mock(com.sw.ck.system.api.user.UserQueryFacade.class);
            Mockito.when(facade.findActiveUserIds(Mockito.anyCollection(), Mockito.eq(1L)))
                    .thenAnswer(inv -> {
                        List<Long> requested = new ArrayList<>((java.util.Collection<Long>) inv.getArgument(0));
                        return requested.stream().filter(id -> id == 8801L || id == 8802L).toList();
                    });
            Mockito.when(facade.findActiveUserIds(Mockito.anyCollection(), Mockito.eq(777L)))
                    .thenAnswer(inv -> List.of(999L));
            Mockito.when(facade.findActiveUserIds(Mockito.anyCollection(), Mockito.eq(0L)))
                    .thenAnswer(inv -> List.of());
            return facade;
        }

        @Bean
        ParticipantFunction g13aSlow() {
            return context -> {
                try {
                    Thread.sleep(6000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted");
                }
                return List.of("8801");
            };
        }

        @Bean
        ParticipantFunction g13aBomb() {
            return context -> {
                throw new IllegalStateException("boom");
            };
        }

        @Bean
        ParticipantFunction g13aBadFmt() {
            return context -> List.of("abc");
        }

        @Bean
        ParticipantFunction g13aOver() {
            return context -> java.util.stream.IntStream.rangeClosed(1, 1001)
                    .mapToObj(String::valueOf).toList();
        }

        @Bean
        ParticipantFunction g13aFallback() {
            return context -> {
                throw new IllegalStateException("fb-boom");
            };
        }

        @Bean
        ParticipantFunction g13aOk() {
            return context -> {
                OK_CALLS.incrementAndGet();
                return List.of("8801");
            };
        }

        @Bean
        ParticipantFunction g13aCross() {
            return context -> List.of("999");
        }

        @Bean
        static MapperScannerConfigurer mapperScannerConfigurer() {
            MapperScannerConfigurer configurer = new MapperScannerConfigurer();
            configurer.setBasePackage("com.sw.ck.bpm.process.mapper");
            return configurer;
        }
    }
}
