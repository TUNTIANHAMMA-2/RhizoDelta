package com.rhizodelta.consensus.service;

import com.rhizodelta.consensus.domain.decision.DecisionOperatorType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.time.OffsetDateTime;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks the safety property: when the target {@code GraphNode} doesn't exist,
 * {@link DecisionMetadataService#recordDecision} must NOT silently leave a
 * dangling {@code :Decision} node — it must throw, so the caller can roll back.
 */
@ExtendWith(MockitoExtension.class)
class DecisionMetadataServiceUnitTest {

    @Test
    void recordDecisionShouldThrowWhenTargetMissing() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(org.mockito.ArgumentMatchers.anyString())
                .bindAll(anyMap())
                .fetch()
                .one()).thenReturn(Optional.empty());

        DecisionMetadataService service = new DecisionMetadataService(neo4jClient);

        UUID missingTarget = UUID.randomUUID();
        assertThatThrownBy(() -> service.recordDecision(
                "decision-1",
                "MERGE",
                DecisionOperatorType.AGENT,
                "agent-1",
                missingTarget,
                "reason",
                OffsetDateTime.now()
        ))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(missingTarget.toString());
    }

    @Test
    void recordDecisionShouldSucceedWhenTargetExists() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(org.mockito.ArgumentMatchers.anyString())
                .bindAll(anyMap())
                .fetch()
                .one()).thenReturn(Optional.of(java.util.Map.of("decisionId", "decision-1")));

        DecisionMetadataService service = new DecisionMetadataService(neo4jClient);

        // Should not throw.
        service.recordDecision(
                "decision-1",
                "MERGE",
                DecisionOperatorType.AGENT,
                "agent-1",
                UUID.randomUUID(),
                "reason",
                OffsetDateTime.now()
        );
    }
}
