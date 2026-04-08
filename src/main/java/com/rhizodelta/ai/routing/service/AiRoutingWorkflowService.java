package com.rhizodelta.ai.routing.service;

import com.rhizodelta.ai.routing.domain.AiRoutingState;
import com.rhizodelta.ai.routing.domain.DecisionExplanation;
import com.rhizodelta.ai.routing.domain.PreFilterResult;
import com.rhizodelta.ai.routing.domain.ReflectionResult;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 定义 AI 路由的状态工作流。
 *
 * <p>该服务基于 LangGraph4j 将召回、规则预过滤、LLM 评估、反思校验、提交前守卫和复核分支
 * 串成一个可执行状态图。
 *
 * <p><b>设计意图</b>：
 * <ul>
 *   <li>把每个路由阶段拆成独立节点，便于观察与扩展。</li>
 *   <li>通过状态图显式表达“跳过 LLM”“重试反思”“进入人工复核”等分支。</li>
 *   <li>把最终动作收敛为 {@code MERGE}/{@code BRANCH}/{@code REVIEW} 三种出口。</li>
 * </ul>
 */
@Service
public class AiRoutingWorkflowService {
    static final String LOAD_POST = "load-post";
    static final String ENSURE_EMBEDDING = "ensure-embedding";
    static final String VECTOR_RECALL = "vector-recall";
    static final String CONTEXT_PRUNE = "context-prune";
    static final String RULE_PRE_FILTER = "rule-pre-filter";
    static final String LLM_EVALUATE = "llm-evaluate";
    static final String REFLECTION_VALIDATE = "reflection-validate";
    static final String PRE_COMMIT_GUARD = "pre-commit-guard";
    static final String EXECUTE_MERGE = "execute-merge";
    static final String EXECUTE_BRANCH = "execute-branch";
    static final String CREATE_REVIEW = "create-review";

    private final CompiledGraph<AiRoutingState> workflow;
    private final AiRoutingEvaluatorService aiRoutingEvaluatorService;
    private final RuleBasedPreFilterService ruleBasedPreFilterService;
    private final PreCommitGuard preCommitGuard;
    private final ReflectionCriticService reflectionCriticService;
    private final int maxReflectionAttempts;

    public AiRoutingWorkflowService(
            AiRoutingEvaluatorService aiRoutingEvaluatorService,
            RuleBasedPreFilterService ruleBasedPreFilterService,
            PreCommitGuard preCommitGuard,
            ReflectionCriticService reflectionCriticService,
            @Value("${rhizodelta.ai.reflection.max-attempts:2}") int maxReflectionAttempts
    ) throws GraphStateException {
        this.aiRoutingEvaluatorService = aiRoutingEvaluatorService;
        this.ruleBasedPreFilterService = ruleBasedPreFilterService;
        this.preCommitGuard = preCommitGuard;
        this.reflectionCriticService = reflectionCriticService;
        this.maxReflectionAttempts = maxReflectionAttempts;
        this.workflow = buildWorkflow();
    }

    /**
     * 以初始输入执行路由骨架工作流。
     *
     * <p>该入口返回最终状态快照，供编排层决定后续是执行决策还是创建复核任务。
     *
     * <p>
     *
     * @param input 初始状态数据。
     * @return 最终状态。
     */
    public Optional<AiRoutingState> invokeSkeleton(Map<String, Object> input) {
        Objects.requireNonNull(input, "input must not be null");
        return workflow.invoke(input);
    }

    CompiledGraph<AiRoutingState> workflow() {
        return workflow;
    }

