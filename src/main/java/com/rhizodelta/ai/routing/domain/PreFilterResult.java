package com.rhizodelta.ai.routing.domain;

public record PreFilterResult(
        String action,
        String reason,
        boolean skipLlm
) {
    public PreFilterResult {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action must not be blank");
        }
    }
}
