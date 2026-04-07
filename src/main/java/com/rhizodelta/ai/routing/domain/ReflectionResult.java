package com.rhizodelta.ai.routing.domain;

public record ReflectionResult(
        boolean confirmed,
        String revisedAction,
        double revisedConfidence,
        String criticReason
) {
    public ReflectionResult {
        if (revisedAction == null || revisedAction.isBlank()) {
            throw new IllegalArgumentException("revisedAction must not be blank");
        }
        if (criticReason == null) {
            throw new IllegalArgumentException("criticReason must not be null");
        }
        if (revisedConfidence < 0.0 || revisedConfidence > 1.0) {
            throw new IllegalArgumentException("revisedConfidence must be between 0.0 and 1.0");
        }
    }
}
