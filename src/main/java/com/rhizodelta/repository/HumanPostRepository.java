package com.rhizodelta.repository;

import com.rhizodelta.domain.node.HumanPost;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.repository.NoRepositoryBean;

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

    @Query("""
            MATCH (consensus:AI_Consensus {node_id: $consensusNodeId})-[:SYNTHESIZED_FROM]->(source:Human_Post)
            WHERE NOT coalesce(consensus._deleted, false)
              AND NOT coalesce(source._deleted, false)
            RETURN source
            ORDER BY source.created_at DESC
            """)
    List<HumanPost> findProvenance(@Param("consensusNodeId") UUID consensusNodeId);
}

@NoRepositoryBean
interface ImmutableNeo4jRepository<T, ID> extends Neo4jRepository<T, ID> {
    @Override
    default <S extends T> S save(S entity) {
        throw immutableGraphError();
    }

    @Override
    default <S extends T> List<S> saveAll(Iterable<S> entities) {
        throw immutableGraphError();
    }

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
        return new UnsupportedOperationException("Graph nodes are immutable. Direct mutation operations are not allowed.");
    }
}
