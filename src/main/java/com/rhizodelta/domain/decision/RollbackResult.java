package com.rhizodelta.domain.decision;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.domain.DecisionCommandValidation;

import java.util.UUID;

public record RollbackResult(
        @JsonProperty("decision_id") String decision_id,
        @JsonProperty("rolled_back_node_id") UUID rolled_back_node_id,
        @JsonProperty("relationships_removed") long relationships_removed
) {
    public RollbackResult {
        decision_id = DecisionCommandValidation.requireText(decision_id, "decision_id");
        rolled_back_node_id = DecisionCommandValidation.requireUuid(rolled_back_node_id, "rolled_back_node_id");
        if (relationships_removed < 0) {
            throw new IllegalArgumentException("relationships_removed must be >= 0");
        }
    }
}
