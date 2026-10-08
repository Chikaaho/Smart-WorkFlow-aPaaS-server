package com.sw.ck.bpm.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * P64 配置化动作 —— Trigger 分支命中后的派发配置（阶段Ⅰ仅独立关联流程发起）。
 * <p>
 * 派发时冻结集合与映射；可靠意图经 {@code sw_bpm_command}(ORCH_ACTION_START) 同事务登记。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ActionConfig implements Serializable {

    /** 动作稳定 ID（同触发内唯一）。 */
    private String actionId;

    /** 业务名称。 */
    private String name;

    /** 动作类型：START_SINGLE / START_EACH / START_GROUPED。 */
    private String type;

    /** START_EACH / START_GROUPED 的集合来源变量 varId（USER_SET/DEPT_SET/ROWS）。 */
    private String sourceVariable;

    /** START_GROUPED 分组稳定身份：USER/DEPT 项按对象 ID 天然分组；ROWS 按指定列名分组。 */
    private String groupBy;

    /** 目标流程定义 key（须已发布且绑定表单）。 */
    private String targetProcessDefKey;

    /** 目标表单 formKey（= 目标流程绑定表单；映射写入该表单新记录）。 */
    private String targetFormKey;

    /** 单次派发上限（默认 50；服务端硬上限 200；超限整体拒绝不截断）。 */
    private Integer maxDispatch;

    /** 输入映射：目标表单字段 ← 来源值。 */
    private List<ActionMapping> mapping;

    /**
     * 输入映射项。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ActionMapping implements Serializable {

        /** 目标表单字段名。 */
        private String targetField;

        /** 来源变量 varId（与 itemField/literal 三选一）。 */
        private String sourceVarId;

        /** 集合项字段（ROWS 行列名；USER/DEPT 项固定取对象 ID）。 */
        private String itemField;

        /** 字面量值（JSON 标量）。 */
        private Object literal;
    }
}
