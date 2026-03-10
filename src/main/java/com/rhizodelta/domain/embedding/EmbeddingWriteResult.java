package com.rhizodelta.domain.embedding;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record EmbeddingWriteResult(
        @JsonProperty("node_id") UUID node_id,
        @JsonProperty("dimension") int dimension
) {
}
