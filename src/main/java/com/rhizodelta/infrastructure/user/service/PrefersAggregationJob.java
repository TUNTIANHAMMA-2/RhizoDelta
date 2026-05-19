package com.rhizodelta.infrastructure.user.service;

import com.rhizodelta.infrastructure.user.observability.PrefersAggregationMetrics;
import com.rhizodelta.infrastructure.user.repository.PrefersAggregationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 周期性把 PreferenceEvent 聚合成 PREFERS 投影边的 Job。
 *
 * <p><b>调度</b>：固定延迟 5 分钟（可通过 {@code rhizodelta.preference.aggregation-interval-ms}
 * 覆盖）。{@code @EnableScheduling} 已由 {@code infrastructure/config/SchedulingConfig} 启用。
 *
 * <p><b>Flag gating</b>：每次进入 {@link #runOnce()} 都读取
 * {@code rhizodelta.feature.prefers-aggregation.enabled}；关闭时立刻递增
 * {@code outcome=skipped} 指标并返回。这让 flag 翻转可以在不重启的情况下生效。
 *
 * <p><b>失败隔离</b>：聚合期间抛出的所有异常被 catch 并记录 {@code outcome=error}；
 * 异常不向 Spring 的调度器传播，保证下一轮调度照常进行。
 *
 * @see PrefersAggregationRepository
 * @see PrefersAggregationPolicy
 * @see PrefersAggregationMetrics
 */
@Service
public class PrefersAggregationJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(PrefersAggregationJob.class);
    private static final String FLAG_KEY = "rhizodelta.feature.prefers-aggregation.enabled";

    private final PrefersAggregationRepository repository;
    private final PrefersAggregationPolicy policy;
    private final PrefersAggregationMetrics metrics;
    private final Environment environment;

    /**
     * Single-flight guard so that scheduled ticks and manual replay (actuator
     * {@code /actuator/prefers-aggregation}) cannot run concurrently. When the lock
     * is already held, the second caller short-circuits to {@code skipped} and the
     * scheduler is never blocked.
     */
    private final ReentrantLock runLock = new ReentrantLock();

    public PrefersAggregationJob(
            PrefersAggregationRepository repository,
            PrefersAggregationPolicy policy,
            PrefersAggregationMetrics metrics,
            Environment environment
    ) {
        this.repository = repository;
        this.policy = policy;
        this.metrics = metrics;
        this.environment = environment;
    }

    /**
     * 调度入口。fixedDelayString 让运行间隔从上一轮结束开始算，避免长尾轮叠加。
     */
    @Scheduled(fixedDelayString = "${rhizodelta.preference.aggregation-interval-ms:300000}")
    public void scheduled() {
        runOnce();
    }

    /**
     * 单轮聚合的可重入入口。
     *
     * <p>暴露为 public 是给集成测试和 runbook 中的"replay"动作用：
     * 集成测试可以同步触发一次聚合，而不需要等待 5 分钟的调度周期；
     * 操作员可以通过 actuator 端点在异常恢复后立刻补一轮。
     *
     * <p>返回 {@link PrefersAggregationOutcome} 让 actuator endpoint 能把结果摘要透出给调用方；
     * {@link #scheduled()} 与历史测试不读返回值，向后兼容。
     */
    public PrefersAggregationOutcome runOnce() {
        Instant invokedAt = Instant.now();
        if (!isEnabled()) {
            metrics.recordSkipped();
            return PrefersAggregationOutcome.skipped(invokedAt);
        }

        if (!runLock.tryLock()) {
            metrics.recordSkipped();
            LOGGER.info("PREFERS aggregation skipped: another run is in progress");
            return PrefersAggregationOutcome.skipped(invokedAt);
        }

        Instant windowStart = invokedAt.minus(policy.windowHours(), ChronoUnit.HOURS);

        try {
            PrefersAggregationResult result = repository.runAggregation(
                    windowStart,
                    policy.halfLifeDays(),
                    policy.weightCeiling(),
                    invokedAt
            );
            Duration elapsed = Duration.between(invokedAt, Instant.now());
            metrics.recordRun(elapsed, result);
            LOGGER.info("PREFERS aggregation completed: events={}, edges={}, duration={}ms, window_start={}",
                    result.eventsProcessed(), result.edgesUpserted(), elapsed.toMillis(), windowStart);
            return PrefersAggregationOutcome.ok(result, invokedAt);
        } catch (Exception exception) {
            metrics.recordError();
            LOGGER.error("PREFERS aggregation failed (window_start={}): {}", windowStart, exception.getMessage(), exception);
            // Intentionally swallow: a failed tick must not poison the scheduler.
            return PrefersAggregationOutcome.error(exception.getMessage(), invokedAt);
        } finally {
            runLock.unlock();
        }
    }

    private boolean isEnabled() {
        Boolean value = environment.getProperty(FLAG_KEY, Boolean.class, Boolean.FALSE);
        return Boolean.TRUE.equals(value);
    }
}
