package com.sw.ck.bpm.api.nodefunc;

import lombok.Builder;
import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点函数输出（I3 §4.9）。
 * <p>
 * 输出只允许：有效用户标识（participantIds）、解释摘要（summary）与
 * 白名单流程结果变量（resultVariables）。超限/非法输出按失败策略处置并审计。
 * </p>
 */
@Getter
@Builder
public class NodeFunctionResult {

    /** 有效用户标识集合（仅 resolveParticipants 类函数输出）。 */
    private List<String> participantIds;

    /** 解释摘要（审计与轨迹展示）。 */
    private String summary;

    /** 白名单流程结果变量（写入节点局部变量）。 */
    @Builder.Default
    private Map<String, Object> resultVariables = new LinkedHashMap<>();

    /** 受控降级结果：BLOCK 中止 / FALLBACK 使用配置结果 / CONTINUE 忽略输出。 */
    private String failureDisposition;
}
