package com.rhizodelta.ai.context.domain.embedding;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 表示一次 embedding 写入请求。
 *
 * <p>该对象用于从 API 层向 {@link com.rhizodelta.ai.context.service.EmbeddingService}
 * 传递待写入的向量数据。
 */
public record EmbeddingWriteRequest(
        @JsonProperty("vector") List<Float> vector
) {
}
