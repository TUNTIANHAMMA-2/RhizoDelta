package com.rhizodelta.ai.summary.domain;

public record SummaryResult(
        String summary,
        int sourceCount,
        String modelUsed
) {
    public SummaryResult {
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
    }
}
