package com.rhizodelta.infrastructure.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 observability 模块在 feature flag 不同状态下的 Bean 装配行为。
 *
 * <p>不启动完整 Spring Boot 上下文（避免拉起 Neo4j / Rabbit testcontainer），
 * 仅用 {@link ApplicationContextRunner} 加载 observability 自身配置类与必要的
 * Micrometer 自动配置，验证：
 *
 * <ul>
 *   <li>flag=true → {@link ObservabilityConfig}、{@link SweeperMetrics} 都装配</li>
 *   <li>flag=false → 上述 Bean 全部缺席，但 MeterRegistry 仍由 Spring Boot 默认提供</li>
 * </ul>
 */
class ObservabilityWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MetricsAutoConfiguration.class,
                    CompositeMeterRegistryAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class, ObservabilityConfig.class, SweeperMetrics.class);

    @Test
    void wiresObservabilityBeansWhenFlagEnabled() {
        runner
                .withPropertyValues("rhizodelta.feature.observability.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ObservabilityConfig.class);
                    assertThat(context).hasSingleBean(SweeperMetrics.class);

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    for (String stage : SweeperMetrics.STAGES) {
                        assertThat(registry.find(SweeperMetrics.COUNTER_NAME).tag("stage", stage).counter())
                                .as("Sweeper counter pre-registered for stage=%s when flag enabled", stage)
                                .isNotNull();
                    }
                });
    }

    @Test
    void wiresObservabilityBeansByDefaultWhenFlagAbsent() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ObservabilityConfig.class);
            assertThat(context).hasSingleBean(SweeperMetrics.class);
        });
    }

    @Test
    void skipsObservabilityBeansWhenFlagDisabled() {
        runner
                .withPropertyValues("rhizodelta.feature.observability.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ObservabilityConfig.class);
                    assertThat(context).doesNotHaveBean(SweeperMetrics.class);

                    // MeterRegistry 仍存在（由 Spring Boot Actuator 自动装配提供），
                    // 只是没有 ai.llm.* / sweeper.* 相关时序被预注册
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.find(SweeperMetrics.COUNTER_NAME).counter())
                            .as("No sweeper counters when flag disabled")
                            .isNull();
                });
    }

    @Configuration
    static class TestConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
