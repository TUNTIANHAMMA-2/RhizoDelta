package com.rhizodelta.consensus.domain.decision;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ForkDecisionResult(
        @JsonProperty("operation_id") String operation_id,
        @JsonProperty("node_ids") List<UUID> node_ids,
        @JsonProperty("status") String status,
        @JsonProperty("created_count") int created_count,
        @JsonProperty("total_count") int total_count
) {
    public ForkDecisionResult {
        Objects.requireNonNull(operation_id, "operation_id must not be null");
        Objects.requireNonNull(node_ids, "node_ids must not be null");
        Objects.requireNonNull(status, "status must not be null");
        node_ids = List.copyOf(node_ids);
    }
}
