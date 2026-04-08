package com.rhizodelta.core.repository;

import com.rhizodelta.core.domain.node.HumanPost;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 提供 {@link HumanPost} 节点的查询仓储。
 *
 * <p>该仓储继承 {@link ImmutableNeo4jRepository}，说明它只承担读取职责；
 * 任何帖子节点写入都应通过 {@code core.service} 中的服务完成。
 *
 * <p><b>设计意图</b>：
 * <ul>
 *   <li>区分“按主键查找”和“仅查询活跃节点”的不同语义。</li>
 *   <li>提供轻量投影查询，避免在只需要文本时反序列化 embedding。</li>
 *   <li>为摘要与溯源链路提供来源帖子查询能力。</li>
 * </ul>
 */
public interface HumanPostRepository extends ImmutableNeo4jRepository<HumanPost, UUID> {
    Optional<HumanPost> findByNodeId(UUID nodeId);

    /**
     * 查询一个仍处于活跃状态的帖子节点。
     *
     * <p>该方法会显式排除软删除节点，适合业务层在需要“当前可见帖子”语义时使用。
     */
    @Query("""
            MATCH (node:Human_Post {node_id: $nodeId})
            WHERE NOT coalesce(node._deleted, false)
            RETURN node
            """)
    Optional<HumanPost> findActiveByNodeId(@Param("nodeId") UUID nodeId);

    /**
     * 判断帖子节点是否仍然活跃可见。
     *
     * <p>该方法存在的意义，是让上层在不加载整个实体的前提下快速完成存在性判断。
     */
    @Query("""
            MATCH (node:Human_Post:GraphNode {node_id: $nodeId})
            WHERE NOT coalesce(node._deleted, false)
            RETURN count(node) > 0
            """)
    boolean existsActiveByNodeId(@Param("nodeId") UUID nodeId);

    /**
     * 返回一组仍然活跃的帖子节点 ID 字符串。
     *
     * <p>该查询适合上层在批量过滤失效节点时使用，避免加载完整实体对象。
     */
    @Query("""
            MATCH (post:Human_Post:GraphNode)
            WHERE post.node_id IN $nodeIds
              AND NOT coalesce(post._deleted, false)
            RETURN toString(post.node_id)
            """)
    List<String> findActiveNodeIdStrings(@Param("nodeIds") List<String> nodeIds);

    List<HumanPost> findAllByNodeIdIn(Collection<UUID> nodeIds);

    /**
     * 仅查询帖子正文内容。
     *
     * <p>该方法是一个<b>轻量投影</b>入口，适合摘要或提示词拼接场景，
     * 可以避免加载不必要的 embedding 字段。
     */
    /** Lightweight projection: returns only content strings, avoiding embedding deserialization. */
    @Query("""
            MATCH (post:Human_Post)
            WHERE post.node_id IN $nodeIds
              AND NOT coalesce(post._deleted, false)
            RETURN post.content
            """)
    List<String> findContentsByNodeIdIn(@Param("nodeIds") Collection<UUID> nodeIds);

    /**
     * 查询某个共识节点的来源帖子。
     *
     * <p>该方法用于恢复 {@code AI_Consensus -> Human_Post} 的溯源关系，
     * 是摘要生成和溯源展示链路的重要基础。
     */
    @Query("""
            MATCH (consensus:AI_Consensus {node_id: $consensusNodeId})-[:SYNTHESIZED_FROM]->(source:Human_Post)
            WHERE NOT coalesce(consensus._deleted, false)
              AND NOT coalesce(source._deleted, false)
            RETURN source
            ORDER BY source.created_at DESC
            """)
    List<HumanPost> findProvenance(@Param("consensusNodeId") UUID consensusNodeId);
}
