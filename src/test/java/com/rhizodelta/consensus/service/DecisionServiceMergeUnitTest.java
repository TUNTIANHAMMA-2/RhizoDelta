package com.rhizodelta.consensus.service;

import com.rhizodelta.consensus.domain.decision.DecisionOperatorType;
import com.rhizodelta.consensus.domain.decision.DecisionResult;
import com.rhizodelta.consensus.domain.decision.MergeDecisionCommand;
import com.rhizodelta.consensus.domain.exception.DagIntegrityViolationException;

import com.rhizodelta.consensus.repository.AIConsensusRepository;
import com.rhizodelta.core.repository.HumanPostRepository;
import com.rhizodelta.consensus.repository.ResultRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DecisionServiceMergeUnitTest {

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
    void executeMergeShouldRejectMissingSourceNode() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        DecisionService decisionService = new DecisionService(
                neo4jClient,
                humanPostRepository,
                aiConsensusRepository,
                resultRepository,
                dagIntegrityService,
                eventPublisher
        );
        UUID sourceNodeId = UUID.randomUUID();
        MergeDecisionCommand command = newMergeCommand(sourceNodeId, UUID.randomUUID());

        when(humanPostRepository.existsActiveByNodeId(sourceNodeId)).thenReturn(false);
        when(aiConsensusRepository.existsActiveByNodeId(sourceNodeId)).thenReturn(false);

        assertThatThrownBy(() -> decisionService.executeMerge(command))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("source_node_id");
    }

    @Test
    void executeMergeShouldRejectMissingSynthesizedFromNode() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        DecisionService decisionService = new DecisionService(
                neo4jClient,
                humanPostRepository,
                aiConsensusRepository,
                resultRepository,
                dagIntegrityService,
                eventPublisher
        );
        UUID sourceNodeId = UUID.randomUUID();
        UUID missingSynthesizedNodeId = UUID.randomUUID();
        MergeDecisionCommand command = newMergeCommand(sourceNodeId, missingSynthesizedNodeId);

        when(humanPostRepository.existsActiveByNodeId(sourceNodeId)).thenReturn(true);
        when(humanPostRepository.findActiveNodeIdStrings(any())).thenReturn(List.of());

        assertThatThrownBy(() -> decisionService.executeMerge(command))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("synthesized_from");
    }

    @Test
    void executeMergeShouldReturnQueuedDecisionResult() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        DecisionService decisionService = new DecisionService(
                neo4jClient,
                humanPostRepository,
                aiConsensusRepository,
                resultRepository,
                dagIntegrityService,
                eventPublisher
        );
        UUID sourceNodeId = UUID.randomUUID();
        UUID synthesizedNodeId = UUID.randomUUID();
        UUID decisionNodeId = UUID.randomUUID();
        MergeDecisionCommand command = newMergeCommand(sourceNodeId, synthesizedNodeId);

        when(humanPostRepository.existsActiveByNodeId(sourceNodeId)).thenReturn(true);
        when(humanPostRepository.findActiveNodeIdStrings(any()))
                .thenReturn(List.of(synthesizedNodeId.toString()));
        when(neo4jClient.query(argThat((String query) -> query != null && query.contains("MERGE (decision:AI_Consensus")))
                .bindAll(anyMap())
                .fetch()
                .one()).thenReturn(Optional.of(Map.of("nodeId", decisionNodeId.toString(), "created", true)));
        when(neo4jClient.query(argThat((String query) -> query != null && query.contains("MERGED_INTO")))
                .bindAll(anyMap())
                .fetch()
                .one()).thenReturn(Optional.of(Map.of("synthesizedCount", 1L)));

        DecisionResult result = decisionService.executeMerge(command);

        assertThat(result.decision_id()).isEqualTo(command.decision_id());
        assertThat(result.node_id()).isEqualTo(decisionNodeId);
        assertThat(result.status()).isEqualTo("QUEUED");
        verify(dagIntegrityService).assertNoVersionEvolutionCycle(decisionNodeId, sourceNodeId);
    }

    private static MergeDecisionCommand newMergeCommand(UUID sourceNodeId, UUID synthesizedNodeId) {
        return new MergeDecisionCommand(
                "decision-001",
                "request-001",
                sourceNodeId,
                "gpt-4.1",
                "summary",
                List.of(synthesizedNodeId),
                DecisionOperatorType.AGENT,
                "agent-1",
                "merge"
        );
    }
}
