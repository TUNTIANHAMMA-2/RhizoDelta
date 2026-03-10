package com.rhizodelta.domain.association;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.domain.DecisionCommandValidation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AssociationResult(
        @JsonProperty("association_id") UUID association_id,
        @JsonProperty("source_node_id") UUID source_node_id,
        @JsonProperty("target_node_id") UUID target_node_id,
        @JsonProperty("type") AssociationType type,
        @JsonProperty("confidence") Float confidence,
        @JsonProperty("reason") String reason,
        @JsonProperty("creator_id") String creator_id,
        @JsonProperty("created_at") Instant created_at
) {
    public AssociationResult {
        association_id = DecisionCommandValidation.requireUuid(association_id, "association_id");
        source_node_id = DecisionCommandValidation.requireUuid(source_node_id, "source_node_id");
        target_node_id = DecisionCommandValidation.requireUuid(target_node_id, "target_node_id");
        type = Objects.requireNonNull(type, "type must not be null");
        created_at = Objects.requireNonNull(created_at, "created_at must not be null");
    }
}
