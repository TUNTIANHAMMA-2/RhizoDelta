package com.rhizodelta.infrastructure.user.service;

import java.time.Instant;

/**
 * 一次 {@link PrefersAggregationJob#runOnce()} 调用的终态摘要。
 *
 * <p>区分三种终态：
 * <ul>
 *   <li>{@link Status#OK}：聚合执行成功，{@link #result()} 携带本轮处理量。</li>
 *   <li>{@link Status#SKIPPED}：{@code rhizodelta.feature.prefers-aggregation.enabled=false}，
 *       Job 立即返回；{@link #result()} 与 {@link #errorMessage()} 均为 {@code null}。</li>
 *   <li>{@link Status#ERROR}：聚合期间抛出异常并被 Job 捕获；{@link #errorMessage()} 携带
 *       异常的 {@link Throwable#getMessage()}，{@link #result()} 为 {@code null}。</li>
 * </ul>
 *
 * <p>调度入口（{@link PrefersAggregationJob#scheduled()}）调用 {@code runOnce()} 后忽略此返回值；
 * actuator endpoint 把它序列化成 JSON 暴露给操作员，让"立刻补跑一轮"具备可观察的反馈。
 *
 * @param status        终态
 * @param result        OK 状态下的处理量摘要，其它状态为 {@code null}
 * @param errorMessage  ERROR 状态下的异常 message，其它状态为 {@code null}
 * @param invokedAt     Job 被调用的时间点（用于 endpoint 响应中的时序对账）
 */
public record PrefersAggregationOutcome(
        Status status,
        PrefersAggregationResult result,
        String errorMessage,
        Instant invokedAt
) {
    public enum Status {
        OK,
        SKIPPED,
        ERROR
    }

    public static PrefersAggregationOutcome ok(PrefersAggregationResult result, Instant invokedAt) {
        return new PrefersAggregationOutcome(Status.OK, result, null, invokedAt);
    }

    public static PrefersAggregationOutcome skipped(Instant invokedAt) {
        return new PrefersAggregationOutcome(Status.SKIPPED, null, null, invokedAt);
    }

    public static PrefersAggregationOutcome error(String errorMessage, Instant invokedAt) {
        return new PrefersAggregationOutcome(Status.ERROR, null, errorMessage, invokedAt);
    }
}
