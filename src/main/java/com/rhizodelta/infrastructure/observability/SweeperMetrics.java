package com.rhizodelta.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 漫游智能体（sweeper）相关指标的预注册 Bean。
 *
 * <p>该 Bean 在启动时把 {@code sweeper.candidates} Counter 的三个 {@code stage} 标签值
 * （{@code embedding} / {@code llm} / {@code merged}）以零值预注册到 {@link MeterRegistry}。
 * 这样做的目的是给后续 {@code sweeper-candidate-edge} change 提供稳定的指标契约：
 *
 * <ul>
 *   <li>从本 change 上线起，{@code /actuator/prometheus} 输出就包含
 *       {@code sweeper_candidates_total{stage="embedding|llm|merged"} 0}</li>
 *   <li>下游 change 仅需在业务代码里 {@code increment()}，无需新增 Bean、无需改契约</li>
 *   <li>Grafana 仪表盘的 sweeper 面板从 day-1 起就有"零线"，便于直观观察上线后的爬升</li>
 * </ul>
 *
 * <p>与 {@link MeteredChatLanguageModel} 不同，本 Bean 不在调用链路上，只承担注册职责。
 */
@Component
@ConditionalOnProperty(
        name = "rhizodelta.feature.observability.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class SweeperMetrics {

    static final String COUNTER_NAME = "sweeper.candidates";
    static final String[] STAGES = {"embedding", "llm", "merged"};

    private final MeterRegistry registry;

    public SweeperMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 启动时预注册三条零值时序。
     */
    @PostConstruct
    public void preRegisterCounters() {
        for (String stage : STAGES) {
            counter(stage);
        }
    }

    /**
     * 给业务侧暴露 increment 的入口。
     *
     * <p>下游 change 注入本 Bean 后，调用 {@code sweeperMetrics.counter("embedding").increment()}
     * 即可累加；保持 Counter 名称约定的单点修改性。
     */
    public Counter counter(String stage) {
        return Counter.builder(COUNTER_NAME)
                .tag("stage", stage)
                .description("Sweeper candidate edges produced per stage")
                .register(registry);
    }
}
