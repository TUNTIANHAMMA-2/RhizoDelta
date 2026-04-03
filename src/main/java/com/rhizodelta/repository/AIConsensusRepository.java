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
            MATCH (node:AI_Consensus:GraphNode {node_id: $nodeId})
            WHERE NOT coalesce(node._deleted, false)
            RETURN count(node) > 0
            """)
    boolean existsActiveByNodeId(@Param("nodeId") UUID nodeId);

    @Query("""
            MATCH (consensus:AI_Consensus {node_id: $nodeId})-[:SYNTHESIZED_FROM]->(source:Human_Post)
            WHERE NOT coalesce(consensus._deleted, false)
              AND NOT coalesce(source._deleted, false)
            RETURN source
            ORDER BY source.created_at DESC
            """)
    List<HumanPost> findProvenance(@Param("nodeId") UUID nodeId);

    @Query("""
            MATCH (consensus:AI_Consensus {node_id: $nodeId})-[:MERGED_INTO]->(target:GraphNode)
            WHERE NOT coalesce(consensus._deleted, false)
              AND NOT coalesce(target._deleted, false)
            RETURN toString(target.node_id)
            LIMIT 1
            """)
    Optional<String> findMergedIntoTargetId(@Param("nodeId") UUID nodeId);

    /** Lightweight projection: returns only the summary text, avoiding embedding deserialization. */
    @Query("""
            MATCH (ai:AI_Consensus {node_id: $nodeId})
            WHERE NOT coalesce(ai._deleted, false)
            RETURN ai.summary_content
            """)
    Optional<String> findSummaryContentByNodeId(@Param("nodeId") UUID nodeId);

    /** Lightweight count: avoids loading full HumanPost entities with embeddings. */
    @Query("""
            MATCH (consensus:AI_Consensus {node_id: $nodeId})-[:SYNTHESIZED_FROM]->(source:Human_Post)
            WHERE NOT coalesce(consensus._deleted, false)
              AND NOT coalesce(source._deleted, false)
            RETURN count(source)
            """)
    long countProvenanceByNodeId(@Param("nodeId") UUID nodeId);

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
