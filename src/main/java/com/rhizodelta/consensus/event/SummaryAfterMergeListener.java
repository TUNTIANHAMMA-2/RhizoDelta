package com.rhizodelta.consensus.event;

import com.rhizodelta.ai.summary.domain.SummaryResult;
import com.rhizodelta.infrastructure.sse.service.SseEventService;
import com.rhizodelta.ai.summary.service.SummaryAgentService;
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

/**
 * 在合并类决策提交后触发摘要生成。
 *
 * <p>该监听器同样运行在 {@link TransactionPhase#AFTER_COMMIT} 阶段，
 * 确保摘要生成失败不会影响主事务提交。
 *
 * <p><b>关键副作用</b>：
 * <ul>
 *   <li>会异步调用 {@link SummaryAgentService} 生成或增量更新摘要。</li>
 *   <li>摘要成功后会通过 {@link SseEventService} 广播 {@code SUMMARY_GENERATED} 事件。</li>
 * </ul>
 */
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

    /**
     * 处理新合并完成事件并触发首次摘要生成。
     *
     * <p>若摘要功能被关闭，则直接跳过，不做隐式降级补偿。
     */
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

    /**
     * 处理追加来源完成事件并触发增量摘要更新。
     */
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

    /**
     * 生成完整摘要并广播结果。
     */
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

    /**
     * 基于新增来源生成增量摘要并广播结果。
     */
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
