package com.rhizodelta.service;

import com.rhizodelta.domain.decision.DecisionOperatorType;
import com.rhizodelta.domain.decision.DecisionResult;
import com.rhizodelta.domain.decision.MaterializeDecisionCommand;
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

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DecisionServiceMaterializeUnitTest {

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
    void executeMaterializeShouldRejectMissingSourceNode() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        DecisionService decisionService = new DecisionService(
                neo4jClient, humanPostRepository, aiConsensusRepository,
                resultRepository, dagIntegrityService, eventPublisher);

        UUID sourceNodeId = UUID.randomUUID();
        MaterializeDecisionCommand command = newMaterializeCommand(sourceNodeId);

        when(humanPostRepository.existsActiveByNodeId(sourceNodeId)).thenReturn(false);
        when(aiConsensusRepository.existsActiveByNodeId(sourceNodeId)).thenReturn(false);
        when(resultRepository.existsActiveByNodeId(sourceNodeId)).thenReturn(false);

        assertThatThrownBy(() -> decisionService.executeMaterialize(command))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("source_node_id");
    }

    @Test
    void executeMaterializeShouldReturnQueuedResult() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        DecisionService decisionService = new DecisionService(
                neo4jClient, humanPostRepository, aiConsensusRepository,
                resultRepository, dagIntegrityService, eventPublisher);

        UUID sourceNodeId = UUID.randomUUID();
        UUID resultNodeId = UUID.randomUUID();
        MaterializeDecisionCommand command = newMaterializeCommand(sourceNodeId);

        when(humanPostRepository.existsActiveByNodeId(sourceNodeId)).thenReturn(true);
        when(neo4jClient.query(argThat((String q) -> q != null && q.contains("MERGE (result:Result")))
                .bindAll(anyMap()).fetch().one())
                .thenReturn(Optional.of(Map.of("nodeId", resultNodeId.toString(), "created", true)));
        when(neo4jClient.query(argThat((String q) -> q != null && q.contains("MATERIALIZED_FROM")))
                .bindAll(anyMap()).fetch().one())
                .thenReturn(Optional.of(Map.of("relType", "MATERIALIZED_FROM")));

        DecisionResult result = decisionService.executeMaterialize(command);

        assertThat(result.decision_id()).isEqualTo(command.decision_id());
        assertThat(result.node_id()).isEqualTo(resultNodeId);
        assertThat(result.status()).isEqualTo("QUEUED");
    }

    @Test
    void executeMaterializeShouldNotCheckDagCycle() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        DecisionService decisionService = new DecisionService(
                neo4jClient, humanPostRepository, aiConsensusRepository,
                resultRepository, dagIntegrityService, eventPublisher);

        UUID sourceNodeId = UUID.randomUUID();
        UUID resultNodeId = UUID.randomUUID();
        MaterializeDecisionCommand command = newMaterializeCommand(sourceNodeId);

        when(humanPostRepository.existsActiveByNodeId(sourceNodeId)).thenReturn(true);
        when(neo4jClient.query(argThat((String q) -> q != null && q.contains("MERGE (result:Result")))
                .bindAll(anyMap()).fetch().one())
                .thenReturn(Optional.of(Map.of("nodeId", resultNodeId.toString(), "created", true)));
        when(neo4jClient.query(argThat((String q) -> q != null && q.contains("MATERIALIZED_FROM")))
                .bindAll(anyMap()).fetch().one())
                .thenReturn(Optional.of(Map.of("relType", "MATERIALIZED_FROM")));

        decisionService.executeMaterialize(command);

        verifyNoInteractions(dagIntegrityService);
    }

    private static MaterializeDecisionCommand newMaterializeCommand(UUID sourceNodeId) {
        return new MaterializeDecisionCommand(
                "decision-materialize-001",
                "request-materialize-001",
                sourceNodeId,
                "materialized content",
                DecisionOperatorType.AGENT,
                "agent-1",
                "materialize"
        );
    }
}
