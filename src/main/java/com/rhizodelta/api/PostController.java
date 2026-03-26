package com.rhizodelta.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.config.AuthenticatedUser;
import com.rhizodelta.config.RabbitMqConfig;
import com.rhizodelta.domain.post.PostEventMessage;
import com.rhizodelta.service.SseEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/api/posts")
public class PostController {
    private static final String QUEUED_STATUS = "QUEUED";
    private static final int SERVICE_UNAVAILABLE_CODE = 50301;
    private static final long PUBLISH_CONFIRM_TIMEOUT_SECONDS = 5L;
    private static final String TARGET_NODE_EXISTS_QUERY = """
            MATCH (node:GraphNode {node_id: $targetNodeId})
            WHERE NOT coalesce(node._deleted, false)
            RETURN count(node) > 0 AS exists
            """;
    private static final Logger LOGGER = LoggerFactory.getLogger(PostController.class);

    private final RabbitTemplate rabbitTemplate;
    private final Neo4jClient neo4jClient;
    private final SseEventService sseEventService;

    public PostController(RabbitTemplate rabbitTemplate, Neo4jClient neo4jClient, SseEventService sseEventService) {
        this.rabbitTemplate = rabbitTemplate;
        this.neo4jClient = neo4jClient;
        this.sseEventService = sseEventService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PostAcceptedResponse>> createPost(
            @RequestBody CreatePostRequest request,
            Authentication authentication
    ) {
        validateRequest(request);
        validateTargetNodeExists(request.targetNodeId());
        AuthenticatedUser authenticatedUser = requireAuthenticatedUser(authentication);

        String eventId = generateEventId(request.requestId());
        PostEventMessage message = new PostEventMessage(
                request.requestId(),
                authenticatedUser.sub(),
                request.content(),
                request.targetNodeId(),
                eventId
        );
        try {
            publishPostMessage(message, eventId);
        } catch (AmqpException exception) {
            LOGGER.error("RabbitMQ unavailable for post submission", exception);
            ApiResponse<PostAcceptedResponse> response = new ApiResponse<>(
                    SERVICE_UNAVAILABLE_CODE,
                    "message broker unavailable",
                    null
            );
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
        }

        publishQueuedStatus(request.requestId(), eventId, authenticatedUser.sub());
        PostAcceptedResponse response = new PostAcceptedResponse(eventId, QUEUED_STATUS);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(response));
    }

    private void publishQueuedStatus(String requestId, String eventId, String authorId) {
        SseEventService.OrchestrationStatusPayload payload = new SseEventService.OrchestrationStatusPayload(
                requestId,
                eventId,
                null,
                "POST_ACCEPTED",
                "post accepted and queued",
                null,
                null,
                authorId
        );
        sseEventService.publish(SseEventService.SseEventType.ORCHESTRATION_STATUS, payload);
    }

    private void publishPostMessage(PostEventMessage message, String eventId) {
        CorrelationData correlationData = new CorrelationData(eventId);
        rabbitTemplate.convertAndSend(RabbitMqConfig.POSTS_EXCHANGE, RabbitMqConfig.POSTS_ROUTING_KEY, message, correlationData);
        awaitPublishConfirm(correlationData);
    }

    private void awaitPublishConfirm(CorrelationData correlationData) {
        try {
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(PUBLISH_CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (confirm == null || !confirm.isAck()) {
                throw new AmqpException(confirmFailureMessage(confirm));
            }
            // RabbitMQ protocol guarantees basic.return arrives before basic.ack,
            // so correlationData.returned is set by the time the confirm future completes.
            if (correlationData.getReturned() != null) {
                throw new AmqpException("message returned by broker: "
                        + correlationData.getReturned().getReplyText());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AmqpException("publisher confirm interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new AmqpException("publisher confirm failed", exception);
        }
    }

    private String confirmFailureMessage(CorrelationData.Confirm confirm) {
        if (confirm == null) {
            return "publisher confirm missing";
        }
        String reason = confirm.getReason();
        if (reason == null || reason.isBlank()) {
            return "publisher confirm not acknowledged";
        }
        return "publisher confirm not acknowledged: " + reason;
    }

    private void validateRequest(CreatePostRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body must not be null");
        }
        requireText(request.requestId(), "request_id");
        requireText(request.content(), "content");
    }

    private AuthenticatedUser requireAuthenticatedUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new IllegalStateException("authenticated user principal not available");
        }
        return user;
    }

    private void validateTargetNodeExists(String targetNodeId) {
        if (targetNodeId == null || targetNodeId.isBlank()) {
            return;
        }
        Map<String, Object> result = neo4jClient.query(TARGET_NODE_EXISTS_QUERY)
                .bind(targetNodeId).to("targetNodeId")
                .fetch()
                .one()
                .orElseThrow(() -> new IllegalStateException("Failed to validate target_node_id"));
        if (!Boolean.TRUE.equals(result.get("exists"))) {
            throw new IllegalArgumentException("target_node_id not found");
        }
    }

    private static String generateEventId(String requestId) {
        return UUID.nameUUIDFromBytes(requestId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    public record CreatePostRequest(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("author_id") String authorId,
            @JsonProperty("content") String content,
            @JsonProperty("target_node_id") String targetNodeId
    ) {
    }

    public record PostAcceptedResponse(
            @JsonProperty("event_id") String eventId,
            @JsonProperty("status") String status
    ) {
    }
}
