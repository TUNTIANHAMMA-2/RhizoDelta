package com.rhizodelta.infrastructure.user.observability;

import com.rhizodelta.infrastructure.user.service.PrefersAggregationResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrefersAggregationMetricsTest {

    @Test
    void recordRunIncrementsAllFourInstrumentsWithCorrectTagsAndValues() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PrefersAggregationMetrics metrics = new PrefersAggregationMetrics(providerOf(registry));

        PrefersAggregationResult result = new PrefersAggregationResult(
                42L, 7L, Instant.now().minusSeconds(86_400L), Instant.now());
        metrics.recordRun(Duration.ofMillis(123L), result);

        assertThat(registry.counter("prefers_aggregation_run_total", "outcome", "ok").count()).isEqualTo(1.0);
        assertThat(registry.counter("prefers_aggregation_events_processed_total").count()).isEqualTo(42.0);
        assertThat(registry.counter("prefers_aggregation_edges_upserted_total").count()).isEqualTo(7.0);
        assertThat(registry.timer("prefers_aggregation_duration_seconds").count()).isEqualTo(1L);
        assertThat(registry.timer("prefers_aggregation_duration_seconds").totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isEqualTo(123.0);
    }

    @Test
    void recordSkippedTouchesOnlyRunCounterWithSkippedTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PrefersAggregationMetrics metrics = new PrefersAggregationMetrics(providerOf(registry));

        metrics.recordSkipped();

        assertThat(registry.counter("prefers_aggregation_run_total", "outcome", "skipped").count()).isEqualTo(1.0);
        assertThat(registry.counter("prefers_aggregation_run_total", "outcome", "ok").count()).isEqualTo(0.0);
        assertThat(registry.counter("prefers_aggregation_events_processed_total").count()).isEqualTo(0.0);
        assertThat(registry.counter("prefers_aggregation_edges_upserted_total").count()).isEqualTo(0.0);
    }

    @Test
    void recordErrorTouchesOnlyRunCounterWithErrorTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PrefersAggregationMetrics metrics = new PrefersAggregationMetrics(providerOf(registry));

        metrics.recordError();

        assertThat(registry.counter("prefers_aggregation_run_total", "outcome", "error").count()).isEqualTo(1.0);
        assertThat(registry.counter("prefers_aggregation_events_processed_total").count()).isEqualTo(0.0);
        assertThat(registry.counter("prefers_aggregation_edges_upserted_total").count()).isEqualTo(0.0);
    }

    @Test
    void missingMeterRegistryMakesAllRecordCallsNoOps() {
        // observability flag off ⇒ MeterRegistry bean absent. The metrics component must not throw.
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);

        PrefersAggregationMetrics metrics = new PrefersAggregationMetrics(emptyProvider);

        PrefersAggregationResult result = new PrefersAggregationResult(1L, 1L, Instant.now(), Instant.now());
        // None of these should throw.
        metrics.recordRun(Duration.ofMillis(5L), result);
        metrics.recordSkipped();
        metrics.recordError();
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<MeterRegistry> providerOf(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }
}
