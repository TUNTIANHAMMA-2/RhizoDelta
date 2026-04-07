package com.rhizodelta.consensus.event;

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
            List<UUID> contributorNodeIds,
            OffsetDateTime relationshipCreatedAt
    ) implements DecisionCommittedEvent {
    }

    record InjectCompleted(
            String decisionId,
            UUID nodeId,
            UUID sourceNodeId,
            String content,
            OffsetDateTime relationshipCreatedAt
    ) implements DecisionCommittedEvent {
    }

    record MaterializeCompleted(
            String decisionId,
            UUID nodeId,
            UUID sourceNodeId,
            String content,
            OffsetDateTime relationshipCreatedAt
    ) implements DecisionCommittedEvent {
    }

    record ForkCompleted(
            String operationId,
            List<UUID> nodeIds,
            UUID sourceNodeId,
            OffsetDateTime relationshipCreatedAt
    ) implements DecisionCommittedEvent {
    }

    record CrossSynthCompleted(
            String decisionId,
            UUID nodeId,
            List<UUID> sourceResultIds,
            String content,
            OffsetDateTime relationshipCreatedAt
    ) implements DecisionCommittedEvent {
    }

    record JoinCompleted(
            String decisionId,
            UUID nodeId,
            List<UUID> sourceNodeIds,
            String summaryContent,
            OffsetDateTime relationshipCreatedAt
    ) implements DecisionCommittedEvent {
    }

    record MergeAppended(
            String decisionId,
            UUID nodeId,
            UUID sourceNodeId,
            List<UUID> synthesizedFrom,
            OffsetDateTime relationshipCreatedAt
    ) implements DecisionCommittedEvent {
    }
}
