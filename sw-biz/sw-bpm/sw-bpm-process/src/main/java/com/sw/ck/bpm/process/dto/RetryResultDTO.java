package com.sw.ck.bpm.process.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 动作意图 / 子流程回写恢复结果。
 * <p>
 * 动作意图恢复带 commandId；子流程回写恢复只有 status/message，null 字段省略。
 * </p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RetryResultDTO {

    private Long commandId;

    private String status;

    private String message;
}
