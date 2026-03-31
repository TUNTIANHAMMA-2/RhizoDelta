package com.rhizodelta.domain.review;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ReviewTask(
        String reviewId,
        String requestId,
        String postNodeId,
        String workflowTraceId,
        Status status,
        String suggestedAction,
        List<String> candidateNodeIds,
        Map<String, Object> draftPayload,
        List<String> reviewReasonCodes,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt
) {
    public ReviewTask {
        candidateNodeIds = candidateNodeIds == null ? List.of() : List.copyOf(candidateNodeIds);
        draftPayload = draftPayload == null ? Map.of() : Map.copyOf(draftPayload);
        reviewReasonCodes = reviewReasonCodes == null ? List.of() : List.copyOf(reviewReasonCodes);
    }

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED,
        EXPIRED,
        EXECUTION_FAILED
    }

    public record CreateReviewTaskCommand(
            String requestId,
            String postNodeId,
            String workflowTraceId,
            String suggestedAction,
            List<String> candidateNodeIds,
            Map<String, Object> draftPayload,
            List<String> reviewReasonCodes
    ) {
    }
}
