package com.rhizodelta.consensus.service;
import com.rhizodelta.consensus.domain.decision.BranchDecisionCommand;
import com.rhizodelta.consensus.domain.decision.CrossSynthDecisionCommand;
import com.rhizodelta.consensus.domain.decision.DecisionResult;
import com.rhizodelta.consensus.domain.decision.DecisionOperatorType;
import com.rhizodelta.consensus.domain.decision.ForkDecisionCommand;
import com.rhizodelta.consensus.domain.decision.ForkDecisionResult;
import com.rhizodelta.consensus.domain.decision.InjectDecisionCommand;
import com.rhizodelta.consensus.domain.decision.JoinDecisionCommand;
import com.rhizodelta.consensus.domain.decision.MaterializeDecisionCommand;
import com.rhizodelta.consensus.domain.decision.MergeDecisionCommand;
import com.rhizodelta.consensus.event.DecisionCommittedEvent;
import com.rhizodelta.consensus.repository.AIConsensusRepository;
import com.rhizodelta.core.repository.HumanPostRepository;
import com.rhizodelta.consensus.repository.ResultRepository;
import com.rhizodelta.consensus.service.DagIntegrityService;
import com.rhizodelta.infrastructure.user.service.TopicService;
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

/**
 * 负责执行共识层的各类决策写操作。
 *
 * <p>该服务是共识层最核心的事务边界，负责把各种决策命令落为图节点、关系边和提交后事件。
 *
 * <p><b>关键副作用</b>：
 * <ul>
 *   <li>会写 Neo4j 节点与关系。</li>
 *   <li>会调用 {@link DagIntegrityService} 做 DAG 环校验。</li>
 *   <li>事务提交后会发布 {@link DecisionCommittedEvent}，驱动 embedding、摘要和 SSE 链路。</li>
 * </ul>
 */
