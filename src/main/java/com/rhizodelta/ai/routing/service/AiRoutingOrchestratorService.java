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

/**
 * 负责串联 AI 路由的完整编排流程。
 *
 * <p>该服务把召回、上下文增强、状态工作流执行、SSE 状态广播、人工复核创建和最终决策执行
 * 串成一条完整链路，是帖子进入自动路由后的顶层协调者。
 *
 * <p><b>关键副作用</b>：
 * <ul>
 *   <li>会发布多阶段的 {@link SseEventService.SseEventType#ORCHESTRATION_STATUS} 事件。</li>
 *   <li>可能会创建 {@link ReviewTask}，也可能直接调用 {@link AiRoutingExecutionService} 落地决策。</li>
 *   <li>本身不直接写图谱节点，但会驱动后续写动作发生。</li>
 * </ul>
 */
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

    /**
     * 对新帖子执行完整路由编排。
     *
     * <p>该方法会先做召回和上下文增强，再运行状态工作流，最后根据结果进入：
     * <ul>
     *   <li>直接执行决策</li>
     *   <li>创建人工复核任务</li>
     *   <li>跳过执行并广播原因</li>
     * </ul>
     *
     * <p><b>根帖短路 (L0)</b>：
     * 当 {@code targetNodeId} 为空，意味着用户提交的是新话题首条帖子（root post），
     * 没有任何上游上下文可以拿来 merge/branch。此时直接广播 {@code STANDALONE}
     * 并 return，不进入召回 / LLM / 复核任何环节。
     *
     * <p>这是单点拦截：避免下游所有"看似 root 但被相似度劫持"的路径
     * （contextPrune 把 candidates[0] 顶替成 sourceNodeId、REVIEW 分支创建
     * source_node_id="" 的复核任务、LLM 在不知道这是根帖的情况下做合并判断）。
     *
     * <p>
     *
     * @param message 帖子事件消息。
     * @param post 已落库的人类帖子节点。
     */
    public void orchestrate(PostEventMessage message, HumanPost post) {
        if (message.targetNodeId() == null || message.targetNodeId().isBlank()) {
            publishStatus(
                    message,
                    post.getNodeId().toString(),
                    "STANDALONE",
                    null,
                    "root post; no upstream context to route against"
            );
            return;
        }
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

    /**
     * 将召回候选格式化为路由上下文文本。
     */
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

    /**
     * 创建人工复核任务并广播待复核状态。
     *
     * <p>该方法会把当前帖子和路由结果重建为一份草稿载荷，供人工批准时转为正式决策命令。
     *
     * <p><b>L4 防御</b>：拒绝创建 {@code source_node_id} 为空的复核任务。
     * 正常路径下 L0 已经把根帖在 {@link #orchestrate} 入口短路；如果走到这里
     * 还能撞上空 source，说明上游某条路径漏了根帖判断（如 contextPrune 在
     * {@code REVIEW} 路径上没劫持候选）。此时不静默生成无意义任务，而是广播
     * {@code SKIPPED} 并附明确原因，便于回溯与告警。
     */
    private void createReviewTask(PostEventMessage message, HumanPost post, AiRoutingState state) {
        if (state.sourceNodeId() == null || state.sourceNodeId().isBlank()) {
            publishStatus(
                    message,
                    post.getNodeId().toString(),
                    "SKIPPED",
                    null,
                    "review task suppressed: source_node_id is empty (likely a root post that bypassed L0 guard)"
            );
            return;
        }
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

    /**
     * 将路由结果交给执行服务落地，并广播排队状态。
     */
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
