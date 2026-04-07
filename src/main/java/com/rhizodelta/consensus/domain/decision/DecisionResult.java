package com.rhizodelta.consensus.domain.decision;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.core.validation.DecisionCommandValidation;

import java.util.UUID;

public record DecisionResult(
        @JsonProperty("decision_id") String decision_id,
        @JsonProperty("node_id") UUID node_id,
        @JsonProperty("status") String status
) {
    public DecisionResult {
        decision_id = DecisionCommandValidation.requireText(decision_id, "decision_id");
        node_id = DecisionCommandValidation.requireUuid(node_id, "node_id");
        status = DecisionCommandValidation.requireText(status, "status");
    }
}
