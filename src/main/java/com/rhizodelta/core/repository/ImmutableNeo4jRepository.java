package com.rhizodelta.core.repository;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.List;

/**
 * 定义只读的 Neo4j 仓储基接口。
 *
 * <p>该接口存在的意义，是在 Spring Data 仓储层面显式阻断直接的
 * {@code save}/{@code delete} 路径，强制调用方通过领域服务维护图谱演化，
 * 从而避免绕过业务约束直接篡改节点状态。
 *
 * <p><b>注意</b>：该接口并不意味着数据库永远只读，而是要求所有写操作必须经由
 * 更高层的事务服务完成。
 */
@NoRepositoryBean
public interface ImmutableNeo4jRepository<T, ID> extends Neo4jRepository<T, ID> {
    @Override
    /**
     * 禁止通过仓储直接保存单个实体。
     *
     * <p>调用该方法会直接失败，因为图节点的创建与更新必须由领域服务控制。
     */
    default <S extends T> S save(S entity) {
        throw immutableGraphError();
    }

    @Override
    /**
     * 禁止通过仓储批量保存实体。
     *
     * <p>批量写入如果绕过服务层，会破坏图关系与审计语义的一致性。
     */
    default <S extends T> List<S> saveAll(Iterable<S> entities) {
        throw immutableGraphError();
    }

    @Override
    /**
     * 禁止通过仓储按 ID 直接删除实体。
     *
     * <p>删除在本系统中通常需要软删除、依赖校验或关系清理，不能走通用仓储捷径。
     */
    default void deleteById(ID id) {
        throw immutableGraphError();
    }

    @Override
    /**
     * 禁止通过仓储直接删除实体实例。
     */
    default void delete(T entity) {
        throw immutableGraphError();
    }

    @Override
    /**
     * 禁止通过仓储批量按 ID 删除实体。
     */
    default void deleteAllById(Iterable<? extends ID> ids) {
        throw immutableGraphError();
    }

    @Override
    /**
     * 禁止通过仓储批量删除实体。
     */
    default void deleteAll(Iterable<? extends T> entities) {
        throw immutableGraphError();
    }

    @Override
    /**
     * 禁止通过仓储清空整个实体集合。
     */
    default void deleteAll() {
        throw immutableGraphError();
    }

    private static UnsupportedOperationException immutableGraphError() {
        return new UnsupportedOperationException("Graph nodes are immutable. Direct mutation operations are not allowed.");
    }
}
