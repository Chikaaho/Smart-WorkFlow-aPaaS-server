package com.sw.ck.bpm.process.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 触发器受控预览结果。
 * <p>
 * 快照失败时只返回 variables/tooLarge/missingRequired/errors/kind；
 * 正常评估时再返回脚本结果与匹配分支。空值字段按原 Map 契约省略。
 * </p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TriggerPreviewDTO {

    private Map<String, Object> variables;

    private boolean tooLarge;

    private List<String> missingRequired;

    private List<String> errors;

    private String kind;

    private Object value;

    private String resultType;

    private String errorMessage;

    private long durationMs;

    private Map<String, Object> variableWrites;

    private String matchedBranchId;

    private List<MatchedActionDTO> matchedActions;

    /** 命中分支的动作摘要。 */
    @Data
    @AllArgsConstructor
    public static class MatchedActionDTO {
        private String actionId;
        private String type;
        private String targetDefKey;
    }
}
