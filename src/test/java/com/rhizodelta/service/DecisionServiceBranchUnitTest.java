package com.rhizodelta.service;

import com.rhizodelta.domain.node.HumanPost;
import com.rhizodelta.repository.AIConsensusRepository;
import com.rhizodelta.repository.HumanPostRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DecisionServiceBranchUnitTest {
    @Mock
    private HumanPostRepository humanPostRepository;

    @Mock
    private AIConsensusRepository aiConsensusRepository;

    @Mock
    private DagIntegrityService dagIntegrityService;

    @Test
    void executeBranchShouldRejectMissingSourceNode() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        DecisionService decisionService = new DecisionService(
                neo4jClient,
                humanPostRepository,
                aiConsensusRepository,
                dagIntegrityService
        );
        BranchDecisionCommand command = newBranchCommand(UUID.randomUUID());

        when(humanPostRepository.findByNodeId(command.source_node_id())).thenReturn(Optional.empty());
        when(aiConsensusRepository.findByNodeId(command.source_node_id())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> decisionService.executeBranch(command))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("source_node_id");
    }

    @Test
    void executeBranchShouldReturnQueuedDecisionResult() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        DecisionService decisionService = new DecisionService(
                neo4jClient,
                humanPostRepository,
                aiConsensusRepository,
                dagIntegrityService
        );
        UUID sourceNodeId = UUID.randomUUID();
        UUID decisionNodeId = UUID.randomUUID();
        BranchDecisionCommand command = newBranchCommand(sourceNodeId);

        when(humanPostRepository.findByNodeId(sourceNodeId))
                .thenReturn(Optional.of(HumanPost.create(sourceNodeId, "source", "author", "req-source")));
        when(neo4jClient.query(argThat((String query) -> query != null && query.contains("MERGE (decision:Human_Post")))
                .bindAll(anyMap())
                .fetch()
                .one()).thenReturn(Optional.of(Map.of("nodeId", decisionNodeId.toString(), "created", true)));
        when(neo4jClient.query(argThat((String query) -> query != null && query.contains("BRANCHED_FROM")))
                .bindAll(anyMap())
                .fetch()
                .one()).thenReturn(Optional.of(Map.of("relType", "BRANCHED_FROM")));

        DecisionResult result = decisionService.executeBranch(command);

        assertThat(result.decision_id()).isEqualTo(command.decision_id());
        assertThat(result.node_id()).isEqualTo(decisionNodeId);
        assertThat(result.status()).isEqualTo("QUEUED");
        verify(dagIntegrityService).assertNoVersionEvolutionCycle(decisionNodeId, sourceNodeId);
    }

    @Test
    void executeBranchShouldPropagateCycleViolation() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        DecisionService decisionService = new DecisionService(
                neo4jClient,
                humanPostRepository,
                aiConsensusRepository,
                dagIntegrityService
        );
        UUID sourceNodeId = UUID.randomUUID();
        BranchDecisionCommand command = newBranchCommand(sourceNodeId);

        when(humanPostRepository.findByNodeId(sourceNodeId))
                .thenReturn(Optional.of(HumanPost.create(sourceNodeId, "source", "author", "req-source")));
        when(neo4jClient.query(argThat((String query) -> query != null && query.contains("MERGE (decision:Human_Post")))
                .bindAll(anyMap())
                .fetch()
                .one()).thenReturn(Optional.of(Map.of("nodeId", sourceNodeId.toString(), "created", true)));
        doThrow(new DagIntegrityViolationException("source_node_id and target_node_id must not be the same"))
                .when(dagIntegrityService)
                .assertNoVersionEvolutionCycle(sourceNodeId, sourceNodeId);

        assertThatThrownBy(() -> decisionService.executeBranch(command))
                .isInstanceOf(DagIntegrityViolationException.class)
                .hasMessageContaining("source_node_id and target_node_id");
    }

    private static BranchDecisionCommand newBranchCommand(UUID sourceNodeId) {
        return new BranchDecisionCommand(
                "decision-branch-001",
                "request-branch-001",
                sourceNodeId,
                "branch content",
                "author-1",
                DecisionOperatorType.HUMAN,
                "human-1",
                "branch"
        );
    }
}
