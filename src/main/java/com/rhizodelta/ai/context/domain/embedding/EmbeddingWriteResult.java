package com.rhizodelta.ai.context.domain.embedding;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * 表示 embedding 写入结果。
 *
 * <p>该对象用于向调用方确认：哪个节点的向量已被接受，以及最终写入的维度是多少。
 */
public record EmbeddingWriteResult(
        @JsonProperty("node_id") UUID node_id,
        @JsonProperty("dimension") int dimension
) {
}
