package com.rhizodelta.infrastructure.user.service;

import com.rhizodelta.infrastructure.user.domain.PreferenceEventType;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * PREFERS 聚合的算法常量与策略参数的单一来源。
 *
 * <p>所有数值都集中在这里，便于通过 Spring 属性覆盖、便于审计、便于在算法迭代时
 * 给一个明确的入口（"换权重就改这里"）。Job 和 Repository 都通过注入这个 bean
 * 拿到当前生效的数值，避免散落在 Cypher 字符串或者构造期 {@code @Value} 里。
 *
 * <p><b>关键约束</b>：
 * <ul>
 *   <li>类型基础权重对齐 {@code NodeQueryController:226} 写入的 {@code weight=0.5}（VIEW），
 *       并按事件强度递增（VIEW &lt; EXPAND &lt; DWELL &lt; LIKE &lt; SHARE）。</li>
 *   <li>半衰期、窗口、上限都允许通过 {@code rhizodelta.preference.*} 属性覆盖。</li>
 *   <li>构造期校验所有数值，给出"启动期失败优于运行期数据腐败"的保证。</li>
 * </ul>
 *
 * @see PrefersAggregationJob
 * @see PrefersAggregationRepository
 */
@Component
public class PrefersAggregationPolicy {

    /** 与 NodeQueryController:226 写入的 {@code recordEvent(..., "VIEW", 0.5, ...)} 对齐。 */
    public static final double BASE_WEIGHT_VIEW = 0.5;
    public static final double BASE_WEIGHT_EXPAND = 1.0;
    public static final double BASE_WEIGHT_DWELL = 1.5;
    public static final double BASE_WEIGHT_LIKE = 2.0;
    public static final double BASE_WEIGHT_SHARE = 3.0;

    private static final Map<PreferenceEventType, Double> BASE_WEIGHTS = new EnumMap<>(PreferenceEventType.class);

    static {
        BASE_WEIGHTS.put(PreferenceEventType.VIEW, BASE_WEIGHT_VIEW);
        BASE_WEIGHTS.put(PreferenceEventType.EXPAND, BASE_WEIGHT_EXPAND);
        BASE_WEIGHTS.put(PreferenceEventType.DWELL, BASE_WEIGHT_DWELL);
        BASE_WEIGHTS.put(PreferenceEventType.LIKE, BASE_WEIGHT_LIKE);
        BASE_WEIGHTS.put(PreferenceEventType.SHARE, BASE_WEIGHT_SHARE);
    }

    private final double halfLifeDays;
    private final long windowHours;
    private final double weightFloor;
    private final double weightCeiling;

    public PrefersAggregationPolicy(
            @Value("${rhizodelta.preference.half-life-days:30}") double halfLifeDays,
            @Value("${rhizodelta.preference.window-hours:24}") long windowHours,
            @Value("${rhizodelta.preference.weight-floor:0.0}") double weightFloor,
            @Value("${rhizodelta.preference.weight-ceiling:1000.0}") double weightCeiling
    ) {
        this.halfLifeDays = halfLifeDays;
        this.windowHours = windowHours;
        this.weightFloor = weightFloor;
        this.weightCeiling = weightCeiling;
    }

    @PostConstruct
    void validate() {
        if (!Double.isFinite(halfLifeDays) || halfLifeDays <= 0.0) {
            throw new IllegalArgumentException(
                    "rhizodelta.preference.half-life-days must be positive and finite, got " + halfLifeDays);
        }
        if (windowHours <= 0L) {
            throw new IllegalArgumentException(
                    "rhizodelta.preference.window-hours must be positive, got " + windowHours);
        }
        if (!Double.isFinite(weightFloor) || !Double.isFinite(weightCeiling)) {
            throw new IllegalArgumentException(
                    "rhizodelta.preference.weight-floor/weight-ceiling must be finite, got "
                            + weightFloor + " / " + weightCeiling);
        }
        if (weightFloor < 0.0 || weightCeiling <= weightFloor) {
            throw new IllegalArgumentException(
                    "weight-floor must be >= 0 and weight-ceiling must be > weight-floor; got floor="
                            + weightFloor + ", ceiling=" + weightCeiling);
        }
    }

    public double halfLifeDays() {
        return halfLifeDays;
    }

    public long windowHours() {
        return windowHours;
    }

    public double weightFloor() {
        return weightFloor;
    }

    public double weightCeiling() {
        return weightCeiling;
    }

    public double baseWeight(PreferenceEventType type) {
        Double w = BASE_WEIGHTS.get(type);
        if (w == null) {
            throw new IllegalArgumentException("Unknown PreferenceEventType: " + type);
        }
        return w;
    }
}
