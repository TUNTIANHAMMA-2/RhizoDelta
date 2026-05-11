package com.rhizodelta.infrastructure.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SweeperMetricsTest {

    @Test
    void preRegistersAllStageCounters() {
        MeterRegistry registry = new SimpleMeterRegistry();
        SweeperMetrics metrics = new SweeperMetrics(registry);
        metrics.preRegisterCounters();

        for (String stage : SweeperMetrics.STAGES) {
            assertThat(
                    registry.find(SweeperMetrics.COUNTER_NAME)
                            .tag("stage", stage)
                            .counter()
            )
                    .as("Counter must be pre-registered for stage=%s", stage)
                    .isNotNull();
        }
    }

    @Test
    void counterReturnsSameInstanceForSameStage() {
        MeterRegistry registry = new SimpleMeterRegistry();
        SweeperMetrics metrics = new SweeperMetrics(registry);

        var first = metrics.counter("embedding");
        var second = metrics.counter("embedding");

        assertThat(first).isSameAs(second);
    }

    @Test
    void incrementShowsUpInRegistry() {
        MeterRegistry registry = new SimpleMeterRegistry();
        SweeperMetrics metrics = new SweeperMetrics(registry);

        metrics.counter("llm").increment(3);

        assertThat(registry.find(SweeperMetrics.COUNTER_NAME).tag("stage", "llm").counter().count())
                .isEqualTo(3.0);
    }
}
