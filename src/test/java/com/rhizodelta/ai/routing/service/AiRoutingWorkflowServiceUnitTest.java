package com.rhizodelta.ai.routing.service;

import com.rhizodelta.ai.routing.domain.AiRoutingState;
import com.rhizodelta.ai.routing.domain.PreFilterResult;
import com.rhizodelta.ai.routing.domain.ReflectionResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiRoutingWorkflowServiceUnitTest {

    private static RuleBasedPreFilterService middleRangePreFilter() {
        RuleBasedPreFilterService preFilterService = mock(RuleBasedPreFilterService.class);
        when(preFilterService.evaluate(org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new PreFilterResult("REVIEW", "middle range", false));
        return preFilterService;
    }

    /**
     * 让规则预过滤直接返回 BRANCH 且 skipLlm=true —— 工作流就不会进入 LLM_EVALUATE，
     * 用于验证 contextPrune / sourceNodeId 类的纯结构性逻辑，不依赖 LLM mock。
     */
    private static RuleBasedPreFilterService alwaysSkipBranchPreFilter() {
        RuleBasedPreFilterService preFilterService = mock(RuleBasedPreFilterService.class);
        when(preFilterService.evaluate(org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new PreFilterResult("BRANCH", "test fixture: skip LLM", true));
        return preFilterService;
    }

    private static ReflectionCriticService confirmedCritic() {
        ReflectionCriticService critic = mock(ReflectionCriticService.class);
        when(critic.critique(anyString(), anyDouble(), anyString(), anyString(), anyString()))
                .thenReturn(new ReflectionResult(true, "MERGE", 0.91, "decision is well-justified"));
        return critic;
    }

    @Test
    void shouldRouteMergeActionToMergeNode() throws Exception {
        AiRoutingEvaluatorService evaluatorService = mock(AiRoutingEvaluatorService.class);
        PreCommitGuard preCommitGuard = mock(PreCommitGuard.class);
        when(evaluatorService.evaluate(any())).thenReturn(new AiRoutingEvaluatorService.RoutingEvaluation(
                "MERGE", "same knowledge unit", 0.91d
        ));
        AiRoutingWorkflowService service = new AiRoutingWorkflowService(
                evaluatorService, middleRangePreFilter(), preCommitGuard, confirmedCritic(), 2);

        AiRoutingState state = service.invokeSkeleton(java.util.Map.of(
                        AiRoutingState.REQUEST_ID, "req-merge-1",
                        AiRoutingState.POST_CONTENT, "post content",
                        AiRoutingState.ROUTING_CONTEXT, "context"
                ))
                .orElseThrow();

        assertThat(state.routingAction()).isEqualTo("MERGE");
        assertThat(state.executedNodes()).containsExactly(
                AiRoutingWorkflowService.LOAD_POST,
                AiRoutingWorkflowService.ENSURE_EMBEDDING,
                AiRoutingWorkflowService.VECTOR_RECALL,
                AiRoutingWorkflowService.CONTEXT_PRUNE,
                AiRoutingWorkflowService.RULE_PRE_FILTER,
                AiRoutingWorkflowService.LLM_EVALUATE,
                AiRoutingWorkflowService.REFLECTION_VALIDATE,
                AiRoutingWorkflowService.PRE_COMMIT_GUARD,
                AiRoutingWorkflowService.EXECUTE_MERGE
        );
        assertThat(state.decisionExplanation()).isNotBlank();
    }

    @Test
    void shouldRouteReviewDecisionToCreateReviewNode() throws Exception {
        AiRoutingEvaluatorService evaluatorService = mock(AiRoutingEvaluatorService.class);
        PreCommitGuard preCommitGuard = mock(PreCommitGuard.class);
        ReflectionCriticService critic = mock(ReflectionCriticService.class);
        when(critic.critique(anyString(), anyDouble(), anyString(), anyString(), anyString()))
                .thenReturn(new ReflectionResult(true, "REVIEW", 0.52, "correctly identified as ambiguous"));
        when(evaluatorService.evaluate(any())).thenReturn(new AiRoutingEvaluatorService.RoutingEvaluation(
                "REVIEW", "ambiguous candidates", 0.52d
        ));
        AiRoutingWorkflowService service = new AiRoutingWorkflowService(
                evaluatorService, middleRangePreFilter(), preCommitGuard, critic, 2);

        AiRoutingState state = service.invokeSkeleton(java.util.Map.of(
                        AiRoutingState.REQUEST_ID, "req-review-1",
                        AiRoutingState.POST_CONTENT, "post content",
                        AiRoutingState.ROUTING_CONTEXT, "context"
                ))
                .orElseThrow();

        assertThat(state.routingAction()).isEqualTo("REVIEW");
        assertThat(state.executedNodes()).containsExactly(
                AiRoutingWorkflowService.LOAD_POST,
                AiRoutingWorkflowService.ENSURE_EMBEDDING,
                AiRoutingWorkflowService.VECTOR_RECALL,
                AiRoutingWorkflowService.CONTEXT_PRUNE,
                AiRoutingWorkflowService.RULE_PRE_FILTER,
                AiRoutingWorkflowService.LLM_EVALUATE,
                AiRoutingWorkflowService.REFLECTION_VALIDATE,
                AiRoutingWorkflowService.PRE_COMMIT_GUARD,
                AiRoutingWorkflowService.CREATE_REVIEW
        );
        assertThat(state.reviewReason()).isEqualTo("ambiguous candidates");
    }

    @Test
    void shouldDowngradeMergeToReviewWhenGuardDetectsStaleGraph() throws Exception {
        AiRoutingEvaluatorService evaluatorService = mock(AiRoutingEvaluatorService.class);
        PreCommitGuard preCommitGuard = mock(PreCommitGuard.class);
        when(evaluatorService.evaluate(any())).thenReturn(new AiRoutingEvaluatorService.RoutingEvaluation(
                "MERGE", "same knowledge unit", 0.91d
        ));
        when(preCommitGuard.evaluate(
                "source-1",
                Instant.parse("2026-03-23T00:00:00Z"),
                "target-1"
        )).thenReturn(new PreCommitGuard.PreCommitGuardResult(true, "source branch advanced during workflow"));
        AiRoutingWorkflowService service = new AiRoutingWorkflowService(
                evaluatorService, middleRangePreFilter(), preCommitGuard, confirmedCritic(), 2);

        AiRoutingState state = service.invokeSkeleton(java.util.Map.of(
                        AiRoutingState.REQUEST_ID, "req-stale-1",
                        AiRoutingState.POST_CONTENT, "post content",
                        AiRoutingState.ROUTING_CONTEXT, "context",
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
    void shouldDeriveSourceNodeFromTargetNodeIdNotFromCandidates() throws Exception {
        // L2 语义：sourceNodeId 兜底用 targetNodeId（用户回复的目标），
        // 不再从相似度候选里随便挑第一个。这避免了 root/边界帖被劫持到无关图节点。
        AiRoutingEvaluatorService evaluatorService = mock(AiRoutingEvaluatorService.class);
        PreCommitGuard preCommitGuard = mock(PreCommitGuard.class);
        when(evaluatorService.evaluate(any())).thenReturn(new AiRoutingEvaluatorService.RoutingEvaluation(
                "REVIEW", "needs review", 0.50d
        ));
        ReflectionCriticService critic = mock(ReflectionCriticService.class);
        when(critic.critique(anyString(), anyDouble(), anyString(), anyString(), anyString()))
                .thenReturn(new ReflectionResult(true, "REVIEW", 0.50, "confirmed review"));
        when(preCommitGuard.evaluate(
                org.mockito.ArgumentMatchers.eq("target-X"),
                org.mockito.ArgumentMatchers.any(Instant.class),
                org.mockito.ArgumentMatchers.eq("target-X"))
        ).thenReturn(new PreCommitGuard.PreCommitGuardResult(false, ""));
        AiRoutingWorkflowService service = new AiRoutingWorkflowService(
                evaluatorService, middleRangePreFilter(), preCommitGuard, critic, 2);

        AiRoutingState state = service.invokeSkeleton(java.util.Map.of(
                        AiRoutingState.REQUEST_ID, "req-context-1",
                        AiRoutingState.POST_CONTENT, "post content",
                        AiRoutingState.ROUTING_CONTEXT, "context",
                        AiRoutingState.TARGET_NODE_ID, "target-X",
                        AiRoutingState.RECALL_CANDIDATE_NODE_IDS, List.of("source-1", "source-2")
                ))
                .orElseThrow();

        assertThat(state.recallCandidateNodeIds()).containsExactly("source-1", "source-2");
        assertThat(state.selectedCandidateNodeIds()).containsExactly("source-1", "source-2");
        // 关键断言：sourceNodeId 取自 targetNodeId，不再是 candidates[0]
        assertThat(state.sourceNodeId()).isEqualTo("target-X");
        assertThat(state.reviewReason()).isEqualTo("needs review");
        assertThat(state.executedNodes()).contains(AiRoutingWorkflowService.VECTOR_RECALL, AiRoutingWorkflowService.CONTEXT_PRUNE);
    }

    @Test
    void shouldKeepSourceBlankWhenNoTargetAndNoExplicitSource() throws Exception {
        // 防御：root/异常路径下 target 与 source 都为空，sourceNodeId 必须保持空，
        // 让上层 orchestrator 走 SKIPPED 而不是错误派发。
        AiRoutingEvaluatorService evaluatorService = mock(AiRoutingEvaluatorService.class);
        PreCommitGuard preCommitGuard = mock(PreCommitGuard.class);
        ReflectionCriticService critic = mock(ReflectionCriticService.class);
        AiRoutingWorkflowService service = new AiRoutingWorkflowService(
                evaluatorService, alwaysSkipBranchPreFilter(), preCommitGuard, critic, 2);

        AiRoutingState state = service.invokeSkeleton(java.util.Map.of(
                        AiRoutingState.REQUEST_ID, "req-blank",
                        AiRoutingState.POST_CONTENT, "post content",
                        AiRoutingState.RECALL_CANDIDATE_NODE_IDS, List.of("c1", "c2")
                ))
                .orElseThrow();

        assertThat(state.sourceNodeId()).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(evaluatorService);
        org.mockito.Mockito.verifyNoInteractions(critic);
    }

    @Test
    void shouldRetryWhenReflectionDisagreesAndNotExhausted() throws Exception {
        AiRoutingEvaluatorService evaluatorService = mock(AiRoutingEvaluatorService.class);
        PreCommitGuard preCommitGuard = mock(PreCommitGuard.class);
        ReflectionCriticService critic = mock(ReflectionCriticService.class);
        // First call: evaluator says MERGE, critic disagrees
        // Second call: evaluator says BRANCH, critic confirms
        when(evaluatorService.evaluate(any()))
                .thenReturn(new AiRoutingEvaluatorService.RoutingEvaluation("MERGE", "same unit", 0.85))
                .thenReturn(new AiRoutingEvaluatorService.RoutingEvaluation("BRANCH", "diverges on closer look", 0.80));
        when(critic.critique(anyString(), anyDouble(), anyString(), anyString(), anyString()))
                .thenReturn(new ReflectionResult(false, "BRANCH", 0.60, "candidates diverge too much for merge"))
                .thenReturn(new ReflectionResult(true, "BRANCH", 0.80, "branch is appropriate"));
        AiRoutingWorkflowService service = new AiRoutingWorkflowService(
                evaluatorService, middleRangePreFilter(), preCommitGuard, critic, 2);

        AiRoutingState state = service.invokeSkeleton(java.util.Map.of(
                        AiRoutingState.REQUEST_ID, "req-retry-1",
                        AiRoutingState.POST_CONTENT, "post content",
                        AiRoutingState.ROUTING_CONTEXT, "context"
                ))
                .orElseThrow();

        org.mockito.Mockito.verify(evaluatorService, org.mockito.Mockito.atLeastOnce()).evaluate(any());
        org.mockito.Mockito.verify(critic, org.mockito.Mockito.atLeastOnce()).critique(anyString(), anyDouble(), anyString(), anyString(), anyString());
        assertThat(state.routingAction()).isEqualTo("BRANCH");
        assertThat(state.reflectionCount()).isEqualTo(1);
        assertThat(state.decisionExplanation()).isNotBlank();
        // Verify retry happened: both evaluator and critic were called twice
        org.mockito.Mockito.verify(evaluatorService, org.mockito.Mockito.times(2)).evaluate(any());
        org.mockito.Mockito.verify(critic, org.mockito.Mockito.times(2))
                .critique(anyString(), anyDouble(), anyString(), anyString(), anyString());
    }

    @Test
    void shouldFallbackToReviewWhenReflectionExhausted() throws Exception {
        AiRoutingEvaluatorService evaluatorService = mock(AiRoutingEvaluatorService.class);
        PreCommitGuard preCommitGuard = mock(PreCommitGuard.class);
        ReflectionCriticService critic = mock(ReflectionCriticService.class);
        // Evaluator always says MERGE, critic always disagrees
        when(evaluatorService.evaluate(any()))
                .thenReturn(new AiRoutingEvaluatorService.RoutingEvaluation("MERGE", "same unit", 0.85));
        when(critic.critique(anyString(), anyDouble(), anyString(), anyString(), anyString()))
                .thenReturn(new ReflectionResult(false, "REVIEW", 0.40, "evidence is insufficient"));
        AiRoutingWorkflowService service = new AiRoutingWorkflowService(
                evaluatorService, middleRangePreFilter(), preCommitGuard, critic, 2);

        AiRoutingState state = service.invokeSkeleton(java.util.Map.of(
                        AiRoutingState.REQUEST_ID, "req-exhausted-1",
                        AiRoutingState.POST_CONTENT, "post content",
                        AiRoutingState.ROUTING_CONTEXT, "context"
                ))
                .orElseThrow();

        assertThat(state.routingAction()).isEqualTo("REVIEW");
        assertThat(state.reflectionCount()).isEqualTo(2);
        assertThat(state.reviewReason()).contains("reflection exhausted");
        assertThat(state.decisionExplanation()).isNotBlank();
        assertThat(state.executedNodes()).contains(AiRoutingWorkflowService.CREATE_REVIEW);
    }

    private static final String LLM_EVALUATE = AiRoutingWorkflowService.LLM_EVALUATE;
    private static final String REFLECTION_VALIDATE = AiRoutingWorkflowService.REFLECTION_VALIDATE;
}
