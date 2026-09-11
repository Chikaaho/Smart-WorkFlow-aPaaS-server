package com.sw.ck.bpm.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 图校验结果 —— 单条错误描述。
 * <p>
 * 校验端点返回 {@code List<GraphValidationError>}，空列表表示校验通过。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GraphValidationError implements Serializable {

    /** 出错的元素 id（节点或边）。 */
    private String elementId;

    /** 出错的节点 id（仅节点错误；供设计器定位并聚焦画布节点）。 */
    private String nodeKey;

    /** 出错的边 id（仅边错误；供设计器定位并聚焦画布连线）。 */
    private String edgeKey;

    /** 错误码（对应 {@code BpmErrorCode.code}）。 */
    private int errorCode;

    /** 错误描述。 */
    private String message;
}
