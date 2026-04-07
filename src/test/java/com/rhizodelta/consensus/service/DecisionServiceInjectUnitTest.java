package com.rhizodelta.consensus.service;

import com.rhizodelta.consensus.domain.decision.DecisionOperatorType;
import com.rhizodelta.consensus.domain.decision.DecisionResult;
import com.rhizodelta.consensus.domain.decision.InjectDecisionCommand;
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
class DecisionServiceInjectUnitTest {

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
    void executeInjectShouldRejectMissingSourceNode() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        DecisionService decisionService = new DecisionService(
                neo4jClient, humanPostRepository, aiConsensusRepository,
                resultRepository, dagIntegrityService, eventPublisher);

        UUID sourceNodeId = UUID.randomUUID();
        InjectDecisionCommand command = newInjectCommand(sourceNodeId);

        when(humanPostRepository.existsActiveByNodeId(sourceNodeId)).thenReturn(false);
        when(aiConsensusRepository.existsActiveByNodeId(sourceNodeId)).thenReturn(false);
        when(resultRepository.existsActiveByNodeId(sourceNodeId)).thenReturn(false);

        assertThatThrownBy(() -> decisionService.executeInject(command))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("source_node_id");
    }

    @Test
    void executeInjectShouldReturnQueuedDecisionResult() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        DecisionService decisionService = new DecisionService(
                neo4jClient, humanPostRepository, aiConsensusRepository,
                resultRepository, dagIntegrityService, eventPublisher);

        UUID sourceNodeId = UUID.randomUUID();
        UUID decisionNodeId = UUID.randomUUID();
        InjectDecisionCommand command = newInjectCommand(sourceNodeId);

        when(humanPostRepository.existsActiveByNodeId(sourceNodeId)).thenReturn(true);
        when(neo4jClient.query(argThat((String q) -> q != null && q.contains("MERGE (decision:Human_Post") && q.contains("CONTINUES_FROM") == false))
                .bindAll(anyMap()).fetch().one())
                .thenReturn(Optional.of(Map.of("nodeId", decisionNodeId.toString(), "created", true)));
        when(neo4jClient.query(argThat((String q) -> q != null && q.contains("CONTINUES_FROM")))
                .bindAll(anyMap()).fetch().one())
                .thenReturn(Optional.of(Map.of("relType", "CONTINUES_FROM")));

        DecisionResult result = decisionService.executeInject(command);

        assertThat(result.decision_id()).isEqualTo(command.decision_id());
        assertThat(result.node_id()).isEqualTo(decisionNodeId);
        assertThat(result.status()).isEqualTo("QUEUED");
        verify(dagIntegrityService).assertNoVersionEvolutionCycle(decisionNodeId, sourceNodeId);
    }

    @Test
    void executeInjectShouldPropagateCycleViolation() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        DecisionService decisionService = new DecisionService(
                neo4jClient, humanPostRepository, aiConsensusRepository,
                resultRepository, dagIntegrityService, eventPublisher);

        UUID sourceNodeId = UUID.randomUUID();
        InjectDecisionCommand command = newInjectCommand(sourceNodeId);

        when(humanPostRepository.existsActiveByNodeId(sourceNodeId)).thenReturn(true);
        when(neo4jClient.query(argThat((String q) -> q != null && q.contains("MERGE (decision:Human_Post")))
                .bindAll(anyMap()).fetch().one())
                .thenReturn(Optional.of(Map.of("nodeId", sourceNodeId.toString(), "created", true)));
        doThrow(new DagIntegrityViolationException("source_node_id and target_node_id must not be the same"))
                .when(dagIntegrityService)
                .assertNoVersionEvolutionCycle(sourceNodeId, sourceNodeId);

        assertThatThrownBy(() -> decisionService.executeInject(command))
                .isInstanceOf(DagIntegrityViolationException.class)
                .hasMessageContaining("source_node_id and target_node_id");
    }

    private static InjectDecisionCommand newInjectCommand(UUID sourceNodeId) {
        return new InjectDecisionCommand(
                "decision-inject-001",
                "request-inject-001",
                sourceNodeId,
                "inject content",
                "author-1",
                DecisionOperatorType.HUMAN,
                "human-1",
                "inject"
        );
    }
}
