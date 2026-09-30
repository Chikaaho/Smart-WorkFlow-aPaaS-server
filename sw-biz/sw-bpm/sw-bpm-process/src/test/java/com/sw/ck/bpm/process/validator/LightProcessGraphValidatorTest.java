package com.sw.ck.bpm.process.validator;

import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.GraphValidationError;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产轻流程图约束校验器单元测试（P62 S2 发布门）。
 * <p>
 * 覆盖：含事务动作节点的图施加白名单/无环/≤16 动作约束；
 * 不含事务动作节点的既有普通图不受约束（兼容回归）。
 * </p>
 */
class LightProcessGraphValidatorTest {

    private final LightProcessGraphValidator validator = new LightProcessGraphValidator();

    // ---------- 正向：合法轻流程图 ----------

    @Test
    void linearLightProcessPasses() {
        // START → A(动作) → END
        List<GraphValidationError> errors = validator.validate(List.of(
                node("n1", "START"), node("n2", "TXN_ACTION"), node("n3", "END"),
                edge("e1", "n1", "n2"), edge("e2", "n2", "n3")));
        assertThat(errors).isEmpty();
    }

    @Test
    void conditionBranchMergePasses() {
        // START → C → A1 → END / C → A2 → END（分支汇合不是环）
        List<GraphValidationError> errors = validator.validate(List.of(
                node("n1", "START"), node("n2", "CONDITION"),
                node("n3", "TXN_ACTION"), node("n4", "TXN_ACTION"), node("n5", "END"),
                edge("e1", "n1", "n2"), edge("e2", "n2", "n3"), edge("e3", "n2", "n4"),
                edge("e4", "n3", "n5"), edge("e5", "n4", "n5")));
        assertThat(errors).isEmpty();
    }

    @Test
    void plainGraphWithoutActionNodeIsNotConstrained() {
        // 普通流程图（无 TXN_ACTION）混人工审批/并行节点不报轻流程约束（兼容既有图）
        List<GraphValidationError> errors = validator.validate(List.of(
                node("n1", "START"), node("n2", "APPROVAL"),
                node("n3", "DYNAMIC_PARALLEL"), node("n4", "END"),
                edge("e1", "n1", "n2"), edge("e2", "n2", "n3"), edge("e3", "n3", "n4")));
        assertThat(errors).isEmpty();
    }

    @Test
    void emptyOrNullGraphPasses() {
        assertThat(validator.validate(List.of())).isEmpty();
        assertThat(validator.validate(null)).isEmpty();
    }

    // ---------- 反向 1：混入不允许的节点类型 ----------

