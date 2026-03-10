package com.rhizodelta.domain.embedding;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record NeighborInfo(
        @JsonProperty("node_id") UUID node_id,
        @JsonProperty("label") String label,
        @JsonProperty("relationship_type") String relationship_type
) {
}
