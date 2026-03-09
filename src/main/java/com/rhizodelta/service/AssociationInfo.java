package com.rhizodelta.service;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AssociationInfo(
        @JsonProperty("association_id") UUID association_id,
        @JsonProperty("type") AssociationType type,
        @JsonProperty("direction") Direction direction,
        @JsonProperty("related_node") RelatedNode related_node,
        @JsonProperty("confidence") Float confidence,
        @JsonProperty("reason") String reason,
        @JsonProperty("creator_id") String creator_id,
        @JsonProperty("created_at") Instant created_at
) {
    public AssociationInfo {
        association_id = DecisionCommandValidation.requireUuid(association_id, "association_id");
        type = Objects.requireNonNull(type, "type must not be null");
        direction = Objects.requireNonNull(direction, "direction must not be null");
        related_node = Objects.requireNonNull(related_node, "related_node must not be null");
        reason = DecisionCommandValidation.requireText(reason, "reason");
        creator_id = DecisionCommandValidation.requireText(creator_id, "creator_id");
        created_at = Objects.requireNonNull(created_at, "created_at must not be null");
        validateConfidence(confidence);
    }

    private static void validateConfidence(Float confidence) {
        if (confidence == null) {
            return;
        }
        if (confidence < 0.0f || confidence > 1.0f) {
            throw new IllegalArgumentException("confidence must be within [0.0,1.0]");
        }
    }

    public enum Direction {
        OUTGOING,
        INCOMING
    }

    public record RelatedNode(
            @JsonProperty("node_id") UUID node_id,
            @JsonProperty("label") String label,
            @JsonProperty("content") String content,
            @JsonProperty("summary_content") String summary_content
    ) {
        public RelatedNode {
            node_id = DecisionCommandValidation.requireUuid(node_id, "node_id");
            label = DecisionCommandValidation.requireText(label, "label");
        }
    }
}
