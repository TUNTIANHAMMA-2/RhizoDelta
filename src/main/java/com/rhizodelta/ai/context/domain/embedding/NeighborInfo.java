package com.rhizodelta.ai.context.domain.embedding;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * 表示相似节点的一个邻居摘要。
 *
 * <p>该对象用于为相似度召回结果补充轻量图上下文，
 * 帮助上层理解候选节点在图中的直接连接关系。
 */
public record NeighborInfo(
        @JsonProperty("node_id") UUID node_id,
        @JsonProperty("label") String label,
        @JsonProperty("relationship_type") String relationship_type
) {
}