@Service
public class DecisionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DecisionService.class);
    private static final String QUEUED_STATUS = "QUEUED";
    private record UpsertResult(UUID nodeId, boolean created) {}

    /**
     * 表示合并最终是“新建共识”还是“追加来源”。
     */
    public record MergeOrAppendResult(
            DecisionResult decisionResult,
            boolean appended
    ) {}

    /**
     * 原子化"合并或追加"查询。
     *
     * <p>该查询是累积合并（cumulative merge）的核心：当多条回复指向同一主题的根节点时，
     * 它们应追加到同一个 AI_Consensus 下，而不是各自创建新的共识节点。
     *
     * <h3>共识查找策略（两级回退）</h3>
     * <ol>
     *   <li><b>直接匹配</b>：检查 source 节点上是否已有 MERGED_INTO 的共识。
     *       这是最常见的情况——用户直接回复根帖子，AI 以根帖子为 source 触发合并。</li>
     *   <li><b>根节点回退</b>：如果 source 上没有直接共识，则通过 source.root_id
     *       查找根节点上的共识。这处理了"用户回复中间节点 B（其 root_id 指向 A），
     *       而共识已存在于根节点 A 上"的场景。若不回退，会错误地创建第二个共识。</li>
     * </ol>
     *
     * <p>优先级：直接匹配 > 根节点回退 > 新建共识。
     *
     * <h3>场景示例</h3>
     * <pre>
     *   1. 创建根帖子 A (node_id=aaa, root_id=aaa)
     *   2. 回复 B → A (CONTINUES_FROM bbb→aaa, root_id=aaa)
     *   3. AI 以 A 为 source 合并 B → 创建 Consensus_1 (MERGED_INTO→A) ✓
     *   4. 用户选中 B，回复 C → B (CONTINUES_FROM ccc→bbb, root_id=aaa)
     *   5. AI 以 B 为 source 尝试合并 C：
     *      - 直接匹配：B 上没有共识 → 未命中
     *      - 根节点回退：B.root_id=aaa，找到 Consensus_1 MERGED_INTO→A → 命中！
     *      - 追加 C 到 Consensus_1 ✓（而非错误地创建 Consensus_2）
     * </pre>
     */
    private static final String ATOMIC_MERGE_OR_APPEND_QUERY = """
            MATCH (source:GraphNode {node_id: $sourceNodeId})
            WHERE NOT coalesce(source._deleted, false)
            SET source._merge_seq = coalesce(source._merge_seq, 0) + 1
            // Neo4j 5 要求 SET 与后续 MATCH/OPTIONAL MATCH 之间必须有 WITH，
            // 否则会报 "WITH is required between SET and MATCH"。
            WITH source

            // Level 1 — direct consensus: is there already a consensus MERGED_INTO this exact source node?
            OPTIONAL MATCH (direct:AI_Consensus)-[:MERGED_INTO]->(source)
              WHERE NOT coalesce(direct._deleted, false)
            WITH source, direct
            ORDER BY direct.created_at DESC
            LIMIT 1

            // Level 2 — root fallback: if no direct consensus, check whether the root node
            // (identified by source.root_id) already has a consensus.  This handles the case
            // where a user replies to an intermediate node B whose root is A, and a consensus
            // already exists on A from a prior merge.
            OPTIONAL MATCH (rootConsensus:AI_Consensus)-[:MERGED_INTO]->(:GraphNode {node_id: source.root_id})
              WHERE NOT coalesce(rootConsensus._deleted, false) AND direct IS NULL
            WITH source, direct, rootConsensus
            ORDER BY rootConsensus.created_at DESC
            LIMIT 1

            // Prefer direct consensus; fall back to root ancestor consensus; else null
            WITH source, direct, rootConsensus,
                 CASE WHEN direct IS NOT NULL THEN direct
                      WHEN rootConsensus IS NOT NULL THEN rootConsensus
                      ELSE NULL END AS existing

            WITH source, existing,
                 CASE WHEN existing IS NOT NULL THEN true ELSE false END AS appended

            // Only create a new consensus when no consensus exists anywhere in the ancestry chain
            FOREACH (_ IN CASE WHEN existing IS NULL THEN [1] ELSE [] END |
              CREATE (newAi:AI_Consensus:GraphNode {
                node_id: $newNodeId,
                decision_id: $decisionId,
                summary_content: $summaryContent,
                agent_version: $agentVersion,
                request_id: $requestId,
                root_id: coalesce(source.root_id, source.node_id),
                created_at: $createdAt,
                embedding: null
              })
              CREATE (newAi)-[:MERGED_INTO {
                operator_type: $operatorType,
                operator_id: $operatorId,
                created_at: $createdAt,
                reason: $reason
              }]->(source)
            )

            // Resolve the active consensus node:
            // - If we found an existing consensus (direct or root), use it directly.
            // - If we just created a new one, find it via MERGED_INTO->source.
            WITH source, existing, appended
            OPTIONAL MATCH (freshConsensus:AI_Consensus)-[:MERGED_INTO]->(source)
              WHERE NOT coalesce(freshConsensus._deleted, false) AND existing IS NULL
            WITH existing, freshConsensus, appended
            ORDER BY freshConsensus.created_at ASC
            LIMIT 1
            WITH CASE WHEN existing IS NOT NULL THEN existing ELSE freshConsensus END AS consensus,
                 appended

            // Link all contributor Human_Post nodes to the consensus via SYNTHESIZED_FROM
            UNWIND $contributorNodeIds AS cid
            MATCH (contributor:Human_Post:GraphNode {node_id: cid})
              WHERE NOT coalesce(contributor._deleted, false)
            MERGE (consensus)-[s:SYNTHESIZED_FROM]->(contributor)
            ON CREATE SET
              s.operator_type = $operatorType,
              s.operator_id = $operatorId,
              s.created_at = $createdAt,
              s.reason = $reason,
              s.decision_id = $decisionId
            RETURN toString(consensus.node_id) AS nodeId,
                   appended,
                   count(s) AS synthesizedCount
            """;
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
            RETURN count(synthesized) AS synthesizedCount
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
    private static final String LINK_EXISTING_BRANCH_QUERY = """
            MATCH (existing:Human_Post:GraphNode {node_id: $existingNodeId})
            WHERE NOT coalesce(existing._deleted, false)
            MATCH (source:GraphNode {node_id: $sourceNodeId})
            WHERE NOT coalesce(source._deleted, false)
            MERGE (existing)-[branched:BRANCHED_FROM]->(source)
            ON CREATE SET
              branched.operator_type = $operatorType,
              branched.operator_id = $operatorId,
              branched.created_at = $createdAt,
              branched.reason = $reason
            RETURN type(branched) AS relType
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
    private final TopicService topicService;
    private final DecisionMetadataService decisionMetadataService;

    public DecisionService(Neo4jClient neo4jClient, HumanPostRepository humanPostRepository, AIConsensusRepository aiConsensusRepository, ResultRepository resultRepository, DagIntegrityService dagIntegrityService, ApplicationEventPublisher eventPublisher, TopicService topicService, DecisionMetadataService decisionMetadataService) {
        this.neo4jClient = neo4jClient;
        this.humanPostRepository = humanPostRepository;
        this.aiConsensusRepository = aiConsensusRepository;
        this.resultRepository = resultRepository;
        this.dagIntegrityService = dagIntegrityService;
        this.eventPublisher = eventPublisher;
        this.topicService = topicService;
        this.decisionMetadataService = decisionMetadataService;
    }

    /**
     * 执行一次标准合并决策。
     *
     * <p>该方法会创建新的共识节点并挂接来源；若命中幂等 upsert，则直接返回现有结果。
     */
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
        topicService.getOrCreateTopic(command.summary_content(), "DECISION");
        decisionMetadataService.recordDecision(
                command.decision_id(), "MERGE", command.operator_type(), command.operator_id(),
                upsertResult.nodeId(), command.reason(), relationshipCreatedAt);
        eventPublisher.publishEvent(new DecisionCommittedEvent.MergeCompleted(
                command.decision_id(), upsertResult.nodeId(), command.source_node_id(),
                command.synthesized_from(), command.summary_content(), relationshipCreatedAt));
        return new DecisionResult(command.decision_id(), upsertResult.nodeId(), QUEUED_STATUS);
    }

    /**
     * 执行“新建或追加”的原子化合并逻辑。
     *
     * <p>若源节点上已经存在共识，则只追加来源；否则新建共识节点。
     */
    @Transactional(transactionManager = "transactionManager")
    public MergeOrAppendResult mergeOrAppend(MergeDecisionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        validateSourceNodeExists(command.source_node_id());
        validateSynthesizedFromNodes(command.synthesized_from());

        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        UUID newNodeId = UUID.randomUUID();

        Map<String, Object> params = new java.util.HashMap<>();
        params.put("sourceNodeId", command.source_node_id().toString());
        params.put("newNodeId", newNodeId.toString());
        params.put("decisionId", command.decision_id());
        params.put("summaryContent", command.summary_content());
        params.put("agentVersion", command.agent_version());
        params.put("requestId", command.request_id());
        params.put("createdAt", createdAt);
        params.put("operatorType", command.operator_type().name());
        params.put("operatorId", command.operator_id());
        params.put("reason", command.reason());
        params.put("contributorNodeIds", command.synthesized_from().stream().map(UUID::toString).toList());

        Map<String, Object> result = neo4jClient.query(ATOMIC_MERGE_OR_APPEND_QUERY)
                .bindAll(params)
                .fetch()
                .one()
                .orElseThrow(() -> new IllegalStateException(
                        "Atomic merge-or-append query returned no result for source " + command.source_node_id()));

        UUID consensusNodeId = UUID.fromString((String) result.get("nodeId"));
        boolean appended = Boolean.TRUE.equals(result.get("appended"));

        LOGGER.info("mergeOrAppend completed: consensus={} appended={} source={} contributors={}",
                consensusNodeId, appended, command.source_node_id(), command.synthesized_from().size());

        if (appended) {
            decisionMetadataService.recordDecision(
                    command.decision_id(), "MERGE", command.operator_type(), command.operator_id(),
                    consensusNodeId, command.reason(), createdAt);
            eventPublisher.publishEvent(new DecisionCommittedEvent.MergeAppended(
                    command.decision_id(), consensusNodeId, command.source_node_id(),
                    command.synthesized_from(), createdAt));
        } else {
            dagIntegrityService.assertNoVersionEvolutionCycle(consensusNodeId, command.source_node_id());
            topicService.getOrCreateTopic(command.summary_content(), "DECISION");
            decisionMetadataService.recordDecision(
                    command.decision_id(), "MERGE", command.operator_type(), command.operator_id(),
                    consensusNodeId, command.reason(), createdAt);
            eventPublisher.publishEvent(new DecisionCommittedEvent.MergeCompleted(
                    command.decision_id(), consensusNodeId, command.source_node_id(),
                    command.synthesized_from(), command.summary_content(), createdAt));
        }

        DecisionResult decisionResult = new DecisionResult(command.decision_id(), consensusNodeId, QUEUED_STATUS);
        return new MergeOrAppendResult(decisionResult, appended);
    }

    /**
     * 执行一次分支决策。
     */
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
        decisionMetadataService.recordDecision(
                command.decision_id(), "BRANCH", command.operator_type(), command.operator_id(),
                upsertResult.nodeId(), command.reason(), relationshipCreatedAt);
        eventPublisher.publishEvent(new DecisionCommittedEvent.BranchCompleted(
                command.decision_id(), upsertResult.nodeId(), command.source_node_id(),
                command.contributor_node_ids(), relationshipCreatedAt));
        return new DecisionResult(command.decision_id(), upsertResult.nodeId(), QUEUED_STATUS);
    }

    /**
     * 将一个既有帖子节点挂为新的分支节点。
     *
     * <p>该入口主要服务于 AI 路由场景：回复帖子本身已存在，只需要补一条
     * {@code BRANCHED_FROM} 关系，而不再创建新节点。
     */
    @Transactional(transactionManager = "transactionManager")
    public DecisionResult linkBranch(
            String decisionId,
            UUID existingNodeId,
            UUID sourceNodeId,
            DecisionOperatorType operatorType,
            String operatorId,
            String reason,
            List<UUID> contributorNodeIds
    ) {
        Objects.requireNonNull(existingNodeId, "existingNodeId must not be null");
        validateSourceNodeExists(sourceNodeId);
        dagIntegrityService.assertNoVersionEvolutionCycle(existingNodeId, sourceNodeId);
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        neo4jClient.query(LINK_EXISTING_BRANCH_QUERY)
                .bindAll(Map.of(
                        "existingNodeId", existingNodeId.toString(),
                        "sourceNodeId", sourceNodeId.toString(),
                        "operatorType", operatorType.name(),
                        "operatorId", operatorId,
                        "createdAt", createdAt,
                        "reason", reason
                ))
                .fetch()
                .one()
                .orElseThrow(() -> new IllegalStateException(
                        "Failed to link branch: existing node " + existingNodeId + " or source " + sourceNodeId + " not found"));
        decisionMetadataService.recordDecision(
                decisionId, "BRANCH", operatorType, operatorId,
                existingNodeId, reason, createdAt);
        eventPublisher.publishEvent(new DecisionCommittedEvent.BranchCompleted(
                decisionId, existingNodeId, sourceNodeId,
                contributorNodeIds, createdAt));
        return new DecisionResult(decisionId, existingNodeId, QUEUED_STATUS);
    }

    /**
     * 执行一次注入决策。
     */
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
        decisionMetadataService.recordDecision(
                command.decision_id(), "INJECT", command.operator_type(), command.operator_id(),
                upsertResult.nodeId(), command.reason(), relationshipCreatedAt);
        eventPublisher.publishEvent(new DecisionCommittedEvent.InjectCompleted(
                command.decision_id(), upsertResult.nodeId(), command.source_node_id(),
                command.content(), relationshipCreatedAt));
        return new DecisionResult(command.decision_id(), upsertResult.nodeId(), QUEUED_STATUS);
    }

    /**
     * 执行一次物化决策。
     */
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
        decisionMetadataService.recordDecision(
                command.decision_id(), "MATERIALIZE", command.operator_type(), command.operator_id(),
                upsertResult.nodeId(), command.reason(), relationshipCreatedAt);
        eventPublisher.publishEvent(new DecisionCommittedEvent.MaterializeCompleted(
                command.decision_id(), upsertResult.nodeId(), command.source_node_id(),
                command.content(), relationshipCreatedAt));
        return new DecisionResult(command.decision_id(), upsertResult.nodeId(), QUEUED_STATUS);
    }

    /**
     * 执行一次分叉决策。
     */
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
        for (int i = 0; i < command.branches().size(); i++) {
            ForkDecisionCommand.ForkBranchSpec branch = command.branches().get(i);
            decisionMetadataService.recordDecision(
                    branch.decision_id(), "FORK", command.operator_type(), command.operator_id(),
                    nodeIds.get(i), command.reason(), createdAt);
        }
        eventPublisher.publishEvent(new DecisionCommittedEvent.ForkCompleted(
                command.operation_id(), nodeIds, command.source_node_id(), createdAt));
        return new ForkDecisionResult(command.operation_id(), nodeIds, QUEUED_STATUS,
                (int) createdCount, command.branches().size());
    }

    /**
     * 执行一次跨结果综合决策。
     */
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
        decisionMetadataService.recordDecision(
                command.decision_id(), "CROSS_SYNTH", command.operator_type(), command.operator_id(),
                resolvedNodeId, command.reason(), createdAt);
        eventPublisher.publishEvent(new DecisionCommittedEvent.CrossSynthCompleted(
                command.decision_id(), resolvedNodeId, command.source_result_ids(),
                command.content(), createdAt));
        return new DecisionResult(command.decision_id(), resolvedNodeId, QUEUED_STATUS);
    }

    /**
     * 执行一次汇合决策。
     */
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
        decisionMetadataService.recordDecision(
                command.decision_id(), "JOIN", command.operator_type(), command.operator_id(),
                upsertResult.nodeId(), command.reason(), relationshipCreatedAt);
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
        // JOIN upsert 需要用首个来源节点推导 root_id；漏绑 sourceNodeIds 会在运行时触发 Neo4j ParameterMissing。
        Map<String, Object> result = neo4jClient.query(UPSERT_JOIN_NODE_QUERY)
                .bindAll(Map.of("decisionId", command.decision_id(), "nodeId", UUID.randomUUID().toString(),
                        "summaryContent", command.summary_content(), "agentVersion", command.agent_version(),
                        "requestId", command.request_id(), "createdAt", createdAt,
                        "sourceNodeIds", stringifySourceIds(command)))
                .fetch()
                .one()
                .orElseThrow(() -> new IllegalStateException("Failed to resolve AI_Consensus node_id from join upsert query"));
        return toUpsertResult(result);
    }
    private static List<String> stringifySourceIds(JoinDecisionCommand command) {
        return command.source_node_ids().stream().map(UUID::toString).toList();
    }
    private void createJoinRelationships(JoinDecisionCommand command, OffsetDateTime relationshipCreatedAt) {
        Map<String, Object> result = neo4jClient.query(JOIN_RELATIONSHIPS_QUERY)
                .bindAll(Map.of("decisionId", command.decision_id(),
                        "sourceNodeIds", stringifySourceIds(command),
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
