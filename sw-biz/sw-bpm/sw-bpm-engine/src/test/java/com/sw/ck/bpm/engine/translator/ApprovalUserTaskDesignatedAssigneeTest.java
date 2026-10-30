package com.sw.ck.bpm.engine.translator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.GraphElement;
import org.flowable.bpmn.model.UserTask;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * DESIGNATED 审批人翻译回归：指定审批人必须落 BPMN 原生 assignee，
 * 保证 Flowable 在任务插入时持久化 ASSIGNEE_（create 监听器内 setAssignee
 * 不落 HI_ACTINST/HI_TASKINST，导致监控流转记录审批人显示 "-"）。
 */
class ApprovalUserTaskDesignatedAssigneeTest {

    private final ApprovalUserTaskTranslator translator =
            new ApprovalUserTaskTranslator(new ObjectMapper());

    private GraphElement approvalNode(Map<String, Object> approver) {
        Map<String, Object> config = new HashMap<>();
        config.put("name", "审批");
        if (approver != null) {
            config.put("approver", approver);
        }
        GraphElement node = new GraphElement();
        node.setId("node_approval");
        node.setKind("node");
        node.setType("APPROVAL");
        node.setConfig(config);
        return node;
    }

    @Test
    void designatedApproverTranslatesToNativeAssignee() {
        UserTask task = (UserTask) translator.translate(
                approvalNode(Map.of("type", "DESIGNATED", "value", List.of("1"))));
        assertEquals("1", task.getAssignee());
    }

    @Test
    void designatedScalarValueTranslatesToNativeAssignee() {
        UserTask task = (UserTask) translator.translate(
                approvalNode(Map.of("type", "DESIGNATED", "value", "42")));
        assertEquals("42", task.getAssignee());
    }

    @Test
    void nonDesignatedTypeLeavesAssigneeToListener() {
        UserTask task = (UserTask) translator.translate(
                approvalNode(Map.of("type", "ROLE", "value", List.of("admin"))));
        assertNull(task.getAssignee());
    }

    @Test
    void missingApproverConfigLeavesAssigneeEmpty() {
        UserTask task = (UserTask) translator.translate(approvalNode(null));
        assertNull(task.getAssignee());
    }
    @Test
    void allowedActionsAreValidatedBeforePublishing() {
        GraphElement node = approvalNode(Map.of("type", "DESIGNATED", "value", List.of("1")));
        node.getConfig().put("allowedActions", List.of());
        assertEquals(0, translator.validateConfig(node).orElseThrow().size());
        node.getConfig().put("allowedActions", List.of("APPROVE", "TRANSFER"));
        assertEquals(0, translator.validateConfig(node).orElseThrow().size());
        node.getConfig().put("allowedActions", List.of("UNKNOWN"));
        assertEquals(1, translator.validateConfig(node).orElseThrow().size());
        node.getConfig().put("allowedActions", "APPROVE");
        assertEquals(1, translator.validateConfig(node).orElseThrow().size());
    }
}
