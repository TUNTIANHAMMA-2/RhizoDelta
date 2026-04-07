package com.rhizodelta.consensus.domain.decision;

import com.rhizodelta.consensus.domain.decision.DecisionOperatorType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.core.validation.DecisionCommandValidation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CrossSynthDecisionCommand(
        @JsonProperty("decision_id") String decision_id,
        @JsonProperty("request_id") String request_id,
        @JsonProperty("source_result_ids") List<UUID> source_result_ids,
        @JsonProperty("content") String content,
        @JsonProperty("operator_type") DecisionOperatorType operator_type,
        @JsonProperty("operator_id") String operator_id,
        @JsonProperty("reason") String reason
) {
    public CrossSynthDecisionCommand {
        decision_id = DecisionCommandValidation.requireText(decision_id, "decision_id");
        request_id = DecisionCommandValidation.requireText(request_id, "request_id");
        if (source_result_ids == null || source_result_ids.size() < 2) {
            throw new IllegalArgumentException("source_result_ids must contain at least 2 entries");
        }
        if (source_result_ids.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("source_result_ids must not contain null");
        }
        source_result_ids = List.copyOf(source_result_ids);
        content = DecisionCommandValidation.requireText(content, "content");
        operator_type = DecisionCommandValidation.requireOperatorType(operator_type);
        operator_id = DecisionCommandValidation.requireText(operator_id, "operator_id");
        reason = DecisionCommandValidation.requireText(reason, "reason");
    }
}
