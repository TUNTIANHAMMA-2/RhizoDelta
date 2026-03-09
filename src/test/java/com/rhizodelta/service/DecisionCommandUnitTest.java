package com.rhizodelta.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionCommandUnitTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void mergeCommandShouldRejectBlankDecisionId() {
        assertThatThrownBy(() -> new MergeDecisionCommand(
                " ",
                "req-1",
                UUID.randomUUID(),
                "gpt-1",
                "summary",
                List.of(UUID.randomUUID()),
                DecisionOperatorType.AGENT,
                "agent-1",
                "reason"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decision_id");
    }

    @Test
    void mergeCommandShouldRejectEmptySynthesizedFrom() {
        assertThatThrownBy(() -> new MergeDecisionCommand(
                "dec-1",
                "req-1",
                UUID.randomUUID(),
                "gpt-1",
                "summary",
                List.of(),
                DecisionOperatorType.AGENT,
                "agent-1",
                "reason"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("synthesized_from");
    }

    @Test
    void mergeCommandShouldRejectNullOperatorType() {
        assertThatThrownBy(() -> new MergeDecisionCommand(
                "dec-1",
                "req-1",
                UUID.randomUUID(),
                "gpt-1",
                "summary",
                List.of(UUID.randomUUID()),
                null,
                "agent-1",
                "reason"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operator_type");
    }

    @Test
    void branchCommandShouldRejectBlankContent() {
        assertThatThrownBy(() -> new BranchDecisionCommand(
                "dec-1",
                "req-1",
                UUID.randomUUID(),
                " ",
                "author-1",
                DecisionOperatorType.HUMAN,
                "operator-1",
                "reason"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content");
    }

    @Test
    void validCommandsShouldExposeAllFields() {
        UUID sourceNodeId = UUID.randomUUID();
        UUID synthesizedNodeId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();

        MergeDecisionCommand mergeCommand = new MergeDecisionCommand(
                "dec-merge-1",
                "req-merge-1",
                sourceNodeId,
                "gpt-4.1",
                "summary",
                List.of(synthesizedNodeId),
                DecisionOperatorType.AGENT,
                "agent-1",
                "merge"
        );
        BranchDecisionCommand branchCommand = new BranchDecisionCommand(
                "dec-branch-1",
                "req-branch-1",
                sourceNodeId,
                "new branch",
                "author-1",
                DecisionOperatorType.HUMAN,
                "human-1",
                "branch"
        );
        DecisionResult result = new DecisionResult("dec-merge-1", nodeId, "QUEUED");

        assertThat(mergeCommand.decision_id()).isEqualTo("dec-merge-1");
        assertThat(mergeCommand.synthesized_from()).containsExactly(synthesizedNodeId);
        assertThat(branchCommand.author_id()).isEqualTo("author-1");
        assertThat(result.node_id()).isEqualTo(nodeId);
    }

    @Test
    void mergeCommandShouldDeserializeAndSerializeWithSnakeCaseFields() throws Exception {
        UUID sourceNodeId = UUID.randomUUID();
        UUID synthesizedNodeId = UUID.randomUUID();
        String requestJson = """
                {
                  "decision_id": "dec-serde-1",
                  "request_id": "req-serde-1",
                  "source_node_id": "%s",
                  "agent_version": "gpt-4.1",
                  "summary_content": "summary",
                  "synthesized_from": ["%s"],
                  "operator_type": "AGENT",
                  "operator_id": "agent-1",
                  "reason": "merge"
                }
                """.formatted(sourceNodeId, synthesizedNodeId);

        MergeDecisionCommand command = OBJECT_MAPPER.readValue(requestJson, MergeDecisionCommand.class);
        String responseJson = OBJECT_MAPPER.writeValueAsString(command);

        assertThat(command.source_node_id()).isEqualTo(sourceNodeId);
        assertThat(responseJson).contains("\"decision_id\":\"dec-serde-1\"");
        assertThat(responseJson).contains("\"source_node_id\":\"" + sourceNodeId + "\"");
    }
}
