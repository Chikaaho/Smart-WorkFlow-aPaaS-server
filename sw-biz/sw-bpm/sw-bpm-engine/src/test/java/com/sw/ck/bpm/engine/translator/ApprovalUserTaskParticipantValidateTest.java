package com.sw.ck.bpm.engine.translator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.GraphValidationError;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 审批节点参与人取值缺失回归（0.3.0）：应用包导入会按设计把用户/部门参与人置空，
 * 校验必须返回结构化错误，而不是 List.of(null) 触发的 NPE（500）。
 */
class ApprovalUserTaskParticipantValidateTest {

    private final ApprovalUserTaskTranslator translator =
            new ApprovalUserTaskTranslator(new ObjectMapper());

    private static GraphElement approval(Map<String, Object> config) {
        return GraphElement.builder()
                .id("node_approval")
                .kind("node")
                .type("APPROVAL")
                .config(config)
                .style(Map.of())
                .build();
    }

    private static Map<String, Object> participant(String strategy, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put("strategy", strategy);
        map.put("value", value);
        return map;
    }

    @Test
    void nullParticipantValueReturnsValidationErrorInsteadOfNpe() {
        Map<String, Object> config = new HashMap<>();
        config.put("name", "会签");
        config.put("participant", participant("ROLE", null));

        assertThatCode(() -> translator.validateConfig(approval(config)))
                .doesNotThrowAnyException();
        List<GraphValidationError> errors = translator.validateConfig(approval(config)).orElseThrow();
        assertThat(errors).anyMatch(e -> e.getMessage().contains("审批人取值不能为空"));
    }

    @Test
    void blankParticipantValueIsRejected() {
        Map<String, Object> config = new HashMap<>();
        config.put("participant", participant("DEPT_LEADER", "  "));
        List<GraphValidationError> errors = translator.validateConfig(approval(config)).orElseThrow();
        assertThat(errors).anyMatch(e -> e.getMessage().contains("审批人取值不能为空"));
    }

    @Test
    void validRoleParticipantPasses() {
        Map<String, Object> config = new HashMap<>();
        config.put("participant", participant("ROLE", List.of("drill_reviewer")));
        List<GraphValidationError> errors = translator.validateConfig(approval(config)).orElseThrow();
        assertThat(errors).isEmpty();
    }
}