    /**
     * 构建并编译路由状态图。
     *
     * <p>这里定义了完整的节点、边和条件跳转关系，是整个 AI 路由流程的结构化说明书。
     */
    private CompiledGraph<AiRoutingState> buildWorkflow() throws GraphStateException {
        StateGraph<AiRoutingState> graph = new StateGraph<>(AiRoutingState.channels(), AiRoutingState::new);
        graph.addNode(LOAD_POST, loadPost());
        graph.addNode(ENSURE_EMBEDDING, appendNode(ENSURE_EMBEDDING));
        graph.addNode(VECTOR_RECALL, vectorRecall());
        graph.addNode(CONTEXT_PRUNE, contextPrune());
        graph.addNode(RULE_PRE_FILTER, rulePreFilter());
        graph.addNode(LLM_EVALUATE, llmEvaluate());
        graph.addNode(REFLECTION_VALIDATE, reflectionValidate());
        graph.addNode(PRE_COMMIT_GUARD, preCommitGuardNode());
        graph.addNode(EXECUTE_MERGE, appendNode(EXECUTE_MERGE));
        graph.addNode(EXECUTE_BRANCH, appendNode(EXECUTE_BRANCH));
        graph.addNode(CREATE_REVIEW, createReview());
        graph.addEdge(GraphDefinition.START, LOAD_POST);
        graph.addEdge(LOAD_POST, ENSURE_EMBEDDING);
        graph.addEdge(ENSURE_EMBEDDING, VECTOR_RECALL);
        graph.addEdge(VECTOR_RECALL, CONTEXT_PRUNE);
        graph.addEdge(CONTEXT_PRUNE, RULE_PRE_FILTER);
        graph.addConditionalEdges(RULE_PRE_FILTER, routeBySkipLlm(), Map.of(
                "skip", PRE_COMMIT_GUARD,
                "evaluate", LLM_EVALUATE
        ));
        graph.addEdge(LLM_EVALUATE, REFLECTION_VALIDATE);
        graph.addConditionalEdges(REFLECTION_VALIDATE, routeByReflection(), Map.of(
                "confirmed", PRE_COMMIT_GUARD,
                "retry", LLM_EVALUATE,
                "exhausted", PRE_COMMIT_GUARD
        ));
        graph.addConditionalEdges(PRE_COMMIT_GUARD, routeByAction(), Map.of(
                "MERGE", EXECUTE_MERGE,
                "BRANCH", EXECUTE_BRANCH,
                "REVIEW", CREATE_REVIEW
        ));
        graph.addEdge(EXECUTE_MERGE, GraphDefinition.END);
        graph.addEdge(EXECUTE_BRANCH, GraphDefinition.END);
        graph.addEdge(CREATE_REVIEW, GraphDefinition.END);
        return graph.compile(CompileConfig.builder()
                .recursionLimit(25 + maxReflectionAttempts * 2)
                .build());
    }

    /**
     * 创建加载初始状态的节点。
     */
    private AsyncNodeAction<AiRoutingState> loadPost() {
        return AsyncNodeAction.node_async(state -> Map.of(
                AiRoutingState.EXECUTED_NODES, List.of(LOAD_POST),
                AiRoutingState.ROUTING_ACTION, normalizeAction(state.routingAction()),
                AiRoutingState.WORKFLOW_STARTED_AT, resolveWorkflowStartedAt(state.workflowStartedAt())
        ));
    }

    private AsyncNodeAction<AiRoutingState> appendNode(String nodeName) {
        return AsyncNodeAction.node_async(state -> Map.of(
                AiRoutingState.EXECUTED_NODES, List.of(nodeName)
        ));
    }

    /**
     * 创建召回候选归一化节点。
     */
    private AsyncNodeAction<AiRoutingState> vectorRecall() {
        return AsyncNodeAction.node_async(state -> Map.of(
                AiRoutingState.EXECUTED_NODES, List.of(VECTOR_RECALL),
                AiRoutingState.RECALL_CANDIDATE_NODE_IDS, normalizeRecallCandidates(state)
        ));
    }

    /**
     * 创建上下文裁剪结果归一化节点。
     */
    private AsyncNodeAction<AiRoutingState> contextPrune() {
        return AsyncNodeAction.node_async(state -> {
            List<String> selectedCandidateNodeIds = normalizeSelectedCandidates(state);
            String sourceNodeId = state.sourceNodeId().isBlank() && !selectedCandidateNodeIds.isEmpty()
                    ? selectedCandidateNodeIds.get(0)
                    : state.sourceNodeId();
            return Map.of(
                    AiRoutingState.EXECUTED_NODES, List.of(CONTEXT_PRUNE),
                    AiRoutingState.SELECTED_CANDIDATE_NODE_IDS, selectedCandidateNodeIds,
                    AiRoutingState.SOURCE_NODE_ID, sourceNodeId
            );
        });
    }

