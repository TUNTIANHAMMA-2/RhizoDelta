package com.rhizodelta.consensus.repository;

import com.rhizodelta.core.repository.ImmutableNeo4jRepository;
import com.rhizodelta.consensus.domain.node.Result;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * 提供 {@link Result} 节点的只读查询仓储。
 *
 * <p>该仓储用于结果层节点的读取和存在性判断，不承担任何直接写职责。
 */
public interface ResultRepository extends ImmutableNeo4jRepository<Result, UUID> {
    Optional<Result> findByNodeId(UUID nodeId);

    /**
     * 查询一个仍处于活跃状态的结果节点。
     */
    @Query("""
            MATCH (node:Result {node_id: $nodeId})
            WHERE NOT coalesce(node._deleted, false)
            RETURN node
            """)
    Optional<Result> findActiveByNodeId(@Param("nodeId") UUID nodeId);

    /**
     * 判断结果节点是否仍然活跃可见。
     */
    @Query("""
            MATCH (node:Result:GraphNode {node_id: $nodeId})
            WHERE NOT coalesce(node._deleted, false)
            RETURN count(node) > 0
            """)
    boolean existsActiveByNodeId(@Param("nodeId") UUID nodeId);
}
