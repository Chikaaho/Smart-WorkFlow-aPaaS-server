package com.sw.ck.bpm.engine.contract;

import com.sw.ck.bpm.api.result.BpmProcessStatus;
import com.sw.ck.bpm.engine.adapter.FormExtDatasourceQueryAdapter;
import com.sw.ck.bpm.engine.config.ExternalDatasourceProperties;
import com.sw.ck.bpm.engine.datasource.ExternalDatasourceManager;
import com.sw.ck.bpm.engine.datasource.ExternalDatasourceUnavailableException;
import com.sw.ck.bpm.engine.entity.ExternalDatasource;
import com.sw.ck.bpm.engine.executor.SqlExecutor;
import com.sw.ck.bpm.engine.facade.BpmRuntimeFacadeImpl;
import com.sw.ck.bpm.engine.service.ExternalDatasourceService;
import com.sw.ck.bpm.engine.service.SqlExecutionAuditService;
import com.sw.ck.form.api.dto.ExtQueryResult;
import com.sw.ck.form.api.port.ExtDatasourceQueryPort;
import com.zaxxer.hikari.HikariDataSource;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 契约边界行为证据（方向 §7 验收项 5/8/11）：
 * <ul>
 *   <li>AM-035 {@code ExtDatasourceQueryPort#executeQuery}：目标缺失（数据源未登记/已停用）
 *       与请求非法（SQL 非单条只读 SELECT）必须可区分；</li>
 *   <li>AM-062 {@code BpmRuntimeFacade#startProcess}：无已发布定义的缺失路径与引擎失败路径必须可区分；</li>
 *   <li>运行期查询的 {@code "NOT_FOUND"} 字符串哨兵已由 {@code Optional.empty()} 取代。</li>
 * </ul>
 */
class ApiOptionalContractBoundaryTest {

    // ==================== AM-035：外部数据源查询端口 ====================

    private static final long ENABLED_DATASOURCE_ID = 9L;
    private static final long DISABLED_DATASOURCE_ID = 10L;
    private static final long MISSING_DATASOURCE_ID = 404L;

    private HikariDataSource dataSource;
    private SqlExecutor sqlExecutor;
    private ExtDatasourceQueryPort port;

    @BeforeEach
    void setUpSqlExecutor() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:mem:optional_boundary_ext;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS ext_rows (id INT PRIMARY KEY)");
        jdbc.update("DELETE FROM ext_rows");
        jdbc.update("INSERT INTO ext_rows(id) VALUES (1), (2)");

        ExternalDatasourceService datasourceService = mock(ExternalDatasourceService.class);
        ExternalDatasource enabled = new ExternalDatasource();
        enabled.setId(ENABLED_DATASOURCE_ID);
        enabled.setName("enabled-h2");
        enabled.setEnabled(1);
        ExternalDatasource disabled = new ExternalDatasource();
        disabled.setId(DISABLED_DATASOURCE_ID);
        disabled.setName("disabled-h2");
        disabled.setEnabled(0);
        when(datasourceService.getById(ENABLED_DATASOURCE_ID)).thenReturn(enabled);
        when(datasourceService.getById(DISABLED_DATASOURCE_ID)).thenReturn(disabled);
        when(datasourceService.getById(MISSING_DATASOURCE_ID)).thenReturn(null);

        ExternalDatasourceManager poolManager = mock(ExternalDatasourceManager.class);
        when(poolManager.getOrCreatePool(any(ExternalDatasource.class))).thenReturn(dataSource);

        ExternalDatasourceProperties properties = new ExternalDatasourceProperties();
        ExternalDatasourceProperties.Execution execution = new ExternalDatasourceProperties.Execution();
        execution.setMaxRows(50);
        execution.setQueryTimeout(5);
        properties.setExecution(execution);

        sqlExecutor = new SqlExecutor(datasourceService, poolManager,
                mock(SqlExecutionAuditService.class), properties);
        port = new FormExtDatasourceQueryAdapter(sqlExecutor);
    }

    @AfterEach
    void tearDownSqlExecutor() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }

    @Test
    @DisplayName("AM-035 正向：数据源存在且 SQL 合法时返回 present 查询结果（零行也是 present）")
    void executeQuery_shouldReturnPresentResultForLegalQuery() {
        Optional<ExtQueryResult> result = port.executeQuery(ENABLED_DATASOURCE_ID,
                "SELECT id FROM ext_rows ORDER BY id", 7L, "tester");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().getRowCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("AM-035 目标缺失：数据源未登记/已停用 → empty，且未执行任何查询")
    void executeQuery_shouldReturnEmptyWhenDatasourceMissingOrDisabled() {
        assertThat(port.executeQuery(MISSING_DATASOURCE_ID, "SELECT id FROM ext_rows", 7L, "tester"))
                .as("未登记数据源：目标缺失").isEmpty();
        assertThat(port.executeQuery(DISABLED_DATASOURCE_ID, "SELECT id FROM ext_rows", 7L, "tester"))
                .as("已停用数据源：目标缺失").isEmpty();

        assertThatThrownBy(() -> sqlExecutor.execute(MISSING_DATASOURCE_ID, "SELECT id FROM ext_rows", 7L, "tester"))
                .as("执行器对目标缺失抛出可识别的子类异常，保持既有 IllegalArgumentException 兼容")
                .isInstanceOf(ExternalDatasourceUnavailableException.class)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AM-035 请求非法：SQL 非单条只读 SELECT 继续抛异常，绝不转成 empty")
    void executeQuery_shouldThrowForIllegalSql() {
        assertThatThrownBy(() -> port.executeQuery(ENABLED_DATASOURCE_ID,
                "DELETE FROM ext_rows", 7L, "tester"))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(ExternalDatasourceUnavailableException.class);

        assertThatThrownBy(() -> port.executeQuery(ENABLED_DATASOURCE_ID, "  ", 7L, "tester"))
                .as("空 SQL 属请求非法")
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(ExternalDatasourceUnavailableException.class);
    }

    @Test
    @DisplayName("AM-035 适配层映射：仅目标缺失转 empty，其它异常原样上抛")
    void adapter_shouldMapOnlyTargetMissingToEmpty() {
        SqlExecutor missingTarget = mock(SqlExecutor.class);
        doThrow(new ExternalDatasourceUnavailableException("External datasource not found: id=404"))
                .when(missingTarget).execute(anyLong(), anyString(), anyLong(), anyString());
        assertThat(new FormExtDatasourceQueryAdapter(missingTarget)
                .executeQuery(404L, "SELECT 1", 1L, "t"))
                .as("目标缺失 → empty")
                .isEmpty();

        SqlExecutor illegalRequest = mock(SqlExecutor.class);
        doThrow(new IllegalArgumentException("SQL must not be empty"))
                .when(illegalRequest).execute(anyLong(), anyString(), anyLong(), anyString());
        assertThatThrownBy(() -> new FormExtDatasourceQueryAdapter(illegalRequest)
                .executeQuery(9L, "", 1L, "t"))
                .as("请求非法不得被适配层吞成 empty")
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(ExternalDatasourceUnavailableException.class);
    }

    // ==================== AM-062：流程启动与状态查询边界 ====================

    private static ProcessEngine processEngine;
    private static RepositoryService repositoryService;
    private static BpmRuntimeFacadeImpl runtimeFacade;

    @BeforeAll
    static void initFlowable() {
        ProcessEngineConfigurationImpl config =
                (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
                        .createStandaloneInMemProcessEngineConfiguration();
        config.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        config.setJdbcUrl("jdbc:h2:mem:optional_boundary_flowable;DB_CLOSE_DELAY=-1");
        config.setJdbcDriver("org.h2.Driver");
        config.setJdbcUsername("sa");
        config.setJdbcPassword("");
        processEngine = config.buildEngine();

        repositoryService = processEngine.getRepositoryService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        HistoryService historyService = processEngine.getHistoryService();
        runtimeFacade = new BpmRuntimeFacadeImpl(runtimeService, historyService);

        String bpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             targetNamespace="http://contract/boundary">
                  <process id="boundary_contract_process" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="flow" sourceRef="start" targetRef="approve"/>
                    <userTask id="approve" name="审批"/>
                  </process>
                </definitions>
                """;
        repositoryService.createDeployment()
                .addString("boundary_contract.bpmn20.xml", bpmn)
                .name("boundary-contract")
                .tenantId("1")
                .deploy();
    }

    @AfterAll
    static void clearFlowable() {
        if (processEngine != null) {
            processEngine.close();
            processEngine = null;
        }
    }

    @Test
    @DisplayName("AM-062 正向：已发布定义在目标租户下启动成功，返回 present 实例 ID")
    void startProcess_shouldReturnInstanceIdForDeployedDefinition() {
        Optional<String> instanceId = runtimeFacade.startProcess(
                "boundary_contract_process", "bk-present", Map.of(), "1");

        assertThat(instanceId).isPresent();
        assertThat(instanceId.orElseThrow()).isNotBlank();
        assertThat(runtimeFacade.getProcessInstanceStatus(instanceId.orElseThrow()))
                .as("运行中实例的状态为 present RUNNING")
                .contains(BpmProcessStatus.RUNNING);
    }

    @Test
    @DisplayName("AM-062 目标缺失：key 未部署或租户无该定义 → empty（不产生实例）")
    void startProcess_shouldReturnEmptyWhenDefinitionMissing() {
        assertThat(runtimeFacade.startProcess("no_such_process_key", "bk-1", Map.of(), "1"))
                .as("未部署的 key：启动目标缺失").isEmpty();
        assertThat(runtimeFacade.startProcess("boundary_contract_process", "bk-2", Map.of(), "999"))
                .as("定义未发布到该租户：启动目标缺失").isEmpty();
    }

    @Test
    @DisplayName("AM-062 哨兵移除：实例不存在不再是 \"NOT_FOUND\" 字符串，而是 empty")
    void processInstanceStatus_shouldReturnEmptyInsteadOfNotFoundSentinel() {
        assertThat(runtimeFacade.getProcessInstanceStatus("no-such-instance")).isEmpty();
        assertThat(runtimeFacade.getProcessInstanceStatus(" ")).isEmpty();
        assertThat(runtimeFacade.getProcessVariables("no-such-instance"))
                .as("实例不存在时变量查询为 empty（原空 Map 缺失语义）").isEmpty();
        assertThat(runtimeFacade.getActiveActivityIds("no-such-instance"))
                .as("实例不存在时活跃节点查询为 empty").isEmpty();
        assertThat(runtimeFacade.getActiveActivityIds(null)).isEmpty();
    }

    @Test
    @DisplayName("§4.4 两层语义：上下文缺失为 empty，合法零匹配为 present 空集合")
    void collectionSemanticsDistinguishMissingContextFromEmptyMatch() {
        Optional<List<String>> missingContext = runtimeFacade.getActiveActivityIds("   ");
        assertThat(missingContext).as("上下文缺失 → empty").isEmpty();

        String instanceId = runtimeFacade.startProcess(
                "boundary_contract_process", "bk-two-layer", Map.of(), "1").orElseThrow();
        Optional<List<String>> zeroMatch = runtimeFacade.getActiveActivityIds(instanceId);
        assertThat(zeroMatch).as("查询已执行且为零匹配 → present 空集合").isPresent();
        assertThat(zeroMatch.orElseThrow()).isNotNull();
    }
}
