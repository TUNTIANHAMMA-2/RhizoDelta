package com.rhizodelta.service;

import com.rhizodelta.domain.ai.AiRoutingState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiRoutingWorkflowServiceUnitTest {

    @Test
    void shouldRouteMergeActionToMergeNode() throws Exception {
        PreCommitGuard preCommitGuard = mock(PreCommitGuard.class);
        AiRoutingWorkflowService service = new AiRoutingWorkflowService(preCommitGuard);

        AiRoutingState state = service.invokeSkeleton(java.util.Map.of(
                        AiRoutingState.REQUEST_ID, "req-merge-1",
                        AiRoutingState.ROUTING_ACTION, "MERGE"
                ))
                .orElseThrow();

        assertThat(state.routingAction()).isEqualTo("MERGE");
        assertThat(state.executedNodes()).containsExactly(
                AiRoutingWorkflowService.LOAD_POST,
                AiRoutingWorkflowService.ENSURE_EMBEDDING,
                AiRoutingWorkflowService.VECTOR_RECALL,
                AiRoutingWorkflowService.CONTEXT_PRUNE,
                AiRoutingWorkflowService.LLM_EVALUATE,
                AiRoutingWorkflowService.REFLECTION_VALIDATE,
                AiRoutingWorkflowService.PRE_COMMIT_GUARD,
                AiRoutingWorkflowService.EXECUTE_MERGE
        );
    }

    @Test
    void shouldFallbackUnknownActionToReviewNode() throws Exception {
        PreCommitGuard preCommitGuard = mock(PreCommitGuard.class);
        AiRoutingWorkflowService service = new AiRoutingWorkflowService(preCommitGuard);

        AiRoutingState state = service.invokeSkeleton(java.util.Map.of(
                        AiRoutingState.REQUEST_ID, "req-review-1",
                        AiRoutingState.ROUTING_ACTION, "UNSURE"
                ))
                .orElseThrow();

        assertThat(state.routingAction()).isEqualTo("REVIEW");
        assertThat(state.executedNodes()).containsExactly(
                AiRoutingWorkflowService.LOAD_POST,
                AiRoutingWorkflowService.ENSURE_EMBEDDING,
                AiRoutingWorkflowService.VECTOR_RECALL,
                AiRoutingWorkflowService.CONTEXT_PRUNE,
                AiRoutingWorkflowService.LLM_EVALUATE,
                AiRoutingWorkflowService.REFLECTION_VALIDATE,
                AiRoutingWorkflowService.PRE_COMMIT_GUARD,
                AiRoutingWorkflowService.CREATE_REVIEW
        );
        assertThat(state.reviewReason()).isEqualTo("workflow skeleton fallback");
    }

    @Test
    void shouldDowngradeMergeToReviewWhenGuardDetectsStaleGraph() throws Exception {
        PreCommitGuard preCommitGuard = mock(PreCommitGuard.class);
        when(preCommitGuard.evaluate(
                "source-1",
                Instant.parse("2026-03-23T00:00:00Z"),
                "target-1"
        )).thenReturn(new PreCommitGuard.PreCommitGuardResult(true, "source branch advanced during workflow"));
        AiRoutingWorkflowService service = new AiRoutingWorkflowService(preCommitGuard);

        AiRoutingState state = service.invokeSkeleton(java.util.Map.of(
                        AiRoutingState.REQUEST_ID, "req-stale-1",
                        AiRoutingState.ROUTING_ACTION, "MERGE",
                        AiRoutingState.SOURCE_NODE_ID, "source-1",
                        AiRoutingState.TARGET_NODE_ID, "target-1",
                        AiRoutingState.WORKFLOW_STARTED_AT, Instant.parse("2026-03-23T00:00:00Z")
                ))
                .orElseThrow();

        assertThat(state.routingAction()).isEqualTo("REVIEW");
        assertThat(state.reviewReason()).isEqualTo("source branch advanced during workflow");
        assertThat(state.executedNodes()).contains(AiRoutingWorkflowService.CREATE_REVIEW);
    }

    @Test
    void shouldDeriveSelectedCandidatesAndSourceNodeFromRecallContext() throws Exception {
        PreCommitGuard preCommitGuard = mock(PreCommitGuard.class);
        when(preCommitGuard.evaluate(
                org.mockito.ArgumentMatchers.eq("source-1"),
                org.mockito.ArgumentMatchers.any(Instant.class),
                org.mockito.ArgumentMatchers.eq(""))
        ).thenReturn(new PreCommitGuard.PreCommitGuardResult(false, ""));
        AiRoutingWorkflowService service = new AiRoutingWorkflowService(preCommitGuard);

        AiRoutingState state = service.invokeSkeleton(java.util.Map.of(
                        AiRoutingState.REQUEST_ID, "req-context-1",
                        AiRoutingState.RECALL_CANDIDATE_NODE_IDS, List.of("source-1", "source-2")
                ))
                .orElseThrow();

        assertThat(state.recallCandidateNodeIds()).containsExactly("source-1", "source-2");
        assertThat(state.selectedCandidateNodeIds()).containsExactly("source-1", "source-2");
        assertThat(state.sourceNodeId()).isEqualTo("source-1");
        assertThat(state.executedNodes()).contains(AiRoutingWorkflowService.VECTOR_RECALL, AiRoutingWorkflowService.CONTEXT_PRUNE);
    }
}
