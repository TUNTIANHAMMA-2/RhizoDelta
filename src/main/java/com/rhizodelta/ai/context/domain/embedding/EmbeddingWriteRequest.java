package com.rhizodelta.ai.context.domain.embedding;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record EmbeddingWriteRequest(
        @JsonProperty("vector") List<Float> vector
) {
}
