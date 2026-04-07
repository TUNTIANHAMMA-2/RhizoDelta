package com.rhizodelta.consensus.domain.decision;

import com.rhizodelta.consensus.domain.decision.DecisionOperatorType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.core.validation.DecisionCommandValidation;

import java.util.UUID;

public record InjectDecisionCommand(
        @JsonProperty("decision_id") String decision_id,
        @JsonProperty("request_id") String request_id,
        @JsonProperty("source_node_id") UUID source_node_id,
        @JsonProperty("content") String content,
        @JsonProperty("author_id") String author_id,
        @JsonProperty("operator_type") DecisionOperatorType operator_type,
        @JsonProperty("operator_id") String operator_id,
        @JsonProperty("reason") String reason
) {
    public InjectDecisionCommand {
        decision_id = DecisionCommandValidation.requireText(decision_id, "decision_id");
        request_id = DecisionCommandValidation.requireText(request_id, "request_id");
        source_node_id = DecisionCommandValidation.requireUuid(source_node_id, "source_node_id");
        content = DecisionCommandValidation.requireText(content, "content");
        author_id = DecisionCommandValidation.requireText(author_id, "author_id");
        operator_type = DecisionCommandValidation.requireOperatorType(operator_type);
        operator_id = DecisionCommandValidation.requireText(operator_id, "operator_id");
        reason = DecisionCommandValidation.requireText(reason, "reason");
    }
}
