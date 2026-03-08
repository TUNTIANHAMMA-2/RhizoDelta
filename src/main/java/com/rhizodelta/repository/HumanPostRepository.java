package com.rhizodelta.repository;

import com.rhizodelta.domain.node.HumanPost;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HumanPostRepository extends ImmutableNeo4jRepository<HumanPost, UUID> {
    Optional<HumanPost> findByNodeId(UUID nodeId);

    @Query("""
            MATCH path = (start {node_id: $nodeId})-[:BRANCHED_FROM|MERGED_INTO*1..50]->(ancestor)
            WHERE length(path) <= $maxDepth
            RETURN DISTINCT ancestor
            ORDER BY ancestor.created_at DESC
            """)
    List<HumanPost> findLineage(
            @Param("nodeId") UUID nodeId,
            @Param("maxDepth") Integer maxDepth
    );

    @Query("""
            MATCH (:AI_Consensus {node_id: $consensusNodeId})-[:SYNTHESIZED_FROM]->(source:Human_Post)
            RETURN source
            ORDER BY source.created_at DESC
            """)
    List<HumanPost> findProvenance(@Param("consensusNodeId") UUID consensusNodeId);
}

@NoRepositoryBean
interface ImmutableNeo4jRepository<T, ID> extends Neo4jRepository<T, ID> {
    @Override
    default void deleteById(ID id) {
        throw immutableGraphError();
    }

    @Override
    default void delete(T entity) {
        throw immutableGraphError();
    }

    @Override
    default void deleteAllById(Iterable<? extends ID> ids) {
        throw immutableGraphError();
    }

    @Override
    default void deleteAll(Iterable<? extends T> entities) {
        throw immutableGraphError();
    }

    @Override
    default void deleteAll() {
        throw immutableGraphError();
    }

    private static UnsupportedOperationException immutableGraphError() {
        return new UnsupportedOperationException("Graph nodes are immutable. Delete operations are not allowed.");
    }
}
