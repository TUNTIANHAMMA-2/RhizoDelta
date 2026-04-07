package com.rhizodelta.ai.context.domain.embedding;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SimilaritySearchResult(
        @JsonProperty("node_id") UUID node_id,
        @JsonProperty("label") String label,
        @JsonProperty("score") Double score,
        @JsonProperty("content") String content,
        @JsonProperty("created_at") Instant created_at,
        @JsonProperty("neighbors") List<NeighborInfo> neighbors
) {
}
