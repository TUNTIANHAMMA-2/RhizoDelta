package com.rhizodelta.service;

import com.rhizodelta.domain.node.AIConsensus;
import com.rhizodelta.domain.node.HumanPost;
import com.rhizodelta.repository.AIConsensusRepository;
import com.rhizodelta.repository.HumanPostRepository;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

@Service
public class NodeQueryService {
    private static final int DEFAULT_MAX_DEPTH = 10;
    private static final int MAX_ALLOWED_DEPTH = 50;

    private static final String LINEAGE_QUERY = """
            MATCH path = (start {node_id: $nodeId})-[:BRANCHED_FROM|MERGED_INTO*0..50]->(ancestor)
            WHERE length(path) <= $maxDepth
            WITH DISTINCT ancestor, labels(ancestor) AS nodeLabels
            RETURN ancestor.node_id AS nodeId,
                   CASE WHEN 'Human_Post' IN nodeLabels THEN 'Human_Post' ELSE 'AI_Consensus' END AS label,
                   ancestor.content AS content,
                   ancestor.summary_content AS summaryContent,
                   ancestor.author_id AS authorId,
                   ancestor.agent_version AS agentVersion,
                   ancestor.created_at AS createdAt,
                   ancestor.embedding IS NOT NULL AS hasEmbedding
            ORDER BY createdAt DESC
            """;

    private static final String NODE_SUMMARY_QUERY = """
            MATCH (n:GraphNode {node_id: $nodeId})
            WITH n, labels(n) AS nodeLabels
            RETURN n.node_id AS nodeId,
                   CASE WHEN 'Human_Post' IN nodeLabels THEN 'Human_Post' ELSE 'AI_Consensus' END AS label,
                   n.content AS content,
                   n.summary_content AS summaryContent,
                   n.author_id AS authorId,
                   n.agent_version AS agentVersion,
                   n.created_at AS createdAt,
                   n.embedding IS NOT NULL AS hasEmbedding
            """;

    private static final String NODE_TYPE_QUERY = """
            MATCH (n:GraphNode {node_id: $nodeId})
            RETURN CASE WHEN 'Human_Post' IN labels(n) THEN 'Human_Post' ELSE 'AI_Consensus' END AS label
            """;

    private static final String PROVENANCE_SUMMARY_QUERY = """
            MATCH (:AI_Consensus {node_id: $nodeId})-[:SYNTHESIZED_FROM]->(source:Human_Post)
            WITH source
            RETURN source.node_id AS nodeId,
                   'Human_Post' AS label,
                   source.content AS content,
                   source.summary_content AS summaryContent,
                   source.author_id AS authorId,
                   source.agent_version AS agentVersion,
                   source.created_at AS createdAt,
                   source.embedding IS NOT NULL AS hasEmbedding
            ORDER BY createdAt DESC
            """;

    private final HumanPostRepository humanPostRepository;
    private final AIConsensusRepository aiConsensusRepository;
    private final Neo4jClient neo4jClient;

    public NodeQueryService(HumanPostRepository humanPostRepository,
                            AIConsensusRepository aiConsensusRepository,
                            Neo4jClient neo4jClient) {
        this.humanPostRepository = humanPostRepository;
        this.aiConsensusRepository = aiConsensusRepository;
        this.neo4jClient = neo4jClient;
    }

    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public NodeResult getNodeById(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");

        return humanPostRepository.findByNodeId(nodeId)
                .<NodeResult>map(HumanPostNode::new)
                .or(() -> aiConsensusRepository.findByNodeId(nodeId).map(AIConsensusNode::new))
                .orElseThrow(() -> new NoSuchElementException("Node not found: " + nodeId));
    }

    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public List<LineageNode> getLineage(UUID nodeId, Integer maxDepth) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        int resolvedMaxDepth = resolveMaxDepth(maxDepth);

        Collection<Map<String, Object>> records = neo4jClient.query(LINEAGE_QUERY)
                .bind(nodeId.toString()).to("nodeId")
                .bind(resolvedMaxDepth).to("maxDepth")
                .fetch().all();

        return records.stream()
                .map(NodeQueryService::toLineageNode)
                .toList();
    }

    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public List<HumanPost> getProvenance(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");

        boolean isHumanPost = humanPostRepository.findByNodeId(nodeId).isPresent();
        if (isHumanPost) {
            return List.of();
        }

        aiConsensusRepository.findByNodeId(nodeId)
                .orElseThrow(() -> new NoSuchElementException("Node not found: " + nodeId));

        return humanPostRepository.findProvenance(nodeId);
    }

    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public LineageNode getNodeSummaryById(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        return neo4jClient.query(NODE_SUMMARY_QUERY)
                .bind(nodeId.toString()).to("nodeId")
                .fetch()
                .one()
                .map(NodeQueryService::toLineageNode)
                .orElseThrow(() -> new NoSuchElementException("Node not found: " + nodeId));
    }

    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public List<LineageNode> getProvenanceSummaries(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Map<String, Object> nodeInfo = neo4jClient.query(NODE_TYPE_QUERY)
                .bind(nodeId.toString()).to("nodeId")
                .fetch()
                .one()
                .orElseThrow(() -> new NoSuchElementException("Node not found: " + nodeId));
        if ("Human_Post".equals(nodeInfo.get("label"))) {
            return List.of();
        }
        return neo4jClient.query(PROVENANCE_SUMMARY_QUERY)
                .bind(nodeId.toString()).to("nodeId")
                .fetch().all()
                .stream()
                .map(NodeQueryService::toLineageNode)
                .toList();
    }

    private int resolveMaxDepth(Integer maxDepth) {
        if (maxDepth == null) {
            return DEFAULT_MAX_DEPTH;
        }
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth must be greater than 0");
        }
        return Math.min(maxDepth, MAX_ALLOWED_DEPTH);
    }

    private static LineageNode toLineageNode(Map<String, Object> record) {
        return new LineageNode(
                (String) record.get("nodeId"),
                (String) record.get("label"),
                (String) record.get("content"),
                (String) record.get("summaryContent"),
                (String) record.get("authorId"),
                (String) record.get("agentVersion"),
                toInstant(record.get("createdAt")),
                toBoolean(record.get("hasEmbedding"))
        );
    }

    private static boolean toBoolean(Object value) {
        return Boolean.TRUE.equals(value);
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime odt) return odt.toInstant();
        if (value instanceof ZonedDateTime zdt) return zdt.toInstant();
        return null;
    }

    public sealed interface NodeResult permits HumanPostNode, AIConsensusNode {
    }

    public record HumanPostNode(HumanPost node) implements NodeResult {
    }

    public record AIConsensusNode(AIConsensus node) implements NodeResult {
    }

    public record LineageNode(
            String nodeId,
            String label,
            String content,
            String summaryContent,
            String authorId,
            String agentVersion,
            Instant createdAt,
            boolean hasEmbedding) {
    }
}
