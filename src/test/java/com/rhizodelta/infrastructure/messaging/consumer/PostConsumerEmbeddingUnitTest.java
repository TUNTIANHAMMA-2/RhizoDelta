package com.rhizodelta.infrastructure.messaging.consumer;

import com.rhizodelta.ai.context.domain.embedding.EmbeddingWriteResult;
import com.rhizodelta.core.domain.node.HumanPost;
import com.rhizodelta.infrastructure.messaging.message.PostEventMessage;
import com.rhizodelta.ai.shared.service.EmbeddingModelService;
import com.rhizodelta.ai.context.service.EmbeddingService;
import com.rhizodelta.core.service.PostService;
import com.rhizodelta.infrastructure.sse.service.SseEventService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PostConsumerEmbeddingUnitTest {
    private static final int EMBEDDING_DIMENSION = 3;
    private static final float[] VALID_VECTOR = new float[] {0.1f, 0.2f, 0.3f};
    private static final float[] INVALID_VECTOR = new float[] {0.1f, 0.2f};
    private static final long ASYNC_TIMEOUT_MS = 1000L;

    @Test
    void shouldGenerateEmbeddingAndWriteForHumanPost() throws Exception {
        UUID nodeId = UUID.randomUUID();
        HumanPost post = HumanPost.create(nodeId, "content-1", "author-1", "req-1");
        CountDownLatch latch = new CountDownLatch(1);
        RecordingEmbeddingService embeddingService = new RecordingEmbeddingService(latch);
        RecordingSseEventService sseEventService = new RecordingSseEventService();
        EmbeddingModelService embeddingModelService = new EmbeddingModelService(
                new FixedEmbeddingModel(VALID_VECTOR),
                EMBEDDING_DIMENSION
        );
        PostConsumer consumer = new PostConsumer(
                new StubPostService(post),
                embeddingModelService,
                embeddingService,
                sseEventService
        );

        invokeProcessMessage(consumer, new PostEventMessage("req-1", "author-1", "content-1", null, "evt-1"));

        assertThat(latch.await(ASYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(embeddingService.nodeId).isEqualTo(nodeId.toString());
        assertThat(embeddingService.vector).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(sseEventService.findStatus("EMBEDDING_READY")).isNotNull();
        assertThat(sseEventService.findStatus("EMBEDDING_READY").postNodeId()).isEqualTo(nodeId.toString());
        assertThat(sseEventService.findStatus("EMBEDDING_READY").eventId()).isEqualTo("evt-1");
    }

    @Test
    void shouldSkipWriteWhenDimensionMismatch() throws Exception {
        UUID nodeId = UUID.randomUUID();
        HumanPost post = HumanPost.create(nodeId, "content-2", "author-2", "req-2");
        CountDownLatch latch = new CountDownLatch(1);
        RecordingEmbeddingService embeddingService = new RecordingEmbeddingService(latch);
        RecordingSseEventService sseEventService = new RecordingSseEventService();
        EmbeddingModelService embeddingModelService = new EmbeddingModelService(
                new FixedEmbeddingModel(INVALID_VECTOR),
                EMBEDDING_DIMENSION
        );
        PostConsumer consumer = new PostConsumer(
                new StubPostService(post),
                embeddingModelService,
                embeddingService,
                sseEventService
        );

        invokeProcessMessage(consumer, new PostEventMessage("req-2", "author-2", "content-2", null, "evt-2"));

        assertThat(latch.await(ASYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(embeddingService.nodeId).isNull();
        assertThat(sseEventService.findStatus("FAILED")).isNotNull();
        assertThat(sseEventService.findStatus("FAILED").postNodeId()).isEqualTo(nodeId.toString());
        assertThat(sseEventService.findStatus("FAILED").eventId()).isEqualTo("evt-2");
    }

    private static void invokeProcessMessage(PostConsumer consumer, PostEventMessage message) throws Exception {
        Method method = PostConsumer.class.getDeclaredMethod("processMessage", PostEventMessage.class);
        method.setAccessible(true);
        method.invoke(consumer, message);
    }

    private static final class FixedEmbeddingModel implements EmbeddingModel {
        private final float[] vector;

        private FixedEmbeddingModel(float[] vector) {
            this.vector = Objects.requireNonNull(vector, "vector must not be null");
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            List<Embedding> embeddings = textSegments.stream()
                    .map(segment -> Embedding.from(vector))
                    .toList();
            return Response.from(embeddings);
        }
    }

    private static final class RecordingEmbeddingService extends EmbeddingService {
        private final CountDownLatch latch;
        private volatile String nodeId;
        private volatile List<Float> vector;

        private RecordingEmbeddingService(CountDownLatch latch) {
            super(null, EMBEDDING_DIMENSION);
            this.latch = latch;
        }

        @Override
        public EmbeddingWriteResult writeEmbedding(String nodeId, List<Float> vector) {
            this.nodeId = nodeId;
            this.vector = List.copyOf(vector);
            latch.countDown();
            return new EmbeddingWriteResult(UUID.fromString(nodeId), vector.size());
        }
    }

    private static final class StubPostService extends PostService {
        private final HumanPost post;

        private StubPostService(HumanPost post) {
            super(null, null);
            this.post = Objects.requireNonNull(post, "post must not be null");
        }

        @Override
        public CreateHumanPostResult createHumanPost(CreateHumanPostCommand command) {
            return new CreateHumanPostResult(post, true);
        }
    }

    private static final class RecordingSseEventService extends SseEventService {
        private final List<OrchestrationStatusPayload> statuses = new ArrayList<>();

        private RecordingSseEventService() {
            super(mock(RabbitTemplate.class));
        }

        @Override
        public void publish(SseEventType type, Object payload) {
            if (type == SseEventType.ORCHESTRATION_STATUS && payload instanceof OrchestrationStatusPayload statusPayload) {
                statuses.add(statusPayload);
            }
        }

        private OrchestrationStatusPayload findStatus(String status) {
            return statuses.stream()
                    .filter(payload -> status.equals(payload.status()))
                    .findFirst()
                    .orElse(null);
        }
    }
}
