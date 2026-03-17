package com.rhizodelta.service;

import com.rhizodelta.domain.decision.DecisionOperatorType;
import com.rhizodelta.domain.decision.ForkDecisionCommand;
import com.rhizodelta.domain.decision.ForkDecisionResult;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DecisionServiceForkUnitTest {

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
    void executeForkShouldRejectMissingSourceNode() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        DecisionService decisionService = new DecisionService(
                neo4jClient, humanPostRepository, aiConsensusRepository,
                resultRepository, dagIntegrityService, eventPublisher);

        UUID sourceNodeId = UUID.randomUUID();
        ForkDecisionCommand command = newForkCommand(sourceNodeId);

        when(humanPostRepository.existsActiveByNodeId(sourceNodeId)).thenReturn(false);
        when(aiConsensusRepository.existsActiveByNodeId(sourceNodeId)).thenReturn(false);
        when(resultRepository.existsActiveByNodeId(sourceNodeId)).thenReturn(false);

        assertThatThrownBy(() -> decisionService.executeFork(command))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("source_node_id");
    }

    @Test
    void executeForkShouldReturnQueuedResultWithCorrectCount() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        DecisionService decisionService = new DecisionService(
                neo4jClient, humanPostRepository, aiConsensusRepository,
                resultRepository, dagIntegrityService, eventPublisher);

        UUID sourceNodeId = UUID.randomUUID();
        ForkDecisionCommand command = newForkCommand(sourceNodeId);

        when(humanPostRepository.existsActiveByNodeId(sourceNodeId)).thenReturn(true);
        when(neo4jClient.query(argThat((String q) -> q != null && q.contains("UNWIND $branches")))
                .bindAll(anyMap()).fetch().one())
                .thenReturn(Optional.of(Map.of("createdCount", 2L)));

        ForkDecisionResult result = decisionService.executeFork(command);

        assertThat(result.operation_id()).isEqualTo(command.operation_id());
        assertThat(result.status()).isEqualTo("QUEUED");
        assertThat(result.created_count()).isEqualTo(2);
        assertThat(result.total_count()).isEqualTo(2);
        assertThat(result.node_ids()).hasSize(2);
    }

    @Test
    void executeForkShouldPublishEvent() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        DecisionService decisionService = new DecisionService(
                neo4jClient, humanPostRepository, aiConsensusRepository,
                resultRepository, dagIntegrityService, eventPublisher);

        UUID sourceNodeId = UUID.randomUUID();
        ForkDecisionCommand command = newForkCommand(sourceNodeId);

        when(humanPostRepository.existsActiveByNodeId(sourceNodeId)).thenReturn(true);
        when(neo4jClient.query(argThat((String q) -> q != null && q.contains("UNWIND $branches")))
                .bindAll(anyMap()).fetch().one())
                .thenReturn(Optional.of(Map.of("createdCount", 2L)));

        decisionService.executeFork(command);

        org.mockito.Mockito.verify(eventPublisher).publishEvent(
                argThat((Object event) -> event instanceof com.rhizodelta.event.DecisionCommittedEvent.ForkCompleted));
    }

    private static ForkDecisionCommand newForkCommand(UUID sourceNodeId) {
        return new ForkDecisionCommand(
                "fork-op-001",
                "request-fork-001",
                sourceNodeId,
                List.of(
                        new ForkDecisionCommand.ForkBranchSpec("fork-branch-1", "branch A content", "author-a"),
                        new ForkDecisionCommand.ForkBranchSpec("fork-branch-2", "branch B content", "author-b")
                ),
                DecisionOperatorType.AGENT,
                "agent-1",
                "fork"
        );
    }
}
