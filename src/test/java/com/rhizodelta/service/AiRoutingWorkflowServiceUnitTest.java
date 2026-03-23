package com.rhizodelta.service;

import com.rhizodelta.domain.ai.AiRoutingState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiRoutingWorkflowServiceUnitTest {

    @Test
    void shouldRouteMergeActionToMergeNode() throws Exception {
        AiRoutingWorkflowService service = new AiRoutingWorkflowService();

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
        AiRoutingWorkflowService service = new AiRoutingWorkflowService();

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
}
