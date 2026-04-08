package com.rhizodelta.ai.context.domain.embedding;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 表示一次相似节点检索请求。
 *
 * <p>该对象承载查询向量和可选的召回数量上限，供向量检索接口和服务层复用。
 */
public record SimilaritySearchRequest(
        @JsonProperty("vector") List<Float> vector,
        @JsonProperty("top_k") Integer top_k
) {
}
