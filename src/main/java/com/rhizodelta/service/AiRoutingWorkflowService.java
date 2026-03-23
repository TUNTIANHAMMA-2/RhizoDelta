package com.rhizodelta.service;

import com.rhizodelta.domain.ai.AiRoutingState;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.springframework.stereotype.Service;

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
    static final String LLM_EVALUATE = "llm-evaluate";
    static final String REFLECTION_VALIDATE = "reflection-validate";
    static final String PRE_COMMIT_GUARD = "pre-commit-guard";
    static final String EXECUTE_MERGE = "execute-merge";
    static final String EXECUTE_BRANCH = "execute-branch";
    static final String CREATE_REVIEW = "create-review";

    private final CompiledGraph<AiRoutingState> workflow;

    public AiRoutingWorkflowService() throws GraphStateException {
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
        graph.addNode(VECTOR_RECALL, appendNode(VECTOR_RECALL));
        graph.addNode(CONTEXT_PRUNE, appendNode(CONTEXT_PRUNE));
        graph.addNode(LLM_EVALUATE, appendNode(LLM_EVALUATE));
        graph.addNode(REFLECTION_VALIDATE, appendNode(REFLECTION_VALIDATE));
        graph.addNode(PRE_COMMIT_GUARD, appendNode(PRE_COMMIT_GUARD));
        graph.addNode(EXECUTE_MERGE, appendNode(EXECUTE_MERGE));
        graph.addNode(EXECUTE_BRANCH, appendNode(EXECUTE_BRANCH));
        graph.addNode(CREATE_REVIEW, createReview());
        graph.addEdge(GraphDefinition.START, LOAD_POST);
        graph.addEdge(LOAD_POST, ENSURE_EMBEDDING);
        graph.addEdge(ENSURE_EMBEDDING, VECTOR_RECALL);
        graph.addEdge(VECTOR_RECALL, CONTEXT_PRUNE);
        graph.addEdge(CONTEXT_PRUNE, LLM_EVALUATE);
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
                AiRoutingState.ROUTING_ACTION, normalizeAction(state.routingAction())
        ));
    }

    private AsyncNodeAction<AiRoutingState> appendNode(String nodeName) {
        return AsyncNodeAction.node_async(state -> Map.of(
                AiRoutingState.EXECUTED_NODES, List.of(nodeName)
        ));
    }

    private AsyncNodeAction<AiRoutingState> createReview() {
        return AsyncNodeAction.node_async(state -> Map.of(
                AiRoutingState.EXECUTED_NODES, List.of(CREATE_REVIEW),
                AiRoutingState.REVIEW_REASON, state.reviewReason().isBlank()
                        ? "workflow skeleton fallback"
                        : state.reviewReason()
        ));
    }

    private AsyncEdgeAction<AiRoutingState> routeByAction() {
        return state -> CompletableFuture.completedFuture(normalizeAction(state.routingAction()));
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
}
