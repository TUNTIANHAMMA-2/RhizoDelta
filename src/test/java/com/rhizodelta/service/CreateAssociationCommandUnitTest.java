package com.rhizodelta.service;

import com.rhizodelta.core.domain.association.AssociationInfo;
import com.rhizodelta.core.domain.association.AssociationType;
import com.rhizodelta.core.domain.association.CreateAssociationCommand;
import com.rhizodelta.consensus.domain.audit.AuditDetail;
import com.rhizodelta.consensus.domain.audit.AuditListResponse;
import com.rhizodelta.consensus.domain.decision.BranchDecisionCommand;
import com.rhizodelta.consensus.domain.decision.DecisionOperatorType;
import com.rhizodelta.consensus.domain.decision.DecisionResult;
import com.rhizodelta.consensus.domain.decision.DecisionType;
import com.rhizodelta.consensus.domain.decision.MergeDecisionCommand;
import com.rhizodelta.consensus.domain.decision.RollbackResult;
import com.rhizodelta.consensus.domain.exception.DagIntegrityViolationException;
import com.rhizodelta.consensus.domain.exception.RollbackBlockedException;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreateAssociationCommandUnitTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldRejectNullSourceNodeId() {
        assertThatThrownBy(() -> new CreateAssociationCommand(
                null,
                UUID.randomUUID(),
                AssociationType.CONCEPTUAL_OVERLAP,
                "creator-1",
                "reason",
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source_node_id");
    }

    @Test
    void shouldRejectBlankSourceNodeIdOnDeserialization() {
        String requestJson = """
                {
                  "source_node_id": "",
                  "target_node_id": "%s",
                  "type": "RELATES_TO",
                  "creator_id": "creator-1",
                  "reason": "reason"
                }
                """.formatted(UUID.randomUUID());

        assertThatThrownBy(() -> OBJECT_MAPPER.readValue(requestJson, CreateAssociationCommand.class))
                .isInstanceOf(Exception.class);
    }

    @Test
    void shouldRejectNullTargetNodeId() {
        assertThatThrownBy(() -> new CreateAssociationCommand(
                UUID.randomUUID(),
                null,
                AssociationType.CONCEPTUAL_OVERLAP,
                "creator-1",
                "reason",
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target_node_id");
    }

    @Test
    void shouldRejectNullAssociationType() {
        assertThatThrownBy(() -> new CreateAssociationCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "creator-1",
                "reason",
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
    }

    @Test
    void shouldRejectInvalidAssociationTypeOnDeserialization() {
        String requestJson = """
                {
                  "source_node_id": "%s",
                  "target_node_id": "%s",
                  "type": "INVALID",
                  "creator_id": "creator-1",
                  "reason": "reason"
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> OBJECT_MAPPER.readValue(requestJson, CreateAssociationCommand.class))
                .isInstanceOf(Exception.class);
    }

    @Test
    void shouldRejectBlankCreatorId() {
        assertThatThrownBy(() -> new CreateAssociationCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                AssociationType.RELATES_TO,
                " ",
                "reason",
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("creator_id");
    }

    @Test
    void shouldRejectBlankReason() {
        assertThatThrownBy(() -> new CreateAssociationCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                AssociationType.RELATES_TO,
                "creator-1",
                " ",
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void shouldRejectConfidenceBelowZero() {
        assertThatThrownBy(() -> new CreateAssociationCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                AssociationType.CONCEPTUAL_OVERLAP,
                "creator-1",
                "reason",
                -0.1f
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence");
    }

    @Test
    void shouldRejectConfidenceAboveOne() {
        assertThatThrownBy(() -> new CreateAssociationCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                AssociationType.CONCEPTUAL_OVERLAP,
                "creator-1",
                "reason",
                1.1f
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence");
    }

    @Test
    void shouldAcceptNullConfidence() {
        CreateAssociationCommand command = new CreateAssociationCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                AssociationType.CONCEPTUAL_OVERLAP,
                "creator-1",
                "reason",
                null
        );

        assertThat(command.confidence()).isNull();
    }
}
