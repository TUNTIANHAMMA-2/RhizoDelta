package com.rhizodelta.domain.ai;

import java.util.UUID;

public record QualityEvaluationCommand(
        UUID nodeId,
        String content,
        String contextSnippet,
        String positionInfo
) {
    public QualityEvaluationCommand {
        if (nodeId == null) {
            throw new IllegalArgumentException("nodeId must not be null");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
    }
}
