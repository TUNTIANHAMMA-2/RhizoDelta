package com.rhizodelta.infrastructure.user.service;

import java.time.Instant;

/**
 * 一次 PREFERS 聚合运行的结果摘要。
 *
 * <p>该记录值不可变；Job 把它交给 {@link PrefersAggregationMetrics} 后即可丢弃。
 * 不携带 Throwable —— 失败路径由 Job 自己 catch 并走 {@code recordError()}。
 *
 * @param eventsProcessed 本次扫描读入的 PreferenceEvent 数量（用于核对吞吐）
 * @param edgesUpserted   本次写入或更新的 PREFERS 边数量（用于核对工作量）
 * @param windowStart     聚合窗口的下界 {@code now - windowHours}（用于日志/告警定位）
 * @param runStartedAt    Job 进入聚合逻辑的时间点（用于 duration 计算）
 */
public record PrefersAggregationResult(
        long eventsProcessed,
        long edgesUpserted,
        Instant windowStart,
        Instant runStartedAt
) {
}
