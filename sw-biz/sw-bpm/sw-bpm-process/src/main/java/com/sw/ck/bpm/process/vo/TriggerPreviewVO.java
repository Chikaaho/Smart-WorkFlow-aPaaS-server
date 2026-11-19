package com.sw.ck.bpm.process.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Map;
import com.sw.ck.common.value.DynamicData;

/**
 * 触发器受控预览结果。
 * <p>
 * 快照失败时只返回 variables/tooLarge/missingRequired/errors/kind；
 * 正常评估时再返回脚本结果与匹配分支。空值字段按原 Map 契约省略。
 * </p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TriggerPreviewVO {

    private DynamicData variables;

    private boolean tooLarge;

    private List<String> missingRequired;

    private List<String> errors;

    private String kind;

    private DynamicData value;

    private String resultType;

    private String errorMessage;

    private long durationMs;

    private DynamicData variableWrites;

    private String matchedBranchId;

    private List<MatchedActionVO> matchedActions;

    /** 命中分支的动作摘要。 */
    @Data
    @AllArgsConstructor
    public static class MatchedActionVO {
        private String actionId;
        private String type;
        private String targetDefKey;
    }
}
