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
     * 阶段Ⅱ（P64 A05/A06）编排语义：CHILD = 主子流程（已发布子模板 + 来源快照 + 输出回写 +
     * 等待策略）；null / INDEPENDENT = 阶段Ⅰ独立关联流程（零行为变化，兼容既有图）。
     */
    private String orchestration;

    /** CHILD 等待策略：ALL / ANY / COUNT / NONE（主方向 §3.3）；独立流程不适用。 */
    private String waitPolicy;

    /** COUNT 策略的正整数 K（K 不得超过本批实际子流程数，运行期结算校验；其余策略忽略）。 */
    private Integer waitCount;

    /** CHILD 输出回写配置（子最终提交数据 → 父来源行/主记录允许字段；仅 CHILD 合法）。 */
    private WriteBackConfig writeBack;

    /** CHILD 动作判定（单一权威，派发/校验/消费共用）。 */
    public boolean isChild() {
        return "CHILD".equalsIgnoreCase(orchestration);
    }

    /**
     * 输出回写配置。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WriteBackConfig implements Serializable {

        /** 子流程结果节点 key（须为子图已绑定节点业务表单的节点；取该节点最新有效轮次最终提交）。 */
        private String resultNodeKey;

        /** 行级回写：子结果表格字段名（子表单 TABLE 字段；每行携带稳定来源行身份）。 */
        private String tableField;

        /** 行级回写：子结果表格中承载来源行 ID 的列名（tableField 配置时必填）。 */
        private String rowKeyField;

        /** 行级回写：父表单承载来源行的 TABLE 字段名（tableField 配置时必填）。 */
        private String parentTableField;

        /** 行级回写列映射：子表格列 → 父表格列（允许字段受控，未列出的列不回写）。 */
        private List<FieldMapping> fields;

        /** 主记录回写字段映射：子结果节点表单字段 → 父主表单字段。 */
        private List<FieldMapping> mainFields;
    }

    /**
     * 输出回写字段映射项（from = 子侧字段/列，to = 父侧字段/列）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FieldMapping implements Serializable {

        /** 子侧来源字段名（节点表单字段或结果表格列）。 */
        private String fromField;

        /** 父侧目标字段名（主表单字段或来源表格列）。 */
        private String toField;
    }

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
