package com.rhizodelta.infrastructure.user.service;

import com.rhizodelta.infrastructure.user.repository.PreferenceEventRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PreferenceEventServiceTest {

    @Test
    void recordEventDelegatesToRepositorySynchronously() {
        PreferenceEventRepository repository = mock(PreferenceEventRepository.class);
        PreferenceEventService service = new PreferenceEventService(repository);

        service.recordEvent("u1", "t1", "VIEW", 0.5, "n1");

        verify(repository, times(1)).createEvent(eq("u1"), eq("t1"), anyString(), eq("VIEW"), eq(0.5), eq("n1"));
    }

    @Test
    void recordEventSwallowsRepositoryFailure() {
        PreferenceEventRepository repository = mock(PreferenceEventRepository.class);
        doThrow(new RuntimeException("neo4j down"))
                .when(repository).createEvent(anyString(), anyString(), anyString(), anyString(), anyDouble(), anyString());
        PreferenceEventService service = new PreferenceEventService(repository);

        // Must not propagate: caller (e.g. NodeQueryController) should never fail on event recording.
        service.recordEvent("u1", "t1", "VIEW", 0.5, "n1");
    }

    @Test
    void recordEventAsyncUsesExecutorAndDelegatesToRecordEvent() throws InterruptedException {
        PreferenceEventRepository repository = mock(PreferenceEventRepository.class);
        CountDownLatch latch = new CountDownLatch(1);
        List<Runnable> submitted = new ArrayList<>();
        Executor capturingExecutor = task -> {
            submitted.add(task);
            task.run();
            latch.countDown();
        };
        PreferenceEventService service = new PreferenceEventService(repository);
        service.setPreferenceEventExecutor(capturingExecutor);

        service.recordEventAsync("u1", null, "VIEW", 0.5, "n1");

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(submitted).hasSize(1);
        // Topic is null on the read-path, the service must pass an empty/null marker to the repo (handled inside recordEvent → repository.createEvent).
        verify(repository).createEvent(eq("u1"), any(), anyString(), eq("VIEW"), eq(0.5), eq("n1"));
    }

    @Test
    void recordEventAsyncDropsEventWhenExecutorRejects() {
        PreferenceEventRepository repository = mock(PreferenceEventRepository.class);
        Executor saturatedExecutor = task -> {
            throw new RejectedExecutionException("queue full");
        };
        PreferenceEventService service = new PreferenceEventService(repository);
        service.setPreferenceEventExecutor(saturatedExecutor);

        // Must not propagate — saturated event queue cannot bubble up to the read path.
        service.recordEventAsync("u1", null, "VIEW", 0.5, "n1");
        verifyNoInteractions(repository);
    }

    @Test
    void defaultExecutorRunsTaskOnCallingThread() {
        PreferenceEventRepository repository = mock(PreferenceEventRepository.class);
        PreferenceEventService service = new PreferenceEventService(repository);
        // No setPreferenceEventExecutor call → falls back to Runnable::run.

        service.recordEventAsync("u1", null, "VIEW", 0.5, "n1");

        verify(repository, times(1)).createEvent(eq("u1"), any(), anyString(), eq("VIEW"), eq(0.5), eq("n1"));
    }
}
