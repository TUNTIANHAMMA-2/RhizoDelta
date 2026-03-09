package com.rhizodelta.service;

import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RollbackServiceUnitTest {
    @Test
    void rollbackDecisionShouldSucceedForMergeNodeWithoutDependents() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        UUID decisionNodeId = UUID.randomUUID();
        when(neo4jClient.query(argThat((String query) -> query != null && query.contains("decision_id: $decisionId")))
                .bind(anyString())
                .to(anyString())
                .fetch()
                .one()).thenReturn(Optional.of(Map.of("nodeId", decisionNodeId.toString())));
        when(neo4jClient.query(argThat((String query) -> query != null && query.contains("dependentNodeId")))
                .bind(anyString())
                .to(anyString())
                .fetch()
                .all()).thenReturn(List.of());
        when(neo4jClient.query(argThat((String query) -> query != null && query.contains("DETACH DELETE decision")))
                .bind(anyString())
                .to(anyString())
                .fetch()
                .one()).thenReturn(Optional.of(Map.of("relationshipsRemoved", 3L)));
        RollbackService rollbackService = new RollbackService(neo4jClient);

        RollbackResult result = rollbackService.rollbackDecision("decision-merge");

        assertThat(result.decision_id()).isEqualTo("decision-merge");
        assertThat(result.rolled_back_node_id()).isEqualTo(decisionNodeId);
        assertThat(result.relationships_removed()).isEqualTo(3L);
    }

    @Test
    void rollbackDecisionShouldSucceedForBranchNodeWithoutDependents() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        UUID decisionNodeId = UUID.randomUUID();
        when(neo4jClient.query(argThat((String query) -> query != null && query.contains("decision_id: $decisionId")))
                .bind(anyString())
                .to(anyString())
                .fetch()
                .one()).thenReturn(Optional.of(Map.of("nodeId", decisionNodeId)));
        when(neo4jClient.query(argThat((String query) -> query != null && query.contains("dependentNodeId")))
                .bind(anyString())
                .to(anyString())
                .fetch()
                .all()).thenReturn(List.of());
        when(neo4jClient.query(argThat((String query) -> query != null && query.contains("DETACH DELETE decision")))
                .bind(anyString())
                .to(anyString())
                .fetch()
                .one()).thenReturn(Optional.of(Map.of("relationshipsRemoved", 1L)));
        RollbackService rollbackService = new RollbackService(neo4jClient);

        RollbackResult result = rollbackService.rollbackDecision("decision-branch");

        assertThat(result.decision_id()).isEqualTo("decision-branch");
        assertThat(result.rolled_back_node_id()).isEqualTo(decisionNodeId);
        assertThat(result.relationships_removed()).isEqualTo(1L);
    }

    @Test
    void rollbackDecisionShouldRejectWhenDependentsExist() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        UUID decisionNodeId = UUID.randomUUID();
        UUID dependentA = UUID.randomUUID();
        UUID dependentB = UUID.randomUUID();
        when(neo4jClient.query(argThat((String query) -> query != null && query.contains("decision_id: $decisionId")))
                .bind(anyString())
                .to(anyString())
                .fetch()
                .one()).thenReturn(Optional.of(Map.of("nodeId", decisionNodeId.toString())));
        when(neo4jClient.query(argThat((String query) -> query != null && query.contains("dependentNodeId")))
                .bind(anyString())
                .to(anyString())
                .fetch()
                .all()).thenReturn(List.of(
                        Map.of("dependentNodeId", dependentA.toString()),
                        Map.of("dependentNodeId", dependentB.toString())
                ));
        RollbackService rollbackService = new RollbackService(neo4jClient);

        assertThatThrownBy(() -> rollbackService.rollbackDecision("decision-blocked"))
                .isInstanceOf(RollbackBlockedException.class)
                .satisfies(exception -> {
                    RollbackBlockedException rollbackBlockedException = (RollbackBlockedException) exception;
                    assertThat(rollbackBlockedException.dependent_node_ids())
                            .containsExactly(dependentA, dependentB);
                });
    }

    @Test
    void rollbackDecisionShouldThrowWhenDecisionDoesNotExist() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(argThat((String query) -> query != null && query.contains("decision_id: $decisionId")))
                .bind(anyString())
                .to(anyString())
                .fetch()
                .one()).thenReturn(Optional.empty());
        RollbackService rollbackService = new RollbackService(neo4jClient);

        assertThatThrownBy(() -> rollbackService.rollbackDecision("missing"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("decision not found: missing");
    }
}
