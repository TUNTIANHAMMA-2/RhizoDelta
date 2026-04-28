package com.rhizodelta.query.service;

import com.rhizodelta.consensus.domain.node.AIConsensus;
import com.rhizodelta.core.domain.node.HumanPost;
import com.rhizodelta.consensus.domain.node.Result;
import com.rhizodelta.consensus.repository.AIConsensusRepository;
import com.rhizodelta.core.repository.HumanPostRepository;
import com.rhizodelta.consensus.repository.ResultRepository;
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

/**
 * 负责构建节点摘要、谱系拓扑与子树拓扑的只读查询服务。
 *
 * <p>该服务统一封装帖子节点、共识节点和结果节点的读取逻辑，并将底层图查询映射为上层可直接消费的
 * 统一摘要与拓扑对象。
 *
 * <p><b>关键特征</b>：
 * <ul>
 *   <li>所有公开方法都以只读事务执行，不修改图数据。</li>
 *   <li>会主动排除软删除节点，避免查询层重新暴露逻辑删除对象。</li>
 *   <li>谱系与子树查询不仅遍历主干关系，还会附带共识、来源和结果层节点。</li>
 * </ul>
 */
@Service
public class NodeQueryService {
    private static final int DEFAULT_MAX_DEPTH = 10;
    private static final int DEFAULT_CHILDREN_DEPTH = 3;
    private static final int MAX_ALLOWED_DEPTH = 50;
    private static final int NO_LIMIT = Integer.MAX_VALUE;

    private static final String LINEAGE_QUERY = """
            // Stage 1: Physical tree traversal (Human_Post backbone)
            MATCH path = (start:GraphNode {node_id: $nodeId})
                         -[:CONTINUES_FROM|BRANCHED_FROM*0..50]->(ancestor)
            WHERE NOT coalesce(start._deleted, false) AND length(path) <= $maxDepth
            WITH collect(nodes(path)) AS nodeLists, collect(relationships(path)) AS relLists
            UNWIND nodeLists AS nodeList
            UNWIND nodeList AS node
            WITH collect(DISTINCT node) AS uniqueNodes,
                 reduce(all = [], rels IN relLists | all + rels) AS allRels
            UNWIND (CASE WHEN allRels = [] THEN [NULL] ELSE allRels END) AS rel
            WITH [node IN uniqueNodes WHERE NOT coalesce(node._deleted, false)] AS filteredNodes,
                 [r IN collect(DISTINCT rel) WHERE r IS NOT NULL] AS uniqueRels

            // Stage 2: Attach AI_Consensus nodes hanging off backbone nodes
            WITH filteredNodes, uniqueRels
            UNWIND filteredNodes AS bn
            OPTIONAL MATCH (ai:AI_Consensus)-[mr:MERGED_INTO]->(bn)
              WHERE NOT coalesce(ai._deleted, false)
            OPTIONAL MATCH (ai)-[sf:SYNTHESIZED_FROM]->(contributor:GraphNode)
              WHERE NOT coalesce(contributor._deleted, false)
            WITH filteredNodes, uniqueRels,
                 collect(DISTINCT ai) AS aiNodes,
                 collect(DISTINCT contributor) AS contributorNodes,
                 collect(DISTINCT mr) AS mergedRels,
                 collect(DISTINCT sf) AS synthRels

            WITH [node IN (filteredNodes +
                           [n IN aiNodes WHERE n IS NOT NULL] +
                           [n IN contributorNodes WHERE n IS NOT NULL])
                  WHERE NOT coalesce(node._deleted, false) | {
                  nodeId: toString(node.node_id),
                  label: CASE WHEN 'Human_Post' IN labels(node) THEN 'Human_Post' WHEN 'Result' IN labels(node) THEN 'Result' ELSE 'AI_Consensus' END,
                  content: node.content,
                  summaryContent: node.summary_content,
                  authorId: node.author_id,
                  agentVersion: node.agent_version,
                  createdAt: node.created_at,
                  hasEmbedding: node.embedding IS NOT NULL,
                  qualityOverall: node.quality_overall
            }] AS nodes,
                 [rel IN (uniqueRels +
                          [r IN mergedRels WHERE r IS NOT NULL] +
                          [r IN synthRels WHERE r IS NOT NULL])
                  WHERE NOT coalesce(startNode(rel)._deleted, false)
                    AND NOT coalesce(endNode(rel)._deleted, false) | {
                  source: toString(startNode(rel).node_id),
                  target: toString(endNode(rel).node_id),
                  type: type(rel),
                  createdAt: rel.created_at
            }] AS edges
            RETURN nodes, edges
            """;

