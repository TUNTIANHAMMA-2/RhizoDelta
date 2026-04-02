package com.rhizodelta.consumer;

import com.rhizodelta.config.RabbitMqConfig;
import com.rhizodelta.domain.ai.QualityEvaluationCommand;
import com.rhizodelta.domain.ai.QualityScore;
import com.rhizodelta.domain.node.HumanPost;
import com.rhizodelta.domain.post.PostEventMessage;
import com.rhizodelta.service.AiRoutingOrchestratorService;
import com.rhizodelta.service.EmbeddingModelService;
import com.rhizodelta.service.EmbeddingService;
import com.rhizodelta.service.PostService;
import com.rhizodelta.service.QualityAgentService;
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
    private AiRoutingOrchestratorService aiRoutingOrchestratorService;
    private QualityAgentService qualityAgentService;
    private boolean qualityEnabled = true;
    private int qualityMinContentLength = 20;
    private Executor embeddingTaskExecutor = Runnable::run;
    private Executor routingTaskExecutor = Runnable::run;

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

    @Autowired
    public void setRoutingTaskExecutor(@Qualifier("routingTaskExecutor") Executor routingTaskExecutor) {
        this.routingTaskExecutor = Objects.requireNonNull(routingTaskExecutor, "routingTaskExecutor must not be null");
    }

    @Autowired(required = false)
    public void setAiRoutingOrchestratorService(AiRoutingOrchestratorService aiRoutingOrchestratorService) {
        this.aiRoutingOrchestratorService = aiRoutingOrchestratorService;
    }

    @Autowired(required = false)
    public void setQualityAgentService(QualityAgentService qualityAgentService) {
        this.qualityAgentService = qualityAgentService;
    }

    @org.springframework.beans.factory.annotation.Value("${rhizodelta.ai.quality.enabled:true}")
    public void setQualityEnabled(boolean qualityEnabled) {
        this.qualityEnabled = qualityEnabled;
    }

    @org.springframework.beans.factory.annotation.Value("${rhizodelta.ai.quality.min-content-length:20}")
    public void setQualityMinContentLength(int qualityMinContentLength) {
        this.qualityMinContentLength = qualityMinContentLength;
    }

    private void processMessage(PostEventMessage message) {
        PostService.CreateHumanPostCommand command = new PostService.CreateHumanPostCommand(
                message.requestId(),
                message.authorId(),
                message.content(),
                message.targetNodeId()
        );
        PostService.CreateHumanPostResult result = postService.createHumanPost(command);
        if (!result.created()) {
            LOGGER.info("Duplicate message detected for request_id={}, skipping side effects", message.requestId());
            return;
        }
        HumanPost post = result.post();
        CompletableFuture.runAsync(() -> writeEmbedding(message, post), embeddingTaskExecutor);
        scheduleQualityEvaluation(post);
        publishNodeCreated(post);
        publishReplyEdge(post, message.targetNodeId());
        scheduleRouting(message, post);
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
                    "embedding generated for queued post",
                    message.authorId()
            );
        } catch (Exception exception) {
            LOGGER.error("Failed to generate embedding for Human_Post node_id={}", post.getNodeId(), exception);
            publishOrchestrationStatus(
                    message.requestId(),
                    message.eventId(),
                    post.getNodeId().toString(),
                    "FAILED",
                    "embedding generation failed",
                    message.authorId()
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

    private void publishReplyEdge(HumanPost post, String targetNodeId) {
        if (targetNodeId == null || targetNodeId.isBlank()) {
            return;
        }
        SseEventService.EdgeCreatedPayload payload = new SseEventService.EdgeCreatedPayload(
                post.getNodeId().toString(),
                targetNodeId,
                "CONTINUES_FROM",
                post.getCreatedAt()
        );
        sseEventService.publish(SseEventService.SseEventType.EDGE_CREATED, payload);
    }

    private void publishOrchestrationStatus(
            String requestId,
            String eventId,
            String postNodeId,
            String status,
            String message,
            String authorId
    ) {
        SseEventService.OrchestrationStatusPayload payload = new SseEventService.OrchestrationStatusPayload(
                requestId,
                eventId,
                postNodeId,
                status,
                message,
                null,
                null,
                authorId
        );
        sseEventService.publish(SseEventService.SseEventType.ORCHESTRATION_STATUS, payload);
    }

    private void scheduleQualityEvaluation(HumanPost post) {
        if (!qualityEnabled || qualityAgentService == null) {
            return;
        }
        String content = post.getContent();
        if (content == null || content.length() < qualityMinContentLength) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                QualityScore score = qualityAgentService.evaluate(new QualityEvaluationCommand(
                        post.getNodeId(), content, "", ""));
                SseEventService.QualityScoredPayload payload = new SseEventService.QualityScoredPayload(
                        post.getNodeId().toString(),
                        score.overall(),
                        score.relevance(),
                        score.density(),
                        score.argumentation(),
                        score.communityValue(),
                        score.reason()
                );
                sseEventService.publish(SseEventService.SseEventType.QUALITY_SCORED, payload);
            } catch (Exception e) {
                LOGGER.error("Quality evaluation failed for post node_id={}", post.getNodeId(), e);
            }
        }, embeddingTaskExecutor);
    }

    private void scheduleRouting(PostEventMessage message, HumanPost post) {
        if (aiRoutingOrchestratorService == null) {
            return;
        }
        CompletableFuture.runAsync(() -> aiRoutingOrchestratorService.orchestrate(message, post), routingTaskExecutor)
                .exceptionally(exception -> {
                    LOGGER.error("AI routing failed for post node_id={}", post.getNodeId(), exception);
                    publishOrchestrationStatus(
                            message.requestId(),
                            message.eventId(),
                            post.getNodeId().toString(),
                            "FAILED",
                            "ai routing failed: " + exception.getMessage(),
                            message.authorId()
                    );
                    return null;
                });
    }
}
