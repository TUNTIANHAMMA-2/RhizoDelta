package com.rhizodelta.consensus.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.infrastructure.web.ApiResponse;
import com.rhizodelta.consensus.domain.decision.BranchDecisionCommand;
import com.rhizodelta.consensus.domain.decision.DecisionOperatorType;
import com.rhizodelta.consensus.domain.decision.DecisionResult;
import com.rhizodelta.consensus.domain.decision.MergeDecisionCommand;
import com.rhizodelta.consensus.domain.review.ReviewTask;
import com.rhizodelta.consensus.service.AuditRelationService;
import com.rhizodelta.consensus.service.DecisionService;
import com.rhizodelta.consensus.service.ReviewTaskService;
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

/**
 * 提供人工复核任务的查询与处理入口。
 *
 * <p>该控制器用于承接自动流程无法直接闭合的决策场景，
 * 允许管理员查看待处理任务、批准建议动作或直接拒绝。
 *
 * <p><b>关键副作用</b>：
 * <ul>
 *   <li>批准操作会真正调用 {@link DecisionService} 执行图谱写入。</li>
 *   <li>批准失败会把任务状态更新为 {@link ReviewTask.Status#EXECUTION_FAILED}。</li>
 *   <li>拒绝操作会直接变更复核任务状态，不执行任何图谱决策。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewController.class);
    private static final String BRANCH_REQUEST_SUFFIX = "branch";

    private final ReviewTaskService reviewTaskService;
    private final DecisionService decisionService;
    private final AuditRelationService auditRelationService;

    public ReviewController(ReviewTaskService reviewTaskService, DecisionService decisionService,
                            AuditRelationService auditRelationService) {
        this.reviewTaskService = reviewTaskService;
        this.decisionService = decisionService;
        this.auditRelationService = auditRelationService;
    }

    /**
     * 返回待处理的复核任务列表。
     *
     * <p>该接口只返回处于可处理状态的任务，不负责恢复过期或终态任务。
     */
    @GetMapping("/pending")
    public ApiResponse<List<ReviewTaskPayload>> getPendingReviews(
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        List<ReviewTaskPayload> tasks = reviewTaskService.findPendingTasks(limit).stream()
                .map(ReviewController::toPayload)
                .toList();
        return ApiResponse.ok(tasks);
    }

    /**
     * 返回单条复核任务详情。
     */
    @GetMapping("/{id}")
    public ApiResponse<ReviewTaskPayload> getReviewById(@PathVariable("id") String reviewId) {
        return ApiResponse.ok(toPayload(reviewTaskService.getTask(reviewId)));
    }

    /**
     * 以人工身份批准一次合并建议。
     *
     * <p>该接口会从任务草稿中重建 {@link MergeDecisionCommand}，并把操作者类型固定为
     * {@link DecisionOperatorType#HUMAN}。
     */
    @PostMapping("/{id}/approve-merge")
    public ResponseEntity<ApiResponse<DecisionResult>> approveMerge(
            @PathVariable("id") String reviewId,
            Authentication authentication
    ) {
        ReviewTask task = reviewTaskService.getTask(reviewId);
        validateApprovableStatus(task);
        MergeDecisionCommand command = toMergeCommand(task, authentication.getName());
        DecisionResult result;
        try {
            result = decisionService.executeMerge(command);
        } catch (RuntimeException ex) {
            LOGGER.error("merge execution failed for review {}", reviewId, ex);
            reviewTaskService.updateStatus(reviewId, ReviewTask.Status.EXECUTION_FAILED);
            throw ex;
        }
        reviewTaskService.updateStatus(reviewId, ReviewTask.Status.APPROVED);
        auditRelationService.recordReview(authentication.getName(), result.decision_id(), "APPROVED");
        return ResponseEntity.accepted().body(ApiResponse.ok(result));
    }

    /**
     * 以人工身份批准一次分支建议。
     */
    @PostMapping("/{id}/approve-branch")
    public ResponseEntity<ApiResponse<DecisionResult>> approveBranch(
            @PathVariable("id") String reviewId,
            Authentication authentication
    ) {
        ReviewTask task = reviewTaskService.getTask(reviewId);
        validateApprovableStatus(task);
        BranchDecisionCommand command = toBranchCommand(task, authentication.getName());
        DecisionResult result;
        try {
            result = decisionService.executeBranch(command);
        } catch (RuntimeException ex) {
            LOGGER.error("branch execution failed for review {}", reviewId, ex);
            reviewTaskService.updateStatus(reviewId, ReviewTask.Status.EXECUTION_FAILED);
            throw ex;
        }
        reviewTaskService.updateStatus(reviewId, ReviewTask.Status.APPROVED);
        auditRelationService.recordReview(authentication.getName(), result.decision_id(), "APPROVED");
        return ResponseEntity.accepted().body(ApiResponse.ok(result));
    }

    private static void validateApprovableStatus(ReviewTask task) {
        ReviewTask.Status status = task.status();
        if (status != ReviewTask.Status.PENDING && status != ReviewTask.Status.EXECUTION_FAILED) {
            throw new IllegalStateException("review task is not in an approvable status: " + status);
        }
    }

    /**
     * 拒绝一条复核任务。
     *
     * <p>拒绝只更新任务状态，不执行底层决策命令。
     */
    @PostMapping("/{id}/reject")
    public ApiResponse<ReviewTaskPayload> rejectReview(@PathVariable("id") String reviewId,
                                                        Authentication authentication) {
        ReviewTask updated = reviewTaskService.updateStatus(reviewId, ReviewTask.Status.REJECTED);
        Map<String, Object> draft = updated.draftPayload();
        String decisionId = draft.containsKey("decision_id") ? draft.get("decision_id").toString() : reviewId;
        auditRelationService.recordReview(authentication.getName(), decisionId, "REJECTED");
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
        List<UUID> contributorNodeIds = List.of();
        Object postNodeId = draft.get("post_node_id");
        if (postNodeId != null) {
            contributorNodeIds = List.of(UUID.fromString(postNodeId.toString()));
        }
        return new BranchDecisionCommand(
                requireDraftText(draft, "decision_id"),
                buildRequestId(requireDraftText(draft, "request_id"), BRANCH_REQUEST_SUFFIX),
                parseUuid(draft.get("source_node_id"), "source_node_id"),
                requireDraftText(draft, "content"),
                requireDraftText(draft, "author_id"),
                DecisionOperatorType.HUMAN,
                operatorId,
                requireDraftText(draft, "reason"),
                contributorNodeIds
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

    /**
     * 表示复核任务的对外响应载荷。
     *
     * <p>该对象把任务的当前状态、候选动作和草稿数据打包返回给管理界面。
     */
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
