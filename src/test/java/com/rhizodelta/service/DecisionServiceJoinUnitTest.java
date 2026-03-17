package com.rhizodelta.service;

import com.rhizodelta.domain.decision.DecisionOperatorType;
import com.rhizodelta.domain.decision.DecisionResult;
import com.rhizodelta.domain.decision.JoinDecisionCommand;
import com.rhizodelta.exception.DagIntegrityViolationException;
import com.rhizodelta.repository.AIConsensusRepository;
import com.rhizodelta.repository.HumanPostRepository;
import com.rhizodelta.repository.ResultRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DecisionServiceJoinUnitTest {

    @Mock
    private HumanPostRepository humanPostRepository;

    @Mock
    private AIConsensusRepository aiConsensusRepository;

    @Mock
    private ResultRepository resultRepository;

    @Mock
    private DagIntegrityService dagIntegrityService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Test
    void executeJoinShouldRejectMissingSourceNode() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        DecisionService decisionService = new DecisionService(
                neo4jClient, humanPostRepository, aiConsensusRepository,
                resultRepository, dagIntegrityService, eventPublisher);

        UUID sourceA = UUID.randomUUID();
        UUID sourceB = UUID.randomUUID();
        JoinDecisionCommand command = newJoinCommand(List.of(sourceA, sourceB));

        when(humanPostRepository.existsActiveByNodeId(sourceA)).thenReturn(true);
        when(humanPostRepository.existsActiveByNodeId(sourceB)).thenReturn(false);
        when(aiConsensusRepository.existsActiveByNodeId(sourceB)).thenReturn(false);
        when(resultRepository.existsActiveByNodeId(sourceB)).thenReturn(false);

        assertThatThrownBy(() -> decisionService.executeJoin(command))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("source_node_id");
    }

    @Test
    void executeJoinShouldReturnQueuedResult() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        DecisionService decisionService = new DecisionService(
                neo4jClient, humanPostRepository, aiConsensusRepository,
                resultRepository, dagIntegrityService, eventPublisher);

        UUID sourceA = UUID.randomUUID();
        UUID sourceB = UUID.randomUUID();
        UUID joinNodeId = UUID.randomUUID();
        JoinDecisionCommand command = newJoinCommand(List.of(sourceA, sourceB));

        when(humanPostRepository.existsActiveByNodeId(sourceA)).thenReturn(true);
        when(humanPostRepository.existsActiveByNodeId(sourceB)).thenReturn(true);
        when(neo4jClient.query(argThat((String q) -> q != null && q.contains("MERGE (decision:AI_Consensus") && !q.contains("MERGED_INTO")))
                .bindAll(anyMap()).fetch().one())
                .thenReturn(Optional.of(Map.of("nodeId", joinNodeId.toString(), "created", true)));
        when(neo4jClient.query(argThat((String q) -> q != null && q.contains("CONVERGED_FROM")))
                .bindAll(anyMap()).fetch().one())
                .thenReturn(Optional.of(Map.of("convergedCount", 2L)));

        DecisionResult result = decisionService.executeJoin(command);

        assertThat(result.decision_id()).isEqualTo(command.decision_id());
        assertThat(result.node_id()).isEqualTo(joinNodeId);
        assertThat(result.status()).isEqualTo("QUEUED");
        verify(dagIntegrityService).assertNoVersionEvolutionCycle(joinNodeId, sourceA);
        verify(dagIntegrityService).assertNoVersionEvolutionCycle(joinNodeId, sourceB);
    }

    @Test
    void executeJoinShouldPropagateCycleViolation() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        DecisionService decisionService = new DecisionService(
                neo4jClient, humanPostRepository, aiConsensusRepository,
                resultRepository, dagIntegrityService, eventPublisher);

        UUID sourceA = UUID.randomUUID();
        UUID sourceB = UUID.randomUUID();
        UUID joinNodeId = UUID.randomUUID();
        JoinDecisionCommand command = newJoinCommand(List.of(sourceA, sourceB));

        when(humanPostRepository.existsActiveByNodeId(sourceA)).thenReturn(true);
        when(humanPostRepository.existsActiveByNodeId(sourceB)).thenReturn(true);
        when(neo4jClient.query(argThat((String q) -> q != null && q.contains("MERGE (decision:AI_Consensus") && !q.contains("MERGED_INTO")))
                .bindAll(anyMap()).fetch().one())
                .thenReturn(Optional.of(Map.of("nodeId", joinNodeId.toString(), "created", true)));
        doThrow(new DagIntegrityViolationException("cycle detected for version evolution relationship"))
                .when(dagIntegrityService)
                .assertNoVersionEvolutionCycle(joinNodeId, sourceA);

        assertThatThrownBy(() -> decisionService.executeJoin(command))
                .isInstanceOf(DagIntegrityViolationException.class)
                .hasMessageContaining("cycle detected");
    }

    private static JoinDecisionCommand newJoinCommand(List<UUID> sourceNodeIds) {
        return new JoinDecisionCommand(
                "decision-join-001",
                "request-join-001",
                sourceNodeIds,
                "joined summary",
                "gpt-4.1",
                DecisionOperatorType.AGENT,
                "agent-1",
                "join"
        );
    }
}
