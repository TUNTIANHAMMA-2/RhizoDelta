package com.rhizodelta.consensus.repository;

import com.rhizodelta.core.repository.ImmutableNeo4jRepository;
import com.rhizodelta.consensus.domain.node.AIConsensus;
import com.rhizodelta.core.domain.node.HumanPost;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 提供 {@link AIConsensus} 节点的只读查询仓储。
 *
 * <p>该仓储继承 {@link ImmutableNeo4jRepository}，说明共识节点的创建与演化必须通过共识服务完成，
 * 仓储本身只负责读取与轻量投影。
 *
 * <p><b>设计意图</b>：
 * <ul>
 *   <li>区分“活跃节点查询”和“普通主键查询”的语义。</li>
 *   <li>为摘要生成与增量更新提供来源内容、摘要内容和目标节点投影。</li>
 *   <li>避免在只需要文本或数量时加载完整实体及其 embedding。</li>
 * </ul>
 */
public interface AIConsensusRepository extends ImmutableNeo4jRepository<AIConsensus, UUID> {
    Optional<AIConsensus> findByNodeId(UUID nodeId);

    /**
     * 查询一个仍处于活跃状态的共识节点。
     */
    @Query("""
            MATCH (node:AI_Consensus {node_id: $nodeId})
            WHERE NOT coalesce(node._deleted, false)
            RETURN node
            """)
    Optional<AIConsensus> findActiveByNodeId(@Param("nodeId") UUID nodeId);

    /**
     * 判断共识节点是否仍然活跃可见。
     */
    @Query("""
            MATCH (node:AI_Consensus:GraphNode {node_id: $nodeId})
            WHERE NOT coalesce(node._deleted, false)
            RETURN count(node) > 0
            """)
    boolean existsActiveByNodeId(@Param("nodeId") UUID nodeId);

    /**
     * 查询共识节点的来源帖子实体。
     *
     * <p>该方法用于恢复共识的溯源链，适合需要完整帖子对象的场景。
     */
    @Query("""
            MATCH (consensus:AI_Consensus {node_id: $nodeId})-[:SYNTHESIZED_FROM]->(source:Human_Post)
            WHERE NOT coalesce(consensus._deleted, false)
              AND NOT coalesce(source._deleted, false)
            RETURN source
            ORDER BY source.created_at DESC
            """)
    List<HumanPost> findProvenance(@Param("nodeId") UUID nodeId);

    /**
     * 查询共识节点当前挂接的目标节点 ID。
     *
     * <p>该轻量投影常用于摘要上下文和 AI 编排链路，不需要加载整个目标节点实体。
     */
    @Query("""
            MATCH (consensus:AI_Consensus {node_id: $nodeId})-[:MERGED_INTO]->(target:GraphNode)
            WHERE NOT coalesce(consensus._deleted, false)
              AND NOT coalesce(target._deleted, false)
            RETURN toString(target.node_id)
            LIMIT 1
            """)
    Optional<String> findMergedIntoTargetId(@Param("nodeId") UUID nodeId);

    /**
     * 仅查询共识摘要文本。
     *
     * <p>这是一个<b>轻量投影</b>入口，适合增量摘要更新场景。
     */
    /** Lightweight projection: returns only the summary text, avoiding embedding deserialization. */
    @Query("""
            MATCH (ai:AI_Consensus {node_id: $nodeId})
            WHERE NOT coalesce(ai._deleted, false)
            RETURN ai.summary_content
            """)
    Optional<String> findSummaryContentByNodeId(@Param("nodeId") UUID nodeId);

    /**
     * 统计共识节点的来源帖子数量。
     *
     * <p>该查询避免加载完整来源实体，适合摘要结果回执和轻量统计场景。
     */
    /** Lightweight count: avoids loading full HumanPost entities with embeddings. */
    @Query("""
            MATCH (consensus:AI_Consensus {node_id: $nodeId})-[:SYNTHESIZED_FROM]->(source:Human_Post)
            WHERE NOT coalesce(consensus._deleted, false)
              AND NOT coalesce(source._deleted, false)
            RETURN count(source)
            """)
    long countProvenanceByNodeId(@Param("nodeId") UUID nodeId);

    /**
     * 仅查询来源帖子的正文内容。
     *
     * <p>该方法是摘要生成提示词拼装的关键入口，可避免加载完整帖子对象及其 embedding。
     */
    /** Lightweight projection: returns only content strings, avoiding embedding deserialization. */
    @Query("""
            MATCH (consensus:AI_Consensus {node_id: $nodeId})-[:SYNTHESIZED_FROM]->(source:Human_Post)
            WHERE NOT coalesce(consensus._deleted, false)
              AND NOT coalesce(source._deleted, false)
            RETURN source.content
            ORDER BY source.created_at DESC
            """)
    List<String> findProvenanceContents(@Param("nodeId") UUID nodeId);
}
