package com.rhizodelta.service;

import com.rhizodelta.domain.ai.AiRoutingState;
import com.rhizodelta.domain.ai.PreFilterResult;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

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

    public AiRoutingWorkflowService(
            AiRoutingEvaluatorService aiRoutingEvaluatorService,
            RuleBasedPreFilterService ruleBasedPreFilterService,
            PreCommitGuard preCommitGuard
    ) throws GraphStateException {
        this.aiRoutingEvaluatorService = aiRoutingEvaluatorService;
        this.ruleBasedPreFilterService = ruleBasedPreFilterService;
        this.preCommitGuard = preCommitGuard;
        this.workflow = buildWorkflow();
    }

    public Optional<AiRoutingState> invokeSkeleton(Map<String, Object> input) {
        Objects.requireNonNull(input, "input must not be null");
        return workflow.invoke(input);
    }

    CompiledGraph<AiRoutingState> workflow() {
        return workflow;
    }

    private CompiledGraph<AiRoutingState> buildWorkflow() throws GraphStateException {
        StateGraph<AiRoutingState> graph = new StateGraph<>(AiRoutingState.channels(), AiRoutingState::new);
        graph.addNode(LOAD_POST, loadPost());
        graph.addNode(ENSURE_EMBEDDING, appendNode(ENSURE_EMBEDDING));
        graph.addNode(VECTOR_RECALL, vectorRecall());
        graph.addNode(CONTEXT_PRUNE, contextPrune());
        graph.addNode(RULE_PRE_FILTER, rulePreFilter());
        graph.addNode(LLM_EVALUATE, llmEvaluate());
        graph.addNode(REFLECTION_VALIDATE, appendNode(REFLECTION_VALIDATE));
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
        graph.addEdge(REFLECTION_VALIDATE, PRE_COMMIT_GUARD);
        graph.addConditionalEdges(PRE_COMMIT_GUARD, routeByAction(), Map.of(
                "MERGE", EXECUTE_MERGE,
                "BRANCH", EXECUTE_BRANCH,
                "REVIEW", CREATE_REVIEW
        ));
        graph.addEdge(EXECUTE_MERGE, GraphDefinition.END);
        graph.addEdge(EXECUTE_BRANCH, GraphDefinition.END);
        graph.addEdge(CREATE_REVIEW, GraphDefinition.END);
        return graph.compile();
    }

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

    private AsyncNodeAction<AiRoutingState> vectorRecall() {
        return AsyncNodeAction.node_async(state -> Map.of(
                AiRoutingState.EXECUTED_NODES, List.of(VECTOR_RECALL),
                AiRoutingState.RECALL_CANDIDATE_NODE_IDS, normalizeRecallCandidates(state)
        ));
    }

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

    private AsyncNodeAction<AiRoutingState> llmEvaluate() {
        return AsyncNodeAction.node_async(state -> {
            AiRoutingEvaluatorService.RoutingEvaluation evaluation = aiRoutingEvaluatorService.evaluate(
                    new AiRoutingEvaluatorService.RoutingEvaluationCommand(
                            state.postContent(),
                            state.routingContext(),
                            state.targetNodeId()
                    )
            );
            return Map.of(
                    AiRoutingState.EXECUTED_NODES, List.of(LLM_EVALUATE),
                    AiRoutingState.ROUTING_ACTION, normalizeAction(evaluation.action()),
                    AiRoutingState.REVIEW_REASON, evaluation.reason()
            );
        });
    }

    private AsyncNodeAction<AiRoutingState> createReview() {
        return AsyncNodeAction.node_async(state -> Map.of(
                AiRoutingState.EXECUTED_NODES, List.of(CREATE_REVIEW),
                AiRoutingState.REVIEW_REASON, state.reviewReason().isBlank()
                        ? "workflow skeleton fallback"
                        : state.reviewReason()
        ));
    }

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
}
