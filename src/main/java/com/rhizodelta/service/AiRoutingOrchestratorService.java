package com.rhizodelta.service;

import com.rhizodelta.domain.ai.AiRoutingState;
import com.rhizodelta.domain.embedding.PrunedContext;
import com.rhizodelta.domain.node.HumanPost;
import com.rhizodelta.domain.post.PostEventMessage;
import com.rhizodelta.domain.review.ReviewTask;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AiRoutingOrchestratorService {
    private final AiRoutingWorkflowService workflowService;
    private final RoutingRecallService routingRecallService;
    private final ReviewTaskService reviewTaskService;
    private final SseEventService sseEventService;

    public AiRoutingOrchestratorService(
            AiRoutingWorkflowService workflowService,
            RoutingRecallService routingRecallService,
            ReviewTaskService reviewTaskService,
            SseEventService sseEventService
    ) {
        this.workflowService = workflowService;
        this.routingRecallService = routingRecallService;
        this.reviewTaskService = reviewTaskService;
        this.sseEventService = sseEventService;
    }

    public void orchestrate(PostEventMessage message, HumanPost post) {
        publishStatus(message, post.getNodeId().toString(), "EVALUATION_STARTED", null, "ai routing workflow started");
        PrunedContext prunedContext = routingRecallService.recall(post.getContent(), message.targetNodeId());
        List<String> candidateNodeIds = prunedContext.selected().stream()
                .map(result -> result.node_id().toString())
                .toList();
        publishStatus(
                message,
                post.getNodeId().toString(),
                "RECALL_COMPLETED",
                null,
                "selected candidates=" + prunedContext.selected().size()
        );
        AiRoutingState state = workflowService.invokeSkeleton(Map.of(
                        AiRoutingState.REQUEST_ID, message.requestId(),
                        AiRoutingState.EVENT_ID, message.eventId(),
                        AiRoutingState.POST_NODE_ID, post.getNodeId().toString(),
                        AiRoutingState.TARGET_NODE_ID, message.targetNodeId() == null ? "" : message.targetNodeId(),
                        AiRoutingState.RECALL_CANDIDATE_NODE_IDS, candidateNodeIds,
                        AiRoutingState.SELECTED_CANDIDATE_NODE_IDS, candidateNodeIds,
                        AiRoutingState.ROUTING_ACTION, "REVIEW"
                ))
                .orElseThrow(() -> new IllegalStateException("ai routing workflow returned no state"));
        if (!"REVIEW".equals(state.routingAction())) {
            publishStatus(message, post.getNodeId().toString(), "FAILED", null,
                    "routing action not yet wired: " + state.routingAction());
            throw new IllegalStateException("routing action not yet wired: " + state.routingAction());
        }
        ReviewTask task = reviewTaskService.createPendingTask(new ReviewTask.CreateReviewTaskCommand(
                message.requestId(),
                post.getNodeId().toString(),
                message.eventId(),
                state.routingAction(),
                state.selectedCandidateNodeIds(),
                Map.of(
                        "request_id", message.requestId(),
                        "post_node_id", post.getNodeId().toString(),
                        "source_node_id", state.sourceNodeId()
                ),
                state.reviewReason().isBlank() ? List.of("WORKFLOW_SKELETON_FALLBACK") : List.of(state.reviewReason())
        ));
        publishStatus(message, post.getNodeId().toString(), "REVIEW_PENDING", task.reviewId(), "review task created");
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
                null
        );
        sseEventService.publish(SseEventService.SseEventType.ORCHESTRATION_STATUS, payload);
    }
}
