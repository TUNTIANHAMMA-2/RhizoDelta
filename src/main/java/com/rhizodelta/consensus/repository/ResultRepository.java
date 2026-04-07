package com.rhizodelta.consensus.repository;

import com.rhizodelta.core.repository.ImmutableNeo4jRepository;
import com.rhizodelta.consensus.domain.node.Result;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ResultRepository extends ImmutableNeo4jRepository<Result, UUID> {
    Optional<Result> findByNodeId(UUID nodeId);

    @Query("""
            MATCH (node:Result {node_id: $nodeId})
            WHERE NOT coalesce(node._deleted, false)
            RETURN node
            """)
    Optional<Result> findActiveByNodeId(@Param("nodeId") UUID nodeId);

    @Query("""
            MATCH (node:Result:GraphNode {node_id: $nodeId})
            WHERE NOT coalesce(node._deleted, false)
            RETURN count(node) > 0
            """)
    boolean existsActiveByNodeId(@Param("nodeId") UUID nodeId);
}
