package com.rhizodelta.consensus.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rhizodelta.consensus.domain.review.ReviewTask;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ReviewTaskService {
    private static final String REVIEW_TASK_KEY_PREFIX = "review:task:";
    private static final String PENDING_REVIEW_INDEX_KEY = "review:pending";
    private static final int DEFAULT_PENDING_LIMIT = 50;
    private static final Set<ReviewTask.Status> TERMINAL_STATUSES = Set.of(
            ReviewTask.Status.APPROVED, ReviewTask.Status.REJECTED, ReviewTask.Status.EXPIRED
    );

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration reviewTtl;

    public ReviewTaskService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${rhizodelta.ai.review.ttl:PT168H}") Duration reviewTtl
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.reviewTtl = reviewTtl;
    }

    public ReviewTask createPendingTask(ReviewTask.CreateReviewTaskCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        Instant createdAt = Instant.now();
        ReviewTask task = new ReviewTask(
                UUID.randomUUID().toString(),
                requireText(command.requestId(), "requestId"),
                requireText(command.postNodeId(), "postNodeId"),
                requireText(command.workflowTraceId(), "workflowTraceId"),
                ReviewTask.Status.PENDING,
                normalizeAction(command.suggestedAction()),
                command.candidateNodeIds(),
                command.draftPayload(),
                command.reviewReasonCodes(),
                createdAt,
                createdAt,
                createdAt.plus(reviewTtl)
        );
        save(task);
        return task;
    }

    public Optional<ReviewTask> findTask(String reviewId) {
        String payload = valueOps().get(taskKey(requireText(reviewId, "reviewId")));
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(deserialize(payload));
    }

    public ReviewTask getTask(String reviewId) {
        return findTask(reviewId)
                .orElseThrow(() -> new NoSuchElementException("review task not found: " + reviewId));
    }

    public List<ReviewTask> findPendingTasks(Integer limit) {
        int resolvedLimit = resolveLimit(limit);
        Set<String> reviewIds = zSetOps().range(PENDING_REVIEW_INDEX_KEY, 0, resolvedLimit - 1);
        if (reviewIds == null || reviewIds.isEmpty()) {
            return List.of();
        }
        List<String> orphanedIds = new java.util.ArrayList<>();
        List<ReviewTask> result = new java.util.ArrayList<>();
        for (String reviewId : reviewIds) {
            Optional<ReviewTask> task = findTask(reviewId);
            if (task.isEmpty() || (task.get().status() != ReviewTask.Status.PENDING && task.get().status() != ReviewTask.Status.EXECUTION_FAILED)) {
                orphanedIds.add(reviewId);
            } else {
                result.add(task.get());
            }
        }
        if (!orphanedIds.isEmpty()) {
            zSetOps().remove(PENDING_REVIEW_INDEX_KEY, orphanedIds.toArray());
        }
        return List.copyOf(result);
    }

    public ReviewTask updateStatus(String reviewId, ReviewTask.Status status) {
        ReviewTask existing = getTask(reviewId);
        Objects.requireNonNull(status, "status must not be null");
        validateTransition(existing.status(), status);
        ReviewTask updated = new ReviewTask(
                existing.reviewId(),
                existing.requestId(),
                existing.postNodeId(),
                existing.workflowTraceId(),
                status,
                existing.suggestedAction(),
                existing.candidateNodeIds(),
                existing.draftPayload(),
                existing.reviewReasonCodes(),
                existing.createdAt(),
                Instant.now(),
                Instant.now().plus(reviewTtl)
        );
        save(updated);
        if (status != ReviewTask.Status.PENDING && status != ReviewTask.Status.EXECUTION_FAILED) {
            zSetOps().remove(PENDING_REVIEW_INDEX_KEY, reviewId);
        }
        return updated;
    }

    private void save(ReviewTask task) {
        valueOps().set(taskKey(task.reviewId()), serialize(task), reviewTtl);
        if (task.status() == ReviewTask.Status.PENDING || task.status() == ReviewTask.Status.EXECUTION_FAILED) {
            zSetOps().add(PENDING_REVIEW_INDEX_KEY, task.reviewId(), task.createdAt().toEpochMilli());
        }
    }

    private ValueOperations<String, String> valueOps() {
        return redisTemplate.opsForValue();
    }

    private ZSetOperations<String, String> zSetOps() {
        return redisTemplate.opsForZSet();
    }

    private ReviewTask deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, ReviewTask.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to deserialize review task", exception);
        }
    }

    private String serialize(ReviewTask task) {
        try {
            return objectMapper.writeValueAsString(task);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize review task", exception);
        }
    }

    private static String taskKey(String reviewId) {
        return REVIEW_TASK_KEY_PREFIX + reviewId;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static int resolveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_PENDING_LIMIT;
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }
        return limit;
    }

    private static String normalizeAction(String suggestedAction) {
        if (suggestedAction == null || suggestedAction.isBlank()) {
            return "REVIEW";
        }
        String normalized = suggestedAction.toUpperCase();
        if ("MERGE".equals(normalized) || "BRANCH".equals(normalized) || "REVIEW".equals(normalized)) {
            return normalized;
        }
        return "REVIEW";
    }

    private static void validateTransition(ReviewTask.Status current, ReviewTask.Status target) {
        if (TERMINAL_STATUSES.contains(current)) {
            throw new IllegalStateException(
                    "cannot transition from terminal status " + current + " to " + target
            );
        }
        if (current == target) {
            throw new IllegalStateException("review task is already in status " + current);
        }
    }
}
