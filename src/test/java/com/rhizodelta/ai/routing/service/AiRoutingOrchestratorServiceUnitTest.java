package com.rhizodelta.ai.routing.service;

import com.rhizodelta.ai.routing.domain.AiRoutingState;
import com.rhizodelta.ai.context.service.BranchContextService;
import com.rhizodelta.ai.context.domain.embedding.PrunedContext;
import com.rhizodelta.ai.context.domain.embedding.SimilaritySearchResult;
import com.rhizodelta.ai.context.service.RoutingRecallService;
import com.rhizodelta.core.domain.node.HumanPost;
import com.rhizodelta.consensus.service.ReviewTaskService;
import com.rhizodelta.infrastructure.messaging.message.PostEventMessage;
import com.rhizodelta.infrastructure.sse.service.SseEventService;
import com.rhizodelta.consensus.domain.review.ReviewTask;
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
        RoutingRecallService routingRecallService = mock(RoutingRecallService.class);
        AiRoutingExecutionService aiRoutingExecutionService = mock(AiRoutingExecutionService.class);
        ReviewTaskService reviewTaskService = mock(ReviewTaskService.class);
        SseEventService sseEventService = mock(SseEventService.class);
        BranchContextService branchContextService = mock(BranchContextService.class);
        AiRoutingOrchestratorService orchestratorService = new AiRoutingOrchestratorService(
                workflowService,
                routingRecallService,
                aiRoutingExecutionService,
                reviewTaskService,
                sseEventService,
                branchContextService,
                "gpt-4o-test"
        );
        UUID postNodeId = UUID.randomUUID();
        UUID recalledCandidateNodeId = UUID.randomUUID();
        UUID workflowCandidateNodeId = UUID.randomUUID();
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
        when(routingRecallService.recall("post content", null)).thenReturn(new PrunedContext(
                List.of(new SimilaritySearchResult(recalledCandidateNodeId, "Human_Post", 0.95d, "candidate", Instant.now(), List.of())),
                false,
                0
        ));
        when(branchContextService.buildContext(any(), any())).thenReturn(
                new BranchContextService.BranchContext(List.of(), List.of(), List.of()));
        when(branchContextService.formatForRouting(any())).thenReturn("");
        when(workflowService.invokeSkeleton(any())).thenReturn(Optional.of(new AiRoutingState(Map.of(
                AiRoutingState.REQUEST_ID, "req-1",
                AiRoutingState.EVENT_ID, "evt-1",
                AiRoutingState.POST_NODE_ID, postNodeId.toString(),
                AiRoutingState.SOURCE_NODE_ID, workflowCandidateNodeId.toString(),
                AiRoutingState.SELECTED_CANDIDATE_NODE_IDS, List.of(workflowCandidateNodeId.toString()),
                AiRoutingState.ROUTING_ACTION, "REVIEW",
                AiRoutingState.REVIEW_REASON, "workflow skeleton fallback"
        ))));
        when(reviewTaskService.createPendingTask(any())).thenReturn(reviewTask);

        orchestratorService.orchestrate(message, post);

        ArgumentCaptor<Map<String, Object>> workflowInputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(workflowService).invokeSkeleton(workflowInputCaptor.capture());
        assertThat(workflowInputCaptor.getValue().get(AiRoutingState.RECALL_CANDIDATE_NODE_IDS))
                .isEqualTo(List.of(recalledCandidateNodeId.toString()));
        assertThat(workflowInputCaptor.getValue().get(AiRoutingState.SELECTED_CANDIDATE_NODE_IDS))
                .isEqualTo(List.of(recalledCandidateNodeId.toString()));
        assertThat(workflowInputCaptor.getValue().get(AiRoutingState.POST_CONTENT)).isEqualTo("post content");
        assertThat((String) workflowInputCaptor.getValue().get(AiRoutingState.ROUTING_CONTEXT))
                .contains(recalledCandidateNodeId.toString())
                .contains("candidate");

        ArgumentCaptor<ReviewTask.CreateReviewTaskCommand> reviewCommandCaptor =
                ArgumentCaptor.forClass(ReviewTask.CreateReviewTaskCommand.class);
        verify(reviewTaskService).createPendingTask(reviewCommandCaptor.capture());
        ReviewTask.CreateReviewTaskCommand command = reviewCommandCaptor.getValue();
        assertThat(command.candidateNodeIds()).containsExactly(workflowCandidateNodeId.toString());
        assertThat(command.draftPayload().get("source_node_id")).isEqualTo(workflowCandidateNodeId.toString());
        assertThat(command.draftPayload().get("decision_id")).isEqualTo("evt-1:review");
        assertThat(command.draftPayload().get("agent_version")).isEqualTo("gpt-4o-test");
        assertThat(command.draftPayload().get("summary_content")).isEqualTo("post content");
        assertThat(command.draftPayload().get("synthesized_from")).isEqualTo(List.of(postNodeId.toString()));
        assertThat(command.draftPayload().get("content")).isEqualTo("post content");
        assertThat(command.draftPayload().get("author_id")).isEqualTo("author-1");
        assertThat(command.draftPayload().get("reason")).isNotNull();

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(sseEventService, org.mockito.Mockito.times(3))
                .publish(org.mockito.Mockito.eq(SseEventService.SseEventType.ORCHESTRATION_STATUS), payloadCaptor.capture());
        List<Object> payloads = payloadCaptor.getAllValues();
        SseEventService.OrchestrationStatusPayload recallPayload =
                (SseEventService.OrchestrationStatusPayload) payloads.get(1);
        SseEventService.OrchestrationStatusPayload lastPayload =
                (SseEventService.OrchestrationStatusPayload) payloads.get(payloads.size() - 1);
        assertThat(recallPayload.status()).isEqualTo("RECALL_COMPLETED");
        assertThat(lastPayload.status()).isEqualTo("REVIEW_PENDING");
        assertThat(lastPayload.reviewId()).isEqualTo("review-1");
        assertThat(lastPayload.postNodeId()).isEqualTo(postNodeId.toString());
        assertThat(lastPayload.authorId()).isEqualTo("author-1");
    }

    @Test
    void shouldExecuteMergeDecisionWithoutCreatingReviewTask() {
        AiRoutingWorkflowService workflowService = mock(AiRoutingWorkflowService.class);
        RoutingRecallService routingRecallService = mock(RoutingRecallService.class);
        AiRoutingExecutionService aiRoutingExecutionService = mock(AiRoutingExecutionService.class);
        ReviewTaskService reviewTaskService = mock(ReviewTaskService.class);
        SseEventService sseEventService = mock(SseEventService.class);
        BranchContextService branchContextService = mock(BranchContextService.class);
        AiRoutingOrchestratorService orchestratorService = new AiRoutingOrchestratorService(
                workflowService,
                routingRecallService,
                aiRoutingExecutionService,
                reviewTaskService,
                sseEventService,
                branchContextService,
                "gpt-4o-test"
        );
        UUID postNodeId = UUID.randomUUID();
        UUID candidateNodeId = UUID.randomUUID();
        HumanPost post = HumanPost.create(postNodeId, "merged content", "author-1", "req-1");
        PostEventMessage message = new PostEventMessage("req-1", "author-1", "merged content", null, "evt-1");
        when(routingRecallService.recall("merged content", null)).thenReturn(new PrunedContext(
                List.of(new SimilaritySearchResult(candidateNodeId, "Human_Post", 0.98d, "candidate", Instant.now(), List.of())),
                false,
                0
        ));
        when(branchContextService.buildContext(any(), any())).thenReturn(
                new BranchContextService.BranchContext(List.of(), List.of(), List.of()));
        when(branchContextService.formatForRouting(any())).thenReturn("");
        when(workflowService.invokeSkeleton(any())).thenReturn(Optional.of(new AiRoutingState(Map.of(
                AiRoutingState.REQUEST_ID, "req-1",
                AiRoutingState.EVENT_ID, "evt-1",
                AiRoutingState.POST_NODE_ID, postNodeId.toString(),
                AiRoutingState.SOURCE_NODE_ID, candidateNodeId.toString(),
                AiRoutingState.ROUTING_ACTION, "MERGE",
                AiRoutingState.REVIEW_REASON, "same knowledge unit"
        ))));
        when(aiRoutingExecutionService.execute(any())).thenReturn(new AiRoutingExecutionService.RoutingExecutionResult(
                "MERGE",
                new com.rhizodelta.consensus.domain.decision.DecisionResult("evt-1:merge", UUID.randomUUID(), "QUEUED")
        ));

        orchestratorService.orchestrate(message, post);

        verify(reviewTaskService, org.mockito.Mockito.never()).createPendingTask(any());
        ArgumentCaptor<AiRoutingExecutionService.RoutingExecutionCommand> executionCaptor =
                ArgumentCaptor.forClass(AiRoutingExecutionService.RoutingExecutionCommand.class);
        verify(aiRoutingExecutionService).execute(executionCaptor.capture());
        AiRoutingExecutionService.RoutingExecutionCommand executionCommand = executionCaptor.getValue();
        assertThat(executionCommand.action()).isEqualTo("MERGE");
        assertThat(executionCommand.sourceNodeId()).isEqualTo(candidateNodeId.toString());
        assertThat(executionCommand.reason()).isEqualTo("same knowledge unit");

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(sseEventService, org.mockito.Mockito.times(3))
                .publish(org.mockito.Mockito.eq(SseEventService.SseEventType.ORCHESTRATION_STATUS), payloadCaptor.capture());
        List<Object> payloads = payloadCaptor.getAllValues();
        SseEventService.OrchestrationStatusPayload lastPayload =
                (SseEventService.OrchestrationStatusPayload) payloads.get(payloads.size() - 1);
        assertThat(lastPayload.status()).isEqualTo("MERGE_QUEUED");
        assertThat(lastPayload.message()).contains("evt-1:merge");
    }
}
