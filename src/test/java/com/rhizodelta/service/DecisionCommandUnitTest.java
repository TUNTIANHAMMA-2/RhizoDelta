package com.rhizodelta.service;

import com.rhizodelta.core.domain.association.AssociationInfo;
import com.rhizodelta.core.domain.association.AssociationType;
import com.rhizodelta.core.domain.association.CreateAssociationCommand;
import com.rhizodelta.consensus.domain.audit.AuditDetail;
import com.rhizodelta.consensus.domain.audit.AuditListResponse;
import com.rhizodelta.consensus.domain.decision.BranchDecisionCommand;
import com.rhizodelta.consensus.domain.decision.CrossSynthDecisionCommand;
import com.rhizodelta.consensus.domain.decision.DecisionOperatorType;
import com.rhizodelta.consensus.domain.decision.DecisionResult;
import com.rhizodelta.consensus.domain.decision.DecisionType;
import com.rhizodelta.consensus.domain.decision.ForkDecisionCommand;
import com.rhizodelta.consensus.domain.decision.InjectDecisionCommand;
import com.rhizodelta.consensus.domain.decision.JoinDecisionCommand;
import com.rhizodelta.consensus.domain.decision.MaterializeDecisionCommand;
import com.rhizodelta.consensus.domain.decision.MergeDecisionCommand;
import com.rhizodelta.consensus.domain.decision.RollbackResult;
import com.rhizodelta.consensus.domain.exception.DagIntegrityViolationException;
import com.rhizodelta.consensus.domain.exception.RollbackBlockedException;

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

    // --- InjectDecisionCommand ---

    @Test
    void injectCommandShouldRejectBlankDecisionId() {
        assertThatThrownBy(() -> new InjectDecisionCommand(
                " ", "req-1", UUID.randomUUID(), "content", "author-1",
                DecisionOperatorType.HUMAN, "op-1", "reason"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decision_id");
    }

    @Test
    void injectCommandShouldRejectBlankContent() {
        assertThatThrownBy(() -> new InjectDecisionCommand(
                "dec-1", "req-1", UUID.randomUUID(), " ", "author-1",
                DecisionOperatorType.HUMAN, "op-1", "reason"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content");
    }

    @Test
    void injectCommandShouldRejectNullOperatorType() {
        assertThatThrownBy(() -> new InjectDecisionCommand(
                "dec-1", "req-1", UUID.randomUUID(), "content", "author-1",
                null, "op-1", "reason"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operator_type");
    }

    @Test
    void injectCommandShouldSerdeWithSnakeCaseFields() throws Exception {
        UUID sourceNodeId = UUID.randomUUID();
        String json = """
                {
                  "decision_id": "dec-inject-1",
                  "request_id": "req-inject-1",
                  "source_node_id": "%s",
                  "content": "injected",
                  "author_id": "author-1",
                  "operator_type": "HUMAN",
                  "operator_id": "human-1",
                  "reason": "inject"
                }
                """.formatted(sourceNodeId);
        InjectDecisionCommand command = OBJECT_MAPPER.readValue(json, InjectDecisionCommand.class);
        String out = OBJECT_MAPPER.writeValueAsString(command);
        assertThat(command.source_node_id()).isEqualTo(sourceNodeId);
        assertThat(out).contains("\"decision_id\":\"dec-inject-1\"");
    }

    // --- MaterializeDecisionCommand ---

    @Test
    void materializeCommandShouldRejectBlankDecisionId() {
        assertThatThrownBy(() -> new MaterializeDecisionCommand(
                " ", "req-1", UUID.randomUUID(), "content",
                DecisionOperatorType.AGENT, "op-1", "reason"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decision_id");
    }

    @Test
    void materializeCommandShouldRejectBlankContent() {
        assertThatThrownBy(() -> new MaterializeDecisionCommand(
                "dec-1", "req-1", UUID.randomUUID(), " ",
                DecisionOperatorType.AGENT, "op-1", "reason"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content");
    }

    @Test
    void materializeCommandShouldRejectNullOperatorType() {
        assertThatThrownBy(() -> new MaterializeDecisionCommand(
                "dec-1", "req-1", UUID.randomUUID(), "content",
                null, "op-1", "reason"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operator_type");
    }

    @Test
    void materializeCommandShouldSerdeWithSnakeCaseFields() throws Exception {
        UUID sourceNodeId = UUID.randomUUID();
        String json = """
                {
                  "decision_id": "dec-mat-1",
                  "request_id": "req-mat-1",
                  "source_node_id": "%s",
                  "content": "materialized",
                  "operator_type": "AGENT",
                  "operator_id": "agent-1",
                  "reason": "materialize"
                }
                """.formatted(sourceNodeId);
        MaterializeDecisionCommand command = OBJECT_MAPPER.readValue(json, MaterializeDecisionCommand.class);
        String out = OBJECT_MAPPER.writeValueAsString(command);
        assertThat(command.source_node_id()).isEqualTo(sourceNodeId);
        assertThat(out).contains("\"decision_id\":\"dec-mat-1\"");
    }

    // --- ForkDecisionCommand ---

    @Test
    void forkCommandShouldRejectBlankOperationId() {
        assertThatThrownBy(() -> new ForkDecisionCommand(
                " ", "req-1", UUID.randomUUID(),
                List.of(
                        new ForkDecisionCommand.ForkBranchSpec("b1", "c1", "a1"),
                        new ForkDecisionCommand.ForkBranchSpec("b2", "c2", "a2")),
                DecisionOperatorType.AGENT, "op-1", "reason"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operation_id");
    }

    @Test
    void forkCommandShouldAcceptSingleBranch() {
        ForkDecisionCommand command = new ForkDecisionCommand(
                "fork-1", "req-1", UUID.randomUUID(),
                List.of(new ForkDecisionCommand.ForkBranchSpec("b1", "c1", "a1")),
                DecisionOperatorType.AGENT, "op-1", "reason"
        );
        assertThat(command.branches()).hasSize(1);
    }

    @Test
    void forkCommandShouldRejectNullBranches() {
        assertThatThrownBy(() -> new ForkDecisionCommand(
                "fork-1", "req-1", UUID.randomUUID(), null,
                DecisionOperatorType.AGENT, "op-1", "reason"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
    }

    @Test
    void forkCommandShouldRejectNullOperatorType() {
        assertThatThrownBy(() -> new ForkDecisionCommand(
                "fork-1", "req-1", UUID.randomUUID(),
                List.of(
                        new ForkDecisionCommand.ForkBranchSpec("b1", "c1", "a1"),
                        new ForkDecisionCommand.ForkBranchSpec("b2", "c2", "a2")),
                null, "op-1", "reason"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operator_type");
    }

    @Test
    void forkCommandShouldSerdeWithSnakeCaseFields() throws Exception {
        UUID sourceNodeId = UUID.randomUUID();
        String json = """
                {
                  "operation_id": "fork-op-1",
                  "request_id": "req-fork-1",
                  "source_node_id": "%s",
                  "branches": [
                    {"decision_id": "b1", "content": "c1", "author_id": "a1"},
                    {"decision_id": "b2", "content": "c2", "author_id": "a2"}
                  ],
                  "operator_type": "AGENT",
                  "operator_id": "agent-1",
                  "reason": "fork"
                }
                """.formatted(sourceNodeId);
        ForkDecisionCommand command = OBJECT_MAPPER.readValue(json, ForkDecisionCommand.class);
        assertThat(command.operation_id()).isEqualTo("fork-op-1");
        assertThat(command.branches()).hasSize(2);
        assertThat(command.source_node_id()).isEqualTo(sourceNodeId);
    }

    // --- CrossSynthDecisionCommand ---

    @Test
    void crossSynthCommandShouldRejectBlankDecisionId() {
        assertThatThrownBy(() -> new CrossSynthDecisionCommand(
                " ", "req-1", List.of(UUID.randomUUID(), UUID.randomUUID()), "content",
                DecisionOperatorType.AGENT, "op-1", "reason"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decision_id");
    }

    @Test
    void crossSynthCommandShouldRejectFewerThanTwoSourceResults() {
        assertThatThrownBy(() -> new CrossSynthDecisionCommand(
                "dec-1", "req-1", List.of(UUID.randomUUID()), "content",
                DecisionOperatorType.AGENT, "op-1", "reason"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 2");
    }

    @Test
    void crossSynthCommandShouldRejectNullInSourceResults() {
        List<UUID> idsWithNull = new java.util.ArrayList<>();
        idsWithNull.add(UUID.randomUUID());
        idsWithNull.add(null);
        assertThatThrownBy(() -> new CrossSynthDecisionCommand(
                "dec-1", "req-1", idsWithNull, "content",
                DecisionOperatorType.AGENT, "op-1", "reason"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    void crossSynthCommandShouldRejectNullOperatorType() {
        assertThatThrownBy(() -> new CrossSynthDecisionCommand(
                "dec-1", "req-1", List.of(UUID.randomUUID(), UUID.randomUUID()), "content",
                null, "op-1", "reason"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operator_type");
    }

    @Test
    void crossSynthCommandShouldSerdeWithSnakeCaseFields() throws Exception {
        UUID r1 = UUID.randomUUID();
        UUID r2 = UUID.randomUUID();
        String json = """
                {
                  "decision_id": "dec-cs-1",
                  "request_id": "req-cs-1",
                  "source_result_ids": ["%s", "%s"],
                  "content": "cross-synth",
                  "operator_type": "AGENT",
                  "operator_id": "agent-1",
                  "reason": "cross-synth"
                }
                """.formatted(r1, r2);
        CrossSynthDecisionCommand command = OBJECT_MAPPER.readValue(json, CrossSynthDecisionCommand.class);
        assertThat(command.source_result_ids()).containsExactly(r1, r2);
        assertThat(command.decision_id()).isEqualTo("dec-cs-1");
    }

    // --- JoinDecisionCommand ---

    @Test
    void joinCommandShouldRejectBlankDecisionId() {
        assertThatThrownBy(() -> new JoinDecisionCommand(
                " ", "req-1", List.of(UUID.randomUUID(), UUID.randomUUID()),
                "summary", "gpt-4.1", DecisionOperatorType.AGENT, "op-1", "reason"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decision_id");
    }

    @Test
    void joinCommandShouldRejectFewerThanTwoSourceNodes() {
        assertThatThrownBy(() -> new JoinDecisionCommand(
                "dec-1", "req-1", List.of(UUID.randomUUID()),
                "summary", "gpt-4.1", DecisionOperatorType.AGENT, "op-1", "reason"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 2");
    }

    @Test
    void joinCommandShouldRejectNullInSourceNodes() {
        List<UUID> idsWithNull = new java.util.ArrayList<>();
        idsWithNull.add(UUID.randomUUID());
        idsWithNull.add(null);
        assertThatThrownBy(() -> new JoinDecisionCommand(
                "dec-1", "req-1", idsWithNull, "summary", "gpt-4.1",
                DecisionOperatorType.AGENT, "op-1", "reason"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    void joinCommandShouldRejectNullOperatorType() {
        assertThatThrownBy(() -> new JoinDecisionCommand(
                "dec-1", "req-1", List.of(UUID.randomUUID(), UUID.randomUUID()),
                "summary", "gpt-4.1", null, "op-1", "reason"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operator_type");
    }

    @Test
    void joinCommandShouldSerdeWithSnakeCaseFields() throws Exception {
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        String json = """
                {
                  "decision_id": "dec-join-1",
                  "request_id": "req-join-1",
                  "source_node_ids": ["%s", "%s"],
                  "summary_content": "joined",
                  "agent_version": "gpt-4.1",
                  "operator_type": "AGENT",
                  "operator_id": "agent-1",
                  "reason": "join"
                }
                """.formatted(s1, s2);
        JoinDecisionCommand command = OBJECT_MAPPER.readValue(json, JoinDecisionCommand.class);
        assertThat(command.source_node_ids()).containsExactly(s1, s2);
        assertThat(command.decision_id()).isEqualTo("dec-join-1");
    }
}
