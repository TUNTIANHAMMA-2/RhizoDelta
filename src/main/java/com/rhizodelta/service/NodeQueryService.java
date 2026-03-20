package com.rhizodelta.service;

import com.rhizodelta.domain.node.AIConsensus;
import com.rhizodelta.domain.node.HumanPost;
import com.rhizodelta.domain.node.Result;
import com.rhizodelta.repository.AIConsensusRepository;
import com.rhizodelta.repository.HumanPostRepository;
import com.rhizodelta.repository.ResultRepository;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class NodeQueryService {
    private static final int DEFAULT_MAX_DEPTH = 10;
    private static final int DEFAULT_CHILDREN_DEPTH = 3;
    private static final int MAX_ALLOWED_DEPTH = 50;
    private static final int NO_LIMIT = Integer.MAX_VALUE;

    private static final String LINEAGE_QUERY = """
            MATCH path = (start:GraphNode {node_id: $nodeId})-[:BRANCHED_FROM|MERGED_INTO|CONTINUES_FROM|CONVERGED_FROM*0..50]->(ancestor)
            WHERE NOT coalesce(start._deleted, false) AND length(path) <= $maxDepth
            WITH collect(nodes(path)) AS nodeLists, collect(relationships(path)) AS relLists
            UNWIND nodeLists AS nodeList
            UNWIND nodeList AS node
            WITH collect(DISTINCT node) AS uniqueNodes,
                 reduce(all = [], rels IN relLists | all + rels) AS allRels
            UNWIND (CASE WHEN allRels = [] THEN [NULL] ELSE allRels END) AS rel
            WITH [node IN uniqueNodes WHERE NOT coalesce(node._deleted, false)] AS filteredNodes,
                 [r IN collect(DISTINCT rel) WHERE r IS NOT NULL] AS uniqueRels
            WITH [node IN filteredNodes | {
                  nodeId: toString(node.node_id),
                  label: CASE WHEN 'Human_Post' IN labels(node) THEN 'Human_Post' WHEN 'Result' IN labels(node) THEN 'Result' ELSE 'AI_Consensus' END,
                  content: node.content,
                  summaryContent: node.summary_content,
                  authorId: node.author_id,
                  agentVersion: node.agent_version,
                  createdAt: node.created_at,
                  hasEmbedding: node.embedding IS NOT NULL
            }] AS nodes,
                 [rel IN uniqueRels WHERE NOT coalesce(startNode(rel)._deleted, false)
                                  AND NOT coalesce(endNode(rel)._deleted, false) | {
                  source: toString(startNode(rel).node_id),
                  target: toString(endNode(rel).node_id),
                  type: type(rel),
                  createdAt: rel.created_at
            }] AS edges
            RETURN nodes, edges
            """;

    private static final String CHILDREN_QUERY = """
            MATCH path = (start:GraphNode {node_id: $nodeId})<-[:BRANCHED_FROM|MERGED_INTO|CONTINUES_FROM|CONVERGED_FROM|MATERIALIZED_FROM|CROSS_SYNTHESIZED_FROM*0..50]-(descendant)
            WHERE NOT coalesce(start._deleted, false) AND length(path) <= $maxDepth
            WITH collect(nodes(path)) AS nodeLists, collect(relationships(path)) AS relLists
            UNWIND nodeLists AS nodeList
            UNWIND nodeList AS node
            WITH collect(DISTINCT node) AS uniqueNodes,
                 reduce(all = [], rels IN relLists | all + rels) AS allRels
            UNWIND (CASE WHEN allRels = [] THEN [NULL] ELSE allRels END) AS rel
            WITH [node IN uniqueNodes WHERE NOT coalesce(node._deleted, false)] AS filteredNodes,
                 [r IN collect(DISTINCT rel) WHERE r IS NOT NULL] AS uniqueRels
            WITH [node IN filteredNodes | {
                  nodeId: toString(node.node_id),
                  label: CASE WHEN 'Human_Post' IN labels(node) THEN 'Human_Post' WHEN 'Result' IN labels(node) THEN 'Result' ELSE 'AI_Consensus' END,
                  content: node.content,
                  summaryContent: node.summary_content,
                  authorId: node.author_id,
                  agentVersion: node.agent_version,
                  createdAt: node.created_at,
                  hasEmbedding: node.embedding IS NOT NULL
            }] AS nodes,
                 [rel IN uniqueRels WHERE NOT coalesce(startNode(rel)._deleted, false)
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
            WHERE NOT coalesce(n._deleted, false)
            WITH n, labels(n) AS nodeLabels
            RETURN n.node_id AS nodeId,
                   CASE WHEN 'Human_Post' IN nodeLabels THEN 'Human_Post' WHEN 'Result' IN nodeLabels THEN 'Result' ELSE 'AI_Consensus' END AS label,
                   n.content AS content,
                   n.summary_content AS summaryContent,
                   n.author_id AS authorId,
                   n.agent_version AS agentVersion,
                   n.created_at AS createdAt,
                   n.embedding IS NOT NULL AS hasEmbedding
            """;

    private static final String NODE_TYPE_QUERY = """
            MATCH (n:GraphNode {node_id: $nodeId})
            WHERE NOT coalesce(n._deleted, false)
            RETURN CASE WHEN 'Human_Post' IN labels(n) THEN 'Human_Post' WHEN 'Result' IN labels(n) THEN 'Result' ELSE 'AI_Consensus' END AS label
            """;

    private static final String PROVENANCE_SUMMARY_QUERY = """
            MATCH (consensus:AI_Consensus {node_id: $nodeId})-[:SYNTHESIZED_FROM]->(source:Human_Post)
            WHERE NOT coalesce(consensus._deleted, false)
              AND NOT coalesce(source._deleted, false)
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

    private static final String ROOTS_QUERY = """
            MATCH (n:GraphNode)
            WHERE NOT coalesce(n._deleted, false)
              AND NOT (n)-[:BRANCHED_FROM|MERGED_INTO|CONTINUES_FROM|CONVERGED_FROM|MATERIALIZED_FROM|CROSS_SYNTHESIZED_FROM]->()
            WITH n, labels(n) AS nodeLabels
            RETURN n.node_id AS nodeId,
                   CASE WHEN 'Human_Post' IN nodeLabels THEN 'Human_Post' WHEN 'Result' IN nodeLabels THEN 'Result' ELSE 'AI_Consensus' END AS label,
                   n.content AS content,
                   n.summary_content AS summaryContent,
                   n.author_id AS authorId,
                   n.agent_version AS agentVersion,
                   n.created_at AS createdAt,
                   n.embedding IS NOT NULL AS hasEmbedding
            ORDER BY createdAt DESC
            LIMIT $limit
            """;

    private final HumanPostRepository humanPostRepository;
    private final AIConsensusRepository aiConsensusRepository;
    private final ResultRepository resultRepository;
    private final Neo4jClient neo4jClient;

    public NodeQueryService(HumanPostRepository humanPostRepository,
                            AIConsensusRepository aiConsensusRepository,
                            ResultRepository resultRepository,
                            Neo4jClient neo4jClient) {
        this.humanPostRepository = humanPostRepository;
        this.aiConsensusRepository = aiConsensusRepository;
        this.resultRepository = resultRepository;
        this.neo4jClient = neo4jClient;
    }

    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public NodeResult getNodeById(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");

        return humanPostRepository.findActiveByNodeId(nodeId)
                .<NodeResult>map(HumanPostNode::new)
                .or(() -> aiConsensusRepository.findActiveByNodeId(nodeId).map(AIConsensusNode::new))
                .or(() -> resultRepository.findActiveByNodeId(nodeId).map(ResultNode::new))
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

        return executeGraphTopologyQuery(LINEAGE_QUERY, nodeId, resolvedMaxDepth);
    }

    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public GraphTopology getChildrenTopology(UUID nodeId, Integer maxDepth, Integer limit) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        int resolvedMaxDepth = resolveChildrenDepth(maxDepth);
        GraphTopology topology = executeGraphTopologyQuery(CHILDREN_QUERY, nodeId, resolvedMaxDepth);
        if (topology.nodes().isEmpty()) {
            throw new NoSuchElementException("Node not found: " + nodeId);
        }
        return applyChildrenLimit(nodeId, topology, limit);
    }

    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public List<HumanPost> getProvenance(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");

        boolean isHumanPost = humanPostRepository.findActiveByNodeId(nodeId).isPresent();
        if (isHumanPost) {
            return List.of();
        }

        aiConsensusRepository.findActiveByNodeId(nodeId)
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

    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public List<LineageNode> getRoots(Integer limit) {
        int resolvedLimit = limit == null || limit <= 0 ? 50 : limit;
        return neo4jClient.query(ROOTS_QUERY)
                .bind(resolvedLimit).to("limit")
                .fetch().all()
                .stream()
                .map(NodeQueryService::toLineageNodeMap)
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

    private int resolveChildrenDepth(Integer maxDepth) {
        if (maxDepth == null) {
            return DEFAULT_CHILDREN_DEPTH;
        }
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth must be greater than 0");
        }
        return Math.min(maxDepth, MAX_ALLOWED_DEPTH);
    }

    private int resolveChildrenLimit(Integer limit) {
        if (limit == null) {
            return NO_LIMIT;
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }
        return limit;
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

    private GraphTopology executeGraphTopologyQuery(String query, UUID nodeId, int maxDepth) {
        Collection<Map<String, Object>> records = neo4jClient.query(query)
                .bind(nodeId.toString()).to("nodeId")
                .bind(maxDepth).to("maxDepth")
                .fetch().all();

        if (records.isEmpty()) {
            return new GraphTopology(List.of(), List.of());
        }

        return toGraphTopology(records.iterator().next());
    }

    private GraphTopology applyChildrenLimit(UUID nodeId, GraphTopology topology, Integer limit) {
        int resolvedLimit = resolveChildrenLimit(limit);
        if (resolvedLimit == NO_LIMIT) {
            return topology;
        }
        return limitTopology(nodeId, topology, resolvedLimit);
    }

    private GraphTopology limitTopology(UUID nodeId, GraphTopology topology, int limit) {
        String rootId = nodeId.toString();
        LineageNode rootNode = findRootNode(topology.nodes(), rootId);
        if (rootNode == null) {
            return topology;
        }
        Map<String, LineageNode> nodeIndex = indexNodes(topology.nodes());
        List<String> orderedNodeIds = collectConnectedNodeIds(rootId, topology.edges(), limit);
        List<LineageNode> limitedNodes = toLimitedNodes(orderedNodeIds, nodeIndex);
        if (limitedNodes.isEmpty()) {
            return topology;
        }
        List<LineageEdge> edges = filterEdges(topology.edges(), limitedNodes);
        return new GraphTopology(List.copyOf(limitedNodes), List.copyOf(edges));
    }

    private LineageNode findRootNode(List<LineageNode> nodes, String rootId) {
        for (LineageNode node : nodes) {
            if (rootId.equals(node.nodeId())) {
                return node;
            }
        }
        return null;
    }

    private Map<String, LineageNode> indexNodes(List<LineageNode> nodes) {
        Map<String, LineageNode> index = new java.util.HashMap<>();
        for (LineageNode node : nodes) {
            if (node.nodeId() != null) {
                index.put(node.nodeId(), node);
            }
        }
        return index;
    }

    private List<String> collectConnectedNodeIds(String rootId, List<LineageEdge> edges, int limit) {
        Map<String, Set<String>> adjacency = buildAdjacency(edges);
        Set<String> visited = new HashSet<>();
        List<String> ordered = new ArrayList<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        visited.add(rootId);
        ordered.add(rootId);
        queue.add(rootId);
        int maxNodes = limit + 1;
        while (!queue.isEmpty() && ordered.size() < maxNodes) {
            String current = queue.removeFirst();
            for (String neighbor : adjacency.getOrDefault(current, Set.of())) {
                if (!visited.add(neighbor)) {
                    continue;
                }
                ordered.add(neighbor);
                if (ordered.size() >= maxNodes) {
                    break;
                }
                queue.addLast(neighbor);
            }
        }
        return ordered;
    }

    private Map<String, Set<String>> buildAdjacency(List<LineageEdge> edges) {
        Map<String, Set<String>> adjacency = new java.util.HashMap<>();
        for (LineageEdge edge : edges) {
            appendNeighbor(adjacency, edge.source(), edge.target());
            appendNeighbor(adjacency, edge.target(), edge.source());
        }
        return adjacency;
    }

    private void appendNeighbor(Map<String, Set<String>> adjacency, String from, String to) {
        if (from == null || to == null) {
            return;
        }
        adjacency.computeIfAbsent(from, key -> new java.util.LinkedHashSet<>()).add(to);
    }

    private List<LineageNode> toLimitedNodes(List<String> nodeIds, Map<String, LineageNode> nodeIndex) {
        List<LineageNode> limitedNodes = new ArrayList<>(nodeIds.size());
        for (String nodeId : nodeIds) {
            LineageNode node = nodeIndex.get(nodeId);
            if (node != null) {
                limitedNodes.add(node);
            }
        }
        return limitedNodes;
    }

    private List<LineageEdge> filterEdges(List<LineageEdge> edges, List<LineageNode> nodes) {
        Set<String> allowedNodeIds = new HashSet<>();
        for (LineageNode node : nodes) {
            if (node.nodeId() != null) {
                allowedNodeIds.add(node.nodeId());
            }
        }
        return edges.stream()
                .filter(edge -> allowedNodeIds.contains(edge.source()) && allowedNodeIds.contains(edge.target()))
                .toList();
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

    public sealed interface NodeResult permits HumanPostNode, AIConsensusNode, ResultNode {
    }

    public record HumanPostNode(HumanPost node) implements NodeResult {
    }

    public record AIConsensusNode(AIConsensus node) implements NodeResult {
    }

    public record ResultNode(Result node) implements NodeResult {
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
