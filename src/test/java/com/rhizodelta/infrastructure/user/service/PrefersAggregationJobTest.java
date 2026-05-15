package com.rhizodelta.infrastructure.user.service;

import com.rhizodelta.infrastructure.user.observability.PrefersAggregationMetrics;
import com.rhizodelta.infrastructure.user.repository.PrefersAggregationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
