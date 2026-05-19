package com.rhizodelta.infrastructure.user.service;

import com.rhizodelta.infrastructure.user.observability.PrefersAggregationMetrics;
import com.rhizodelta.infrastructure.user.repository.PrefersAggregationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PrefersAggregationJobTest {

    @Test
    void flagDisabledShortCircuitsBeforeRepository() {
        PrefersAggregationRepository repository = mock(PrefersAggregationRepository.class);
        PrefersAggregationMetrics metrics = mock(PrefersAggregationMetrics.class);
        Environment env = mock(Environment.class);
        when(env.getProperty(eq("rhizodelta.feature.prefers-aggregation.enabled"), eq(Boolean.class), eq(Boolean.FALSE)))
                .thenReturn(Boolean.FALSE);

        PrefersAggregationJob job = new PrefersAggregationJob(repository, newPolicy(), metrics, env);

        job.runOnce();

        verifyNoInteractions(repository);
        verify(metrics).recordSkipped();
        verify(metrics, never()).recordRun(any(), any());
        verify(metrics, never()).recordError();
    }

    @Test
    void flagEnabledInvokesRepositoryWithWindowFromPolicy() {
        PrefersAggregationRepository repository = mock(PrefersAggregationRepository.class);
        PrefersAggregationMetrics metrics = mock(PrefersAggregationMetrics.class);
        Environment env = enabledEnv();
        PrefersAggregationPolicy policy = newPolicy();

        // Use a small window so the windowStart is far from runStartedAt for easy verification
        Instant before = Instant.now();
        PrefersAggregationResult result = new PrefersAggregationResult(7L, 3L, before.minus(24L, ChronoUnit.HOURS), before);
        when(repository.runAggregation(any(), anyDouble(), anyDouble(), any())).thenReturn(result);

        PrefersAggregationJob job = new PrefersAggregationJob(repository, policy, metrics, env);

        job.runOnce();

        ArgumentCaptor<Instant> windowStartCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> runStartedCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(repository).runAggregation(
                windowStartCaptor.capture(),
                eq(policy.halfLifeDays()),
                eq(policy.weightCeiling()),
                runStartedCaptor.capture()
        );
        Instant runStartedAt = runStartedCaptor.getValue();
        Instant windowStart = windowStartCaptor.getValue();
        assertThatCode(() -> {
            if (!windowStart.equals(runStartedAt.minus(policy.windowHours(), ChronoUnit.HOURS))) {
                throw new AssertionError("windowStart should equal runStartedAt - windowHours");
            }
        }).doesNotThrowAnyException();

        verify(metrics).recordRun(any(Duration.class), eq(result));
        verify(metrics, never()).recordSkipped();
        verify(metrics, never()).recordError();
    }

    @Test
    void repositoryFailureIsCaughtAndRecordedAsError() {
        PrefersAggregationRepository repository = mock(PrefersAggregationRepository.class);
        PrefersAggregationMetrics metrics = mock(PrefersAggregationMetrics.class);
        Environment env = enabledEnv();
        when(repository.runAggregation(any(), anyDouble(), anyDouble(), any()))
                .thenThrow(new RuntimeException("simulated Neo4j unavailable"));

        PrefersAggregationJob job = new PrefersAggregationJob(repository, newPolicy(), metrics, env);

        assertThatCode(job::runOnce).doesNotThrowAnyException();

        verify(metrics).recordError();
        verify(metrics, never()).recordRun(any(), any());
        verify(metrics, never()).recordSkipped();
    }

    @Test
    void scheduledMethodIsAThinDelegateToRunOnce() {
        // Spot-check: the @Scheduled method just calls runOnce. We don't need a complex test here —
        // the rest of the suite exercises runOnce, so a smoke call is enough to confirm wiring.
        PrefersAggregationRepository repository = mock(PrefersAggregationRepository.class);
        PrefersAggregationMetrics metrics = mock(PrefersAggregationMetrics.class);
        Environment env = mock(Environment.class);
        when(env.getProperty(eq("rhizodelta.feature.prefers-aggregation.enabled"), eq(Boolean.class), eq(Boolean.FALSE)))
                .thenReturn(Boolean.FALSE);

        PrefersAggregationJob job = new PrefersAggregationJob(repository, newPolicy(), metrics, env);

        assertThatCode(job::scheduled).doesNotThrowAnyException();
        verify(metrics).recordSkipped();
    }

    @Test
    void concurrentRunsAreSerializedBySingleFlightLock() throws Exception {
        // Block the first runAggregation call until the second invocation has already attempted to
        // acquire the lock. The second call must short-circuit to skipped without touching the
        // repository, leaving exactly one runAggregation invocation.
        PrefersAggregationRepository repository = mock(PrefersAggregationRepository.class);
        PrefersAggregationMetrics metrics = mock(PrefersAggregationMetrics.class);
        Environment env = enabledEnv();

        CountDownLatch firstEnteredRepo = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Instant ts = Instant.now();
        PrefersAggregationResult result = new PrefersAggregationResult(1L, 1L, ts.minus(24L, ChronoUnit.HOURS), ts);

        when(repository.runAggregation(any(), anyDouble(), anyDouble(), any())).thenAnswer(invocation -> {
            firstEnteredRepo.countDown();
            // Hold the lock long enough for the second runOnce() to try and fail tryLock().
            if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("releaseFirst latch was never opened");
            }
            return result;
        });

        PrefersAggregationJob job = new PrefersAggregationJob(repository, newPolicy(), metrics, env);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<PrefersAggregationOutcome> first = pool.submit(job::runOnce);
            // Make sure thread #1 is inside the locked critical section before #2 starts.
            assertThat(firstEnteredRepo.await(2, TimeUnit.SECONDS))
                    .as("first call must reach the repository").isTrue();

            Future<PrefersAggregationOutcome> second = pool.submit(job::runOnce);
            PrefersAggregationOutcome secondOutcome = second.get(2, TimeUnit.SECONDS);
            assertThat(secondOutcome.status())
                    .as("second concurrent runOnce must short-circuit to skipped")
                    .isEqualTo(PrefersAggregationOutcome.Status.SKIPPED);

            releaseFirst.countDown();
            PrefersAggregationOutcome firstOutcome = first.get(2, TimeUnit.SECONDS);
            assertThat(firstOutcome.status()).isEqualTo(PrefersAggregationOutcome.Status.OK);
        } finally {
            pool.shutdownNow();
        }

        // Repository is touched exactly once; metrics record both a successful run and a skip.
        verify(repository, times(1)).runAggregation(any(), anyDouble(), anyDouble(), any());
        verify(metrics, times(1)).recordRun(any(Duration.class), eq(result));
        verify(metrics, times(1)).recordSkipped();
    }

    @Test
    void lockIsReleasedAfterFailureSoNextRunCanProceed() {
        // A failed runAggregation must release the single-flight lock; otherwise an error would
        // wedge the job permanently.
        PrefersAggregationRepository repository = mock(PrefersAggregationRepository.class);
        PrefersAggregationMetrics metrics = mock(PrefersAggregationMetrics.class);
        Environment env = enabledEnv();
        AtomicInteger callCount = new AtomicInteger();
        Instant ts = Instant.now();
        PrefersAggregationResult success = new PrefersAggregationResult(2L, 1L, ts.minus(24L, ChronoUnit.HOURS), ts);
        when(repository.runAggregation(any(), anyDouble(), anyDouble(), any())).thenAnswer(invocation -> {
            if (callCount.getAndIncrement() == 0) {
                throw new RuntimeException("first run fails");
            }
            return success;
        });

        PrefersAggregationJob job = new PrefersAggregationJob(repository, newPolicy(), metrics, env);

        assertThatCode(job::runOnce).doesNotThrowAnyException();
        PrefersAggregationOutcome second = job.runOnce();
        assertThat(second.status()).isEqualTo(PrefersAggregationOutcome.Status.OK);

        verify(metrics, times(1)).recordError();
        verify(metrics, times(1)).recordRun(any(Duration.class), eq(success));
    }

    private PrefersAggregationPolicy newPolicy() {
        PrefersAggregationPolicy policy = new PrefersAggregationPolicy(30.0, 24L, 0.0, 1000.0);
        policy.validate();
        return policy;
    }

    private Environment enabledEnv() {
        Environment env = mock(Environment.class);
        when(env.getProperty(eq("rhizodelta.feature.prefers-aggregation.enabled"), eq(Boolean.class), eq(Boolean.FALSE)))
                .thenReturn(Boolean.TRUE);
        return env;
    }
}
