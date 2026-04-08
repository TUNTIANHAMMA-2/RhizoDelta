package com.rhizodelta.consensus.event;

import com.rhizodelta.consensus.domain.decision.DecisionType;
import com.rhizodelta.ai.shared.service.EmbeddingModelService;
import com.rhizodelta.ai.context.service.EmbeddingService;
import com.rhizodelta.infrastructure.sse.service.SseEventService;
import com.rhizodelta.infrastructure.sse.service.SseEventService.SseEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 在决策事务提交后补充执行 embedding 与 SSE 广播。
 *
 * <p>该监听器不参与主事务中的决策写入，而是在
 * {@link TransactionPhase#AFTER_COMMIT} 阶段消费 {@link DecisionCommittedEvent}，
 * 以避免后置副作用影响主决策提交。
 *
 * <p><b>关键副作用</b>：
 * <ul>
 *   <li>会异步生成并写回共识或结果节点的 embedding。</li>
 *   <li>会通过 {@link SseEventService} 广播边创建事件和决策完成事件。</li>
 *   <li>embedding 失败只记日志，不会回滚已提交的决策事务。</li>
 * </ul>
 */
@Component
public class DecisionAfterCommitListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(DecisionAfterCommitListener.class);

    private final EmbeddingModelService embeddingModelService;
    private final EmbeddingService embeddingService;
    private final SseEventService sseEventService;
    private Executor embeddingTaskExecutor = Runnable::run;

    public DecisionAfterCommitListener(
            EmbeddingModelService embeddingModelService,
            EmbeddingService embeddingService,
            SseEventService sseEventService
    ) {
        this.embeddingModelService = embeddingModelService;
        this.embeddingService = embeddingService;
        this.sseEventService = sseEventService;
    }

    @Autowired
    public void setEmbeddingTaskExecutor(@Qualifier("embeddingTaskExecutor") Executor embeddingTaskExecutor) {
        this.embeddingTaskExecutor = Objects.requireNonNull(embeddingTaskExecutor, "embeddingTaskExecutor must not be null");
    }

    /**
     * 处理合并完成事件。
     *
     * <p>该处理器会异步写入共识 embedding，并同步广播合并边、来源边和决策完成事件。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMergeCompleted(DecisionCommittedEvent.MergeCompleted event) {
        CompletableFuture.runAsync(
                () -> writeConsensusEmbedding(event.nodeId(), event.summaryContent(), event.decisionId()),
                embeddingTaskExecutor);
        publishEdgeCreated(event.nodeId(), event.sourceNodeId(), "MERGED_INTO", event.relationshipCreatedAt());
        for (UUID contributorId : event.synthesizedFrom()) {
            publishEdgeCreated(event.nodeId(), contributorId, "SYNTHESIZED_FROM", event.relationshipCreatedAt());
        }
        publishDecisionComplete(event.nodeId(), DecisionType.MERGE, event.decisionId());
    }

    /**
     * 处理分支完成事件。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBranchCompleted(DecisionCommittedEvent.BranchCompleted event) {
        publishEdgeCreated(event.nodeId(), event.sourceNodeId(), "BRANCHED_FROM", event.relationshipCreatedAt());
        publishDecisionComplete(event.nodeId(), DecisionType.BRANCH, event.decisionId());
    }

    /**
     * 处理注入完成事件。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInjectCompleted(DecisionCommittedEvent.InjectCompleted event) {
        publishEdgeCreated(event.nodeId(), event.sourceNodeId(), "CONTINUES_FROM", event.relationshipCreatedAt());
        publishDecisionComplete(event.nodeId(), DecisionType.INJECT, event.decisionId());
    }

    /**
     * 处理物化完成事件。
     *
     * <p>该处理器会异步生成结果节点的 embedding，并广播物化边和决策完成事件。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMaterializeCompleted(DecisionCommittedEvent.MaterializeCompleted event) {
        CompletableFuture.runAsync(
                () -> writeResultEmbedding(event.nodeId(), event.content(), event.decisionId()),
                embeddingTaskExecutor);
        publishEdgeCreated(event.nodeId(), event.sourceNodeId(), "MATERIALIZED_FROM", event.relationshipCreatedAt());
        publishDecisionComplete(event.nodeId(), DecisionType.MATERIALIZE, event.decisionId());
    }

    /**
     * 处理分叉完成事件。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onForkCompleted(DecisionCommittedEvent.ForkCompleted event) {
        for (UUID nodeId : event.nodeIds()) {
            publishEdgeCreated(nodeId, event.sourceNodeId(), "BRANCHED_FROM", event.relationshipCreatedAt());
        }
        publishDecisionComplete(event.sourceNodeId(), DecisionType.FORK, event.operationId());
    }

    /**
     * 处理跨综合完成事件。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCrossSynthCompleted(DecisionCommittedEvent.CrossSynthCompleted event) {
        CompletableFuture.runAsync(
                () -> writeResultEmbedding(event.nodeId(), event.content(), event.decisionId()),
                embeddingTaskExecutor);
        for (UUID sourceResultId : event.sourceResultIds()) {
            publishEdgeCreated(event.nodeId(), sourceResultId, "CROSS_SYNTHESIZED_FROM", event.relationshipCreatedAt());
        }
        publishDecisionComplete(event.nodeId(), DecisionType.CROSS_SYNTH, event.decisionId());
    }

    /**
     * 处理汇合完成事件。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onJoinCompleted(DecisionCommittedEvent.JoinCompleted event) {
        CompletableFuture.runAsync(
                () -> writeConsensusEmbedding(event.nodeId(), event.summaryContent(), event.decisionId()),
                embeddingTaskExecutor);
        for (UUID sourceNodeId : event.sourceNodeIds()) {
            publishEdgeCreated(event.nodeId(), sourceNodeId, "CONVERGED_FROM", event.relationshipCreatedAt());
        }
        publishDecisionComplete(event.nodeId(), DecisionType.JOIN, event.decisionId());
    }

    /**
     * 处理追加来源完成事件。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMergeAppended(DecisionCommittedEvent.MergeAppended event) {
        for (UUID contributorId : event.synthesizedFrom()) {
            publishEdgeCreated(event.nodeId(), contributorId, "SYNTHESIZED_FROM", event.relationshipCreatedAt());
        }
        publishDecisionComplete(event.nodeId(), DecisionType.MERGE, event.decisionId());
    }

    /**
     * 为共识节点生成并写回 embedding。
     *
     * <p>失败时仅记录日志，不中断已提交的主事务。
     */
    private void writeConsensusEmbedding(UUID nodeId, String summaryContent, String decisionId) {
        try {
            List<Float> vector = embeddingModelService.embed(summaryContent);
            embeddingService.writeEmbedding(nodeId.toString(), vector);
        } catch (Exception exception) {
            LOGGER.error("Failed to generate embedding for AI_Consensus node_id={}, decision_id={}",
                    nodeId, decisionId, exception);
        }
    }

    /**
     * 为结果节点生成并写回 embedding。
     */
    private void writeResultEmbedding(UUID nodeId, String content, String decisionId) {
        try {
            List<Float> vector = embeddingModelService.embed(content);
            embeddingService.writeEmbedding(nodeId.toString(), vector);
        } catch (Exception exception) {
            LOGGER.error("Failed to generate embedding for Result node_id={}, decision_id={}",
                    nodeId, decisionId, exception);
        }
    }

    private void publishDecisionComplete(UUID nodeId, DecisionType decisionType, String decisionId) {
        SseEventService.DecisionCompletePayload payload = new SseEventService.DecisionCompletePayload(
                decisionId, decisionType.name(), nodeId.toString());
        sseEventService.publish(SseEventType.DECISION_COMPLETE, payload);
    }

    private void publishEdgeCreated(UUID sourceNodeId, UUID targetNodeId, String relationshipType, OffsetDateTime createdAt) {
        SseEventService.EdgeCreatedPayload payload = new SseEventService.EdgeCreatedPayload(
                sourceNodeId.toString(), targetNodeId.toString(), relationshipType, createdAt.toInstant());
        sseEventService.publish(SseEventType.EDGE_CREATED, payload);
    }

}
