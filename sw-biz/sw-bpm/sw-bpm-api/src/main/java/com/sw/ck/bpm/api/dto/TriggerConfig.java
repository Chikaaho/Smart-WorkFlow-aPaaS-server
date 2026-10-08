package com.sw.ck.bpm.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * P64 Trigger 判断配置 —— 流程图文档级配置，随发布版本冻结（ADR-P64-001 §3）。
 * <p>
 * 事件绑定明确：TASK_SUBMITTED（逐任务，须显式选择）/ NODE_ROUND_COMPLETED（默认按节点本轮
 * 完成触发一次）/ PROCESS_COMPLETED（流程合法完成，仅 APPROVED 终态）。
 * 脚本只判断：受控上下文只读 {@code variables} 快照，返回有限 Number/String/Boolean；
 * null/异常/超时/超限/未匹配进入可查看处置，不自动走成功路径。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TriggerConfig implements Serializable {

    /** 触发器稳定 ID（同图唯一）。 */
    private String triggerId;

    /** 业务名称。 */
    private String name;

    /** 事件：TASK_SUBMITTED / NODE_ROUND_COMPLETED / PROCESS_COMPLETED。 */
    private String event;

    /** 事件源节点 key（TASK_SUBMITTED/NODE_ROUND_COMPLETED 必填；PROCESS_COMPLETED 留空）。 */
    private String nodeKey;

    /** 判断脚本（顶层 {@code function handle(variables) { ... }} 或以 variables 为上下文的表达体）。 */
    private String script;

    /** 脚本可读取的变量 varId 列表（越权读取在解析层拒绝：未列入的变量不进入快照）。 */
    private List<String> variables;

    /** 结果分支（matchType+matchValue 同类型同值精确匹配；同类型同值重复发布拒绝）。 */
    private List<TriggerBranch> branches;

    /** 未匹配处置：HALT（记录并可诊断，不动作；默认）/ IGNORE（记录后继续，仍不动作）。 */
    private String unmatchedDisposition;

    /** 异常处置：HALT（默认）/ IGNORE —— 均只记录，不自动产生动作。 */
    private String errorDisposition;

    /**
     * 结果分支。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TriggerBranch implements Serializable {

        /** 分支 ID（同触发内唯一）。 */
        private String branchId;

        /** 业务名称。 */
        private String name;

        /** 匹配类型：NUMBER / STRING / BOOLEAN（与脚本返回值同类型才比较）。 */
        private String matchType;

        /** 匹配值（按 matchType 解释；NUMBER 数值相等、STRING/BOOLEAN 全等）。 */
        private String matchValue;

        /** 命中后执行的动作列表。 */
        private List<ActionConfig> actions;
    }
}