    /**
     * 创建规则预过滤节点。
     *
     * <p>该节点决定当前是否可以跳过 LLM 直接进入后续阶段。
     */
    private AsyncNodeAction<AiRoutingState> rulePreFilter() {
        return AsyncNodeAction.node_async(state -> {
            boolean hasCandidates = !state.selectedCandidateNodeIds().isEmpty();
            PreFilterResult result = ruleBasedPreFilterService.evaluate(state.topScore(), hasCandidates);
            var output = new HashMap<String, Object>();
            output.put(AiRoutingState.EXECUTED_NODES, List.of(RULE_PRE_FILTER));
            output.put(AiRoutingState.RULE_DECISION, result.action());
            output.put(AiRoutingState.SKIP_LLM, result.skipLlm());
            if (result.skipLlm()) {
                output.put(AiRoutingState.ROUTING_ACTION, normalizeAction(result.action()));
                output.put(AiRoutingState.REVIEW_REASON, result.reason());
            }
            return output;
        });
    }

    private AsyncEdgeAction<AiRoutingState> routeBySkipLlm() {
        return state -> CompletableFuture.completedFuture(state.skipLlm() ? "skip" : "evaluate");
    }

    /**
     * 创建主 LLM 评估节点。
     */
    private AsyncNodeAction<AiRoutingState> llmEvaluate() {
        return AsyncNodeAction.node_async(state -> {
            AiRoutingEvaluatorService.RoutingEvaluation evaluation = aiRoutingEvaluatorService.evaluate(
                    new AiRoutingEvaluatorService.RoutingEvaluationCommand(
                            state.postContent(),
                            state.routingContext(),
                            state.targetNodeId(),
                            state.criticFeedback()
                    )
            );
            var output = new HashMap<String, Object>();
            output.put(AiRoutingState.EXECUTED_NODES, List.of(LLM_EVALUATE));
            output.put(AiRoutingState.ROUTING_ACTION, normalizeAction(evaluation.action()));
            output.put(AiRoutingState.REVIEW_REASON, evaluation.reason());
            // Store initial action/confidence on first evaluation (reflectionCount == 0)
            if (state.reflectionCount() == 0) {
                output.put(AiRoutingState.INITIAL_ACTION, normalizeAction(evaluation.action()));
                output.put(AiRoutingState.INITIAL_CONFIDENCE, evaluation.confidence());
            }
            return output;
        });
    }

    /**
     * 创建反思校验节点。
     *
     * <p>该节点可能确认原结论、触发重试，或在达到最大次数后降级为人工复核。
     */
    private AsyncNodeAction<AiRoutingState> reflectionValidate() {
        return AsyncNodeAction.node_async(state -> {
            ReflectionResult result = reflectionCriticService.critique(
                    state.routingAction(),
                    state.initialConfidence() > 0 ? state.initialConfidence() : 0.5,
                    state.reviewReason(),
                    state.postContent(),
                    state.routingContext()
            );

            var output = new HashMap<String, Object>();
            output.put(AiRoutingState.EXECUTED_NODES, List.of(REFLECTION_VALIDATE));

            if (result.confirmed()) {
                DecisionExplanation explanation = new DecisionExplanation(
                        state.routingAction(),
                        result.revisedConfidence(),
                        state.reviewReason(),
                        state.routingContext(),
                        result.criticReason()
                );
                output.put(AiRoutingState.DECISION_EXPLANATION, serializeExplanation(explanation));
                return output;
            }

            int currentCount = state.reflectionCount() + 1;
            output.put(AiRoutingState.REFLECTION_COUNT, currentCount);

            if (currentCount >= maxReflectionAttempts) {
                // Exhausted — fall back to REVIEW
                output.put(AiRoutingState.ROUTING_ACTION, "REVIEW");
                output.put(AiRoutingState.REVIEW_REASON, "reflection exhausted after " + currentCount + " attempts: " + result.criticReason());
                DecisionExplanation explanation = new DecisionExplanation(
                        "REVIEW",
                        result.revisedConfidence(),
                        "reflection exhausted",
                        state.routingContext(),
                        result.criticReason()
                );
                output.put(AiRoutingState.DECISION_EXPLANATION, serializeExplanation(explanation));
                return output;
            }

            // Retry — feed critic reason back to LLM
            output.put(AiRoutingState.CRITIC_FEEDBACK, result.criticReason());
            return output;
        });
    }

