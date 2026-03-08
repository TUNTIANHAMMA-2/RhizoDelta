package com.rhizodelta.service;

import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DagIntegrityServiceUnitTest {

    @Test
    void shouldRejectSelfReference() {
        DagIntegrityService service = new DagIntegrityService(mock(Neo4jClient.class));
        UUID nodeId = UUID.randomUUID();

        assertThatThrownBy(() -> service.assertNoVersionEvolutionCycle(nodeId, nodeId))
                .isInstanceOf(DagIntegrityViolationException.class)
                .hasMessageContaining("source_node_id and target_node_id");
    }

    @Test
    void shouldRejectWhenCycleExists() {
        Neo4jClient client = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        DagIntegrityService service = new DagIntegrityService(client);
        UUID sourceNodeId = UUID.randomUUID();
        UUID targetNodeId = UUID.randomUUID();

        when(client.query(anyString())
                .bind(eq(targetNodeId.toString())).to(eq("targetNodeId"))
                .bind(eq(sourceNodeId.toString())).to(eq("sourceNodeId"))
                .fetchAs(Boolean.class)
                .one()).thenReturn(Optional.of(Boolean.TRUE));

        assertThatThrownBy(() -> service.assertNoVersionEvolutionCycle(sourceNodeId, targetNodeId))
                .isInstanceOf(DagIntegrityViolationException.class)
                .hasMessageContaining("cycle detected");
    }

    @Test
    void shouldAllowWhenNoCycleExists() {
        Neo4jClient client = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        DagIntegrityService service = new DagIntegrityService(client);
        UUID sourceNodeId = UUID.randomUUID();
        UUID targetNodeId = UUID.randomUUID();

        when(client.query(anyString())
                .bind(eq(targetNodeId.toString())).to(eq("targetNodeId"))
                .bind(eq(sourceNodeId.toString())).to(eq("sourceNodeId"))
                .fetchAs(Boolean.class)
                .one()).thenReturn(Optional.of(Boolean.FALSE));

        assertThatCode(() -> service.assertNoVersionEvolutionCycle(sourceNodeId, targetNodeId))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectNullNodeIds() {
        DagIntegrityService service = new DagIntegrityService(mock(Neo4jClient.class));

        assertThatThrownBy(() -> service.assertNoVersionEvolutionCycle(null, UUID.randomUUID()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sourceNodeId");
        assertThatThrownBy(() -> service.assertNoVersionEvolutionCycle(UUID.randomUUID(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("targetNodeId");
    }
}
