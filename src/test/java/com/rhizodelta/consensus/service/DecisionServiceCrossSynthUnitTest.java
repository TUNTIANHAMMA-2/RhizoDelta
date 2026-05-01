package com.rhizodelta.consensus.service;

import com.rhizodelta.consensus.domain.decision.CrossSynthDecisionCommand;
import com.rhizodelta.consensus.domain.decision.DecisionOperatorType;
import com.rhizodelta.consensus.domain.decision.DecisionResult;
import com.rhizodelta.consensus.domain.exception.DagIntegrityViolationException;
import com.rhizodelta.consensus.repository.AIConsensusRepository;
import com.rhizodelta.core.repository.HumanPostRepository;
import com.rhizodelta.consensus.repository.ResultRepository;
import com.rhizodelta.infrastructure.user.service.TopicService;
import com.rhizodelta.consensus.service.DecisionMetadataService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DecisionServiceCrossSynthUnitTest {

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

    @Mock
    private TopicService topicService;

    @Mock
    private DecisionMetadataService decisionMetadataService;

    @Test
    void executeCrossSynthShouldRejectMissingSourceResult() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        DecisionService decisionService = new DecisionService(
                neo4jClient, humanPostRepository, aiConsensusRepository,
                resultRepository, dagIntegrityService, eventPublisher, topicService, decisionMetadataService);

        UUID resultA = UUID.randomUUID();
        UUID resultB = UUID.randomUUID();
        CrossSynthDecisionCommand command = newCrossSynthCommand(List.of(resultA, resultB));

        when(neo4jClient.query(argThat((String q) -> q != null && q.contains("MATCH (r:Result:GraphNode")))
                .bind(resultA.toString()).to("nodeId")
                .fetchAs(Boolean.class).one())
                .thenReturn(Optional.of(Boolean.FALSE));

        assertThatThrownBy(() -> decisionService.executeCrossSynth(command))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("source_result_id");
    }

    @Test
    void executeCrossSynthShouldReturnQueuedResult() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        DecisionService decisionService = new DecisionService(
                neo4jClient, humanPostRepository, aiConsensusRepository,
                resultRepository, dagIntegrityService, eventPublisher, topicService, decisionMetadataService);

        UUID resultA = UUID.randomUUID();
        UUID resultB = UUID.randomUUID();
        UUID newNodeId = UUID.randomUUID();
        CrossSynthDecisionCommand command = newCrossSynthCommand(List.of(resultA, resultB));

        // Both source results exist
        when(neo4jClient.query(argThat((String q) -> q != null && q.contains("MATCH (r:Result:GraphNode")))
                .bind(anyString()).to("nodeId")
                .fetchAs(Boolean.class).one())
                .thenReturn(Optional.of(Boolean.TRUE));
        // Cross-synth upsert
        when(neo4jClient.query(argThat((String q) -> q != null && q.contains("CROSS_SYNTHESIZED_FROM")))
                .bindAll(anyMap()).fetch().one())
                .thenReturn(Optional.of(Map.of("nodeId", newNodeId.toString(), "crossSynthCount", 2L)));

        DecisionResult result = decisionService.executeCrossSynth(command);

        assertThat(result.decision_id()).isEqualTo(command.decision_id());
        assertThat(result.node_id()).isEqualTo(newNodeId);
        assertThat(result.status()).isEqualTo("QUEUED");
    }

    @Test
    void executeCrossSynthShouldRejectCountMismatch() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        DecisionService decisionService = new DecisionService(
                neo4jClient, humanPostRepository, aiConsensusRepository,
                resultRepository, dagIntegrityService, eventPublisher, topicService, decisionMetadataService);

        UUID resultA = UUID.randomUUID();
        UUID resultB = UUID.randomUUID();
        UUID newNodeId = UUID.randomUUID();
        CrossSynthDecisionCommand command = newCrossSynthCommand(List.of(resultA, resultB));

        when(neo4jClient.query(argThat((String q) -> q != null && q.contains("MATCH (r:Result:GraphNode")))
                .bind(anyString()).to("nodeId")
                .fetchAs(Boolean.class).one())
                .thenReturn(Optional.of(Boolean.TRUE));
        // Return mismatched count
        when(neo4jClient.query(argThat((String q) -> q != null && q.contains("CROSS_SYNTHESIZED_FROM")))
                .bindAll(anyMap()).fetch().one())
                .thenReturn(Optional.of(Map.of("nodeId", newNodeId.toString(), "crossSynthCount", 1L)));

        assertThatThrownBy(() -> decisionService.executeCrossSynth(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Expected 2")
                .hasMessageContaining("created 1");
    }

    @Test
    void executeCrossSynthShouldPropagateResultLayerCycle() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        DecisionService decisionService = new DecisionService(
                neo4jClient, humanPostRepository, aiConsensusRepository,
                resultRepository, dagIntegrityService, eventPublisher, topicService, decisionMetadataService);

        UUID resultA = UUID.randomUUID();
        UUID resultB = UUID.randomUUID();
        UUID newNodeId = UUID.randomUUID();
        CrossSynthDecisionCommand command = newCrossSynthCommand(List.of(resultA, resultB));

        when(neo4jClient.query(argThat((String q) -> q != null && q.contains("MATCH (r:Result:GraphNode")))
                .bind(anyString()).to("nodeId")
                .fetchAs(Boolean.class).one())
                .thenReturn(Optional.of(Boolean.TRUE));
        when(neo4jClient.query(argThat((String q) -> q != null && q.contains("CROSS_SYNTHESIZED_FROM")))
                .bindAll(anyMap()).fetch().one())
                .thenReturn(Optional.of(Map.of("nodeId", newNodeId.toString(), "crossSynthCount", 2L)));
        doThrow(new DagIntegrityViolationException("cycle detected in Result layer via CROSS_SYNTHESIZED_FROM"))
                .when(dagIntegrityService)
                .assertNoResultLayerCycle(newNodeId, resultA);

        assertThatThrownBy(() -> decisionService.executeCrossSynth(command))
                .isInstanceOf(DagIntegrityViolationException.class)
                .hasMessageContaining("Result layer");
    }

    private static CrossSynthDecisionCommand newCrossSynthCommand(List<UUID> sourceResultIds) {
        return new CrossSynthDecisionCommand(
                "decision-crosssynth-001",
                "request-crosssynth-001",
                sourceResultIds,
                "cross-synthesized content",
                DecisionOperatorType.AGENT,
                "agent-1",
                "cross-synth"
        );
    }
}
