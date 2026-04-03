package com.rhizodelta.event;

import com.rhizodelta.domain.ai.SummaryResult;
import com.rhizodelta.service.SseEventService;
import com.rhizodelta.service.SummaryAgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Component
public class SummaryAfterMergeListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(SummaryAfterMergeListener.class);

    private final SummaryAgentService summaryAgentService;
    private final SseEventService sseEventService;
    private final boolean summaryEnabled;
    private Executor embeddingTaskExecutor = Runnable::run;

    public SummaryAfterMergeListener(
            SummaryAgentService summaryAgentService,
            SseEventService sseEventService,
            @Value("${rhizodelta.ai.summary.enabled:true}") boolean summaryEnabled
    ) {
        this.summaryAgentService = summaryAgentService;
        this.sseEventService = sseEventService;
        this.summaryEnabled = summaryEnabled;
    }

    @Autowired
    public void setEmbeddingTaskExecutor(@Qualifier("embeddingTaskExecutor") Executor embeddingTaskExecutor) {
        this.embeddingTaskExecutor = Objects.requireNonNull(embeddingTaskExecutor);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMergeCompleted(DecisionCommittedEvent.MergeCompleted event) {
        if (!summaryEnabled) {
            LOGGER.debug("Summary generation disabled, skipping for node_id={}", event.nodeId());
            return;
        }
        CompletableFuture.runAsync(() -> generateSummary(event), embeddingTaskExecutor)
                .exceptionally(ex -> {
                    LOGGER.error("Summary generation failed for node_id={}", event.nodeId(), ex);
                    return null;
                });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMergeAppended(DecisionCommittedEvent.MergeAppended event) {
        if (!summaryEnabled) {
            LOGGER.debug("Summary generation disabled, skipping incremental for node_id={}", event.nodeId());
            return;
        }
        CompletableFuture.runAsync(() -> generateIncrementalSummary(event), embeddingTaskExecutor)
                .exceptionally(ex -> {
                    LOGGER.error("Incremental summary generation failed for node_id={}", event.nodeId(), ex);
                    return null;
                });
    }

    private void generateSummary(DecisionCommittedEvent.MergeCompleted event) {
        try {
            SummaryResult result = summaryAgentService.generate(event.nodeId());
            SseEventService.SummaryGeneratedPayload payload = new SseEventService.SummaryGeneratedPayload(
                    event.nodeId().toString(),
                    result.summary(),
                    result.sourceCount(),
                    result.modelUsed()
            );
            sseEventService.publish(SseEventService.SseEventType.SUMMARY_GENERATED, payload);
            LOGGER.info("Summary generated and published for node_id={} sources={}", event.nodeId(), result.sourceCount());
        } catch (Exception e) {
            LOGGER.error("Summary generation failed for node_id={}", event.nodeId(), e);
        }
    }

    private void generateIncrementalSummary(DecisionCommittedEvent.MergeAppended event) {
        try {
            List<UUID> newContributorIds = event.synthesizedFrom();
            SummaryResult result = summaryAgentService.regenerateIncremental(event.nodeId(), newContributorIds);
            SseEventService.SummaryGeneratedPayload payload = new SseEventService.SummaryGeneratedPayload(
                    event.nodeId().toString(),
                    result.summary(),
                    result.sourceCount(),
                    result.modelUsed()
            );
            sseEventService.publish(SseEventService.SseEventType.SUMMARY_GENERATED, payload);
            LOGGER.info("Incremental summary generated and published for node_id={} new_contributors={}",
                    event.nodeId(), newContributorIds.size());
        } catch (Exception e) {
            LOGGER.error("Incremental summary generation failed for node_id={}", event.nodeId(), e);
        }
    }
}
