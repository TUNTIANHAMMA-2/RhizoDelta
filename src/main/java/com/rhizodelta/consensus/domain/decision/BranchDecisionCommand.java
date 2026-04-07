package com.rhizodelta.consensus.domain.decision;

import com.rhizodelta.consensus.domain.decision.DecisionOperatorType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.core.validation.DecisionCommandValidation;

import java.util.List;
import java.util.UUID;

public record BranchDecisionCommand(
        @JsonProperty("decision_id") String decision_id,
        @JsonProperty("request_id") String request_id,
        @JsonProperty("source_node_id") UUID source_node_id,
        @JsonProperty("content") String content,
        @JsonProperty("author_id") String author_id,
        @JsonProperty("operator_type") DecisionOperatorType operator_type,
        @JsonProperty("operator_id") String operator_id,
        @JsonProperty("reason") String reason,
        @JsonProperty("contributor_node_ids") List<UUID> contributor_node_ids
) {
    public BranchDecisionCommand {
        decision_id = DecisionCommandValidation.requireText(decision_id, "decision_id");
        request_id = DecisionCommandValidation.requireText(request_id, "request_id");
        source_node_id = DecisionCommandValidation.requireUuid(source_node_id, "source_node_id");
        content = DecisionCommandValidation.requireText(content, "content");
        author_id = DecisionCommandValidation.requireText(author_id, "author_id");
        operator_type = DecisionCommandValidation.requireOperatorType(operator_type);
        operator_id = DecisionCommandValidation.requireText(operator_id, "operator_id");
        reason = DecisionCommandValidation.requireText(reason, "reason");
        if (contributor_node_ids == null) {
            contributor_node_ids = List.of();
        }
    }

    /** Backwards-compatible constructor for callers without contributor info. */
    public BranchDecisionCommand(
            String decision_id, String request_id, UUID source_node_id,
            String content, String author_id,
            DecisionOperatorType operator_type, String operator_id, String reason
    ) {
        this(decision_id, request_id, source_node_id, content, author_id,
                operator_type, operator_id, reason, List.of());
    }
}
