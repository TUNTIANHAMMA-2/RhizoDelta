package com.rhizodelta.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.domain.decision.BranchDecisionCommand;
import com.rhizodelta.domain.decision.DecisionOperatorType;
import com.rhizodelta.domain.decision.DecisionResult;
import com.rhizodelta.domain.decision.MergeDecisionCommand;
import com.rhizodelta.domain.review.ReviewTask;
import com.rhizodelta.service.DecisionService;
import com.rhizodelta.service.ReviewTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewController.class);
    private static final String BRANCH_REQUEST_SUFFIX = "branch";

    private final ReviewTaskService reviewTaskService;
    private final DecisionService decisionService;

    public ReviewController(ReviewTaskService reviewTaskService, DecisionService decisionService) {
        this.reviewTaskService = reviewTaskService;
        this.decisionService = decisionService;
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

    @PostMapping("/{id}/approve-merge")
    public ResponseEntity<ApiResponse<DecisionResult>> approveMerge(
            @PathVariable("id") String reviewId,
            Authentication authentication
    ) {
        ReviewTask task = reviewTaskService.getTask(reviewId);
        reviewTaskService.updateStatus(reviewId, ReviewTask.Status.APPROVED);
        MergeDecisionCommand command = toMergeCommand(task, authentication.getName());
        DecisionResult result = decisionService.executeMerge(command);
        return ResponseEntity.accepted().body(ApiResponse.ok(result));
    }

    @PostMapping("/{id}/approve-branch")
    public ResponseEntity<ApiResponse<DecisionResult>> approveBranch(
            @PathVariable("id") String reviewId,
            Authentication authentication
    ) {
        ReviewTask task = reviewTaskService.getTask(reviewId);
        reviewTaskService.updateStatus(reviewId, ReviewTask.Status.APPROVED);
        BranchDecisionCommand command = toBranchCommand(task, authentication.getName());
        DecisionResult result = decisionService.executeBranch(command);
        return ResponseEntity.accepted().body(ApiResponse.ok(result));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<ReviewTaskPayload> rejectReview(@PathVariable("id") String reviewId) {
        ReviewTask updated = reviewTaskService.updateStatus(reviewId, ReviewTask.Status.REJECTED);
        return ApiResponse.ok(toPayload(updated));
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
                task.updatedAt(),
                task.expiresAt()
        );
    }

    private static MergeDecisionCommand toMergeCommand(ReviewTask task, String operatorId) {
        Map<String, Object> draft = task.draftPayload();
        return new MergeDecisionCommand(
                requireDraftText(draft, "decision_id"),
                requireDraftText(draft, "request_id"),
                parseUuid(draft.get("source_node_id"), "source_node_id"),
                requireDraftText(draft, "agent_version"),
                requireDraftText(draft, "summary_content"),
                parseUuidList(draft.get("synthesized_from")),
                DecisionOperatorType.HUMAN,
                operatorId,
                requireDraftText(draft, "reason")
        );
    }

    private static BranchDecisionCommand toBranchCommand(ReviewTask task, String operatorId) {
        Map<String, Object> draft = task.draftPayload();
        return new BranchDecisionCommand(
                requireDraftText(draft, "decision_id"),
                buildRequestId(requireDraftText(draft, "request_id"), BRANCH_REQUEST_SUFFIX),
                parseUuid(draft.get("source_node_id"), "source_node_id"),
                requireDraftText(draft, "content"),
                requireDraftText(draft, "author_id"),
                DecisionOperatorType.HUMAN,
                operatorId,
                requireDraftText(draft, "reason")
        );
    }

    private static String buildRequestId(String requestId, String suffix) {
        return requestId + ":" + suffix.toLowerCase(Locale.ROOT);
    }

    private static String requireDraftText(Map<String, Object> draft, String fieldName) {
        Object value = draft.get(fieldName);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.toString();
    }

    private static UUID parseUuid(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        return UUID.fromString(value.toString());
    }

    private static List<UUID> parseUuidList(Object value) {
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalArgumentException("synthesized_from must not be empty");
        }
        return values.stream()
                .map(item -> UUID.fromString(item.toString()))
                .toList();
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
            @JsonProperty("updated_at") Instant updatedAt,
            @JsonProperty("expires_at") Instant expiresAt
    ) {
    }
}
