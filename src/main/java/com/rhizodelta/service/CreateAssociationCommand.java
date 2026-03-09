package com.rhizodelta.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Objects;
import java.util.UUID;

public record CreateAssociationCommand(
        @JsonProperty("source_node_id")
        @NotNull(message = "source_node_id must not be null")
        UUID source_node_id,
        @JsonProperty("target_node_id")
        @NotNull(message = "target_node_id must not be null")
        UUID target_node_id,
        @JsonProperty("type")
        @NotNull(message = "type must not be null")
        AssociationType type,
        @JsonProperty("creator_id")
        @NotBlank(message = "creator_id must not be blank")
        String creator_id,
        @JsonProperty("reason")
        @NotBlank(message = "reason must not be blank")
        String reason,
        @JsonProperty("confidence")
        @DecimalMin(value = "0.0", message = "confidence must be greater than or equal to 0.0")
        @DecimalMax(value = "1.0", message = "confidence must be less than or equal to 1.0")
        Float confidence
) {
    public CreateAssociationCommand {
        source_node_id = DecisionCommandValidation.requireUuid(source_node_id, "source_node_id");
        target_node_id = DecisionCommandValidation.requireUuid(target_node_id, "target_node_id");
        type = Objects.requireNonNull(type, "type must not be null");
        creator_id = DecisionCommandValidation.requireText(creator_id, "creator_id");
        reason = DecisionCommandValidation.requireText(reason, "reason");
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
}
