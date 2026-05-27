package com.rhizodelta.infrastructure.user.service;

import com.rhizodelta.infrastructure.user.repository.PreferenceEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 偏好事件写入服务。
 *
 * <p>对外暴露两种语义：
 * <ul>
 *   <li>{@link #recordEvent} —— 同步写。供 {@code POST /api/preference-events} 等
 *       用户显式触发的端点使用，保留即时反馈。</li>
 *   <li>{@link #recordEventAsync} —— 异步 fire-and-forget。供 {@code GET /api/nodes/{id}}
 *       等读路径的隐式 VIEW 埋点使用，避免读请求线程被 Neo4j 写事务阻塞。</li>
 * </ul>
 *
 * <p>异步路径在 {@code preferenceEventExecutor} 上排队；队列饱和 ({@link RejectedExecutionException})
 * 被记入 debug 日志后丢弃事件——读路径不应因为埋点写满而失败。
 */
@Service
public class PreferenceEventService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PreferenceEventService.class);

    private final PreferenceEventRepository repository;
    /** 默认 same-thread executor 让纯单元测试不必装配真实线程池。 */
    private Executor preferenceEventExecutor = Runnable::run;

    public PreferenceEventService(PreferenceEventRepository repository) {
        this.repository = repository;
    }

    @Autowired(required = false)
    public void setPreferenceEventExecutor(@Qualifier("preferenceEventExecutor") Executor preferenceEventExecutor) {
        this.preferenceEventExecutor = Objects.requireNonNull(preferenceEventExecutor, "preferenceEventExecutor must not be null");
    }

    /**
     * 同步写偏好事件；失败仅记 debug 日志（不抛异常）。
     *
     * <p>用于用户显式 POST 创建事件等需要"调用即落库"语义的路径。
     */
    public void recordEvent(String userId, String topicId, String type, double weight, String sourceNodeId) {
        try {
            repository.createEvent(userId, topicId, UUID.randomUUID().toString(), type, weight, sourceNodeId);
        } catch (Exception e) {
            LOGGER.debug("Failed to record preference event type={} for user={}: {}", type, userId, e.getMessage());
        }
    }

    /**
     * 异步写偏好事件；不阻塞调用线程。
     *
     * <p>用于 {@code GET /api/nodes/{id}} 等读路径的隐式埋点。 调用方拿到返回时事件可能尚未落库，
     * 这是有意为之：读请求不该被偏好埋点的写事务拖慢。
     *
     * <p>线程池排队上限触发 {@link RejectedExecutionException} 时，事件直接丢弃并记 debug 日志，
     * 让读路径优先保持低延迟。
     */
    public void recordEventAsync(String userId, String topicId, String type, double weight, String sourceNodeId) {
        try {
            preferenceEventExecutor.execute(() -> recordEvent(userId, topicId, type, weight, sourceNodeId));
        } catch (RejectedExecutionException rejected) {
            LOGGER.debug("Preference event queue saturated, dropping event type={} user={}", type, userId);
        }
    }
}
