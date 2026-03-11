package com.rhizodelta.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.config.RabbitMqConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

@Service
public class SseEventService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SseEventService.class);
    private static final long STREAM_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(30);
    private static final long HEARTBEAT_INTERVAL_MILLIS = 30_000L;

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final RabbitTemplate rabbitTemplate;
    private final String instanceId = UUID.randomUUID().toString();

    public SseEventService(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        emitters.add(emitter);
        registerCallbacks(emitter);
        return emitter;
    }

    public void publish(SseEventType type, Object payload) {
        publishLocal(type, payload);
        publishToBroker(type, payload);
    }

    @RabbitListener(queues = "#{sseEventsQueue.name}")
    public void handleBroadcast(SseEventMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("SSE broadcast message must not be null");
        }
        if (instanceId.equals(message.origin())) {
            return;
        }
        publishLocal(SseEventType.valueOf(message.type()), message.payload());
    }

    @Scheduled(fixedRate = HEARTBEAT_INTERVAL_MILLIS)
    public void sendHeartbeat() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception exception) {
                LOGGER.warn("Failed to send SSE heartbeat", exception);
                removeEmitter(emitter);
            }
        }
    }

    private void registerCallbacks(SseEmitter emitter) {
        emitter.onCompletion(() -> removeEmitter(emitter));
        emitter.onTimeout(() -> removeEmitter(emitter));
        emitter.onError(error -> removeEmitter(emitter));
    }

    private void publishLocal(SseEventType type, Object payload) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(type.name()).data(payload));
            } catch (Exception exception) {
                LOGGER.warn("Failed to publish SSE event type={}", type, exception);
                removeEmitter(emitter);
            }
        }
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

    private void removeEmitter(SseEmitter emitter) {
        emitters.remove(emitter);
    }

    public enum SseEventType {
        NODE_CREATED,
        EDGE_CREATED,
        DECISION_COMPLETE
    }

    public record NodeCreatedPayload(
            @JsonProperty("node_id") String nodeId,
            @JsonProperty("label") String label,
            @JsonProperty("created_at") Instant createdAt
    ) {
    }

    public record EdgeCreatedPayload(
            @JsonProperty("source") String source,
            @JsonProperty("target") String target,
            @JsonProperty("type") String type,
            @JsonProperty("created_at") Instant createdAt
    ) {
    }

    public record DecisionCompletePayload(
            @JsonProperty("decision_id") String decisionId,
            @JsonProperty("decision_type") String decisionType,
            @JsonProperty("node_id") String nodeId
    ) {
    }

    public record SseEventMessage(String origin, String type, Object payload) {
    }
}
