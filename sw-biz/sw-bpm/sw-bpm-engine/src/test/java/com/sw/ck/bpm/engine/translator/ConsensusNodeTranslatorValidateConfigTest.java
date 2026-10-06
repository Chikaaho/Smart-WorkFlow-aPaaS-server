package com.sw.ck.bpm.engine.translator;

import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.GraphValidationError;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会签 validateConfig 回归：mode 缺省（translate 按 ALL 结算）不得 NPE，
 * 只拒绝显式非法值；FORM_FIELD 参与人在会签节点同样按对象契约校验。
 */
class ConsensusNodeTranslatorValidateConfigTest {

    private final ConsensusNodeTranslator translator =
            new ConsensusNodeTranslator(new ObjectMapper());

    private static GraphElement consensus(Map<String, Object> config) {
        return GraphElement.builder()
                .id("node_cs")
                .kind("node")
                .type("CONSENSUS")
                .config(config)
                .style(Map.of())
                .build();
    }

    @Test
    void missingModeIsValidatesAsDefaultAll() {
        // 缺 mode：不可变 List.contains(null) 曾直接 NPE（P63 矩阵流程实爆）
        List<GraphValidationError> errors = translator
                .validateConfig(consensus(Map.of(
                        "name", "会签",
                        "participant", Map.of(
                                "strategy", "FORM_FIELD",
                                "value", Map.of(
                                        "objectType", "USER", "scope", "MAIN",
                                        "field", "field_approvers")))))
                .orElseThrow();
        assertThat(errors).isEmpty();
    }

    @Test
    void explicitIllegalModeIsRejected() {
        List<GraphValidationError> errors = translator
                .validateConfig(consensus(Map.of(
                        "mode", "WHATEVER",
                        "participant", Map.of(
                                "strategy", "FORM_FIELD",
                                "value", Map.of(
                                        "objectType", "USER", "scope", "MAIN",
                                        "field", "field_approvers")))))
                .orElseThrow();
        assertThat(errors).anyMatch(e -> e.getMessage().contains("会签方式不合法"));
    }

    @Test
    void formFieldParticipantWithoutFieldIsRejected() {
        List<GraphValidationError> errors = translator
                .validateConfig(consensus(Map.of(
                        "mode", "ALL",
                        "participant", Map.of(
                                "strategy", "FORM_FIELD",
                                "value", Map.of(
                                        "objectType", "USER", "scope", "MAIN",
                                        "field", " ")))))
                .orElseThrow();
        assertThat(errors).anyMatch(e -> e.getMessage().contains("会签"));
    }
}
