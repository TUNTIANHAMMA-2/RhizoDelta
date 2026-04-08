package com.rhizodelta.consensus.domain.exception;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 表示某条决策因仍有下游依赖而无法回滚。
 *
 * <p>该异常存在的意义，是把“不能删，因为还有后继节点依赖它”这个业务事实显式暴露给上层，
 * 而不是简单返回一个模糊的失败状态。
 */
public class RollbackBlockedException extends RuntimeException {
    private final List<UUID> dependent_node_ids;

    /**
     * 创建回滚受阻异常并记录依赖节点列表。
     *
     * <p>依赖列表会作为后续 API 响应和人工排障的重要依据，因此必须完整且不可变。
     */
    public RollbackBlockedException(List<UUID> dependentNodeIds) {
        super("cannot rollback: node has downstream dependents");
        Objects.requireNonNull(dependentNodeIds, "dependentNodeIds must not be null");
        if (dependentNodeIds.isEmpty()) {
            throw new IllegalArgumentException("dependentNodeIds must not be empty");
        }
        if (dependentNodeIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("dependentNodeIds must not contain null");
        }
        this.dependent_node_ids = List.copyOf(dependentNodeIds);
    }

    public List<UUID> dependent_node_ids() {
        return dependent_node_ids;
    }
}
