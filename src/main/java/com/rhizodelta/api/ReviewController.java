package com.rhizodelta.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.domain.review.ReviewTask;
import com.rhizodelta.service.ReviewTaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {
    private final ReviewTaskService reviewTaskService;

    public ReviewController(ReviewTaskService reviewTaskService) {
        this.reviewTaskService = reviewTaskService;
    }

    @GetMapping("/pending")
    public ApiResponse<List<ReviewTaskPayload>> getPendingReviews(
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        List<ReviewTaskPayload> tasks = reviewTaskService.findPendingTasks(limit).stream()
                .map(ReviewController::toPayload)
                .toList();
        return ApiResponse.ok(tasks);
    }

    @GetMapping("/{id}")
    public ApiResponse<ReviewTaskPayload> getReviewById(@PathVariable("id") String reviewId) {
        return ApiResponse.ok(toPayload(reviewTaskService.getTask(reviewId)));
    }

    private static ReviewTaskPayload toPayload(ReviewTask task) {
        return new ReviewTaskPayload(
                task.reviewId(),
                task.requestId(),
                task.postNodeId(),
                task.workflowTraceId(),
                task.status().name(),
                task.suggestedAction(),
                task.candidateNodeIds(),
                task.draftPayload(),
                task.reviewReasonCodes(),
                task.createdAt(),
                task.expiresAt()
        );
    }

    public record ReviewTaskPayload(
            @JsonProperty("review_id") String reviewId,
            @JsonProperty("request_id") String requestId,
            @JsonProperty("post_node_id") String postNodeId,
            @JsonProperty("workflow_trace_id") String workflowTraceId,
            @JsonProperty("status") String status,
            @JsonProperty("suggested_action") String suggestedAction,
            @JsonProperty("candidate_node_ids") List<String> candidateNodeIds,
            @JsonProperty("draft_payload") java.util.Map<String, Object> draftPayload,
            @JsonProperty("review_reason_codes") List<String> reviewReasonCodes,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("expires_at") Instant expiresAt
    ) {
    }
}
