package com.rhizodelta.repository;

import com.rhizodelta.domain.node.HumanPost;
import org.springframework.data.neo4j.repository.Neo4jRepository;

import java.util.Optional;
import java.util.UUID;

public interface HumanPostRepository extends ImmutableNeo4jRepository<HumanPost, UUID> {
    Optional<HumanPost> findByNodeId(UUID nodeId);
}

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
