package com.sw.ck.bpm.api.node;

/**
 * 节点能力清单中供设计端判断阶段可用性的稳定布尔契约。
 */
public record BpmNodeSupports(
        boolean design,
        boolean save,
        boolean publish,
        boolean run) {
}
