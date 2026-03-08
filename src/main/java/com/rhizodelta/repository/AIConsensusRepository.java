package com.rhizodelta.repository;

import com.rhizodelta.domain.node.AIConsensus;

import java.util.Optional;
import java.util.UUID;

public interface AIConsensusRepository extends ImmutableNeo4jRepository<AIConsensus, UUID> {
    Optional<AIConsensus> findByNodeId(UUID nodeId);
}