    private static final String CHILDREN_QUERY = """
            // Stage 1: Physical tree traversal (inbound CONTINUES_FROM|BRANCHED_FROM)
            MATCH path = (start:GraphNode {node_id: $nodeId})
                         <-[:CONTINUES_FROM|BRANCHED_FROM*0..50]-(descendant)
            WHERE NOT coalesce(start._deleted, false) AND length(path) <= $maxDepth
            WITH collect(nodes(path)) AS nodeLists, collect(relationships(path)) AS relLists
            UNWIND nodeLists AS nodeList
            UNWIND nodeList AS node
            WITH collect(DISTINCT node) AS uniqueNodes,
                 reduce(all = [], rels IN relLists | all + rels) AS allRels
            UNWIND (CASE WHEN allRels = [] THEN [NULL] ELSE allRels END) AS rel
            WITH [node IN uniqueNodes WHERE NOT coalesce(node._deleted, false)] AS filteredNodes,
                 [r IN collect(DISTINCT rel) WHERE r IS NOT NULL] AS uniqueRels

            // Stage 2: Attach AI_Consensus + SYNTHESIZED_FROM edges
            WITH filteredNodes, uniqueRels
            UNWIND filteredNodes AS bn
            OPTIONAL MATCH (ai:AI_Consensus)-[mr:MERGED_INTO]->(bn)
              WHERE NOT coalesce(ai._deleted, false)
            OPTIONAL MATCH (ai)-[sf:SYNTHESIZED_FROM]->(contributor:GraphNode)
              WHERE NOT coalesce(contributor._deleted, false)
            WITH filteredNodes, uniqueRels,
                 collect(DISTINCT ai) AS aiNodes,
                 collect(DISTINCT contributor) AS contributorNodes,
                 collect(DISTINCT mr) AS mergedRels,
                 collect(DISTINCT sf) AS synthRels

            // Also attach Result and cross-synth edges from backbone
            WITH filteredNodes, uniqueRels, aiNodes, contributorNodes, mergedRels, synthRels
            UNWIND filteredNodes AS bn2
            OPTIONAL MATCH (res:Result)-[mf:MATERIALIZED_FROM]->(bn2)
              WHERE NOT coalesce(res._deleted, false)
            OPTIONAL MATCH (cs:Result)-[csf:CROSS_SYNTHESIZED_FROM]->(res)
              WHERE NOT coalesce(cs._deleted, false) AND res IS NOT NULL
            WITH filteredNodes, uniqueRels, aiNodes, contributorNodes, mergedRels, synthRels,
                 collect(DISTINCT res) AS resNodes,
                 collect(DISTINCT cs) AS csNodes,
                 collect(DISTINCT mf) AS matRels,
                 collect(DISTINCT csf) AS csRels

            WITH [node IN (filteredNodes +
                           [n IN aiNodes WHERE n IS NOT NULL] +
                           [n IN contributorNodes WHERE n IS NOT NULL] +
                           [n IN resNodes WHERE n IS NOT NULL] +
                           [n IN csNodes WHERE n IS NOT NULL])
                  WHERE NOT coalesce(node._deleted, false) | {
                  nodeId: toString(node.node_id),
                  label: CASE WHEN 'Human_Post' IN labels(node) THEN 'Human_Post' WHEN 'Result' IN labels(node) THEN 'Result' ELSE 'AI_Consensus' END,
                  content: node.content,
                  summaryContent: node.summary_content,
                  authorId: node.author_id,
                  agentVersion: node.agent_version,
                  createdAt: node.created_at,
                  hasEmbedding: node.embedding IS NOT NULL,
                  qualityOverall: node.quality_overall
            }] AS nodes,
                 [rel IN (uniqueRels +
                          [r IN mergedRels WHERE r IS NOT NULL] +
                          [r IN synthRels WHERE r IS NOT NULL] +
                          [r IN matRels WHERE r IS NOT NULL] +
                          [r IN csRels WHERE r IS NOT NULL])
                  WHERE NOT coalesce(startNode(rel)._deleted, false)
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
                   n.embedding IS NOT NULL AS hasEmbedding,
                   n.quality_overall AS qualityOverall
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
                   source.embedding IS NOT NULL AS hasEmbedding,
                   source.quality_overall AS qualityOverall
            ORDER BY createdAt DESC
            """;

