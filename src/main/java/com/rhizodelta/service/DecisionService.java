package com.rhizodelta.service;
import com.rhizodelta.domain.decision.BranchDecisionCommand;
import com.rhizodelta.domain.decision.DecisionResult;
import com.rhizodelta.domain.decision.DecisionType;
import com.rhizodelta.domain.decision.MergeDecisionCommand;
import com.rhizodelta.repository.AIConsensusRepository;
import com.rhizodelta.repository.HumanPostRepository;
import com.rhizodelta.service.SseEventService.SseEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
@Service
public class DecisionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DecisionService.class);
    private static final String QUEUED_STATUS = "QUEUED";
    private record UpsertResult(UUID nodeId, boolean created) {}
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
            RETURN toString(decision.node_id) AS nodeId,
                   decision.node_id = $nodeId AS created
            """;
    private static final String MERGE_RELATIONSHIPS_QUERY = """
            MATCH (decision:AI_Consensus {decision_id: $decisionId})
            MATCH (source:GraphNode {node_id: $sourceNodeId})
            MERGE (decision)-[merged:MERGED_INTO]->(source)
            ON CREATE SET
              merged.operator_type = $operatorType,
              merged.operator_id = $operatorId,
              merged.created_at = $createdAt,
              merged.reason = $reason
            WITH decision, $contributorNodeIds AS contributorNodeIds,
                 $operatorType AS operatorType, $operatorId AS operatorId,
                 $createdAt AS createdAt, $reason AS reason
            UNWIND contributorNodeIds AS contributorNodeId
            MATCH (contributor:Human_Post:GraphNode {node_id: contributorNodeId})
            MERGE (decision)-[synthesized:SYNTHESIZED_FROM]->(contributor)
            ON CREATE SET
              synthesized.operator_type = operatorType,
              synthesized.operator_id = operatorId,
              synthesized.created_at = createdAt,
              synthesized.reason = reason
            RETURN count(synthesized) AS synthesizedCount
            """;
    private static final String UPSERT_BRANCH_NODE_QUERY = """
            MERGE (decision:Human_Post {decision_id: $decisionId})
            ON CREATE SET
              decision:GraphNode,
              decision.node_id = $nodeId,
              decision.request_id = $requestId,
              decision.content = $content,
              decision.author_id = $authorId,
              decision.created_at = $createdAt,
              decision.embedding = null
            RETURN toString(decision.node_id) AS nodeId,
                   decision.node_id = $nodeId AS created
            """;
    private static final String BRANCH_RELATIONSHIP_QUERY = """
            MATCH (decision:Human_Post {decision_id: $decisionId})
            MATCH (source:GraphNode {node_id: $sourceNodeId})
            MERGE (decision)-[branched:BRANCHED_FROM]->(source)
            ON CREATE SET
              branched.operator_type = $operatorType,
              branched.operator_id = $operatorId,
              branched.created_at = $createdAt,
              branched.reason = $reason
            RETURN type(branched) AS relType
            """;
    private static final String ACTIVE_SOURCE_EXISTS_QUERY = """
            MATCH (node:GraphNode {node_id: $nodeId})
            WHERE (node:Human_Post OR node:AI_Consensus)
              AND NOT coalesce(node._deleted, false)
            RETURN count(node) > 0 AS exists
            """;
    private static final String ACTIVE_HUMAN_POST_IDS_QUERY = """
            MATCH (post:Human_Post:GraphNode)
            WHERE post.node_id IN $nodeIds
              AND NOT coalesce(post._deleted, false)
            RETURN toString(post.node_id) AS nodeId
            """;
    private final Neo4jClient neo4jClient;
    private final HumanPostRepository humanPostRepository;
    private final AIConsensusRepository aiConsensusRepository;
    private final DagIntegrityService dagIntegrityService;
    private final EmbeddingModelService embeddingModelService;
    private final EmbeddingService embeddingService;
    private final SseEventService sseEventService;
    private Executor embeddingTaskExecutor = Runnable::run;
    public DecisionService(Neo4jClient neo4jClient, HumanPostRepository humanPostRepository, AIConsensusRepository aiConsensusRepository, DagIntegrityService dagIntegrityService, EmbeddingModelService embeddingModelService, EmbeddingService embeddingService, SseEventService sseEventService) {
        this.neo4jClient = neo4jClient;
        this.humanPostRepository = humanPostRepository;
        this.aiConsensusRepository = aiConsensusRepository;
        this.dagIntegrityService = dagIntegrityService;
        this.embeddingModelService = embeddingModelService;
        this.embeddingService = embeddingService;
        this.sseEventService = sseEventService;
    }
    @Autowired
    public void setEmbeddingTaskExecutor(@Qualifier("embeddingTaskExecutor") Executor embeddingTaskExecutor) {
        this.embeddingTaskExecutor = Objects.requireNonNull(embeddingTaskExecutor, "embeddingTaskExecutor must not be null");
    }
    @Transactional(transactionManager = "transactionManager")
    public DecisionResult executeMerge(MergeDecisionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        validateSourceNodeExists(command.source_node_id());
        validateSynthesizedFromNodes(command.synthesized_from());
        UpsertResult upsertResult = upsertMergeNode(command);
        if (!upsertResult.created()) {
            return new DecisionResult(command.decision_id(), upsertResult.nodeId(), QUEUED_STATUS);
        }
        dagIntegrityService.assertNoVersionEvolutionCycle(upsertResult.nodeId(), command.source_node_id());
        OffsetDateTime relationshipCreatedAt = OffsetDateTime.now(ZoneOffset.UTC);
        createMergeRelationships(command, relationshipCreatedAt);
        publishMergeEvents(command, upsertResult.nodeId(), relationshipCreatedAt);
        triggerEmbeddingForConsensus(upsertResult.nodeId(), command.summary_content(), command.decision_id());
        return new DecisionResult(command.decision_id(), upsertResult.nodeId(), QUEUED_STATUS);
    }
    @Transactional(transactionManager = "transactionManager")
    public DecisionResult executeBranch(BranchDecisionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        validateSourceNodeExists(command.source_node_id());
        UpsertResult upsertResult = upsertBranchNode(command);
        if (!upsertResult.created()) {
            return new DecisionResult(command.decision_id(), upsertResult.nodeId(), QUEUED_STATUS);
        }
        dagIntegrityService.assertNoVersionEvolutionCycle(upsertResult.nodeId(), command.source_node_id());
        OffsetDateTime relationshipCreatedAt = OffsetDateTime.now(ZoneOffset.UTC);
        createBranchRelationship(command, relationshipCreatedAt);
        publishBranchEvents(command, upsertResult.nodeId(), relationshipCreatedAt);
        return new DecisionResult(command.decision_id(), upsertResult.nodeId(), QUEUED_STATUS);
    }
    private UpsertResult upsertMergeNode(MergeDecisionCommand command) {
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        Map<String, Object> result = neo4jClient.query(UPSERT_MERGE_NODE_QUERY)
                .bindAll(Map.of("decisionId", command.decision_id(), "nodeId", UUID.randomUUID().toString(),
                        "summaryContent", command.summary_content(), "agentVersion", command.agent_version(),
                        "requestId", command.request_id(), "createdAt", createdAt))
                .fetch()
                .one()
                .orElseThrow(() -> new IllegalStateException("Failed to resolve AI_Consensus node_id from upsert query"));
        return toUpsertResult(result);
    }
    private void createMergeRelationships(MergeDecisionCommand command, OffsetDateTime relationshipCreatedAt) {
        Map<String, Object> result = neo4jClient.query(MERGE_RELATIONSHIPS_QUERY)
                .bindAll(Map.of("decisionId", command.decision_id(), "sourceNodeId", command.source_node_id().toString(),
                        "operatorType", command.operator_type().name(), "operatorId", command.operator_id(),
                        "createdAt", relationshipCreatedAt, "reason", command.reason(),
                        "contributorNodeIds", command.synthesized_from().stream().map(UUID::toString).toList()))
                .fetch()
                .one()
                .orElseThrow(() -> new IllegalStateException("Failed to create MERGED_INTO relationship"));
        long synthesizedCount = ((Number) result.get("synthesizedCount")).longValue();
        if (synthesizedCount != command.synthesized_from().size()) {
            throw new IllegalStateException(
                    "Expected " + command.synthesized_from().size()
                    + " SYNTHESIZED_FROM relationships but created " + synthesizedCount);
        }
    }
    private UpsertResult upsertBranchNode(BranchDecisionCommand command) {
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        Map<String, Object> result = neo4jClient.query(UPSERT_BRANCH_NODE_QUERY)
                .bindAll(Map.of("decisionId", command.decision_id(), "nodeId", UUID.randomUUID().toString(),
                        "requestId", command.request_id(), "content", command.content(),
                        "authorId", command.author_id(), "createdAt", createdAt))
                .fetch()
                .one()
                .orElseThrow(() -> new IllegalStateException("Failed to resolve Human_Post node_id from upsert query"));
        return toUpsertResult(result);
    }
    private void createBranchRelationship(BranchDecisionCommand command, OffsetDateTime relationshipCreatedAt) {
        neo4jClient.query(BRANCH_RELATIONSHIP_QUERY)
                .bindAll(Map.of("decisionId", command.decision_id(), "sourceNodeId", command.source_node_id().toString(),
                        "operatorType", command.operator_type().name(), "operatorId", command.operator_id(),
                        "createdAt", relationshipCreatedAt, "reason", command.reason()))
                .fetch()
                .one()
                .orElseThrow(() -> new IllegalStateException("Failed to create BRANCHED_FROM relationship"));
    }
    private void triggerEmbeddingForConsensus(UUID nodeId, String summaryContent, String decisionId) {
        runAfterCommit(() -> CompletableFuture.runAsync(
                () -> writeConsensusEmbedding(nodeId, summaryContent, decisionId),
                embeddingTaskExecutor
        ));
    }
    private void writeConsensusEmbedding(UUID nodeId, String summaryContent, String decisionId) {
        try {
            List<Float> vector = embeddingModelService.embed(summaryContent);
            embeddingService.writeEmbedding(nodeId.toString(), vector);
        } catch (Exception exception) {
            LOGGER.error("Failed to generate embedding for AI_Consensus node_id={}, decision_id={}",
                    nodeId,
                    decisionId,
                    exception
            );
        }
    }
    private void publishMergeEvents(MergeDecisionCommand command, UUID decisionNodeId, OffsetDateTime relationshipCreatedAt) {
        runAfterCommit(() -> CompletableFuture.runAsync(() -> {
            publishEdgeCreated(decisionNodeId, command.source_node_id(), "MERGED_INTO", relationshipCreatedAt);
            for (UUID contributorId : command.synthesized_from()) {
                publishEdgeCreated(decisionNodeId, contributorId, "SYNTHESIZED_FROM", relationshipCreatedAt);
            }
            publishDecisionComplete(decisionNodeId, DecisionType.MERGE, command.decision_id());
        }, embeddingTaskExecutor));
    }
    private void publishBranchEvents(BranchDecisionCommand command, UUID decisionNodeId, OffsetDateTime relationshipCreatedAt) {
        runAfterCommit(() -> CompletableFuture.runAsync(() -> {
            publishEdgeCreated(decisionNodeId, command.source_node_id(), "BRANCHED_FROM", relationshipCreatedAt);
            publishDecisionComplete(decisionNodeId, DecisionType.BRANCH, command.decision_id());
        }, embeddingTaskExecutor));
    }
    private void publishDecisionComplete(UUID nodeId, DecisionType decisionType, String decisionId) {
        SseEventService.DecisionCompletePayload payload = new SseEventService.DecisionCompletePayload(
                decisionId,
                decisionType.name(),
                nodeId.toString()
        );
        sseEventService.publish(SseEventType.DECISION_COMPLETE, payload);
    }
    private void publishEdgeCreated(UUID sourceNodeId, UUID targetNodeId, String relationshipType, OffsetDateTime createdAt) {
        SseEventService.EdgeCreatedPayload payload = new SseEventService.EdgeCreatedPayload(
                sourceNodeId.toString(),
                targetNodeId.toString(),
                relationshipType,
                createdAt.toInstant()
        );
        sseEventService.publish(SseEventType.EDGE_CREATED, payload);
    }
    private void runAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
            return;
        }
        task.run();
    }
    private void validateSourceNodeExists(UUID sourceNodeId) {
        Map<String, Object> result = neo4jClient.query(ACTIVE_SOURCE_EXISTS_QUERY)
                .bind(sourceNodeId.toString()).to("nodeId")
                .fetch()
                .one()
                .orElseThrow(() -> new IllegalStateException("Failed to validate source_node_id"));
        if (!Boolean.TRUE.equals(result.get("exists"))) {
            throw new NoSuchElementException("source_node_id not found: " + sourceNodeId);
        }
    }
    private void validateSynthesizedFromNodes(List<UUID> synthesizedFrom) {
        Set<UUID> uniqueIds = new LinkedHashSet<>(synthesizedFrom);
        List<Map<String, Object>> found = List.copyOf(neo4jClient.query(ACTIVE_HUMAN_POST_IDS_QUERY)
                .bind(uniqueIds.stream().map(UUID::toString).toList()).to("nodeIds")
                .fetch()
                .all());
        if (found.size() == uniqueIds.size()) {
            return;
        }
        Set<UUID> foundIds = new LinkedHashSet<>();
        for (Map<String, Object> row : found) {
            Object value = row.get("nodeId");
            if (value instanceof String nodeId) {
                foundIds.add(UUID.fromString(nodeId));
            }
        }
        Set<UUID> missing = new LinkedHashSet<>(uniqueIds);
        missing.removeAll(foundIds);
        throw new NoSuchElementException("synthesized_from node_id not found: " + missing);
    }
    private static UpsertResult toUpsertResult(Map<String, Object> result) {
        UUID nodeId = UUID.fromString((String) result.get("nodeId"));
        boolean created = Boolean.TRUE.equals(result.get("created"));
        return new UpsertResult(nodeId, created);
    }
}
