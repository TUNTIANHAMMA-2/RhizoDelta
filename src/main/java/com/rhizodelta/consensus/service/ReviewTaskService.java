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

/**
 * 负责复核任务的持久化与状态流转。
 *
 * <p>该服务使用 Redis 保存待复核任务及其有序索引，
 * 让管理界面可以快速读取任务并执行状态更新。
 *
 * <p><b>关键副作用</b>：
 * <ul>
 *   <li>会读写 Redis 字符串键和值集合索引。</li>
 *   <li>会序列化和反序列化 {@link ReviewTask}。</li>
 *   <li>终态任务会从待处理索引中移除。</li>
 * </ul>
 */
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

    /**
     * 创建一条新的待复核任务。
     *
     * <p>该方法会立即把任务写入 Redis，并加入待处理索引。
     *
     * <p>
     *
     * @param command 创建命令。
     * @return 已持久化任务。
     */
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

    /**
     * 按 ID 查询一条复核任务。
     *
     * <p>任务不存在时返回空结果，适合做存在性探测。
     */
    public Optional<ReviewTask> findTask(String reviewId) {
        String payload = valueOps().get(taskKey(requireText(reviewId, "reviewId")));
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(deserialize(payload));
    }

    /**
     * 强制读取一条复核任务。
     *
     * <p>任务不存在时抛出 {@link NoSuchElementException}。
     */
    public ReviewTask getTask(String reviewId) {
        return findTask(reviewId)
                .orElseThrow(() -> new NoSuchElementException("review task not found: " + reviewId));
    }

    /**
     * 返回当前可处理的复核任务列表。
     *
     * <p>该方法会顺带清理待处理索引中的孤儿任务 ID。
     *
     * <p>
     *
     * @param limit 可选返回数量。
     * @return 待处理任务列表。
     */
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

    /**
     * 更新任务状态并维护待处理索引。
     *
     * <p>非法状态迁移会直接失败，不会静默覆盖。
     *
     * <p>
     *
     * @param reviewId 任务 ID。
     * @param status 目标状态。
     * @return 更新后的任务。
     */
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
