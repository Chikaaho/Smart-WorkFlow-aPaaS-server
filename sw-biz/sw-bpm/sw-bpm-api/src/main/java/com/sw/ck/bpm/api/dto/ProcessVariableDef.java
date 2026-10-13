package com.sw.ck.bpm.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * P64 BPM 变量定义 —— 流程图文档级配置（发布时随 {@code sw_bpm_process_def_version.graph_json} 冻结）。
 * <p>
 * 稳定引用 = {@code varId}（显示名 {@code name} 修改不改变引用）；内部表名列名不进入业务界面。
 * 旧图缺省该字段 = 无变量、零行为（向后兼容）。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProcessVariableDef implements Serializable {

    /** 稳定引用 ID（发布校验要求非空且同图唯一，建议 var_ 前缀）。 */
    private String varId;

    /** 业务显示名。 */
    private String name;

    /** 值类型：NUMBER / STRING / BOOLEAN / USER / DEPT / USER_SET / DEPT_SET / ROWS。 */
    private String type;

    /** 来源：MAIN_FORM（主业务表单字段）/ NODE_FORM（节点表单本轮有效提交）/ SYSTEM（只读白名单）。 */
    private String source;

    /** MAIN_FORM：主业务表单字段名。 */
    private String sourceField;

    /** NODE_FORM：来源节点 key（须为已绑定节点业务表单的节点）。 */
    private String sourceNodeKey;

    /** NODE_FORM：节点表单字段名。 */
    private String sourceFormField;

    /** 轮次口径：CURRENT（本轮有效提交；阶段Ⅰ唯一取值）。 */
    private String roundRule;

    /** 聚合规则：NONE（标量/单任务）/ UNION（USER/DEPT 集合并集去重）/ CONCAT（ROWS 拼接）。 */
    private String aggregation;

    /** 缺值处置：true = 可空变量返回 null；false = 必填变量缺失阻止触发（可诊断失败）。 */
    private Boolean nullable;
}