    @Test
    void mixedApprovalNodeRejected() {
        // START → A(动作) → APPROVAL(人工等待) → END：轻流程禁混人工等待
        List<GraphValidationError> errors = validator.validate(List.of(
                node("n1", "START"), node("n2", "TXN_ACTION"),
                node("n3", "APPROVAL"), node("n4", "END"),
                edge("e1", "n1", "n2"), edge("e2", "n2", "n3"), edge("e3", "n3", "n4")));
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getErrorCode())
                .isEqualTo(BpmErrorCode.LIGHT_PROCESS_NODE_NOT_ALLOWED.getCode());
        assertThat(errors.get(0).getElementId()).isEqualTo("n3");
    }

    @Test
    void mixedNotificationAndParallelRejected() {
        List<GraphValidationError> errors = validator.validate(List.of(
                node("n1", "START"), node("n2", "TXN_ACTION"),
                node("n3", "NOTIFICATION"), node("n4", "DYNAMIC_PARALLEL"), node("n5", "END"),
                edge("e1", "n1", "n2"), edge("e2", "n2", "n3"),
                edge("e3", "n3", "n4"), edge("e4", "n4", "n5")));
        assertThat(errors).extracting(GraphValidationError::getErrorCode)
                .containsOnly(BpmErrorCode.LIGHT_PROCESS_NODE_NOT_ALLOWED.getCode());
        assertThat(errors).extracting(GraphValidationError::getElementId)
                .containsExactlyInAnyOrder("n3", "n4");
    }

    // ---------- 反向 2：环 ----------

    @Test
    void cycleThroughConditionRejected() {
        // START → C → A1 → C（回边成环）
        List<GraphValidationError> errors = validator.validate(List.of(
                node("n1", "START"), node("n2", "CONDITION"), node("n3", "TXN_ACTION"), node("n4", "END"),
                edge("e1", "n1", "n2"), edge("e2", "n2", "n3"),
                edge("e3", "n3", "n2"), edge("e4", "n2", "n4")));
        assertThat(errors).isNotEmpty();
        assertThat(errors).extracting(GraphValidationError::getErrorCode)
                .contains(BpmErrorCode.LIGHT_PROCESS_CYCLE.getCode());
        // 环上节点（n2/n3）被定位，环外节点（n1/n4）不被牵连
        assertThat(errors).extracting(GraphValidationError::getElementId)
                .containsExactlyInAnyOrder("n2", "n3");
    }

    @Test
    void selfLoopRejectedAsCycle() {
        // 自环边（通用校验也会拒绝；本层独立判定不放过）
        List<GraphValidationError> errors = validator.validate(List.of(
                node("n1", "START"), node("n2", "TXN_ACTION"), node("n3", "END"),
                edge("e1", "n1", "n2"), edge("e2", "n2", "n2"), edge("e3", "n2", "n3")));
        assertThat(errors).extracting(GraphValidationError::getErrorCode)
                .contains(BpmErrorCode.LIGHT_PROCESS_CYCLE.getCode());
    }

    @Test
    void diamondIsNotACycle() {
        // 菱形汇合：C → A1 → E2 / C → A2 → E2（同一后继汇合，无回边）
        List<GraphValidationError> errors = validator.validate(List.of(
                node("n1", "START"), node("n2", "CONDITION"),
                node("n3", "TXN_ACTION"), node("n4", "TXN_ACTION"), node("n5", "END"),
                edge("e1", "n1", "n2"), edge("e2", "n2", "n3"), edge("e3", "n2", "n4"),
                edge("e4", "n3", "n5"), edge("e5", "n4", "n5"),
                // 重复指向同一边不构成环（重复边本身由通用校验拒绝，此处防御性跳过）
                edge("e6", "n1", "n2")));
        assertThat(errors).isEmpty();
    }

    // ---------- 反向 3：动作节点数量上限 ----------

    @Test
    void sixteenActionNodesPass() {
        List<GraphElement> elements = new ArrayList<>();
        elements.add(node("start", "START"));
        for (int i = 1; i <= LightProcessGraphValidator.MAX_ACTION_NODES; i++) {
            elements.add(node("a" + i, "TXN_ACTION"));
            elements.add(edge("ea" + i, i == 1 ? "start" : "a" + (i - 1), "a" + i));
        }
        elements.add(node("end", "END"));
        elements.add(edge("ee", "a" + LightProcessGraphValidator.MAX_ACTION_NODES, "end"));
        assertThat(validator.validate(elements)).isEmpty();
    }

    @Test
    void seventeenActionNodesRejected() {
        List<GraphElement> elements = new ArrayList<>();
        elements.add(node("start", "START"));
        for (int i = 1; i <= LightProcessGraphValidator.MAX_ACTION_NODES + 1; i++) {
            elements.add(node("a" + i, "TXN_ACTION"));
            elements.add(edge("ea" + i, i == 1 ? "start" : "a" + (i - 1), "a" + i));
        }
        elements.add(node("end", "END"));
        elements.add(edge("ee", "a" + (LightProcessGraphValidator.MAX_ACTION_NODES + 1), "end"));
        List<GraphValidationError> errors = validator.validate(elements);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getErrorCode())
                .isEqualTo(BpmErrorCode.LIGHT_PROCESS_ACTION_LIMIT_EXCEEDED.getCode());
    }

    // ---------- 组合：多约束同时报告 ----------

    @Test
    void mixedViolationsAllReported() {
        // 环 + 非法节点同时存在：两类错误都报，不短路
        List<GraphValidationError> errors = validator.validate(List.of(
                node("n1", "START"), node("n2", "TXN_ACTION"),
                node("n3", "APPROVAL"), node("n4", "END"),
                edge("e1", "n1", "n2"), edge("e2", "n2", "n3"),
                edge("e3", "n3", "n2"), edge("e4", "n2", "n4")));
        assertThat(errors).extracting(GraphValidationError::getErrorCode)
                .contains(BpmErrorCode.LIGHT_PROCESS_NODE_NOT_ALLOWED.getCode(),
                        BpmErrorCode.LIGHT_PROCESS_CYCLE.getCode());
    }

    // ---------- helpers ----------

    private static GraphElement node(String id, String type) {
        return GraphElement.builder().id(id).kind("node").type(type)
                .config(Map.of()).style(Map.of()).build();
    }

    private static GraphElement edge(String id, String source, String target) {
        return GraphElement.builder().id(id).kind("edge").source(source).target(target)
                .config(Map.of()).style(Map.of()).build();
    }
}
