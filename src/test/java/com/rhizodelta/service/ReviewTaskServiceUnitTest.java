package com.rhizodelta.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rhizodelta.domain.review.ReviewTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewTaskServiceUnitTest {
    private static final Duration REVIEW_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);
    private ReviewTaskService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        service = new ReviewTaskService(redisTemplate, new ObjectMapper().findAndRegisterModules(), REVIEW_TTL);
    }

    @Test
    void shouldPersistPendingReviewTaskWithTtl() {
        ReviewTask task = service.createPendingTask(new ReviewTask.CreateReviewTaskCommand(
                "req-1",
                "post-1",
                "trace-1",
                "merge",
                List.of("candidate-1", "candidate-2"),
                Map.of("summary_content", "merged summary"),
                List.of("LOW_CONFIDENCE")
        ));

        assertThat(task.status()).isEqualTo(ReviewTask.Status.PENDING);
        assertThat(task.suggestedAction()).isEqualTo("MERGE");
        verify(valueOperations).set(eq("review:task:" + task.reviewId()), anyString(), eq(REVIEW_TTL));
        verify(zSetOperations).add(eq("review:pending"), eq(task.reviewId()), anyDouble());
    }

    @Test
    void shouldReturnOnlyExistingPendingTasksFromIndex() throws Exception {
        ReviewTask persistedTask = new ReviewTask(
                "review-1",
                "req-1",
                "post-1",
                "trace-1",
                ReviewTask.Status.PENDING,
                "REVIEW",
                List.of("candidate-1"),
                Map.of("content", "draft"),
                List.of("BUDGET_EXCEEDED"),
                java.time.Instant.parse("2026-03-23T00:00:00Z"),
                java.time.Instant.parse("2026-03-23T00:00:00Z"),
                java.time.Instant.parse("2026-03-30T00:00:00Z")
        );
        String serializedTask = new ObjectMapper()
                .findAndRegisterModules()
                .writeValueAsString(persistedTask);
        LinkedHashSet<String> reviewIds = new LinkedHashSet<>();
        reviewIds.add("review-1");
        reviewIds.add("review-missing");
        when(zSetOperations.range("review:pending", 0, 1)).thenReturn(reviewIds);
        when(valueOperations.get("review:task:review-1")).thenReturn(serializedTask);
        when(valueOperations.get("review:task:review-missing")).thenReturn(null);

        List<ReviewTask> pendingTasks = service.findPendingTasks(2);

        assertThat(pendingTasks).containsExactly(persistedTask);
    }

    @Test
    void shouldUpdateStatusAndRemoveCompletedTaskFromPendingIndex() throws Exception {
        ReviewTask existingTask = new ReviewTask(
                "review-2",
                "req-2",
                "post-2",
                "trace-2",
                ReviewTask.Status.PENDING,
                "BRANCH",
                List.of("candidate-2"),
                Map.of("content", "branch draft"),
                List.of("LOW_CONFIDENCE"),
                java.time.Instant.parse("2026-03-23T00:00:00Z"),
                java.time.Instant.parse("2026-03-23T00:00:00Z"),
                java.time.Instant.parse("2026-03-30T00:00:00Z")
        );
        when(valueOperations.get("review:task:review-2")).thenReturn(new ObjectMapper()
                .findAndRegisterModules()
                .writeValueAsString(existingTask));

        ReviewTask updatedTask = service.updateStatus("review-2", ReviewTask.Status.APPROVED);

        assertThat(updatedTask.status()).isEqualTo(ReviewTask.Status.APPROVED);
        verify(zSetOperations).remove("review:pending", "review-2");
        verify(zSetOperations, never()).add(eq("review:pending"), eq("review-2"), anyDouble());
    }

    @Test
    void shouldRejectTransitionFromTerminalStatus() throws Exception {
        ReviewTask approvedTask = new ReviewTask(
                "review-3",
                "req-3",
                "post-3",
                "trace-3",
                ReviewTask.Status.APPROVED,
                "MERGE",
                List.of("candidate-3"),
                Map.of("content", "approved draft"),
                List.of("LOW_CONFIDENCE"),
                java.time.Instant.parse("2026-03-23T00:00:00Z"),
                java.time.Instant.parse("2026-03-23T00:00:00Z"),
                java.time.Instant.parse("2026-03-30T00:00:00Z")
        );
        when(valueOperations.get("review:task:review-3")).thenReturn(new ObjectMapper()
                .findAndRegisterModules()
                .writeValueAsString(approvedTask));

        assertThatThrownBy(() -> service.updateStatus("review-3", ReviewTask.Status.REJECTED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot transition from terminal status");
    }

    @Test
    void shouldCleanOrphanedEntriesFromPendingIndex() throws Exception {
        ReviewTask pendingTask = new ReviewTask(
                "review-4",
                "req-4",
                "post-4",
                "trace-4",
                ReviewTask.Status.PENDING,
                "REVIEW",
                List.of("candidate-4"),
                Map.of("content", "draft"),
                List.of("BUDGET_EXCEEDED"),
                java.time.Instant.parse("2026-03-23T00:00:00Z"),
                java.time.Instant.parse("2026-03-23T00:00:00Z"),
                java.time.Instant.parse("2026-03-30T00:00:00Z")
        );
        String serialized = new ObjectMapper().findAndRegisterModules().writeValueAsString(pendingTask);
        LinkedHashSet<String> reviewIds = new LinkedHashSet<>();
        reviewIds.add("review-4");
        reviewIds.add("review-orphan");
        when(zSetOperations.range("review:pending", 0, 49)).thenReturn(reviewIds);
        when(valueOperations.get("review:task:review-4")).thenReturn(serialized);
        when(valueOperations.get("review:task:review-orphan")).thenReturn(null);

        List<ReviewTask> result = service.findPendingTasks(null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).reviewId()).isEqualTo("review-4");
        verify(zSetOperations).remove(eq("review:pending"), (Object[]) org.mockito.ArgumentMatchers.any());
    }
}
