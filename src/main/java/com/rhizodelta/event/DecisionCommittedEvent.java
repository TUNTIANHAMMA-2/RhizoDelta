package com.rhizodelta.event;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public sealed interface DecisionCommittedEvent {

    record MergeCompleted(
            String decisionId,
            UUID nodeId,
            UUID sourceNodeId,
            List<UUID> synthesizedFrom,
            String summaryContent,
            OffsetDateTime relationshipCreatedAt
    ) implements DecisionCommittedEvent {
    }

    record BranchCompleted(
            String decisionId,
            UUID nodeId,
            UUID sourceNodeId,
            OffsetDateTime relationshipCreatedAt
    ) implements DecisionCommittedEvent {
    }
}
