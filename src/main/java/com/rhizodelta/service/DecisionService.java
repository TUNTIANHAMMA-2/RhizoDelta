package com.rhizodelta.service;
import com.rhizodelta.domain.decision.BranchDecisionCommand;
import com.rhizodelta.domain.decision.CrossSynthDecisionCommand;
import com.rhizodelta.domain.decision.DecisionResult;
import com.rhizodelta.domain.decision.ForkDecisionCommand;
import com.rhizodelta.domain.decision.ForkDecisionResult;
import com.rhizodelta.domain.decision.InjectDecisionCommand;
import com.rhizodelta.domain.decision.JoinDecisionCommand;
import com.rhizodelta.domain.decision.MaterializeDecisionCommand;
import com.rhizodelta.domain.decision.MergeDecisionCommand;
import com.rhizodelta.event.DecisionCommittedEvent;
import com.rhizodelta.repository.AIConsensusRepository;
import com.rhizodelta.repository.HumanPostRepository;
import com.rhizodelta.repository.ResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
    private static final Logger LOGGER = LoggerFactory.getLogger(DecisionService.class);
    private static final String QUEUED_STATUS = "QUEUED";
    private record UpsertResult(UUID nodeId, boolean created) {}
    private static final String UPSERT_MERGE_NODE_QUERY = """
            MATCH (source:GraphNode {node_id: $sourceNodeId})
            MERGE (decision:AI_Consensus {decision_id: $decisionId})
            ON CREATE SET
              decision:GraphNode,
              decision.node_id = $nodeId,
              decision.summary_content = $summaryContent,
              decision.agent_version = $agentVersion,
              decision.request_id = $requestId,
              decision.root_id = coalesce(source.root_id, source.node_id),
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
            WITH contributor, count(synthesized) AS cnt
            OPTIONAL MATCH (contributor)-[pending:PENDING_EVALUATION]->()
            DELETE pending
            RETURN sum(cnt) AS synthesizedCount
            """;
    private static final String UPSERT_BRANCH_NODE_QUERY = """
            MATCH (source:GraphNode {node_id: $sourceNodeId})
            MERGE (decision:Human_Post {decision_id: $decisionId})
            ON CREATE SET
              decision:GraphNode,
              decision.node_id = $nodeId,
              decision.request_id = $requestId,
              decision.content = $content,
              decision.author_id = $authorId,
              decision.root_id = coalesce(source.root_id, source.node_id),
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
    private static final String DELETE_PENDING_EVALUATION_QUERY = """
            UNWIND $contributorNodeIds AS cid
            MATCH (contributor:Human_Post:GraphNode {node_id: cid})
            OPTIONAL MATCH (contributor)-[pending:PENDING_EVALUATION]->()
            DELETE pending
            RETURN count(pending) AS deletedCount
            """;
    private static final String UPSERT_INJECT_NODE_QUERY = """
            MATCH (source:GraphNode {node_id: $sourceNodeId})
            MERGE (decision:Human_Post {decision_id: $decisionId})
            ON CREATE SET
              decision:GraphNode,
              decision.node_id = $nodeId,
              decision.request_id = $requestId,
              decision.content = $content,
              decision.author_id = $authorId,
              decision.root_id = coalesce(source.root_id, source.node_id),
              decision.created_at = $createdAt,
              decision.embedding = null
            RETURN toString(decision.node_id) AS nodeId,
                   decision.node_id = $nodeId AS created
            """;
    private static final String INJECT_RELATIONSHIP_QUERY = """
            MATCH (decision:Human_Post {decision_id: $decisionId})
            MATCH (source:GraphNode {node_id: $sourceNodeId})
            MERGE (decision)-[rel:CONTINUES_FROM]->(source)
            ON CREATE SET
              rel.operator_type = $operatorType,
              rel.operator_id = $operatorId,
              rel.created_at = $createdAt,
              rel.reason = $reason
            RETURN type(rel) AS relType
            """;
    private static final String UPSERT_MATERIALIZE_NODE_QUERY = """
            MATCH (source:GraphNode {node_id: $sourceNodeId})
            MERGE (result:Result {decision_id: $decisionId})
            ON CREATE SET
              result:GraphNode,
              result.node_id = $nodeId,
              result.request_id = $requestId,
              result.content = $content,
              result.operator_type = $operatorType,
              result.operator_id = $operatorId,
              result.root_id = coalesce(source.root_id, source.node_id),
              result.created_at = $createdAt,
              result.embedding = null
            RETURN toString(result.node_id) AS nodeId,
                   result.node_id = $nodeId AS created
            """;
    private static final String MATERIALIZE_RELATIONSHIP_QUERY = """
            MATCH (result:Result {decision_id: $decisionId})
            MATCH (source:GraphNode {node_id: $sourceNodeId})
            MERGE (result)-[rel:MATERIALIZED_FROM]->(source)
            ON CREATE SET
              rel.operator_type = $operatorType,
              rel.operator_id = $operatorId,
              rel.created_at = $createdAt,
              rel.reason = $reason
            RETURN type(rel) AS relType
            """;
    private static final String FORK_QUERY = """
            MATCH (source:GraphNode {node_id: $sourceNodeId})
            UNWIND $branches AS b
            MERGE (decision:Human_Post {decision_id: b.decisionId})
            ON CREATE SET
              decision:GraphNode,
              decision.node_id = b.nodeId,
              decision.request_id = b.requestId,
              decision.content = b.content,
              decision.author_id = b.authorId,
              decision.root_id = coalesce(source.root_id, source.node_id),
              decision.created_at = $createdAt,
              decision.embedding = null,
              decision.operation_id = $operationId
            WITH source, decision, b
            MERGE (decision)-[rel:BRANCHED_FROM]->(source)
            ON CREATE SET
              rel.operator_type = $operatorType,
              rel.operator_id = $operatorId,
              rel.created_at = $createdAt,
              rel.reason = $reason,
              rel.operation_id = $operationId
            RETURN count(DISTINCT decision) AS createdCount
            """;
    private static final String UPSERT_CROSS_SYNTH_QUERY = """
            WITH $sourceResultNodeIds AS sourceIds
            MATCH (lineageSource:Result:GraphNode {node_id: sourceIds[0]})
            MERGE (result:Result {decision_id: $decisionId})
            ON CREATE SET
              result:GraphNode,
              result.node_id = $nodeId,
              result.request_id = $requestId,
              result.content = $content,
              result.operator_type = $operatorType,
              result.operator_id = $operatorId,
              result.root_id = coalesce(lineageSource.root_id, lineageSource.node_id),
              result.created_at = $createdAt,
              result.embedding = null
            WITH result, sourceIds
            UNWIND sourceIds AS sourceId
            MATCH (source:Result:GraphNode {node_id: sourceId})
            MERGE (result)-[rel:CROSS_SYNTHESIZED_FROM]->(source)
            ON CREATE SET
              rel.operator_type = $operatorType,
              rel.operator_id = $operatorId,
              rel.created_at = $createdAt,
              rel.reason = $reason
            RETURN toString(result.node_id) AS nodeId,
                   count(rel) AS crossSynthCount
            """;
    private static final String UPSERT_JOIN_NODE_QUERY = """
            WITH $sourceNodeIds AS sourceIds
            MATCH (lineageSource:GraphNode {node_id: sourceIds[0]})
            MERGE (decision:AI_Consensus {decision_id: $decisionId})
            ON CREATE SET
              decision:GraphNode,
              decision.node_id = $nodeId,
              decision.summary_content = $summaryContent,
              decision.agent_version = $agentVersion,
              decision.request_id = $requestId,
              decision.root_id = coalesce(lineageSource.root_id, lineageSource.node_id),
              decision.created_at = $createdAt,
              decision.embedding = null
            RETURN toString(decision.node_id) AS nodeId,
                   decision.node_id = $nodeId AS created
            """;
    private static final String JOIN_RELATIONSHIPS_QUERY = """
            MATCH (decision:AI_Consensus {decision_id: $decisionId})
            WITH decision, $sourceNodeIds AS sourceIds
            UNWIND sourceIds AS sourceId
            MATCH (source:GraphNode {node_id: sourceId})
            MERGE (decision)-[rel:CONVERGED_FROM]->(source)
            ON CREATE SET
              rel.operator_type = $operatorType,
              rel.operator_id = $operatorId,
              rel.created_at = $createdAt,
              rel.reason = $reason
            RETURN count(rel) AS convergedCount
            """;
    private final Neo4jClient neo4jClient;
    private final HumanPostRepository humanPostRepository;
    private final AIConsensusRepository aiConsensusRepository;
    private final ResultRepository resultRepository;
    private final DagIntegrityService dagIntegrityService;
    private final ApplicationEventPublisher eventPublisher;
    public DecisionService(Neo4jClient neo4jClient, HumanPostRepository humanPostRepository, AIConsensusRepository aiConsensusRepository, ResultRepository resultRepository, DagIntegrityService dagIntegrityService, ApplicationEventPublisher eventPublisher) {
        this.neo4jClient = neo4jClient;
        this.humanPostRepository = humanPostRepository;
        this.aiConsensusRepository = aiConsensusRepository;
        this.resultRepository = resultRepository;
        this.dagIntegrityService = dagIntegrityService;
        this.eventPublisher = eventPublisher;
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
        eventPublisher.publishEvent(new DecisionCommittedEvent.MergeCompleted(
                command.decision_id(), upsertResult.nodeId(), command.source_node_id(),
                command.synthesized_from(), command.summary_content(), relationshipCreatedAt));
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
        deletePendingEvaluationEdges(command.contributor_node_ids());
        eventPublisher.publishEvent(new DecisionCommittedEvent.BranchCompleted(
                command.decision_id(), upsertResult.nodeId(), command.source_node_id(),
                command.contributor_node_ids(), relationshipCreatedAt));
        return new DecisionResult(command.decision_id(), upsertResult.nodeId(), QUEUED_STATUS);
    }
    @Transactional(transactionManager = "transactionManager")
    public DecisionResult executeInject(InjectDecisionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        validateSourceNodeExists(command.source_node_id());
        UpsertResult upsertResult = upsertInjectNode(command);
        if (!upsertResult.created()) {
            return new DecisionResult(command.decision_id(), upsertResult.nodeId(), QUEUED_STATUS);
        }
        dagIntegrityService.assertNoVersionEvolutionCycle(upsertResult.nodeId(), command.source_node_id());
        OffsetDateTime relationshipCreatedAt = OffsetDateTime.now(ZoneOffset.UTC);
        createInjectRelationship(command, relationshipCreatedAt);
        eventPublisher.publishEvent(new DecisionCommittedEvent.InjectCompleted(
                command.decision_id(), upsertResult.nodeId(), command.source_node_id(),
                command.content(), relationshipCreatedAt));
        return new DecisionResult(command.decision_id(), upsertResult.nodeId(), QUEUED_STATUS);
    }
    @Transactional(transactionManager = "transactionManager")
    public DecisionResult executeMaterialize(MaterializeDecisionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        validateSourceNodeExists(command.source_node_id());
        UpsertResult upsertResult = upsertMaterializeNode(command);
        if (!upsertResult.created()) {
            return new DecisionResult(command.decision_id(), upsertResult.nodeId(), QUEUED_STATUS);
        }
        OffsetDateTime relationshipCreatedAt = OffsetDateTime.now(ZoneOffset.UTC);
        createMaterializeRelationship(command, relationshipCreatedAt);
        eventPublisher.publishEvent(new DecisionCommittedEvent.MaterializeCompleted(
                command.decision_id(), upsertResult.nodeId(), command.source_node_id(),
                command.content(), relationshipCreatedAt));
        return new DecisionResult(command.decision_id(), upsertResult.nodeId(), QUEUED_STATUS);
    }
    @Transactional(transactionManager = "transactionManager")
    public ForkDecisionResult executeFork(ForkDecisionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        validateSourceNodeExists(command.source_node_id());
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        List<Map<String, Object>> branchParams = new java.util.ArrayList<>();
        for (int i = 0; i < command.branches().size(); i++) {
            ForkDecisionCommand.ForkBranchSpec b = command.branches().get(i);
            branchParams.add(Map.of(
                    "decisionId", b.decision_id(),
                    "nodeId", UUID.randomUUID().toString(),
                    "content", b.content(),
                    "authorId", b.author_id(),
                    "requestId", command.request_id() + "-" + i));
        }
        Map<String, Object> result = neo4jClient.query(FORK_QUERY)
                .bindAll(Map.of(
                        "sourceNodeId", command.source_node_id().toString(),
                        "branches", branchParams,
                        "createdAt", createdAt,
                        "operationId", command.operation_id(),
                        "operatorType", command.operator_type().name(),
                        "operatorId", command.operator_id(),
                        "reason", command.reason()))
                .fetch()
                .one()
                .orElseThrow(() -> new IllegalStateException("Failed to execute fork query"));
        long createdCount = ((Number) result.get("createdCount")).longValue();
        List<UUID> nodeIds = branchParams.stream()
                .map(b -> UUID.fromString((String) b.get("nodeId")))
                .toList();
        eventPublisher.publishEvent(new DecisionCommittedEvent.ForkCompleted(
                command.operation_id(), nodeIds, command.source_node_id(), createdAt));
        return new ForkDecisionResult(command.operation_id(), nodeIds, QUEUED_STATUS,
                (int) createdCount, command.branches().size());
    }
    @Transactional(transactionManager = "transactionManager")
    public DecisionResult executeCrossSynth(CrossSynthDecisionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        validateAllSourceResultsExist(command.source_result_ids());
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        UUID nodeId = UUID.randomUUID();
        Map<String, Object> result = neo4jClient.query(UPSERT_CROSS_SYNTH_QUERY)
                .bindAll(Map.of(
                        "decisionId", command.decision_id(),
                        "nodeId", nodeId.toString(),
                        "requestId", command.request_id(),
                        "content", command.content(),
                        "operatorType", command.operator_type().name(),
                        "operatorId", command.operator_id(),
                        "createdAt", createdAt,
                        "reason", command.reason(),
                        "sourceResultNodeIds", command.source_result_ids().stream().map(UUID::toString).toList()))
                .fetch()
                .one()
                .orElseThrow(() -> new IllegalStateException("Failed to execute cross-synth query"));
        UUID resolvedNodeId = UUID.fromString((String) result.get("nodeId"));
        long crossSynthCount = ((Number) result.get("crossSynthCount")).longValue();
        if (crossSynthCount != command.source_result_ids().size()) {
            throw new IllegalStateException(
                    "Expected " + command.source_result_ids().size()
                    + " CROSS_SYNTHESIZED_FROM relationships but created " + crossSynthCount);
        }
        for (UUID sourceResultId : command.source_result_ids()) {
            dagIntegrityService.assertNoResultLayerCycle(resolvedNodeId, sourceResultId);
        }
        eventPublisher.publishEvent(new DecisionCommittedEvent.CrossSynthCompleted(
                command.decision_id(), resolvedNodeId, command.source_result_ids(),
                command.content(), createdAt));
        return new DecisionResult(command.decision_id(), resolvedNodeId, QUEUED_STATUS);
    }
    @Transactional(transactionManager = "transactionManager")
    public DecisionResult executeJoin(JoinDecisionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        validateAllSourceNodesExist(command.source_node_ids());
        UpsertResult upsertResult = upsertJoinNode(command);
        if (!upsertResult.created()) {
            return new DecisionResult(command.decision_id(), upsertResult.nodeId(), QUEUED_STATUS);
        }
        for (UUID sourceNodeId : command.source_node_ids()) {
            dagIntegrityService.assertNoVersionEvolutionCycle(upsertResult.nodeId(), sourceNodeId);
        }
        OffsetDateTime relationshipCreatedAt = OffsetDateTime.now(ZoneOffset.UTC);
        createJoinRelationships(command, relationshipCreatedAt);
        eventPublisher.publishEvent(new DecisionCommittedEvent.JoinCompleted(
                command.decision_id(), upsertResult.nodeId(), command.source_node_ids(),
                command.summary_content(), relationshipCreatedAt));
        return new DecisionResult(command.decision_id(), upsertResult.nodeId(), QUEUED_STATUS);
    }
    private UpsertResult upsertMergeNode(MergeDecisionCommand command) {
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        Map<String, Object> result = neo4jClient.query(UPSERT_MERGE_NODE_QUERY)
                .bindAll(Map.of("decisionId", command.decision_id(), "nodeId", UUID.randomUUID().toString(),
                        "summaryContent", command.summary_content(), "agentVersion", command.agent_version(),
                        "requestId", command.request_id(), "createdAt", createdAt,
                        "sourceNodeId", command.source_node_id().toString()))
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
                        "authorId", command.author_id(), "createdAt", createdAt,
                        "sourceNodeId", command.source_node_id().toString()))
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
    private void deletePendingEvaluationEdges(List<UUID> contributorNodeIds) {
        if (contributorNodeIds == null || contributorNodeIds.isEmpty()) {
            return;
        }
        neo4jClient.query(DELETE_PENDING_EVALUATION_QUERY)
                .bind(contributorNodeIds.stream().map(UUID::toString).toList()).to("contributorNodeIds")
                .fetch()
                .one()
                .ifPresent(result -> {
                    long deleted = ((Number) result.get("deletedCount")).longValue();
                    if (deleted > 0) {
                        LOGGER.debug("Deleted {} PENDING_EVALUATION edge(s) for contributors {}", deleted, contributorNodeIds);
                    }
                });
    }
    private UpsertResult upsertInjectNode(InjectDecisionCommand command) {
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        Map<String, Object> result = neo4jClient.query(UPSERT_INJECT_NODE_QUERY)
                .bindAll(Map.of("decisionId", command.decision_id(), "nodeId", UUID.randomUUID().toString(),
                        "requestId", command.request_id(), "content", command.content(),
                        "authorId", command.author_id(), "createdAt", createdAt,
                        "sourceNodeId", command.source_node_id().toString()))
                .fetch()
                .one()
                .orElseThrow(() -> new IllegalStateException("Failed to resolve Human_Post node_id from inject upsert query"));
        return toUpsertResult(result);
    }
    private void createInjectRelationship(InjectDecisionCommand command, OffsetDateTime relationshipCreatedAt) {
        neo4jClient.query(INJECT_RELATIONSHIP_QUERY)
                .bindAll(Map.of("decisionId", command.decision_id(), "sourceNodeId", command.source_node_id().toString(),
                        "operatorType", command.operator_type().name(), "operatorId", command.operator_id(),
                        "createdAt", relationshipCreatedAt, "reason", command.reason()))
                .fetch()
                .one()
                .orElseThrow(() -> new IllegalStateException("Failed to create CONTINUES_FROM relationship"));
    }
    private UpsertResult upsertMaterializeNode(MaterializeDecisionCommand command) {
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        Map<String, Object> result = neo4jClient.query(UPSERT_MATERIALIZE_NODE_QUERY)
                .bindAll(Map.of("decisionId", command.decision_id(), "nodeId", UUID.randomUUID().toString(),
                        "requestId", command.request_id(), "content", command.content(),
                        "operatorType", command.operator_type().name(), "operatorId", command.operator_id(),
                        "createdAt", createdAt, "sourceNodeId", command.source_node_id().toString()))
                .fetch()
                .one()
                .orElseThrow(() -> new IllegalStateException("Failed to resolve Result node_id from materialize upsert query"));
        return toUpsertResult(result);
    }
    private void createMaterializeRelationship(MaterializeDecisionCommand command, OffsetDateTime relationshipCreatedAt) {
        neo4jClient.query(MATERIALIZE_RELATIONSHIP_QUERY)
                .bindAll(Map.of("decisionId", command.decision_id(), "sourceNodeId", command.source_node_id().toString(),
                        "operatorType", command.operator_type().name(), "operatorId", command.operator_id(),
                        "createdAt", relationshipCreatedAt, "reason", command.reason()))
                .fetch()
                .one()
                .orElseThrow(() -> new IllegalStateException("Failed to create MATERIALIZED_FROM relationship"));
    }
    private UpsertResult upsertJoinNode(JoinDecisionCommand command) {
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        Map<String, Object> result = neo4jClient.query(UPSERT_JOIN_NODE_QUERY)
                .bindAll(Map.of("decisionId", command.decision_id(), "nodeId", UUID.randomUUID().toString(),
                        "summaryContent", command.summary_content(), "agentVersion", command.agent_version(),
                        "requestId", command.request_id(), "createdAt", createdAt))
                .fetch()
                .one()
                .orElseThrow(() -> new IllegalStateException("Failed to resolve AI_Consensus node_id from join upsert query"));
        return toUpsertResult(result);
    }
    private void createJoinRelationships(JoinDecisionCommand command, OffsetDateTime relationshipCreatedAt) {
        Map<String, Object> result = neo4jClient.query(JOIN_RELATIONSHIPS_QUERY)
                .bindAll(Map.of("decisionId", command.decision_id(),
                        "sourceNodeIds", command.source_node_ids().stream().map(UUID::toString).toList(),
                        "operatorType", command.operator_type().name(), "operatorId", command.operator_id(),
                        "createdAt", relationshipCreatedAt, "reason", command.reason()))
                .fetch()
                .one()
                .orElseThrow(() -> new IllegalStateException("Failed to create CONVERGED_FROM relationships"));
        long convergedCount = ((Number) result.get("convergedCount")).longValue();
        if (convergedCount != command.source_node_ids().size()) {
            throw new IllegalStateException(
                    "Expected " + command.source_node_ids().size()
                    + " CONVERGED_FROM relationships but created " + convergedCount);
        }
    }
    private void validateSourceNodeExists(UUID sourceNodeId) {
        boolean exists = humanPostRepository.existsActiveByNodeId(sourceNodeId)
                || aiConsensusRepository.existsActiveByNodeId(sourceNodeId)
                || resultRepository.existsActiveByNodeId(sourceNodeId);
        if (!exists) {
            throw new NoSuchElementException("source_node_id not found: " + sourceNodeId);
        }
    }
    private void validateSynthesizedFromNodes(List<UUID> synthesizedFrom) {
        Set<UUID> uniqueIds = new LinkedHashSet<>(synthesizedFrom);
        List<String> foundStrings = humanPostRepository.findActiveNodeIdStrings(
                uniqueIds.stream().map(UUID::toString).toList());
        if (foundStrings.size() == uniqueIds.size()) {
            return;
        }
        Set<UUID> foundIds = new LinkedHashSet<>();
        for (String nodeId : foundStrings) {
            foundIds.add(UUID.fromString(nodeId));
        }
        Set<UUID> missing = new LinkedHashSet<>(uniqueIds);
        missing.removeAll(foundIds);
        throw new NoSuchElementException("synthesized_from node_id not found: " + missing);
    }
    private void validateAllSourceNodesExist(List<UUID> sourceNodeIds) {
        for (UUID sourceNodeId : sourceNodeIds) {
            validateSourceNodeExists(sourceNodeId);
        }
    }
    private void validateAllSourceResultsExist(List<UUID> sourceResultIds) {
        if (sourceResultIds == null || sourceResultIds.isEmpty()) {
            throw new IllegalArgumentException("source_result_ids must not be empty");
        }
        for (UUID resultId : sourceResultIds) {
            boolean exists = neo4jClient.query("""
                    MATCH (r:Result:GraphNode {node_id: $nodeId})
                    WHERE NOT coalesce(r._deleted, false)
                    RETURN count(r) > 0 AS exists
                    """)
                    .bind(resultId.toString()).to("nodeId")
                    .fetchAs(Boolean.class)
                    .one()
                    .orElse(Boolean.FALSE);
            if (!exists) {
                throw new NoSuchElementException("source_result_id not found: " + resultId);
            }
        }
    }
    private static UpsertResult toUpsertResult(Map<String, Object> result) {
        UUID nodeId = UUID.fromString((String) result.get("nodeId"));
        boolean created = Boolean.TRUE.equals(result.get("created"));
        return new UpsertResult(nodeId, created);
    }
}