    private AsyncEdgeAction<AiRoutingState> routeByReflection() {
        return state -> {
            String explanation = state.decisionExplanation();
            if (!explanation.isBlank()) {
                // Has explanation means either confirmed or exhausted
                if ("REVIEW".equals(state.routingAction()) && state.reflectionCount() >= maxReflectionAttempts) {
                    return CompletableFuture.completedFuture("exhausted");
                }
                return CompletableFuture.completedFuture("confirmed");
            }
            return CompletableFuture.completedFuture("retry");
        };
    }

    private AsyncNodeAction<AiRoutingState> createReview() {
        return AsyncNodeAction.node_async(state -> Map.of(
                AiRoutingState.EXECUTED_NODES, List.of(CREATE_REVIEW),
                AiRoutingState.REVIEW_REASON, state.reviewReason().isBlank()
                        ? "workflow skeleton fallback"
                        : state.reviewReason()
        ));
    }

    /**
     * 创建提交前守卫节点。
     *
     * <p>若图状态已经前进，则会把动作降级为 {@code REVIEW}，避免基于旧上下文继续执行。
     */
    private AsyncNodeAction<AiRoutingState> preCommitGuardNode() {
        return AsyncNodeAction.node_async(state -> {
            if (state.sourceNodeId().isBlank()) {
                return Map.of(AiRoutingState.EXECUTED_NODES, List.of(PRE_COMMIT_GUARD));
            }
            PreCommitGuard.PreCommitGuardResult result = preCommitGuard.evaluate(
                    state.sourceNodeId(),
                    resolveWorkflowStartedAt(state.workflowStartedAt()),
                    state.targetNodeId()
            );
            if (!result.stale()) {
                return Map.of(AiRoutingState.EXECUTED_NODES, List.of(PRE_COMMIT_GUARD));
            }
            return Map.of(
                    AiRoutingState.EXECUTED_NODES, List.of(PRE_COMMIT_GUARD),
                    AiRoutingState.ROUTING_ACTION, "REVIEW",
                    AiRoutingState.REVIEW_REASON, result.reason()
            );
        });
    }

    private AsyncEdgeAction<AiRoutingState> routeByAction() {
        return state -> CompletableFuture.completedFuture(normalizeAction(state.routingAction()));
    }

    private List<String> normalizeRecallCandidates(AiRoutingState state) {
        if (!state.recallCandidateNodeIds().isEmpty()) {
            return List.copyOf(state.recallCandidateNodeIds());
        }
        if (state.sourceNodeId().isBlank()) {
            return List.of();
        }
        return List.of(state.sourceNodeId());
    }

    private List<String> normalizeSelectedCandidates(AiRoutingState state) {
        if (!state.selectedCandidateNodeIds().isEmpty()) {
            return List.copyOf(state.selectedCandidateNodeIds());
        }
        return normalizeRecallCandidates(state);
    }

    private String normalizeAction(String action) {
        if ("MERGE".equalsIgnoreCase(action)) {
            return "MERGE";
        }
        if ("BRANCH".equalsIgnoreCase(action)) {
            return "BRANCH";
        }
        return "REVIEW";
    }

    private Instant resolveWorkflowStartedAt(Instant workflowStartedAt) {
        if (workflowStartedAt == null || Instant.EPOCH.equals(workflowStartedAt)) {
            return Instant.now();
        }
        return workflowStartedAt;
    }

    private String serializeExplanation(DecisionExplanation explanation) {
        return "{\"action\":\"%s\",\"confidence\":%.2f,\"reason\":\"%s\",\"candidateComparison\":\"%s\",\"reflectionSummary\":\"%s\"}".formatted(
                explanation.action(),
                explanation.confidence(),
                escapeJson(explanation.reason()),
                escapeJson(truncate(explanation.candidateComparison(), 500)),
                escapeJson(explanation.reflectionSummary())
        );
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return "";
        if (value.length() <= maxLength) return value;
        return value.substring(0, maxLength) + "...";
    }
}
