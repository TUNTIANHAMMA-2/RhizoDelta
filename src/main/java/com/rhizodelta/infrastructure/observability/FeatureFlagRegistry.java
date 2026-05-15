package com.rhizodelta.infrastructure.observability;

import java.util.List;

/**
 * 集中登记项目内所有 feature flag 的命名与默认值。
 *
 * <p>该注册表是 {@link FeatureFlagLogger} 的输入源，启动期遍历此处的列表
 * 即可输出全部 flag 的解析状态。后续 change 引入新模块时，只需在此追加一项即可。
 *
 * <p><b>命名约定</b>：所有 flag 必须形如 {@code rhizodelta.feature.<module>.enabled}，
 * 其中 {@code <module>} 为 kebab-case 模块标识，且应与对应 change 的能力名一致。
 */
public final class FeatureFlagRegistry {

    /**
     * 已注册的 feature flag 列表。
     *
     * <p>列表顺序即启动日志输出顺序，按"基础先于业务"的归类排列。
     */
    public static final List<FeatureFlag> FLAGS = List.of(
            new FeatureFlag("observability", "rhizodelta.feature.observability.enabled", true),
            new FeatureFlag("sweeper", "rhizodelta.feature.sweeper.enabled", false),
            new FeatureFlag("proposal", "rhizodelta.feature.proposal.enabled", false),
            // Implemented by `prefers-aggregation-job` change. See docs/runbooks/prefers-aggregation.md
            // for the recommended rollout order: turn `prefers-aggregation` on first, observe for 24h,
            // then enable `prefers-feed-ranking`. Reverse order on rollback.
            new FeatureFlag("prefers-aggregation", "rhizodelta.feature.prefers-aggregation.enabled", false),
            new FeatureFlag("prefers-feed-ranking", "rhizodelta.feature.prefers-feed-ranking.enabled", false)
    );

    private FeatureFlagRegistry() {
    }

    /**
     * 描述单个 feature flag 的元数据。
     *
     * @param module       模块标识（kebab-case）
     * @param propertyKey  Spring 属性键（{@code rhizodelta.feature.<module>.enabled}）
     * @param defaultValue 当属性源未提供该键时的默认值
     */
    public record FeatureFlag(String module, String propertyKey, boolean defaultValue) {
    }
}
