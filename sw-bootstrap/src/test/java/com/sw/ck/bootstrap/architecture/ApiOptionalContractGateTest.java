package com.sw.ck.bootstrap.architecture;

import com.sw.ck.bootstrap.architecture.fixture.CompliantFacade;
import com.sw.ck.bootstrap.architecture.fixture.CompliantSupport;
import com.sw.ck.bootstrap.architecture.fixture.NonCompliantFacade;
import com.sw.ck.bootstrap.architecture.fixture.NonCompliantSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 后端 {@code -api} 模块 Optional 契约守门（方向 §3 阶段 C）。
 * <p>
 * 正向：扫描 classpath 上全部 {@code -api} 模块产物，要求纳入边界的契约方法均返回
 * 参数化 {@code Optional<T>}。反向：同一守门逻辑对故意违规夹具必须稳定报错，
 * 证明守门不是只对当前代码成立的一次性文本检查。
 * </p>
 */
class ApiOptionalContractGateTest {

    @Test
    @DisplayName("守门发现范围非空：-api 模块契约类型与方法规模符合探索基线")
    void apiScopeIsDiscovered() {
        Set<Class<?>> apiTypes = ApiOptionalContractGate.scanApiModuleTypes();
        int contractMethods = ApiOptionalContractGate.contractMethodCount(apiTypes);
        System.out.println("[api-optional-gate] 纳入守门类型=" + apiTypes.size()
                + " 接口=" + ApiOptionalContractGate.interfacesOf(apiTypes).size()
                + " 契约方法=" + contractMethods);

        assertThat(ApiOptionalContractGate.interfacesOf(apiTypes))
                .as("纳入守门的 -api 契约接口数量")
                .hasSizeGreaterThanOrEqualTo(33);
        assertThat(contractMethods)
                .as("纳入守门的契约方法数量（121 个 AM ID 扣除已删除 8 项）")
                .isGreaterThanOrEqualTo(113);

        List<String> typeNames = apiTypes.stream().map(Class::getSimpleName).toList();
        assertThat(typeNames).contains("JobFacade", "JobHandler", "StorageFacade", "NotifyFacade",
                "NotifyChannelAdapter", "NotifyLinkAuthorizer", "NotifyRoutingService",
                "NotifyTargetResolver", "FormDataSubmitFacade", "FormDefinitionService",
                "ExtDatasourceQueryPort", "FlowStartPort", "DeptQueryFacade", "DictFacade",
                "TenantValidityFacade", "UserQueryFacade", "BpmDeployFacade", "BpmRuntimeFacade",
                "BpmTaskFacade", "BpmNodeDefinition", "BpmNodeRegistry", "ConsensusVotePort",
                "DynamicBranchPort", "LifecycleTaskEntryPort", "NodeParticipantResolver",
                "RestrictedExpressionEvaluator");
    }

    @Test
    @DisplayName("正向：所有纳入的 -api 契约方法返回参数化 Optional<T>，无 raw/Void/嵌套")
    void everyContractMethodReturnsParameterizedOptional() {
        Set<Class<?>> apiTypes = ApiOptionalContractGate.scanApiModuleTypes();

        assertThat(ApiOptionalContractGate.violationsIn(apiTypes))
                .as("纳入守门的契约方法必须全部返回参数化 Optional<T>")
                .isEmpty();
    }

    @Test
    @DisplayName("反向：故意违规的接口/公开静态方法被守门稳定拒绝")
    void nonCompliantFixturesAreRejected() {
        List<String> interfaceViolations =
                ApiOptionalContractGate.violationsIn(List.of(NonCompliantFacade.class));
        assertThat(interfaceViolations)
                .as("非 Optional 返回值、raw Optional、Optional<Void>、嵌套 Optional 都必须被报出")
                .hasSize(5);

        assertThat(ApiOptionalContractGate.violationsIn(List.of(NonCompliantSupport.class)))
                .as("公开类的 public static 非 Optional 方法必须被报出")
                .hasSize(1);
    }

    @Test
    @DisplayName("反向对照：合规夹具不产生误报")
    void compliantFixturesPass() {
        assertThat(ApiOptionalContractGate.violationsIn(
                List.of(CompliantFacade.class, CompliantSupport.class))).isEmpty();
    }

    @Test
    @DisplayName("账本闭合：8 个零调用删除项在 -api 契约上不存在任何残留定义")
    void deletedZeroCallerMethodsAreAbsent() {
        Set<Class<?>> apiTypes = ApiOptionalContractGate.scanApiModuleTypes();
        List<String> deleted = List.of(
                "StorageFacade#getUrl",
                "FormDefinitionService#getFormDefinitionById",
                "FormDefinitionService#getFormDefById",
                "DeptQueryFacade#searchActiveDepts",
                "DictFacade#resolveLabel",
                "BpmDeployFacade#findProcessDefinitionIdByDeployment",
                "BpmTaskFacade#getTaskOwner",
                "BpmTaskFacade#addCandidateUser");

        for (String entry : deleted) {
            String[] parts = entry.split("#");
            Class<?> type = apiTypes.stream()
                    .filter(candidate -> candidate.getSimpleName().equals(parts[0]))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("未发现契约类型: " + parts[0]));
            boolean present = Arrays.stream(type.getDeclaredMethods())
                    .map(Method::getName)
                    .anyMatch(name -> name.equals(parts[1]));
            assertThat(present).as("删除项仍存在定义: " + entry).isFalse();
        }
    }

    @Test
    @DisplayName("守门逻辑覆盖返回值形态：Optional 参数化与 raw/Void/嵌套判定")
    void gateClassifiesOptionalShapes() throws Exception {
        Method compliant = CompliantSupport.class.getMethod("value", String.class);
        assertThat(compliant.getReturnType()).isEqualTo(Optional.class);
        assertThat(ApiOptionalContractGate.violationsIn(List.of(CompliantSupport.class))).isEmpty();
    }
}
