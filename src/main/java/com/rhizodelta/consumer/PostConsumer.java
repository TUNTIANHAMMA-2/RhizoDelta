package com.rhizodelta.consumer;

import com.rhizodelta.config.RabbitMqConfig;
import com.rhizodelta.domain.node.HumanPost;
import com.rhizodelta.domain.post.PostEventMessage;
import com.rhizodelta.service.EmbeddingModelService;
import com.rhizodelta.service.EmbeddingService;
import com.rhizodelta.service.PostService;
import com.rhizodelta.service.SseEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Component
public class PostConsumer {
    private static final Logger LOGGER = LoggerFactory.getLogger(PostConsumer.class);

    private final PostService postService;
    private final EmbeddingModelService embeddingModelService;
    private final EmbeddingService embeddingService;
    private final SseEventService sseEventService;
    private Executor embeddingTaskExecutor = Runnable::run;

    public PostConsumer(
            PostService postService,
            EmbeddingModelService embeddingModelService,
            EmbeddingService embeddingService,
            SseEventService sseEventService
    ) {
        this.postService = postService;
        this.embeddingModelService = embeddingModelService;
        this.embeddingService = embeddingService;
        this.sseEventService = sseEventService;
    }

    @RabbitListener(queues = RabbitMqConfig.POSTS_QUEUE)
    public void handleMessage(PostEventMessage message) {
        processMessage(message);
    }

    @Autowired
    public void setEmbeddingTaskExecutor(@Qualifier("embeddingTaskExecutor") Executor embeddingTaskExecutor) {
        this.embeddingTaskExecutor = Objects.requireNonNull(embeddingTaskExecutor, "embeddingTaskExecutor must not be null");
    }

    private void processMessage(PostEventMessage message) {
        PostService.CreateHumanPostCommand command = new PostService.CreateHumanPostCommand(
                message.requestId(),
                message.authorId(),
                message.content(),
                message.targetNodeId()
        );
        HumanPost post = postService.createHumanPost(command);
        CompletableFuture.runAsync(() -> writeEmbedding(message, post), embeddingTaskExecutor);
        publishNodeCreated(post);
    }

    private void writeEmbedding(PostEventMessage message, HumanPost post) {
        try {
            List<Float> vector = embeddingModelService.embed(post.getContent());
            embeddingService.writeEmbedding(post.getNodeId().toString(), vector);
            publishOrchestrationStatus(
                    message.requestId(),
                    message.eventId(),
                    post.getNodeId().toString(),
                    "EMBEDDING_READY",
                    "embedding generated for queued post"
            );
        } catch (Exception exception) {
            LOGGER.error("Failed to generate embedding for Human_Post node_id={}", post.getNodeId(), exception);
            publishOrchestrationStatus(
                    message.requestId(),
                    message.eventId(),
                    post.getNodeId().toString(),
                    "FAILED",
                    "embedding generation failed"
            );
        }
    }

    private void publishNodeCreated(HumanPost post) {
        SseEventService.NodeCreatedPayload payload = new SseEventService.NodeCreatedPayload(
                post.getNodeId().toString(),
                "Human_Post",
                post.getCreatedAt()
        );
        sseEventService.publish(SseEventService.SseEventType.NODE_CREATED, payload);
    }

    private void publishOrchestrationStatus(
            String requestId,
            String eventId,
            String postNodeId,
            String status,
            String message
    ) {
        SseEventService.OrchestrationStatusPayload payload = new SseEventService.OrchestrationStatusPayload(
                requestId,
                eventId,
                postNodeId,
                status,
                message,
                null,
                null
        );
        sseEventService.publish(SseEventService.SseEventType.ORCHESTRATION_STATUS, payload);
    }
}
