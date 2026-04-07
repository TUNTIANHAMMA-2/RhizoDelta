package com.rhizodelta.consensus.domain.decision;

import com.rhizodelta.consensus.domain.decision.DecisionOperatorType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.core.validation.DecisionCommandValidation;

import java.util.List;
import java.util.UUID;

public record ForkDecisionCommand(
        @JsonProperty("operation_id") String operation_id,
        @JsonProperty("request_id") String request_id,
        @JsonProperty("source_node_id") UUID source_node_id,
        @JsonProperty("branches") List<ForkBranchSpec> branches,
        @JsonProperty("operator_type") DecisionOperatorType operator_type,
        @JsonProperty("operator_id") String operator_id,
        @JsonProperty("reason") String reason
) {
    public ForkDecisionCommand {
        operation_id = DecisionCommandValidation.requireText(operation_id, "operation_id");
        request_id = DecisionCommandValidation.requireText(request_id, "request_id");
        source_node_id = DecisionCommandValidation.requireUuid(source_node_id, "source_node_id");
        if (branches == null || branches.isEmpty()) {
            throw new IllegalArgumentException("branches must not be empty");
        }
        // [MVP Design Note]: Previously required branches.size() >= 2.
        // Changed to allow size >= 1 to support real-world interactions where a user
        // creates a single branch (alternative) at a time based on a specific node.
        // The topological relationship (NewNode -[:BRANCHED_FROM]-> SourceNode) is
        // intentionally preserved in the database to maintain temporal causality and provenance.
        // The UI (frontend) is responsible for rendering these causal children as
        // logical siblings on parallel tracks.
        branches = List.copyOf(branches);
        operator_type = DecisionCommandValidation.requireOperatorType(operator_type);
        operator_id = DecisionCommandValidation.requireText(operator_id, "operator_id");
        reason = DecisionCommandValidation.requireText(reason, "reason");
    }

    public record ForkBranchSpec(
            @JsonProperty("decision_id") String decision_id,
            @JsonProperty("content") String content,
            @JsonProperty("author_id") String author_id
    ) {
        public ForkBranchSpec {
            decision_id = DecisionCommandValidation.requireText(decision_id, "decision_id");
            content = DecisionCommandValidation.requireText(content, "content");
            author_id = DecisionCommandValidation.requireText(author_id, "author_id");
        }
    }
}
