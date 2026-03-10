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