    private static final String ROOTS_QUERY = """
            MATCH (n:GraphNode)
            WHERE NOT coalesce(n._deleted, false)
              AND n.root_id = n.node_id
              AND NOT EXISTS {
                MATCH (n)<-[:SYNTHESIZED_FROM]-(ai:AI_Consensus)-[:MERGED_INTO]->(target)
                WHERE target.node_id <> n.node_id AND NOT coalesce(ai._deleted, false)
              }
            WITH n, labels(n) AS nodeLabels
            RETURN n.node_id AS nodeId,
                   CASE WHEN 'Human_Post' IN nodeLabels THEN 'Human_Post' WHEN 'Result' IN nodeLabels THEN 'Result' ELSE 'AI_Consensus' END AS label,
                   n.content AS content,
                   n.summary_content AS summaryContent,
                   n.author_id AS authorId,
                   n.agent_version AS agentVersion,
                   n.created_at AS createdAt,
                   n.embedding IS NOT NULL AS hasEmbedding,
                   n.quality_overall AS qualityOverall
            ORDER BY createdAt DESC
            LIMIT $limit
            """;
    private static final String AUTHOR_PROJECTION_QUERY = """
            MATCH (user:UserAccount)
            WHERE user.user_id IN $authorIds
            OPTIONAL MATCH (user)-[:HAS_PROFILE]->(profile:UserProfile)
            WITH user, head(collect(profile.display_name)) AS profileDisplayName
            RETURN user.user_id AS authorId,
                   user.username AS authorUsername,
                   CASE
                     WHEN profileDisplayName IS NULL OR trim(profileDisplayName) = '' THEN user.username
                     ELSE profileDisplayName
                   END AS authorDisplayName
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

    /**
     * 按节点 ID 返回底层实体的统一视图。
     *
     * <p>该方法存在的意义，是让上层调用方无需分别访问多个仓储，
     * 就能在一个入口中解析 {@link HumanPost}、{@link AIConsensus} 与 {@link Result}。
     *
     * <p>
     *
     * @param nodeId 节点 ID。
     * @return 节点结果视图。
     */
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public NodeResult getNodeById(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");

        return humanPostRepository.findActiveByNodeId(nodeId)
                .<NodeResult>map(HumanPostNode::new)
                .or(() -> aiConsensusRepository.findActiveByNodeId(nodeId).map(AIConsensusNode::new))
                .or(() -> resultRepository.findActiveByNodeId(nodeId).map(ResultNode::new))
                .orElseThrow(() -> new NoSuchElementException("Node not found: " + nodeId));
    }

    /**
     * 返回节点的祖先谱系节点列表。
     *
     * <p>这是 {@link #getLineageTopology(UUID, Integer)} 的便捷封装，
     * 适合只关心节点集合而不需要边关系的场景。
     *
     * <p>
     *
     * @param nodeId 节点 ID。
     * @param maxDepth 可选最大深度。
     * @return 谱系节点列表。
     */
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public List<LineageNode> getLineage(UUID nodeId, Integer maxDepth) {
        return getLineageTopology(nodeId, maxDepth).nodes();
    }

    /**
     * 构建节点向上的谱系拓扑。
     *
     * <p>该方法会沿着版本演化主干向祖先遍历，并补挂与主干节点相关的共识及来源边，
     * 以提供完整的“回溯视图”。
     *
     * <p>
     *
     * @param nodeId 节点 ID。
     * @param maxDepth 可选最大深度。
     * @return 谱系拓扑。
     */
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public GraphTopology getLineageTopology(UUID nodeId, Integer maxDepth) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        int resolvedMaxDepth = resolveMaxDepth(maxDepth);

        return executeGraphTopologyQuery(LINEAGE_QUERY, nodeId, resolvedMaxDepth);
    }

    /**
     * 构建节点向下的子树拓扑。
     *
     * <p>该方法会把回复、分支、共识与结果层节点一起纳入结果，因此适合“从当前节点继续向后观察”的场景。
     *
     * <p><b>注意事项</b>：
     * <ul>
     *   <li>若节点不存在，会抛出 {@link NoSuchElementException} 而不是返回空拓扑。</li>
     *   <li>{@code limit} 作用于最终裁剪后的连通节点集合，而不是底层 Cypher 直接限流。</li>
     * </ul>
     *
     * <p>
     *
     * @param nodeId 节点 ID。
     * @param maxDepth 可选最大深度。
     * @param limit 可选节点数量限制。
     * @return 子树拓扑。
     */
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

    /**
     * 返回共识节点的来源帖子实体。
     *
     * <p>该方法适用于需要完整帖子对象而非摘要投影的场景。
     *
     * <p>
     *
     * @param nodeId 节点 ID。
     * @return 来源帖子列表；若目标是帖子节点则返回空列表。
     */
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

    /**
     * 返回单个节点的统一摘要。
     *
     * <p>该摘要结构跨越三种节点类型做了字段归一化，便于上层统一渲染。
     *
     * <p>
     *
     * @param nodeId 节点 ID。
     * @return 节点摘要。
     */
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public LineageNode getNodeSummaryById(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        return neo4jClient.query(NODE_SUMMARY_QUERY)
                .bind(nodeId.toString()).to("nodeId")
                .fetch()
                .one()
                .map(NodeQueryService::toLineageNode)
                .map(this::enrichAuthorProjection)
                .orElseThrow(() -> new NoSuchElementException("Node not found: " + nodeId));
    }

    /**
     * 返回共识节点的来源摘要列表。
     *
     * <p>相比 {@link #getProvenance(UUID)}，该方法更轻量，更适合 UI 展示与提示词构建。
     *
     * <p>
     *
     * @param nodeId 节点 ID。
     * @return 来源摘要列表；若目标本身是帖子节点则返回空列表。
     */
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
        List<LineageNode> nodes = neo4jClient.query(PROVENANCE_SUMMARY_QUERY)
                .bind(nodeId.toString()).to("nodeId")
                .fetch().all()
                .stream()
                .map(NodeQueryService::toLineageNode)
                .toList();
        return enrichAuthorProjections(nodes);
    }

    /**
     * 返回当前图谱中的根节点摘要列表。
     *
     * <p>该方法服务于全局导航和入口视图，会排除已删除节点以及仅作为挂接来源的特殊节点。
     *
     * <p>
     *
     * @param limit 可选数量限制。
     * @return 根节点摘要列表。
     */
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public List<LineageNode> getRoots(Integer limit) {
        int resolvedLimit = limit == null || limit <= 0 ? 50 : limit;
        List<LineageNode> nodes = neo4jClient.query(ROOTS_QUERY)
                .bind(resolvedLimit).to("limit")
                .fetch().all()
                .stream()
                .map(NodeQueryService::toLineageNodeMap)
                .toList();
        return enrichAuthorProjections(nodes);
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
                toBoolean(record.get("hasEmbedding")),
                toNullableDouble(record.get("qualityOverall"))
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

        return enrichGraphTopology(toGraphTopology(records.iterator().next()));
    }

    private GraphTopology enrichGraphTopology(GraphTopology topology) {
        return new GraphTopology(enrichAuthorProjections(topology.nodes()), topology.edges());
    }

    private List<LineageNode> enrichAuthorProjections(List<LineageNode> nodes) {
        Set<String> authorIds = nodes.stream()
                .map(LineageNode::authorId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (authorIds.isEmpty()) {
            return nodes;
        }
        Map<String, AuthorProjection> projections = fetchAuthorProjections(authorIds);
        return nodes.stream()
                .map(node -> enrichAuthorProjection(node, projections))
                .toList();
    }

    private LineageNode enrichAuthorProjection(LineageNode node) {
        if (node.authorId() == null) {
            return node;
        }
        return enrichAuthorProjections(List.of(node)).get(0);
    }

    private LineageNode enrichAuthorProjection(LineageNode node, Map<String, AuthorProjection> projections) {
        if (node.authorId() == null) {
            return node;
        }
        AuthorProjection projection = projections.get(node.authorId());
        if (projection == null) {
            return node;
        }
        return new LineageNode(
                node.nodeId(),
                node.label(),
                node.content(),
                node.summaryContent(),
                node.authorId(),
                projection.authorUsername(),
                projection.authorDisplayName(),
                node.agentVersion(),
                node.createdAt(),
                node.hasEmbedding(),
                node.qualityOverall()
        );
    }

    private Map<String, AuthorProjection> fetchAuthorProjections(Set<String> authorIds) {
        if (authorIds.isEmpty()) {
            return Map.of();
        }
        return neo4jClient.query(AUTHOR_PROJECTION_QUERY)
                .bind(List.copyOf(authorIds)).to("authorIds")
                .fetch().all()
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        record -> record.get("authorId").toString(),
                        record -> new AuthorProjection(
                                record.get("authorId").toString(),
                                record.get("authorUsername").toString(),
                                record.get("authorDisplayName").toString()
                        ),
                        (left, right) -> left,
                        java.util.LinkedHashMap::new
                ));
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
                toBoolean(entry.get("hasEmbedding")),
                toNullableDouble(entry.get("qualityOverall"))
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

    private static Double toNullableDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime odt) return odt.toInstant();
        if (value instanceof ZonedDateTime zdt) return zdt.toInstant();
        return null;
    }

    /**
     * 表示节点读取结果的统一抽象。
     *
     * <p>该密封接口让调用方可以在保持类型安全的前提下处理不同底层节点实体。
     */
    public sealed interface NodeResult permits HumanPostNode, AIConsensusNode, ResultNode {
    }

    /**
     * 表示帖子节点读取结果。
     */
    public record HumanPostNode(HumanPost node) implements NodeResult {
    }

    /**
     * 表示共识节点读取结果。
     */
    public record AIConsensusNode(AIConsensus node) implements NodeResult {
    }

    /**
     * 表示结果节点读取结果。
     */
    public record ResultNode(Result node) implements NodeResult {
    }

    /**
     * 表示一张完整的查询拓扑。
     *
     * <p>该对象将节点集与边集成对返回，适合图组件或上层服务直接消费。
     */
    public record GraphTopology(
            List<LineageNode> nodes,
            List<LineageEdge> edges) {
    }

    /**
     * 表示拓扑中的一条边。
     */
    public record LineageEdge(
            String source,
            String target,
            String type,
            Instant createdAt) {
    }

    /**
     * 表示查询层统一使用的节点摘要。
     *
     * <p>该对象屏蔽了帖子、共识与结果节点之间的字段差异，供控制器和前端统一使用。
     */
    public record LineageNode(
            String nodeId,
            String label,
            String content,
            String summaryContent,
            String authorId,
            String authorUsername,
            String authorDisplayName,
            String agentVersion,
            Instant createdAt,
            boolean hasEmbedding,
            Double qualityOverall) {

        /**
         * 创建一个不包含质量分的节点摘要。
         *
         * <p>该重载主要用于兼容只返回基础字段的查询映射结果。
         */
        public LineageNode(
                String nodeId, String label, String content, String summaryContent,
                String authorId, String agentVersion, Instant createdAt, boolean hasEmbedding
        ) {
            this(nodeId, label, content, summaryContent, authorId, null, null, agentVersion, createdAt, hasEmbedding, null);
        }

        public LineageNode(
                String nodeId, String label, String content, String summaryContent,
                String authorId, String agentVersion, Instant createdAt, boolean hasEmbedding, Double qualityOverall
        ) {
            this(nodeId, label, content, summaryContent, authorId, null, null, agentVersion, createdAt, hasEmbedding, qualityOverall);
        }
    }

    private record AuthorProjection(
            String authorId,
            String authorUsername,
            String authorDisplayName
    ) {
    }
}
