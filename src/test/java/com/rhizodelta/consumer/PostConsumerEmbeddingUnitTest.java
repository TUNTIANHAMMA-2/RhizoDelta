package com.rhizodelta.consumer;

import com.rhizodelta.domain.embedding.EmbeddingWriteResult;
import com.rhizodelta.domain.node.HumanPost;
import com.rhizodelta.domain.post.PostEventMessage;
import com.rhizodelta.service.EmbeddingModelService;
import com.rhizodelta.service.EmbeddingService;
import com.rhizodelta.service.PostService;
import com.rhizodelta.service.SseEventService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.lang.reflect.Method;
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
        EmbeddingModelService embeddingModelService = new EmbeddingModelService(
                new FixedEmbeddingModel(VALID_VECTOR),
                EMBEDDING_DIMENSION
        );
        PostConsumer consumer = new PostConsumer(
                new StubPostService(post),
                embeddingModelService,
                embeddingService,
                new SseEventService(mock(RabbitTemplate.class))
        );

        invokeProcessMessage(consumer, new PostEventMessage("req-1", "author-1", "content-1", null, "evt-1"));

        assertThat(latch.await(ASYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(embeddingService.nodeId).isEqualTo(nodeId.toString());
        assertThat(embeddingService.vector).containsExactly(0.1f, 0.2f, 0.3f);
    }

    @Test
    void shouldSkipWriteWhenDimensionMismatch() throws Exception {
        UUID nodeId = UUID.randomUUID();
        HumanPost post = HumanPost.create(nodeId, "content-2", "author-2", "req-2");
        CountDownLatch latch = new CountDownLatch(1);
        RecordingEmbeddingService embeddingService = new RecordingEmbeddingService(latch);
        EmbeddingModelService embeddingModelService = new EmbeddingModelService(
                new FixedEmbeddingModel(INVALID_VECTOR),
                EMBEDDING_DIMENSION
        );
        PostConsumer consumer = new PostConsumer(
                new StubPostService(post),
                embeddingModelService,
                embeddingService,
                new SseEventService(mock(RabbitTemplate.class))
        );

        invokeProcessMessage(consumer, new PostEventMessage("req-2", "author-2", "content-2", null, "evt-2"));

        assertThat(latch.await(ASYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(embeddingService.nodeId).isNull();
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
        public HumanPost createHumanPost(CreateHumanPostCommand command) {
            return post;
        }
    }
}
