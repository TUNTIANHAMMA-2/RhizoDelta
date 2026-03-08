package com.rhizodelta.service;

import com.rhizodelta.repository.AIConsensusRepository;
import com.rhizodelta.repository.HumanPostRepository;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class DecisionService {
    private static final String QUEUED_STATUS = "QUEUED";

    private static final String UPSERT_MERGE_NODE_QUERY = """
            MERGE (decision:AI_Consensus {decision_id: $decisionId})
            ON CREATE SET
              decision:GraphNode,
              decision.node_id = $nodeId,
              decision.summary_content = $summaryContent,
              decision.agent_version = $agentVersion,
              decision.request_id = $requestId,
              decision.created_at = $createdAt,
              decision.embedding = null
            RETURN toString(decision.node_id) AS nodeId
            """;

    private static final String MERGE_RELATIONSHIPS_QUERY = """
            MATCH (decision:AI_Consensus {node_id: $decisionNodeId})
            MATCH (source:GraphNode {node_id: $sourceNodeId})
            MERGE (decision)-[merged:MERGED_INTO]->(source)
            ON CREATE SET
              merged.operator_type = $operatorType,
              merged.operator_id = $operatorId,
              merged.created_at = $createdAt,
              merged.reason = $reason
            WITH decision, $synthesizedFromNodeIds AS sourceNodeIds,
                 $operatorType AS operatorType, $operatorId AS operatorId,
                 $createdAt AS createdAt, $reason AS reason
            UNWIND sourceNodeIds AS sourceNodeId
            MATCH (contributor:Human_Post {node_id: sourceNodeId})
            MERGE (decision)-[synthesized:SYNTHESIZED_FROM]->(contributor)
            ON CREATE SET
              synthesized.operator_type = operatorType,
              synthesized.operator_id = operatorId,
              synthesized.created_at = createdAt,
              synthesized.reason = reason
            """;

    private final Neo4jClient neo4jClient;
    private final HumanPostRepository humanPostRepository;
    private final AIConsensusRepository aiConsensusRepository;
    private final DagIntegrityService dagIntegrityService;

    public DecisionService(
            Neo4jClient neo4jClient,
            HumanPostRepository humanPostRepository,
            AIConsensusRepository aiConsensusRepository,
            DagIntegrityService dagIntegrityService
    ) {
        this.neo4jClient = neo4jClient;
        this.humanPostRepository = humanPostRepository;
        this.aiConsensusRepository = aiConsensusRepository;
        this.dagIntegrityService = dagIntegrityService;
    }

    @Transactional(transactionManager = "transactionManager")
    public DecisionResult executeMerge(MergeDecisionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        validateSourceNodeExists(command.source_node_id());
        validateSynthesizedFromNodes(command.synthesized_from());

        UUID decisionNodeId = upsertMergeNode(command);
        dagIntegrityService.assertNoVersionEvolutionCycle(decisionNodeId, command.source_node_id());
        createMergeRelationships(decisionNodeId, command);

        return new DecisionResult(command.decision_id(), decisionNodeId, QUEUED_STATUS);
    }

    private UUID upsertMergeNode(MergeDecisionCommand command) {
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        String nodeId = neo4jClient.query(UPSERT_MERGE_NODE_QUERY)
                .bindAll(Map.of(
                        "decisionId", command.decision_id(),
                        "nodeId", UUID.randomUUID().toString(),
                        "summaryContent", command.summary_content(),
                        "agentVersion", command.agent_version(),
                        "requestId", command.request_id(),
                        "createdAt", createdAt
                ))
                .fetchAs(String.class)
                .one()
                .orElseThrow(() -> new IllegalStateException("Failed to resolve AI_Consensus node_id from upsert query"));
        return UUID.fromString(nodeId);
    }

    private void createMergeRelationships(UUID decisionNodeId, MergeDecisionCommand command) {
        OffsetDateTime relationshipCreatedAt = OffsetDateTime.now(ZoneOffset.UTC);
        neo4jClient.query(MERGE_RELATIONSHIPS_QUERY)
                .bindAll(Map.of(
                        "decisionNodeId", decisionNodeId.toString(),
                        "sourceNodeId", command.source_node_id().toString(),
                        "operatorType", command.operator_type().name(),
                        "operatorId", command.operator_id(),
                        "createdAt", relationshipCreatedAt,
                        "reason", command.reason(),
                        "synthesizedFromNodeIds", command.synthesized_from().stream().map(UUID::toString).toList()
                ))
                .run();
    }

    private void validateSourceNodeExists(UUID sourceNodeId) {
        boolean sourceExists = humanPostRepository.findByNodeId(sourceNodeId).isPresent()
                || aiConsensusRepository.findByNodeId(sourceNodeId).isPresent();
        if (!sourceExists) {
            throw new NoSuchElementException("source_node_id not found: " + sourceNodeId);
        }
    }

    private void validateSynthesizedFromNodes(List<UUID> synthesizedFrom) {
        Set<UUID> missingNodeIds = new LinkedHashSet<>();
        for (UUID sourceNodeId : synthesizedFrom) {
            if (humanPostRepository.findByNodeId(sourceNodeId).isEmpty()) {
                missingNodeIds.add(sourceNodeId);
            }
        }
        if (!missingNodeIds.isEmpty()) {
            throw new NoSuchElementException("synthesized_from node_id not found: " + missingNodeIds);
        }
    }
}
