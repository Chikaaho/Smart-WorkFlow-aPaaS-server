package com.sw.ck.bpm.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 图元素 —— 节点或边。
 * <p>
 * 节点：kind="node"，type ∈ {START, END, APPROVAL}（可扩展），source/target 为 null。<br>
 * 边：kind="edge"，type 为 null，通过 source/target 引用两端节点 id。
 * </p>
 * <p>
 * {@code config} 与 {@code style} 为不透明 Map，后端原样存储与透传，严禁解析内部字段。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GraphElement implements Serializable {

    /** 元素唯一标识（设计器分配）。 */
    private String id;

    /** 元素种类："node" | "edge"。 */
    private String kind;

    /** 节点类型（START/END/APPROVAL/…），边为 null。 */
    private String type;

    /** 边起点节点 id（仅边使用，节点为 null）。 */
    private String source;

    /** 边终点节点 id（仅边使用，节点为 null）。 */
    private String target;

    /** 不透明配置（后端不解释）。 */
    private Map<String, Object> config;

    /** 不透明样式（画布样式，原样透传）。 */
    private Map<String, Object> style;

    /** 节点画布 X 坐标（第一方设计器显式契约；旧图缺省时兼容读取 style.x）。 */
    private Double x;

    /** 节点画布 Y 坐标（第一方设计器显式契约；旧图缺省时兼容读取 style.y）。 */
    private Double y;

    /** 边折点列表 [{x,y},...]（第一方设计器显式契约；为空时前端按直线渲染）。 */
    private List<java.util.Map<String, Object>> waypoints;
}
