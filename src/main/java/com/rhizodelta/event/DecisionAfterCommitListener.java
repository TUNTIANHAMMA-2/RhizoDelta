package com.rhizodelta.event;

import com.rhizodelta.domain.decision.DecisionType;
import com.rhizodelta.service.EmbeddingModelService;
import com.rhizodelta.service.EmbeddingService;
import com.rhizodelta.service.SseEventService;
import com.rhizodelta.service.SseEventService.SseEventType;
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

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMergeCompleted(DecisionCommittedEvent.MergeCompleted event) {
        CompletableFuture.runAsync(
                () -> writeConsensusEmbedding(event.nodeId(), event.summaryContent(), event.decisionId()),
                embeddingTaskExecutor);
        for (UUID contributorId : event.synthesizedFrom()) {
            publishEdgeRemoved(contributorId, "PENDING_EVALUATION");
        }
        publishEdgeCreated(event.nodeId(), event.sourceNodeId(), "MERGED_INTO", event.relationshipCreatedAt());
        for (UUID contributorId : event.synthesizedFrom()) {
            publishEdgeCreated(event.nodeId(), contributorId, "SYNTHESIZED_FROM", event.relationshipCreatedAt());
        }
        publishDecisionComplete(event.nodeId(), DecisionType.MERGE, event.decisionId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBranchCompleted(DecisionCommittedEvent.BranchCompleted event) {
        for (UUID contributorId : event.contributorNodeIds()) {
            publishEdgeRemoved(contributorId, "PENDING_EVALUATION");
        }
        publishEdgeCreated(event.nodeId(), event.sourceNodeId(), "BRANCHED_FROM", event.relationshipCreatedAt());
        publishDecisionComplete(event.nodeId(), DecisionType.BRANCH, event.decisionId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInjectCompleted(DecisionCommittedEvent.InjectCompleted event) {
        publishEdgeCreated(event.nodeId(), event.sourceNodeId(), "CONTINUES_FROM", event.relationshipCreatedAt());
        publishDecisionComplete(event.nodeId(), DecisionType.INJECT, event.decisionId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMaterializeCompleted(DecisionCommittedEvent.MaterializeCompleted event) {
        CompletableFuture.runAsync(
                () -> writeResultEmbedding(event.nodeId(), event.content(), event.decisionId()),
                embeddingTaskExecutor);
        publishEdgeCreated(event.nodeId(), event.sourceNodeId(), "MATERIALIZED_FROM", event.relationshipCreatedAt());
        publishDecisionComplete(event.nodeId(), DecisionType.MATERIALIZE, event.decisionId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onForkCompleted(DecisionCommittedEvent.ForkCompleted event) {
        for (UUID nodeId : event.nodeIds()) {
            publishEdgeCreated(nodeId, event.sourceNodeId(), "BRANCHED_FROM", event.relationshipCreatedAt());
        }
        publishDecisionComplete(event.sourceNodeId(), DecisionType.FORK, event.operationId());
    }

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

    private void writeConsensusEmbedding(UUID nodeId, String summaryContent, String decisionId) {
        try {
            List<Float> vector = embeddingModelService.embed(summaryContent);
            embeddingService.writeEmbedding(nodeId.toString(), vector);
        } catch (Exception exception) {
            LOGGER.error("Failed to generate embedding for AI_Consensus node_id={}, decision_id={}",
                    nodeId, decisionId, exception);
        }
    }

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

    private void publishEdgeRemoved(UUID sourceNodeId, String relationshipType) {
        SseEventService.EdgeRemovedPayload payload = new SseEventService.EdgeRemovedPayload(
                sourceNodeId.toString(), relationshipType);
        sseEventService.publish(SseEventType.EDGE_REMOVED, payload);
    }
}
