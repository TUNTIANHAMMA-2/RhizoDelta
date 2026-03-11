package com.rhizodelta.service;

import com.rhizodelta.domain.association.AssociationInfo;
import com.rhizodelta.domain.association.AssociationType;
import com.rhizodelta.domain.association.CreateAssociationCommand;
import com.rhizodelta.domain.audit.AuditDetail;
import com.rhizodelta.domain.audit.AuditListResponse;
import com.rhizodelta.domain.decision.BranchDecisionCommand;
import com.rhizodelta.domain.decision.DecisionOperatorType;
import com.rhizodelta.domain.decision.DecisionResult;
import com.rhizodelta.domain.decision.DecisionType;
import com.rhizodelta.domain.decision.MergeDecisionCommand;
import com.rhizodelta.domain.decision.RollbackResult;
import com.rhizodelta.exception.DagIntegrityViolationException;
import com.rhizodelta.exception.RollbackBlockedException;

import com.rhizodelta.domain.node.HumanPost;
import com.rhizodelta.repository.AIConsensusRepository;
import com.rhizodelta.repository.HumanPostRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import java.util.Map;

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
    private DagIntegrityService dagIntegrityService;

    @Mock
    private EmbeddingModelService embeddingModelService;

    @Mock
    private EmbeddingService embeddingService;

    @Test
    void executeMergeShouldRejectMissingSourceNode() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        DecisionService decisionService = new DecisionService(
                neo4jClient,
                humanPostRepository,
                aiConsensusRepository,
                dagIntegrityService,
                embeddingModelService,
                embeddingService
        );
        MergeDecisionCommand command = newMergeCommand(UUID.randomUUID(), UUID.randomUUID());

        when(humanPostRepository.findByNodeId(command.source_node_id())).thenReturn(Optional.empty());
        when(aiConsensusRepository.findByNodeId(command.source_node_id())).thenReturn(Optional.empty());

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
                dagIntegrityService,
                embeddingModelService,
                embeddingService
        );
        UUID sourceNodeId = UUID.randomUUID();
        UUID missingSynthesizedNodeId = UUID.randomUUID();
        MergeDecisionCommand command = newMergeCommand(sourceNodeId, missingSynthesizedNodeId);

        when(humanPostRepository.findByNodeId(sourceNodeId))
                .thenReturn(Optional.of(HumanPost.create(sourceNodeId, "source", "author", "req-source")));
        when(humanPostRepository.findAllByNodeIdIn(any())).thenReturn(List.of());

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
                dagIntegrityService,
                embeddingModelService,
                embeddingService
        );
        UUID sourceNodeId = UUID.randomUUID();
        UUID synthesizedNodeId = UUID.randomUUID();
        UUID decisionNodeId = UUID.randomUUID();
        MergeDecisionCommand command = newMergeCommand(sourceNodeId, synthesizedNodeId);

        HumanPost synthesizedPost = HumanPost.create(synthesizedNodeId, "synth", "author", "req-synth");
        when(humanPostRepository.findByNodeId(sourceNodeId))
                .thenReturn(Optional.of(HumanPost.create(sourceNodeId, "source", "author", "req-source")));
        when(humanPostRepository.findAllByNodeIdIn(any()))
                .thenReturn(List.of(synthesizedPost));
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
