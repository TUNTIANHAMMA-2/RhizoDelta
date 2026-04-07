package com.rhizodelta.core.service;

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

import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssociationServiceUnitTest {
    @Test
    void createAssociationShouldRejectMissingSourceNode() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        AssociationService service = new AssociationService(neo4jClient);
        CreateAssociationCommand command = new CreateAssociationCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                AssociationType.CONCEPTUAL_OVERLAP,
                "creator-1",
                "reason",
                0.5f
        );

        when(neo4jClient.query(argThat((String query) -> query != null && query.contains("RETURN count(node) > 0 AS exists")))
                .bind(anyString()).to("nodeId")
                .fetch().one()).thenReturn(Optional.of(Map.of("exists", false)));

        assertThatThrownBy(() -> service.createAssociation(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source_node_id");
    }

    @Test
    void createAssociationShouldRejectSelfAssociation() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        AssociationService service = new AssociationService(neo4jClient);
        UUID nodeId = UUID.randomUUID();
        CreateAssociationCommand command = new CreateAssociationCommand(
                nodeId,
                nodeId,
                AssociationType.CONCEPTUAL_OVERLAP,
                "creator-1",
                "reason",
                0.5f
        );

        assertThatThrownBy(() -> service.createAssociation(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be different");
    }

    @Test
    void createAssociationShouldReturnExistingResultWhenMergeAlreadyExists() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        AssociationService service = new AssociationService(neo4jClient);
        UUID sourceNodeId = UUID.randomUUID();
        UUID targetNodeId = UUID.randomUUID();
        UUID associationId = UUID.randomUUID();
        CreateAssociationCommand command = new CreateAssociationCommand(
                sourceNodeId,
                targetNodeId,
                AssociationType.CONCEPTUAL_OVERLAP,
                "creator-1",
                "reason",
                0.0f
        );

        when(neo4jClient.query(argThat((String query) -> query != null && query.contains("RETURN count(node) > 0 AS exists")))
                .bind(anyString()).to("nodeId")
                .fetch().one()).thenReturn(Optional.of(Map.of("exists", true)));
        when(neo4jClient.query(argThat((String query) -> query != null && query.contains("association:CONCEPTUAL_OVERLAP")))
                .bindAll(anyMap())
                .fetch().one()).thenReturn(Optional.of(Map.of(
                "associationId", associationId.toString(),
                "sourceNodeId", sourceNodeId.toString(),
                "targetNodeId", targetNodeId.toString(),
                "created", false,
                "createdAt", OffsetDateTime.now(ZoneOffset.UTC),
                "reason", "reason",
                "creatorId", "creator-1"
        )));

        AssociationService.CreateAssociationOutcome outcome = service.createAssociation(command);

        assertThat(outcome.created()).isFalse();
        assertThat(outcome.association().association_id()).isEqualTo(associationId);
        assertThat(outcome.association().type()).isEqualTo(AssociationType.CONCEPTUAL_OVERLAP);
    }

    @Test
    void createAssociationShouldAcceptUpperConfidenceBoundary() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        AssociationService service = new AssociationService(neo4jClient);
        UUID sourceNodeId = UUID.randomUUID();
        UUID targetNodeId = UUID.randomUUID();
        UUID associationId = UUID.randomUUID();
        CreateAssociationCommand command = new CreateAssociationCommand(
                sourceNodeId,
                targetNodeId,
                AssociationType.RELATES_TO,
                "creator-1",
                "reason",
                1.0f
        );

        when(neo4jClient.query(argThat((String query) -> query != null && query.contains("RETURN count(node) > 0 AS exists")))
                .bind(anyString()).to("nodeId")
                .fetch().one()).thenReturn(Optional.of(Map.of("exists", true)));
        when(neo4jClient.query(argThat((String query) -> query != null && query.contains("association:RELATES_TO")))
                .bindAll(anyMap())
                .fetch().one()).thenReturn(Optional.of(Map.of(
                "associationId", associationId.toString(),
                "sourceNodeId", sourceNodeId.toString(),
                "targetNodeId", targetNodeId.toString(),
                "created", true,
                "createdAt", OffsetDateTime.now(ZoneOffset.UTC),
                "reason", "reason",
                "creatorId", "creator-1"
        )));

        AssociationService.CreateAssociationOutcome outcome = service.createAssociation(command);

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.association().association_id()).isEqualTo(associationId);
    }

    @Test
    void deleteAssociationShouldThrowWhenAssociationNotFound() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        AssociationService service = new AssociationService(neo4jClient);
        UUID associationId = UUID.randomUUID();

        when(neo4jClient.query(argThat((String query) -> query != null && query.contains("DELETE association")))
                .bind(eq(associationId.toString())).to("associationId")
                .fetch().one()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteAssociation(associationId))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("association_id not found");
    }

    @Test
    void findAssociationsByNodeIdShouldMapDirectionAndBindTypeFilter() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        AssociationService service = new AssociationService(neo4jClient);
        UUID nodeId = UUID.randomUUID();
        UUID relatedNodeId = UUID.randomUUID();
        UUID associationId = UUID.randomUUID();

        when(neo4jClient.query(argThat((String query) -> query != null && query.contains("RETURN count(node) > 0 AS exists")))
                .bind(eq(nodeId.toString())).to("nodeId")
                .fetch().one()).thenReturn(Optional.of(Map.of("exists", true)));
        Map<String, Object> record = new HashMap<>();
        record.put("associationId", associationId.toString());
        record.put("associationType", "RELATES_TO");
        record.put("direction", "INCOMING");
        record.put("relatedNodeId", relatedNodeId.toString());
        record.put("relatedLabel", "Human_Post");
        record.put("relatedContent", "content");
        record.put("relatedSummaryContent", null);
        record.put("confidence", 0.7d);
        record.put("reason", "reason");
        record.put("creatorId", "creator-1");
        record.put("createdAt", OffsetDateTime.now(ZoneOffset.UTC));
        when(neo4jClient.query(argThat((String query) -> query != null && query.contains("MATCH (node)-[association:CONCEPTUAL_OVERLAP|RELATES_TO]-(related:GraphNode)")))
                .bind(eq(nodeId.toString())).to("nodeId")
                .bind(eq("RELATES_TO")).to("associationType")
                .bind(eq(100)).to("limit")
                .fetch().all()).thenReturn(List.of(record));

        List<AssociationInfo> infos = service.findAssociationsByNodeId(nodeId, AssociationType.RELATES_TO, null);

        assertThat(infos).hasSize(1);
        assertThat(infos.get(0).direction()).isEqualTo(AssociationInfo.Direction.INCOMING);
        assertThat(infos.get(0).type()).isEqualTo(AssociationType.RELATES_TO);
    }
}
