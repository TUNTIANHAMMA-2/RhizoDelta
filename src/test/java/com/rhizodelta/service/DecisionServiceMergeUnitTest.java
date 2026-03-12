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
    private static final String ACTIVE_SOURCE_QUERY_FRAGMENT = "RETURN count(node) > 0 AS exists";
    private static final String ACTIVE_HUMAN_POST_IDS_QUERY_FRAGMENT = "RETURN toString(post.node_id) AS nodeId";

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

    @Mock
    private SseEventService sseEventService;

    @Test
    void executeMergeShouldRejectMissingSourceNode() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        DecisionService decisionService = new DecisionService(
                neo4jClient,
                humanPostRepository,
                aiConsensusRepository,
                dagIntegrityService,
                embeddingModelService,
                embeddingService,
                sseEventService
        );
        MergeDecisionCommand command = newMergeCommand(UUID.randomUUID(), UUID.randomUUID());

        when(neo4jClient.query(argThat((String query) -> query != null && query.contains(ACTIVE_SOURCE_QUERY_FRAGMENT)))
                .bind(any()).to("nodeId")
                .fetch()
                .one()).thenReturn(Optional.of(Map.of("exists", false)));

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
                embeddingService,
                sseEventService
        );
        UUID sourceNodeId = UUID.randomUUID();
        UUID missingSynthesizedNodeId = UUID.randomUUID();
        MergeDecisionCommand command = newMergeCommand(sourceNodeId, missingSynthesizedNodeId);

        when(neo4jClient.query(argThat((String query) -> query != null && query.contains(ACTIVE_SOURCE_QUERY_FRAGMENT)))
                .bind(any()).to("nodeId")
                .fetch()
                .one()).thenReturn(Optional.of(Map.of("exists", true)));
        when(neo4jClient.query(argThat((String query) -> query != null && query.contains(ACTIVE_HUMAN_POST_IDS_QUERY_FRAGMENT)))
                .bind(any()).to("nodeIds")
                .fetch()
                .all()).thenReturn(List.of());

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
                embeddingService,
                sseEventService
        );
        UUID sourceNodeId = UUID.randomUUID();
        UUID synthesizedNodeId = UUID.randomUUID();
        UUID decisionNodeId = UUID.randomUUID();
        MergeDecisionCommand command = newMergeCommand(sourceNodeId, synthesizedNodeId);

        when(neo4jClient.query(argThat((String query) -> query != null && query.contains(ACTIVE_SOURCE_QUERY_FRAGMENT)))
                .bind(any()).to("nodeId")
                .fetch()
                .one()).thenReturn(Optional.of(Map.of("exists", true)));
        when(neo4jClient.query(argThat((String query) -> query != null && query.contains(ACTIVE_HUMAN_POST_IDS_QUERY_FRAGMENT)))
                .bind(any()).to("nodeIds")
                .fetch()
                .all()).thenReturn(List.of(Map.of("nodeId", synthesizedNodeId.toString())));
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
