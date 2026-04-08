package com.rhizodelta.infrastructure.sse.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.rhizodelta.infrastructure.messaging.config.RabbitMqConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

/**
 * 负责本地 SSE 推送与跨实例广播。
 *
 * <p>该服务维护当前实例上的 SSE 连接集合，并通过 RabbitMQ 把事件广播到其他实例，
 * 从而让任意节点产生的事件都能被所有订阅者感知。
 *
 * <p><b>关键副作用</b>：
 * <ul>
 *   <li>会维护内存中的 emitter 注册表。</li>
 *   <li>会通过 RabbitMQ 广播事件。</li>
 *   <li>会定期发送心跳并清理失活连接。</li>
 * </ul>
 */
@Service
public class SseEventService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SseEventService.class);
    private static final long STREAM_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(30);
    private static final long HEARTBEAT_INTERVAL_MILLIS = 30_000L;

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> emitterUserMap = new ConcurrentHashMap<>();
    private final RabbitTemplate rabbitTemplate;
    private final String instanceId = UUID.randomUUID().toString();

    public SseEventService(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 注册一个新的 SSE 连接。
     *
     * <p>若提供了用户 ID，则后续可按用户粒度过滤某些敏感状态事件。
     *
     * <p>
     *
     * @param userId 可选用户 ID。
     * @return 新的 SSE emitter。
     */
    public SseEmitter register(String userId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        String emitterId = UUID.randomUUID().toString();
        emitters.put(emitterId, emitter);
        if (userId != null) {
            emitterUserMap.put(emitterId, userId);
        }
        registerCallbacks(emitterId, emitter);
        return emitter;
    }

    /**
     * 发布一条 SSE 事件。
     *
     * <p>该方法会先在当前实例本地推送，再尽力广播到消息总线；广播失败只记录日志，不影响本地已推送结果。
     */
    public void publish(SseEventType type, Object payload) {
        publishLocal(type, payload);
        publishToBroker(type, payload);
    }

    /**
     * 处理来自其他实例的 SSE 广播消息。
     *
     * <p>当前实例自己发出的消息会被忽略，避免重复推送。
     */
    @RabbitListener(queues = "#{sseEventsQueue.name}")
    public void handleBroadcast(SseEventMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("SSE broadcast message must not be null");
        }
        if (instanceId.equals(message.origin())) {
            return;
        }
        SseEventType eventType;
        try {
            eventType = SseEventType.valueOf(message.type());
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("Ignoring unknown SSE event type: {}", message.type());
            return;
        }
        publishLocal(eventType, message.payload());
    }

    /**
     * 定期发送 SSE 心跳并清理失活连接。
     */
    @Scheduled(fixedRate = HEARTBEAT_INTERVAL_MILLIS)
    public void sendHeartbeat() {
        emitters.forEach((emitterId, emitter) -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (java.io.IOException ioException) {
                LOGGER.debug("SSE heartbeat detected dead connection, removing emitter", ioException);
                emitter.completeWithError(ioException);
                removeEmitter(emitterId, emitter);
            } catch (Exception exception) {
                LOGGER.warn("Failed to send SSE heartbeat", exception);
                removeEmitter(emitterId, emitter);
            }
        });
    }

    private void registerCallbacks(String emitterId, SseEmitter emitter) {
        emitter.onCompletion(() -> removeEmitter(emitterId, emitter));
        emitter.onTimeout(() -> removeEmitter(emitterId, emitter));
        emitter.onError(error -> removeEmitter(emitterId, emitter));
    }

    private void publishLocal(SseEventType type, Object payload) {
        emitters.forEach((emitterId, emitter) -> {
            try {
                if (type == SseEventType.ORCHESTRATION_STATUS && payload instanceof OrchestrationStatusPayload statusPayload) {
                    String emitterUserId = emitterUserMap.get(emitterId);
                    if (emitterUserId == null || !emitterUserId.equals(statusPayload.authorId())) {
                        return;
                    }
                }
                emitter.send(SseEmitter.event().name(type.name()).data(payload));
            } catch (Exception exception) {
                LOGGER.warn("Failed to publish SSE event type={}", type, exception);
                removeEmitter(emitterId, emitter);
            }
        });
    }

    private void publishToBroker(SseEventType type, Object payload) {
        try {
            SseEventMessage message = new SseEventMessage(instanceId, type.name(), payload);
            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.SSE_EVENTS_EXCHANGE,
                    RabbitMqConfig.SSE_EVENTS_ROUTING_KEY,
                    message
            );
        } catch (AmqpException exception) {
            LOGGER.warn("Failed to broadcast SSE event via RabbitMQ type={}", type, exception);
        }
    }

    private void removeEmitter(String emitterId, SseEmitter emitter) {
        emitters.remove(emitterId, emitter);
        emitterUserMap.remove(emitterId);
    }

    /**
     * 定义系统支持的 SSE 事件类型。
     */
    public enum SseEventType {
        NODE_CREATED,
        EDGE_CREATED,
        EDGE_REMOVED,
        DECISION_COMPLETE,
        ORCHESTRATION_STATUS,
        SUMMARY_GENERATED,
        QUALITY_SCORED
    }

    /**
     * 表示节点创建事件载荷。
     */
    public record NodeCreatedPayload(
            @JsonProperty("node_id") String nodeId,
            @JsonProperty("label") String label,
            @JsonProperty("created_at") Instant createdAt
    ) {
    }

    /**
     * 表示边创建事件载荷。
     */
    public record EdgeCreatedPayload(
            @JsonProperty("source") String source,
            @JsonProperty("target") String target,
            @JsonProperty("type") String type,
            @JsonProperty("created_at") Instant createdAt
    ) {
    }

    public record EdgeRemovedPayload(
            @JsonProperty("source") String source,
            @JsonProperty("type") String type
    ) {
    }

    public record DecisionCompletePayload(
            @JsonProperty("decision_id") String decisionId,
            @JsonProperty("decision_type") String decisionType,
            @JsonProperty("node_id") String nodeId
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    /**
     * 表示编排状态事件载荷。
     *
     * <p>该对象支持附带可选的复核 ID、决策 ID 和结构化解释文本。
     */
    public record OrchestrationStatusPayload(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("event_id") String eventId,
            @JsonProperty("post_node_id") String postNodeId,
            @JsonProperty("status") String status,
            @JsonProperty("message") String message,
            @JsonProperty("review_id") String reviewId,
            @JsonProperty("decision_id") String decisionId,
            @JsonProperty("author_id") String authorId,
            @JsonProperty("explanation") String explanation
    ) {
        public OrchestrationStatusPayload(
                String requestId, String eventId, String postNodeId,
                String status, String message, String reviewId,
                String decisionId, String authorId
        ) {
            this(requestId, eventId, postNodeId, status, message, reviewId, decisionId, authorId, null);
        }
    }

    /**
     * 表示跨实例 SSE 广播消息。
     */
    public record SseEventMessage(String origin, String type, Object payload) {
    }

    /**
     * 表示摘要生成完成事件载荷。
     */
    public record SummaryGeneratedPayload(
            @JsonProperty("node_id") String nodeId,
            @JsonProperty("summary") String summary,
            @JsonProperty("source_count") int sourceCount,
            @JsonProperty("model_used") String modelUsed
    ) {
    }

    /**
     * 表示质量评分完成事件载荷。
     */
    public record QualityScoredPayload(
            @JsonProperty("node_id") String nodeId,
            @JsonProperty("quality_overall") double qualityOverall,
            @JsonProperty("quality_relevance") double qualityRelevance,
            @JsonProperty("quality_density") double qualityDensity,
            @JsonProperty("quality_argumentation") double qualityArgumentation,
            @JsonProperty("quality_community_value") double qualityCommunityValue,
            @JsonProperty("reason") String reason
    ) {
    }
}
