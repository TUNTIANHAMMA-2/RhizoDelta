package com.rhizodelta.exception;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class RollbackBlockedException extends RuntimeException {
    private final List<UUID> dependent_node_ids;

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
