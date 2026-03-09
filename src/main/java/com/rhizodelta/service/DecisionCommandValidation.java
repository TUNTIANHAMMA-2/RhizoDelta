package com.rhizodelta.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class DecisionCommandValidation {
    private DecisionCommandValidation() {
    }

    static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    static UUID requireUuid(UUID value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        return value;
    }

    static DecisionOperatorType requireOperatorType(DecisionOperatorType value) {
        if (value == null) {
            throw new IllegalArgumentException("operator_type must not be null");
        }
        return value;
    }

    static List<UUID> requireUuidList(List<UUID> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("synthesized_from must not be empty");
        }
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("synthesized_from must not contain null");
        }
        return List.copyOf(values);
    }
}
