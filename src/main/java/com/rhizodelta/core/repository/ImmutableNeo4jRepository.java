package com.rhizodelta.core.repository;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.List;

@NoRepositoryBean
public interface ImmutableNeo4jRepository<T, ID> extends Neo4jRepository<T, ID> {
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
