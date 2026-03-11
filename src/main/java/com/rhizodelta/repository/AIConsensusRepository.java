package com.rhizodelta.repository;

import com.rhizodelta.domain.node.AIConsensus;
import com.rhizodelta.domain.node.HumanPost;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AIConsensusRepository extends ImmutableNeo4jRepository<AIConsensus, UUID> {
    Optional<AIConsensus> findByNodeId(UUID nodeId);

    @Query("""
            MATCH (node:AI_Consensus {node_id: $nodeId})
            WHERE NOT coalesce(node._deleted, false)
            RETURN node
            """)
    Optional<AIConsensus> findActiveByNodeId(@Param("nodeId") UUID nodeId);

    @Query("""
            MATCH (consensus:AI_Consensus {node_id: $nodeId})-[:SYNTHESIZED_FROM]->(source:Human_Post)
            RETURN source
            ORDER BY source.created_at DESC
            """)
    List<HumanPost> findProvenance(@Param("nodeId") UUID nodeId);
}
