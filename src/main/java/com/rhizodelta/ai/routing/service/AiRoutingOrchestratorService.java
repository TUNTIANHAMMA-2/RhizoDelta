package com.rhizodelta.ai.routing.service;

import com.rhizodelta.ai.routing.domain.AiRoutingState;
import com.rhizodelta.ai.context.service.BranchContextService;
import com.rhizodelta.ai.context.service.RoutingRecallService;
import com.rhizodelta.ai.context.domain.embedding.PrunedContext;
import com.rhizodelta.ai.context.domain.embedding.SimilaritySearchResult;
import com.rhizodelta.core.domain.node.HumanPost;
import com.rhizodelta.consensus.service.ReviewTaskService;
import com.rhizodelta.infrastructure.messaging.message.PostEventMessage;
import com.rhizodelta.infrastructure.sse.service.SseEventService;
import com.rhizodelta.consensus.domain.review.ReviewTask;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AiRoutingOrchestratorService {
    private final AiRoutingWorkflowService workflowService;
    private final RoutingRecallService routingRecallService;
    private final AiRoutingExecutionService aiRoutingExecutionService;
    private final ReviewTaskService reviewTaskService;
    private final SseEventService sseEventService;
    private final BranchContextService branchContextService;
    private final String agentVersion;

    public AiRoutingOrchestratorService(
            AiRoutingWorkflowService workflowService,
            RoutingRecallService routingRecallService,
            AiRoutingExecutionService aiRoutingExecutionService,
            ReviewTaskService reviewTaskService,
            SseEventService sseEventService,
            BranchContextService branchContextService,
            @Value("${langchain4j.open-ai.chat-model.model-name}") String agentVersion
    ) {
        this.workflowService = workflowService;
        this.routingRecallService = routingRecallService;
        this.aiRoutingExecutionService = aiRoutingExecutionService;
        this.reviewTaskService = reviewTaskService;
        this.sseEventService = sseEventService;
        this.branchContextService = branchContextService;
        this.agentVersion = agentVersion;
    }

    public void orchestrate(PostEventMessage message, HumanPost post) {
        publishStatus(message, post.getNodeId().toString(), "EVALUATION_STARTED", null, "ai routing workflow started");
        PrunedContext prunedContext = routingRecallService.recall(post.getContent(), message.targetNodeId());
        List<String> candidateNodeIds = prunedContext.selected().stream()
                .map(result -> result.node_id().toString())
                .toList();
        double topScore = prunedContext.selected().stream()
                .map(SimilaritySearchResult::score)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0.0);
        publishStatus(
                message,
                post.getNodeId().toString(),
                "RECALL_COMPLETED",
                null,
                "selected candidates=" + prunedContext.selected().size()
        );
        // Enrich routing context with branch ancestors, consensus, and siblings
        String routingContext = buildRoutingContext(prunedContext);
        if (!candidateNodeIds.isEmpty()) {
            UUID topCandidate = prunedContext.selected().get(0).node_id();
            BranchContextService.BranchContext branchCtx =
                    branchContextService.buildContext(topCandidate, post.getNodeId());
            String branchContextText = branchContextService.formatForRouting(branchCtx);
            if (!branchContextText.isEmpty()) {
                routingContext = routingContext + "\n" + branchContextText;
            }
        }
        AiRoutingState state = workflowService.invokeSkeleton(Map.of(
                        AiRoutingState.REQUEST_ID, message.requestId(),
                        AiRoutingState.EVENT_ID, message.eventId(),
                        AiRoutingState.POST_NODE_ID, post.getNodeId().toString(),
                        AiRoutingState.POST_CONTENT, post.getContent(),
                        AiRoutingState.TARGET_NODE_ID, message.targetNodeId() == null ? "" : message.targetNodeId(),
                        AiRoutingState.RECALL_CANDIDATE_NODE_IDS, candidateNodeIds,
                        AiRoutingState.SELECTED_CANDIDATE_NODE_IDS, candidateNodeIds,
                        AiRoutingState.ROUTING_CONTEXT, routingContext,
                        AiRoutingState.ROUTING_ACTION, "REVIEW",
                        AiRoutingState.TOP_SCORE, topScore
                ))
                .orElseThrow(() -> new IllegalStateException("ai routing workflow returned no state"));
        // Publish reflection status if LLM path was taken
        if (!state.skipLlm()) {
            String explanationJson = state.decisionExplanation();
            if (!explanationJson.isBlank()) {
                String reflectionStatus = state.reflectionCount() >= 2 ? "REFLECTION_EXHAUSTED"
                        : state.reflectionCount() > 0 ? "REFLECTION_REVISED"
                        : "REFLECTION_CONFIRMED";
                publishStatusWithExplanation(message, post.getNodeId().toString(), reflectionStatus, explanationJson,
                        "reflection completed: " + reflectionStatus.toLowerCase().replace('_', ' '));
            }
        }
        if ("REVIEW".equals(state.routingAction())) {
            createReviewTask(message, post, state);
            return;
        }
        if (state.sourceNodeId() == null || state.sourceNodeId().isBlank()) {
            publishStatus(message, post.getNodeId().toString(), "SKIPPED",
                    null, "no source node — standalone post, skipping decision execution");
            return;
        }
        executeDecision(message, post, state);
    }

    private void publishStatus(
            PostEventMessage message,
            String postNodeId,
            String status,
            String reviewId,
            String statusMessage
    ) {
        SseEventService.OrchestrationStatusPayload payload = new SseEventService.OrchestrationStatusPayload(
                message.requestId(),
                message.eventId(),
                postNodeId,
                status,
                statusMessage,
                reviewId,
                null,
                message.authorId()
        );
        sseEventService.publish(SseEventService.SseEventType.ORCHESTRATION_STATUS, payload);
    }

    private void publishStatusWithExplanation(
            PostEventMessage message,
            String postNodeId,
            String status,
            String explanation,
            String statusMessage
    ) {
        SseEventService.OrchestrationStatusPayload payload = new SseEventService.OrchestrationStatusPayload(
                message.requestId(),
                message.eventId(),
                postNodeId,
                status,
                statusMessage,
                null,
                null,
                message.authorId(),
                explanation
        );
        sseEventService.publish(SseEventService.SseEventType.ORCHESTRATION_STATUS, payload);
    }

    private String buildRoutingContext(PrunedContext prunedContext) {
        return prunedContext.selected().stream()
                .map(result -> "node_id=%s label=%s score=%.4f content=%s".formatted(
                        result.node_id(),
                        result.label(),
                        result.score(),
                        sanitizeContent(result.content())
                ))
                .collect(Collectors.joining("\n"));
    }

    private String sanitizeContent(String content) {
        return Objects.requireNonNullElse(content, "")
                .replace('\n', ' ')
                .trim();
    }

    private void createReviewTask(PostEventMessage message, HumanPost post, AiRoutingState state) {
        Map<String, Object> draft = new HashMap<>();
        draft.put("request_id", message.requestId());
        draft.put("post_node_id", post.getNodeId().toString());
        draft.put("source_node_id", state.sourceNodeId());
        draft.put("decision_id", message.eventId() + ":review");
        draft.put("reason", state.reviewReason().isBlank() ? "pending human review" : state.reviewReason());
        draft.put("agent_version", agentVersion);
        draft.put("summary_content", post.getContent());
        draft.put("synthesized_from", List.of(post.getNodeId().toString()));
        draft.put("content", post.getContent());
        draft.put("author_id", post.getAuthorId());

        ReviewTask task = reviewTaskService.createPendingTask(new ReviewTask.CreateReviewTaskCommand(
                message.requestId(),
                post.getNodeId().toString(),
                message.eventId(),
                state.routingAction(),
                state.selectedCandidateNodeIds(),
                draft,
                state.reviewReason().isBlank() ? List.of("WORKFLOW_SKELETON_FALLBACK") : List.of(state.reviewReason())
        ));
        publishStatus(message, post.getNodeId().toString(), "REVIEW_PENDING", task.reviewId(), "review task created");
    }

    private void executeDecision(PostEventMessage message, HumanPost post, AiRoutingState state) {
        AiRoutingExecutionService.RoutingExecutionResult executionResult = aiRoutingExecutionService.execute(
                new AiRoutingExecutionService.RoutingExecutionCommand(
                        message.requestId(),
                        message.eventId(),
                        state.sourceNodeId(),
                        state.routingAction(),
                        state.reviewReason(),
                        post
                )
        );
        publishStatus(
                message,
                post.getNodeId().toString(),
                executionResult.action() + "_QUEUED",
                null,
                "decision queued: " + executionResult.decisionResult().decision_id()
        );
    }
}
