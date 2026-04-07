package com.rhizodelta.consensus.domain.decision;

import com.rhizodelta.consensus.domain.decision.DecisionOperatorType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.core.validation.DecisionCommandValidation;

import java.util.List;
import java.util.UUID;

public record MergeDecisionCommand(
        @JsonProperty("decision_id") String decision_id,
        @JsonProperty("request_id") String request_id,
        @JsonProperty("source_node_id") UUID source_node_id,
        @JsonProperty("agent_version") String agent_version,
        @JsonProperty("summary_content") String summary_content,
        @JsonProperty("synthesized_from") List<UUID> synthesized_from,
        @JsonProperty("operator_type") DecisionOperatorType operator_type,
        @JsonProperty("operator_id") String operator_id,
        @JsonProperty("reason") String reason
) {
    public MergeDecisionCommand {
        decision_id = DecisionCommandValidation.requireText(decision_id, "decision_id");
        request_id = DecisionCommandValidation.requireText(request_id, "request_id");
        source_node_id = DecisionCommandValidation.requireUuid(source_node_id, "source_node_id");
        agent_version = DecisionCommandValidation.requireText(agent_version, "agent_version");
        summary_content = DecisionCommandValidation.requireText(summary_content, "summary_content");
        synthesized_from = DecisionCommandValidation.requireUuidList(synthesized_from);
        operator_type = DecisionCommandValidation.requireOperatorType(operator_type);
        operator_id = DecisionCommandValidation.requireText(operator_id, "operator_id");
        reason = DecisionCommandValidation.requireText(reason, "reason");
    }
}
