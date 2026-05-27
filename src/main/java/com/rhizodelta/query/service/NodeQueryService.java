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
            // Stage 3: Resolve author identities inline so callers no longer need a follow-up
            // AUTHOR_PROJECTION_QUERY round-trip. UNWIND over [NULL] keeps the result row alive
            // when `nodes` is empty; the `WHERE pair.authorId IS NOT NULL` filters out the
            // sentinel row before returning.
            WITH nodes, edges,
                 [n IN nodes WHERE n.authorId IS NOT NULL | n.authorId] AS allAuthorIds
            UNWIND (CASE WHEN allAuthorIds = [] THEN [NULL] ELSE allAuthorIds END) AS aid
            OPTIONAL MATCH (author:UserAccount {user_id: aid})
            OPTIONAL MATCH (author)-[:HAS_PROFILE]->(profile:UserProfile)
            WITH nodes, edges,
                 [pair IN collect(DISTINCT {
                   authorId: author.user_id,
                   authorUsername: author.username,
                   authorDisplayName: coalesce(profile.display_name, author.username)
                 }) WHERE pair.authorId IS NOT NULL] AS authors
            RETURN nodes, edges, authors
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
            // Stage 3: Resolve author identities inline (see LINEAGE_QUERY for rationale).
            WITH nodes, edges,
                 [n IN nodes WHERE n.authorId IS NOT NULL | n.authorId] AS allAuthorIds
            UNWIND (CASE WHEN allAuthorIds = [] THEN [NULL] ELSE allAuthorIds END) AS aid
            OPTIONAL MATCH (author:UserAccount {user_id: aid})
            OPTIONAL MATCH (author)-[:HAS_PROFILE]->(profile:UserProfile)
            WITH nodes, edges,
                 [pair IN collect(DISTINCT {
                   authorId: author.user_id,
                   authorUsername: author.username,
                   authorDisplayName: coalesce(profile.display_name, author.username)
                 }) WHERE pair.authorId IS NOT NULL] AS authors
            RETURN nodes, edges, authors
            """;

    private static final String NODE_SUMMARY_QUERY = """
            MATCH (n:GraphNode {node_id: $nodeId})
            WHERE NOT coalesce(n._deleted, false)
            WITH n, labels(n) AS nodeLabels
            OPTIONAL MATCH (caller:UserAccount {user_id: $callerUserId})
            OPTIONAL MATCH (caller)-[followRel:FOLLOWS]->(n)
            OPTIONAL MATCH (caller)-[muteRel:MUTED]->(n)
            OPTIONAL MATCH (author:UserAccount {user_id: n.author_id})
            OPTIONAL MATCH (author)-[:HAS_PROFILE]->(profile:UserProfile)
            RETURN n.node_id AS nodeId,
                   CASE WHEN 'Human_Post' IN nodeLabels THEN 'Human_Post' WHEN 'Result' IN nodeLabels THEN 'Result' ELSE 'AI_Consensus' END AS label,
                   n.content AS content,
                   n.summary_content AS summaryContent,
                   n.author_id AS authorId,
                   author.username AS authorUsername,
                   coalesce(profile.display_name, author.username) AS authorDisplayName,
                   n.agent_version AS agentVersion,
                   n.created_at AS createdAt,
                   n.embedding IS NOT NULL AS hasEmbedding,
                   n.quality_overall AS qualityOverall,
                   followRel IS NOT NULL AS isFollowing,
                   muteRel IS NOT NULL AS isMuted,
                   followRel.follow_id AS followId,
                   muteRel.mute_id AS muteId
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
            OPTIONAL MATCH (author:UserAccount {user_id: source.author_id})
            OPTIONAL MATCH (author)-[:HAS_PROFILE]->(profile:UserProfile)
            RETURN source.node_id AS nodeId,
                   'Human_Post' AS label,
                   source.content AS content,
                   source.summary_content AS summaryContent,
                   source.author_id AS authorId,
                   author.username AS authorUsername,
                   coalesce(profile.display_name, author.username) AS authorDisplayName,
                   source.agent_version AS agentVersion,
                   source.created_at AS createdAt,
                   source.embedding IS NOT NULL AS hasEmbedding,
                   source.quality_overall AS qualityOverall
            ORDER BY createdAt DESC
            """;

    private static final String HUMAN_POST_PROVENANCE_QUERY = """
            MATCH (target:Human_Post {node_id: $nodeId})-[:CONTINUES_FROM|BRANCHED_FROM]->(parent:GraphNode)
            WHERE NOT coalesce(target._deleted, false)
              AND NOT coalesce(parent._deleted, false)
            WITH parent, labels(parent) AS parentLabels
            OPTIONAL MATCH (author:UserAccount {user_id: parent.author_id})
            OPTIONAL MATCH (author)-[:HAS_PROFILE]->(profile:UserProfile)
            RETURN parent.node_id AS nodeId,
                   CASE WHEN 'Human_Post' IN parentLabels THEN 'Human_Post' WHEN 'Result' IN parentLabels THEN 'Result' ELSE 'AI_Consensus' END AS label,
                   parent.content AS content,
                   parent.summary_content AS summaryContent,
                   parent.author_id AS authorId,
                   author.username AS authorUsername,
                   coalesce(profile.display_name, author.username) AS authorDisplayName,
                   parent.agent_version AS agentVersion,
                   parent.created_at AS createdAt,
                   parent.embedding IS NOT NULL AS hasEmbedding,
                   parent.quality_overall AS qualityOverall
            ORDER BY createdAt DESC
            """;

    private static final String RESULT_PROVENANCE_QUERY = """
            MATCH (target:Result {node_id: $nodeId})-[:MATERIALIZED_FROM]->(source:GraphNode)
            WHERE NOT coalesce(target._deleted, false)
              AND NOT coalesce(source._deleted, false)
            WITH source, labels(source) AS sourceLabels
            OPTIONAL MATCH (author:UserAccount {user_id: source.author_id})
            OPTIONAL MATCH (author)-[:HAS_PROFILE]->(profile:UserProfile)
            RETURN source.node_id AS nodeId,
                   CASE WHEN 'Human_Post' IN sourceLabels THEN 'Human_Post' WHEN 'Result' IN sourceLabels THEN 'Result' ELSE 'AI_Consensus' END AS label,
                   source.content AS content,
                   source.summary_content AS summaryContent,
                   source.author_id AS authorId,
                   author.username AS authorUsername,
                   coalesce(profile.display_name, author.username) AS authorDisplayName,
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
            OPTIONAL MATCH (author:UserAccount {user_id: n.author_id})
            OPTIONAL MATCH (author)-[:HAS_PROFILE]->(profile:UserProfile)
            RETURN n.node_id AS nodeId,
                   CASE WHEN 'Human_Post' IN nodeLabels THEN 'Human_Post' WHEN 'Result' IN nodeLabels THEN 'Result' ELSE 'AI_Consensus' END AS label,
                   n.content AS content,
                   n.summary_content AS summaryContent,
                   n.author_id AS authorId,
                   author.username AS authorUsername,
                   coalesce(profile.display_name, author.username) AS authorDisplayName,
                   n.agent_version AS agentVersion,
                   n.created_at AS createdAt,
                   n.embedding IS NOT NULL AS hasEmbedding,
                   n.quality_overall AS qualityOverall
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
     * 按节点 ID 返回面向 API 的节点摘要，并附带调用方关注/屏蔽状态。
     *
     * @param nodeId 节点 ID。
     * @param callerUserId 当前调用方用户 ID；匿名访问传 {@code null}。
     * @return 节点摘要。
     */
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public LineageNode getNodeById(UUID nodeId, String callerUserId) {
        return getNodeSummaryById(nodeId, callerUserId);
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
     * 一次性返回节点的祖先谱系拓扑 + 子树拓扑，让前端的画布初始化把两次串行 HTTP
     * 收敛为一次。同一只读事务内顺序跑两条查询；调用方仅承担一次 HTTP RTT，少一次
     * 跨网络往返。
     *
     * <p>如果该节点是叶子（无后代），{@link #getChildrenTopology} 会抛
     * {@link NoSuchElementException}（"Node not found" 语义实质等价于 "stage1 路径为空"），
     * 这里降级为空的 children 拓扑，避免单纯的叶子节点让整个聚合端点变成 404。
     * 真正"节点完全不存在"的场景会从 lineage 路径暴露——它会返回空 nodes/empty edges，
     * 调用方按现有 404 规约（参见 NodeQueryController#getNodeById）处理。
     *
     * @param nodeId 根节点 ID。
     * @param lineageDepth 可选谱系深度（由 {@link #getLineageTopology} 进一步校验/裁剪）。
     * @param childrenDepth 可选子树深度。
     * @param childrenLimit 可选子树节点数上限。
     * @return 同一根节点的 lineage + children 拓扑。
     */
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public TopologyContext getTopologyContext(UUID nodeId,
                                              Integer lineageDepth,
                                              Integer childrenDepth,
                                              Integer childrenLimit) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        GraphTopology lineage = getLineageTopology(nodeId, lineageDepth);
        GraphTopology children;
        try {
            children = getChildrenTopology(nodeId, childrenDepth, childrenLimit);
        } catch (NoSuchElementException leafOrMissing) {
            // 叶子节点 vs. 真不存在：在 lineage 已经把"真不存在"暴露为空集的前提下，
            // 这里只可能是"叶子"，回退为空 children 而不是 404。
            children = new GraphTopology(List.of(), List.of());
        }
        return new TopologyContext(lineage, children);
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
        return getNodeSummaryById(nodeId, null);
    }

    /**
     * 返回单个节点的统一摘要，并在已认证调用方存在时附带关注/屏蔽状态。
     *
     * @param nodeId 节点 ID。
     * @param callerUserId 当前调用方用户 ID；匿名访问传 {@code null}。
     * @return 节点摘要。
     */
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public LineageNode getNodeSummaryById(UUID nodeId, String callerUserId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        return neo4jClient.query(NODE_SUMMARY_QUERY)
                .bind(nodeId.toString()).to("nodeId")
                .bind(callerUserId == null ? "" : callerUserId).to("callerUserId")
                .fetch()
                .one()
                .map(NodeQueryService::toLineageNode)
                .orElseThrow(() -> new NoSuchElementException("Node not found: " + nodeId));
    }

    /**
     * 返回任意节点的直接上游摘要列表，是确权溯源面板的数据来源。
     *
     * <p>查询深度固定为 1 跳（直接上游），不进行多层递归——多层谱系视图由
     * {@link #getLineage(UUID, Integer)} 承担。返回结构按节点类型分发：
     *
     * <ul>
     *   <li>{@code AI_Consensus} → 沿 {@code SYNTHESIZED_FROM} 返回合成来源；</li>
     *   <li>{@code Human_Post} → 沿 {@code CONTINUES_FROM} 或 {@code BRANCHED_FROM} 返回父节点；</li>
     *   <li>{@code Result} → 沿 {@code MATERIALIZED_FROM} 返回物化来源。</li>
     * </ul>
     *
     * <p>所有分支均会过滤掉软删除节点，作者投影在主查询内的 OPTIONAL MATCH 中一次取齐。
     * 对没有上游的节点（例如根帖、独立 Result）返回空列表。
     *
     * <p>相比 {@link #getProvenance(UUID)}，该方法更轻量，更适合 UI 展示与提示词构建。
     *
     * <p>
     *
     * @param nodeId 节点 ID。
     * @return 直接上游节点摘要列表；若节点为根或无上游来源则返回空列表。
     */
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public List<LineageNode> getProvenanceSummaries(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Map<String, Object> nodeInfo = neo4jClient.query(NODE_TYPE_QUERY)
                .bind(nodeId.toString()).to("nodeId")
                .fetch()
                .one()
                .orElseThrow(() -> new NoSuchElementException("Node not found: " + nodeId));
        String label = (String) nodeInfo.get("label");
        if (label == null) {
            throw new IllegalStateException("Node " + nodeId + " has no resolvable label");
        }
        String query = switch (label) {
            case "AI_Consensus" -> PROVENANCE_SUMMARY_QUERY;
            case "Human_Post" -> HUMAN_POST_PROVENANCE_QUERY;
            case "Result" -> RESULT_PROVENANCE_QUERY;
            default -> throw new IllegalStateException("Unsupported node label '" + label + "' for node " + nodeId);
        };
        return neo4jClient.query(query)
                .bind(nodeId.toString()).to("nodeId")
                .fetch().all()
                .stream()
                .map(NodeQueryService::toLineageNode)
                .toList();
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
        return neo4jClient.query(ROOTS_QUERY)
                .bind(resolvedLimit).to("limit")
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
        // Single-row queries (NODE_SUMMARY / ROOTS / *_PROVENANCE) now inline the author
        // OPTIONAL MATCH so authorUsername / authorDisplayName arrive in the same record —
        // no follow-up AUTHOR_PROJECTION_QUERY round-trip required.
        return new LineageNode(
                (String) record.get("nodeId"),
                (String) record.get("label"),
                (String) record.get("content"),
                (String) record.get("summaryContent"),
                (String) record.get("authorId"),
                (String) record.get("authorUsername"),
                (String) record.get("authorDisplayName"),
                (String) record.get("agentVersion"),
                toInstant(record.get("createdAt")),
                toBoolean(record.get("hasEmbedding")),
                toNullableDouble(record.get("qualityOverall")),
                toBoolean(record.get("isFollowing")),
                toBoolean(record.get("isMuted")),
                toNullableString(record.get("followId")),
                toNullableString(record.get("muteId"))
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

        Map<String, Object> record = records.iterator().next();
        GraphTopology topology = toGraphTopology(record);
        // The Cypher returns `authors` alongside `nodes`/`edges` (LINEAGE_QUERY / CHILDREN_QUERY
        // stage 3), eliminating the follow-up AUTHOR_PROJECTION_QUERY round-trip.
        Map<String, AuthorProjection> authorMap = parseAuthorProjections(record.get("authors"));
        if (authorMap.isEmpty()) {
            return topology;
        }
        List<LineageNode> nodesWithAuthor = topology.nodes().stream()
                .map(node -> attachAuthor(node, authorMap))
                .toList();
        return new GraphTopology(nodesWithAuthor, topology.edges());
    }

    private static Map<String, AuthorProjection> parseAuthorProjections(Object value) {
        if (!(value instanceof List<?> list)) {
            return Map.of();
        }
        Map<String, AuthorProjection> result = new java.util.LinkedHashMap<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            Object authorIdValue = map.get("authorId");
            if (authorIdValue == null) {
                continue;
            }
            String authorId = authorIdValue.toString();
            String username = toNullableString(map.get("authorUsername"));
            String displayName = toNullableString(map.get("authorDisplayName"));
            result.put(authorId, new AuthorProjection(authorId, username, displayName));
        }
        return result;
    }

    private static LineageNode attachAuthor(LineageNode node, Map<String, AuthorProjection> projections) {
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
                node.qualityOverall(),
                node.isFollowing(),
                node.isMuted(),
                node.followId(),
                node.muteId()
        );
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
                null,
                null,
                (String) entry.get("agentVersion"),
                toInstant(entry.get("createdAt")),
                toBoolean(entry.get("hasEmbedding")),
                toNullableDouble(entry.get("qualityOverall")),
                toBoolean(entry.get("isFollowing")),
                toBoolean(entry.get("isMuted")),
                toNullableString(entry.get("followId")),
                toNullableString(entry.get("muteId"))
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

    private static String toNullableString(Object value) {
        return value == null ? null : value.toString();
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
     * 表示一次聚合的图谱上下文（lineage + children）。
     *
     * <p>该对象专门服务 {@code /api/nodes/{id}/topology-context} 端点，让前端画布的初始化
     * 在一次 HTTP RTT 内拿到完整骨架。
     */
    public record TopologyContext(
            GraphTopology lineage,
            GraphTopology children) {
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
            Double qualityOverall,
            boolean isFollowing,
            boolean isMuted,
            String followId,
            String muteId) {

        /**
         * 创建一个不包含质量分的节点摘要。
         *
         * <p>该重载主要用于兼容只返回基础字段的查询映射结果。
         */
        public LineageNode(
                String nodeId, String label, String content, String summaryContent,
                String authorId, String agentVersion, Instant createdAt, boolean hasEmbedding
        ) {
            this(nodeId, label, content, summaryContent, authorId, null, null, agentVersion, createdAt, hasEmbedding, null, false, false, null, null);
        }

        public LineageNode(
                String nodeId, String label, String content, String summaryContent,
                String authorId, String agentVersion, Instant createdAt, boolean hasEmbedding, Double qualityOverall
        ) {
            this(nodeId, label, content, summaryContent, authorId, null, null, agentVersion, createdAt, hasEmbedding, qualityOverall, false, false, null, null);
        }
    }

    private record AuthorProjection(
            String authorId,
            String authorUsername,
            String authorDisplayName
    ) {
    }
}
