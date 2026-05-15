package com.rhizodelta.infrastructure.user.observability;

import com.rhizodelta.infrastructure.user.service.PrefersAggregationResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * PREFERS 聚合 Job 的可观测性埋点。
 *
 * <p>四个指标对应 design.md §D6：
 * <ul>
 *   <li>{@code prefers_aggregation_run_total{outcome=ok|skipped|error}} — Counter，每次进入 Job 都会 +1。</li>
 *   <li>{@code prefers_aggregation_events_processed_total} — Counter，本轮扫描读入的 PreferenceEvent 数。</li>
 *   <li>{@code prefers_aggregation_edges_upserted_total} — Counter，本轮写入或更新的 PREFERS 边数。</li>
 *   <li>{@code prefers_aggregation_duration_seconds} — Timer，本轮聚合的端到端耗时。</li>
 * </ul>
 *
 * <p>当 {@code rhizodelta.feature.observability.enabled=false} 时 {@link MeterRegistry} bean 不存在，
 * 本组件使用 {@link ObjectProvider} 延迟解析，缺失时所有 record 方法都是 no-op。
 */
@Component
public class PrefersAggregationMetrics {

    private static final String RUN_COUNTER_NAME = "prefers_aggregation_run_total";
    private static final String EVENTS_COUNTER_NAME = "prefers_aggregation_events_processed_total";
    private static final String EDGES_COUNTER_NAME = "prefers_aggregation_edges_upserted_total";
    private static final String DURATION_TIMER_NAME = "prefers_aggregation_duration_seconds";

    private static final String OUTCOME_OK = "ok";
    private static final String OUTCOME_SKIPPED = "skipped";
    private static final String OUTCOME_ERROR = "error";

    private final ObjectProvider<MeterRegistry> registryProvider;

    public PrefersAggregationMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this.registryProvider = registryProvider;
    }

    /**
     * 成功跑完一次聚合：递增 run/events/edges 三个 Counter，记录 duration。
     */
    public void recordRun(Duration duration, PrefersAggregationResult result) {
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        runCounter(registry, OUTCOME_OK).increment();
        registry.counter(EVENTS_COUNTER_NAME).increment(result.eventsProcessed());
        registry.counter(EDGES_COUNTER_NAME).increment(result.edgesUpserted());
        Timer.builder(DURATION_TIMER_NAME)
                .description("Duration of a single PREFERS aggregation tick")
                .register(registry)
                .record(duration);
    }

    /**
     * Flag 关闭、Job 直接退出：只递增 {@code outcome=skipped}。
     */
    public void recordSkipped() {
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        runCounter(registry, OUTCOME_SKIPPED).increment();
    }

    /**
     * Job 抛异常：只递增 {@code outcome=error}，events/edges 不动。
     * 让 dashboard 的 {@code rate(events) / rate(run)} 一眼看出"在跑但失败"。
     */
    public void recordError() {
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        runCounter(registry, OUTCOME_ERROR).increment();
    }

    private Counter runCounter(MeterRegistry registry, String outcome) {
        return Counter.builder(RUN_COUNTER_NAME)
                .tag("outcome", outcome)
                .description("Number of PREFERS aggregation invocations, tagged by terminal outcome")
                .register(registry);
    }
}
