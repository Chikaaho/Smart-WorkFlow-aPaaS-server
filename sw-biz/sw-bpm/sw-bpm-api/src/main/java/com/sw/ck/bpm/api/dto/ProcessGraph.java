package com.sw.ck.bpm.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 流程定义图模型 —— 设计器核心数据结构。
 * <p>
 * 后端仅解释拓扑（id/kind/type/source/target），{@code config} 与 {@code style}
 * 为不透明 Map 原样透传，严禁在后端解析其内部字段。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessGraph implements Serializable {

    /** 流程业务 key（发布后冻结，本刀不校验冻结）。 */
    private String processKey;

    /** 流程名称。 */
    private String name;

    /** 绑定表单 formKey。 */
    private String formKey;

    /** 版本号（默认 1）。 */
    private Integer version;

    /** 图元素列表（节点 + 边）。 */
    private List<GraphElement> elements;

    /** 图契约版本（I3 起第一方设计器显式坐标契约 = 2；null = 旧版遗留图）。 */
    private Integer contractVersion;

    /** 画布元数据（不透明，原样透传）。 */
    private Map<String, Object> canvas;
}
