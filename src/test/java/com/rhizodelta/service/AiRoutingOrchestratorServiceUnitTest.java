package com.rhizodelta.service;

import com.rhizodelta.domain.ai.AiRoutingState;
import com.rhizodelta.domain.node.HumanPost;
import com.rhizodelta.domain.post.PostEventMessage;
import com.rhizodelta.domain.review.ReviewTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiRoutingOrchestratorServiceUnitTest {

    @Test
    void shouldCreateReviewTaskAndPublishReviewPendingStatus() {
        AiRoutingWorkflowService workflowService = mock(AiRoutingWorkflowService.class);
        ReviewTaskService reviewTaskService = mock(ReviewTaskService.class);
        SseEventService sseEventService = mock(SseEventService.class);
        AiRoutingOrchestratorService orchestratorService = new AiRoutingOrchestratorService(
                workflowService,
                reviewTaskService,
                sseEventService
        );
        UUID postNodeId = UUID.randomUUID();
        HumanPost post = HumanPost.create(postNodeId, "post content", "author-1", "req-1");
        PostEventMessage message = new PostEventMessage("req-1", "author-1", "post content", null, "evt-1");
        ReviewTask reviewTask = new ReviewTask(
                "review-1",
                "req-1",
                postNodeId.toString(),
                "evt-1",
                ReviewTask.Status.PENDING,
                "REVIEW",
                List.of(),
                Map.of("request_id", "req-1"),
                List.of("WORKFLOW_SKELETON_FALLBACK"),
                Instant.parse("2026-03-23T00:00:00Z"),
                Instant.parse("2026-03-23T00:00:00Z"),
                Instant.parse("2026-03-30T00:00:00Z")
        );
        when(workflowService.invokeSkeleton(any())).thenReturn(Optional.of(new AiRoutingState(Map.of(
                AiRoutingState.REQUEST_ID, "req-1",
                AiRoutingState.EVENT_ID, "evt-1",
                AiRoutingState.POST_NODE_ID, postNodeId.toString(),
                AiRoutingState.ROUTING_ACTION, "REVIEW",
                AiRoutingState.REVIEW_REASON, "workflow skeleton fallback"
        ))));
        when(reviewTaskService.createPendingTask(any())).thenReturn(reviewTask);

        orchestratorService.orchestrate(message, post);

        verify(reviewTaskService).createPendingTask(any());
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(sseEventService, org.mockito.Mockito.times(2))
                .publish(org.mockito.Mockito.eq(SseEventService.SseEventType.ORCHESTRATION_STATUS), payloadCaptor.capture());
        List<Object> payloads = payloadCaptor.getAllValues();
        SseEventService.OrchestrationStatusPayload lastPayload =
                (SseEventService.OrchestrationStatusPayload) payloads.get(payloads.size() - 1);
        assertThat(lastPayload.status()).isEqualTo("REVIEW_PENDING");
        assertThat(lastPayload.reviewId()).isEqualTo("review-1");
        assertThat(lastPayload.postNodeId()).isEqualTo(postNodeId.toString());
    }
}
