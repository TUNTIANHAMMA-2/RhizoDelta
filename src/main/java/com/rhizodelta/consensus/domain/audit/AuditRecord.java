package com.rhizodelta.consensus.domain.audit;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.core.validation.DecisionCommandValidation;
import com.rhizodelta.consensus.domain.decision.DecisionOperatorType;
import com.rhizodelta.consensus.domain.decision.DecisionType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AuditRecord(
        @JsonProperty("decision_id") String decision_id,
        @JsonProperty("decision_type") DecisionType decision_type,
        @JsonProperty("node_id") UUID node_id,
        @JsonProperty("source_node_id") UUID source_node_id,
        @JsonProperty("operator_type") DecisionOperatorType operator_type,
        @JsonProperty("operator_id") String operator_id,
        @JsonProperty("reason") String reason,
        @JsonProperty("created_at") Instant created_at
) {
    public AuditRecord {
        decision_id = DecisionCommandValidation.requireText(decision_id, "decision_id");
        decision_type = Objects.requireNonNull(decision_type, "decision_type must not be null");
        node_id = DecisionCommandValidation.requireUuid(node_id, "node_id");
        source_node_id = DecisionCommandValidation.requireUuid(source_node_id, "source_node_id");
        operator_type = DecisionCommandValidation.requireOperatorType(operator_type);
        operator_id = DecisionCommandValidation.requireText(operator_id, "operator_id");
        reason = DecisionCommandValidation.requireText(reason, "reason");
        created_at = Objects.requireNonNull(created_at, "created_at must not be null");
    }
}
