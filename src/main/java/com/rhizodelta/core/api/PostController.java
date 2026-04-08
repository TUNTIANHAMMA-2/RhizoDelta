package com.rhizodelta.core.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.infrastructure.web.ApiResponse;
import com.rhizodelta.infrastructure.security.model.AuthenticatedUser;
import com.rhizodelta.infrastructure.messaging.config.RabbitMqConfig;
import com.rhizodelta.infrastructure.messaging.message.PostEventMessage;
import com.rhizodelta.infrastructure.sse.service.SseEventService;
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

/**
 * 接收帖子提交并将其投递到异步处理链路。
 *
 * <p>该控制器位于 {@code com.rhizodelta.core.api} 的核心写入口，负责把同步 HTTP 请求
 * 转换为 {@link PostEventMessage}，再交给 RabbitMQ 驱动后续的落库、embedding、
 * 质量评估与 AI 路由流程。
 *
 * <p><b>关键副作用</b>：
 * <ul>
 *   <li>会校验 {@code target_node_id} 是否指向仍然有效的图节点。</li>
 *   <li>会向 {@link RabbitMqConfig#POSTS_EXCHANGE} 发布消息，并等待 publisher confirm。</li>
 *   <li>会通过 {@link SseEventService} 立即广播一条 {@code POST_ACCEPTED} 编排状态。</li>
 * </ul>
 *
 * <p><b>隐藏约束</b>：
 * <ul>
 *   <li>客户端传入的 {@code author_id} 不参与鉴权，真实作者始终取自认证主体。</li>
 *   <li>一旦消息代理不可用，请求会直接返回 {@code 503}，不会在本地做静默降级。</li>
 * </ul>
 */
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

    /**
     * 接收帖子并将其排入异步处理队列。
     *
     * <p>该方法存在的意义，是把用户发帖请求和后续高成本处理解耦：
     * HTTP 层只负责完成参数校验、认证绑定和消息投递确认，不在请求线程内执行
     * 图谱写入、向量生成或 AI 路由。
     *
     * <p><b>关键副作用</b>：
     * <ul>
     *   <li>会读取 Neo4j 校验目标节点是否存在且未删除。</li>
     *   <li>会向 RabbitMQ 发布 {@link PostEventMessage}。</li>
     *   <li>会发送一条面向当前作者的 SSE 编排状态事件。</li>
     * </ul>
     *
     * <p><b>注意事项</b>：
     * <ul>
     *   <li>返回 {@code 202 Accepted} 仅表示消息已入队，不表示帖子已成功落库。</li>
     *   <li>认证失败或主体缺失会抛出 {@link IllegalStateException}，交由全局异常处理器兜底。</li>
     * </ul>
     *
     * <p>
     *
     * @param request 帖子提交请求；其中 {@code author_id} 仅保留接口兼容性，不作为最终作者来源。
     * @param authentication 当前请求的认证主体。
     * @return 包含事件 ID 与排队状态的统一响应。
     */
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

    /**
     * 广播帖子已被接受并进入编排队列的状态。
     *
     * <p>该方法的存在，是为了让前端在真正的消费链路开始前就能收到“已接单”的即时反馈，
     * 避免用户把异步排队误判为接口超时或失败。
     *
     * <p><b>关键副作用</b>：
     * <ul>
     *   <li>会通过 {@link SseEventService} 向当前作者推送 {@code ORCHESTRATION_STATUS} 事件。</li>
     *   <li>不会修改数据库，但会影响前端对任务状态的可见性。</li>
     * </ul>
     */
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

    /**
     * 发布帖子消息并强制等待 broker 确认。
     *
     * <p>这里显式等待 confirm 的原因，是在 HTTP 请求仍然活跃时尽早暴露消息代理不可用、
     * routing key 不可达或 broker 拒收等问题，而不是先向客户端返回成功再在后台丢失任务。
     *
     * <p><b>关键副作用</b>：
     * <ul>
     *   <li>会向 RabbitMQ 发送消息。</li>
     *   <li>若 confirm 或 return 异常，会抛出 {@link AmqpException} 中断当前请求。</li>
     * </ul>
     */
    private void publishPostMessage(PostEventMessage message, String eventId) {
        CorrelationData correlationData = new CorrelationData(eventId);
        rabbitTemplate.convertAndSend(RabbitMqConfig.POSTS_EXCHANGE, RabbitMqConfig.POSTS_ROUTING_KEY, message, correlationData);
        awaitPublishConfirm(correlationData);
    }

    /**
     * 等待 publisher confirm 并把 broker 级失败转成同步异常。
     *
     * <p>该方法存在的意义，是把 AMQP 层的确认失败、超时、中断与 basic.return
     * 统一收敛成显式异常，避免上层把“消息未真正入队”误当成成功。
     *
     * <p><b>注意事项</b>：
     * <ul>
     *   <li>线程中断会被恢复中断标记，然后抛出 {@link AmqpException}。</li>
     *   <li>{@code confirm=ack} 但消息被 return 的情况，也会被视为失败。</li>
     * </ul>
     */
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

    /**
     * 校验目标节点在当前图谱中可见且可关联。
     *
     * <p>该校验之所以放在入口层执行，是为了在消息入队前就拦截无效回复目标，
     * 避免消费者在异步链路里才暴露错误并增加补偿成本。
     *
     * <p><b>关键副作用</b>：
     * <ul>
     *   <li>会读取 Neo4j。</li>
     *   <li>不会写数据库，但会在目标不存在时抛出 {@link IllegalArgumentException}。</li>
     * </ul>
     */
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

    /**
     * 表示帖子提交请求体。
     *
     * <p><b>注意事项</b>：
     * <ul>
     *   <li>{@code request_id} 用于生成稳定的事件 ID，并支撑下游幂等处理。</li>
     *   <li>{@code author_id} 不会覆盖认证主体中的用户标识，调用方不应依赖它伪造作者身份。</li>
     * </ul>
     */
    public record CreatePostRequest(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("author_id") String authorId,
            @JsonProperty("content") String content,
            @JsonProperty("target_node_id") String targetNodeId
    ) {
    }

    /**
     * 表示帖子已被接收后的同步回执。
     *
     * <p>该对象只承诺“请求已排队”，不承诺后续消费者一定成功完成落库和编排。
     */
    public record PostAcceptedResponse(
            @JsonProperty("event_id") String eventId,
            @JsonProperty("status") String status
    ) {
    }
}
