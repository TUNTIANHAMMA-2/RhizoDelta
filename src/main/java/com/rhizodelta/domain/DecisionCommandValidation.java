package com.rhizodelta.domain;

import com.rhizodelta.domain.decision.DecisionOperatorType;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class DecisionCommandValidation {
    private DecisionCommandValidation() {
    }

    public static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    public static UUID requireUuid(UUID value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        return value;
    }

    public static DecisionOperatorType requireOperatorType(DecisionOperatorType value) {
        if (value == null) {
            throw new IllegalArgumentException("operator_type must not be null");
        }
        return value;
    }

    public static void validateConfidence(Float confidence) {
        if (confidence == null) {
            return;
        }
        if (confidence < 0.0f || confidence > 1.0f) {
            throw new IllegalArgumentException("confidence must be within [0.0,1.0]");
        }
    }

    public static List<UUID> requireUuidList(List<UUID> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("synthesized_from must not be empty");
        }
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("synthesized_from must not contain null");
        }
        return List.copyOf(values);
    }
}
