package com.rhizodelta.ai.context.domain.embedding;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 表示一次相似度检索返回的候选节点。
 *
 * <p>该对象除了返回节点基本信息和相似度分值，还携带邻居摘要，
 * 供上下文裁剪、路由判断和提示词构建阶段使用。
 */
public record SimilaritySearchResult(
        @JsonProperty("node_id") UUID node_id,
        @JsonProperty("label") String label,
        @JsonProperty("score") Double score,
        @JsonProperty("content") String content,
        @JsonProperty("created_at") Instant created_at,
        @JsonProperty("neighbors") List<NeighborInfo> neighbors
) {
}
