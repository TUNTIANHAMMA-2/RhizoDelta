package com.rhizodelta.domain.ai;

public record DecisionExplanation(
        String action,
        double confidence,
        String reason,
        String candidateComparison,
        String reflectionSummary
) {
    public DecisionExplanation {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action must not be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
