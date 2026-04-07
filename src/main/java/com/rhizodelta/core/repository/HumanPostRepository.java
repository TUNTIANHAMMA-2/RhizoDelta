package com.rhizodelta.core.repository;

import com.rhizodelta.core.domain.node.HumanPost;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HumanPostRepository extends ImmutableNeo4jRepository<HumanPost, UUID> {
    Optional<HumanPost> findByNodeId(UUID nodeId);

    @Query("""
            MATCH (node:Human_Post {node_id: $nodeId})
            WHERE NOT coalesce(node._deleted, false)
            RETURN node
            """)
    Optional<HumanPost> findActiveByNodeId(@Param("nodeId") UUID nodeId);

    @Query("""
            MATCH (node:Human_Post:GraphNode {node_id: $nodeId})
            WHERE NOT coalesce(node._deleted, false)
            RETURN count(node) > 0
            """)
    boolean existsActiveByNodeId(@Param("nodeId") UUID nodeId);

    @Query("""
            MATCH (post:Human_Post:GraphNode)
            WHERE post.node_id IN $nodeIds
              AND NOT coalesce(post._deleted, false)
            RETURN toString(post.node_id)
            """)
    List<String> findActiveNodeIdStrings(@Param("nodeIds") List<String> nodeIds);

    List<HumanPost> findAllByNodeIdIn(Collection<UUID> nodeIds);

    /** Lightweight projection: returns only content strings, avoiding embedding deserialization. */
    @Query("""
            MATCH (post:Human_Post)
            WHERE post.node_id IN $nodeIds
              AND NOT coalesce(post._deleted, false)
            RETURN post.content
            """)
    List<String> findContentsByNodeIdIn(@Param("nodeIds") Collection<UUID> nodeIds);

    @Query("""
            MATCH (consensus:AI_Consensus {node_id: $consensusNodeId})-[:SYNTHESIZED_FROM]->(source:Human_Post)
            WHERE NOT coalesce(consensus._deleted, false)
              AND NOT coalesce(source._deleted, false)
            RETURN source
            ORDER BY source.created_at DESC
            """)
    List<HumanPost> findProvenance(@Param("consensusNodeId") UUID consensusNodeId);
}
