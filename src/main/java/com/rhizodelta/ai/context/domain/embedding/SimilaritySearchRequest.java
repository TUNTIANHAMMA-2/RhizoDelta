package com.rhizodelta.ai.context.domain.embedding;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record SimilaritySearchRequest(
        @JsonProperty("vector") List<Float> vector,
        @JsonProperty("top_k") Integer top_k
) {
}
