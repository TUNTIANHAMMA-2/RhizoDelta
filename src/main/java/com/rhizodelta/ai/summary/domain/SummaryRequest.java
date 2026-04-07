package com.rhizodelta.ai.summary.domain;

import java.util.List;
import java.util.UUID;

public record SummaryRequest(
        UUID nodeId,
        List<String> sourceContents,
        String existingSummary
) {
    public SummaryRequest {
        if (nodeId == null) {
            throw new IllegalArgumentException("nodeId must not be null");
        }
        if (sourceContents == null || sourceContents.isEmpty()) {
            throw new IllegalArgumentException("sourceContents must not be empty");
        }
    }
}
