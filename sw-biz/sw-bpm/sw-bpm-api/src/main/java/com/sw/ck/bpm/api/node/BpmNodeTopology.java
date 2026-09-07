package com.sw.ck.bpm.api.node;

/**
 * 节点拓扑约束。
 *
 * @param minIncoming 最小入边数
 * @param maxIncoming 最大入边数，{@link Integer#MAX_VALUE} 表示不限制
 * @param minOutgoing 最小出边数
 * @param maxOutgoing 最大出边数，{@link Integer#MAX_VALUE} 表示不限制
 */
public record BpmNodeTopology(int minIncoming, int maxIncoming, int minOutgoing, int maxOutgoing) {
}
