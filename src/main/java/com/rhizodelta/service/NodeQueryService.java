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
import java.util.ArrayList;
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
            WITH collect(nodes(path)) AS nodeLists, collect(relationships(path)) AS relLists
            UNWIND nodeLists AS nodeList
            UNWIND nodeList AS node
            WITH collect(DISTINCT node) AS uniqueNodes, relLists
            WITH [node IN uniqueNodes WHERE NOT coalesce(node._deleted, false)] AS filteredNodes,
                 reduce(all = [], rels IN relLists | all + rels) AS allRels
            WITH [node IN filteredNodes | {
                  nodeId: toString(node.node_id),
                  label: CASE WHEN 'Human_Post' IN labels(node) THEN 'Human_Post' ELSE 'AI_Consensus' END,
                  content: node.content,
                  summaryContent: node.summary_content,
                  authorId: node.author_id,
                  agentVersion: node.agent_version,
                  createdAt: node.created_at,
                  hasEmbedding: node.embedding IS NOT NULL
            }] AS nodes,
                 [rel IN allRels WHERE NOT coalesce(startNode(rel)._deleted, false)
                                  AND NOT coalesce(endNode(rel)._deleted, false) | {
                  source: toString(startNode(rel).node_id),
                  target: toString(endNode(rel).node_id),
                  type: type(rel),
                  createdAt: rel.created_at
            }] AS edges
            RETURN nodes, edges
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
        return getLineageTopology(nodeId, maxDepth).nodes();
    }

    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public GraphTopology getLineageTopology(UUID nodeId, Integer maxDepth) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        int resolvedMaxDepth = resolveMaxDepth(maxDepth);

        Collection<Map<String, Object>> records = neo4jClient.query(LINEAGE_QUERY)
                .bind(nodeId.toString()).to("nodeId")
                .bind(resolvedMaxDepth).to("maxDepth")
                .fetch().all();

        if (records.isEmpty()) {
            return new GraphTopology(List.of(), List.of());
        }

        return toGraphTopology(records.iterator().next());
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

    private static GraphTopology toGraphTopology(Map<String, Object> record) {
        List<LineageNode> nodes = toLineageNodes(record.get("nodes"));
        List<LineageEdge> edges = toLineageEdges(record.get("edges"));
        return new GraphTopology(nodes, edges);
    }

    private static List<LineageNode> toLineageNodes(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("nodes must be a list");
        }
        List<LineageNode> nodes = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                throw new IllegalStateException("node entry must be a map");
            }
            nodes.add(toLineageNodeMap(map));
        }
        return List.copyOf(nodes);
    }

    private static List<LineageEdge> toLineageEdges(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("edges must be a list");
        }
        List<LineageEdge> edges = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                throw new IllegalStateException("edge entry must be a map");
            }
            edges.add(toLineageEdgeMap(map));
        }
        return List.copyOf(edges);
    }

    private static LineageNode toLineageNodeMap(Map<?, ?> entry) {
        return new LineageNode(
                toStringValue(entry.get("nodeId"), "nodeId"),
                (String) entry.get("label"),
                (String) entry.get("content"),
                (String) entry.get("summaryContent"),
                (String) entry.get("authorId"),
                (String) entry.get("agentVersion"),
                toInstant(entry.get("createdAt")),
                toBoolean(entry.get("hasEmbedding"))
        );
    }

    private static LineageEdge toLineageEdgeMap(Map<?, ?> entry) {
        return new LineageEdge(
                toStringValue(entry.get("source"), "source"),
                toStringValue(entry.get("target"), "target"),
                (String) entry.get("type"),
                toInstant(entry.get("createdAt"))
        );
    }

    private static String toStringValue(Object value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof UUID uuid) {
            return uuid.toString();
        }
        throw new IllegalStateException(fieldName + " must be a string");
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

    public record GraphTopology(
            List<LineageNode> nodes,
            List<LineageEdge> edges) {
    }

    public record LineageEdge(
            String source,
            String target,
            String type,
            Instant createdAt) {
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
