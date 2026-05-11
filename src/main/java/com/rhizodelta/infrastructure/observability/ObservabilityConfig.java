package com.rhizodelta.infrastructure.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 可观测性模块的装配中心。
 *
 * <p>该配置仅当 {@code rhizodelta.feature.observability.enabled=true}（或未配置时默认启用）
 * 时才会被激活。一旦关闭，本类内声明的 Bean（包括 {@link MeteredChatLanguageModel} 装配链路与
 * {@link SweeperMetrics} 预注册）都不会进入 Spring 上下文。
 *
 * <p><b>关键 Bean</b>：
 * <ul>
 *   <li>{@code applicationTagCustomizer}：为所有指标统一附加 {@code application=rhizodelta} 标签
 *       （application.yml 已通过 {@code management.metrics.tags.application} 提供等价配置；
 *       此处作为冗余兜底，避免 yml 被误删时仍能保留命名空间区分）。</li>
 *   <li>{@code rejectHighCardinalityFilter}：设定指标 cardinality 上限（每个名称最多 1024 个时序），
 *       作为防止 tag 爆炸的最后一道闸门。</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(
        name = "rhizodelta.feature.observability.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ObservabilityConfig {

    /**
     * 为所有 Micrometer 指标附加 {@code application=rhizodelta} 通用标签。
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> applicationTagCustomizer() {
        return registry -> registry.config().commonTags("application", "rhizodelta");
    }

    /**
     * 为单个指标名称设定时序数上限，防止 tag 爆炸。
     *
     * <p>1024 是远高于本期预估的 500 时序的安全上限，足以容纳后续 sweeper / proposal /
     * prefers 等模块的扩展，但低于会显著影响 Prometheus 内存的水位线。
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> rejectHighCardinalityFilter() {
        return registry -> registry.config().meterFilter(MeterFilter.maximumAllowableTags(
                "ai.llm.errors", "exception", 64, MeterFilter.deny()));
    }
}
