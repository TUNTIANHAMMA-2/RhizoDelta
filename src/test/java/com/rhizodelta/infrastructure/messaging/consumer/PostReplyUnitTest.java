package com.rhizodelta.infrastructure.messaging.consumer;

import com.rhizodelta.ai.context.domain.embedding.EmbeddingWriteResult;
import com.rhizodelta.core.domain.node.HumanPost;
import com.rhizodelta.infrastructure.messaging.message.PostEventMessage;
import com.rhizodelta.core.repository.HumanPostRepository;
import com.rhizodelta.ai.shared.service.EmbeddingModelService;
import com.rhizodelta.ai.context.service.EmbeddingService;
import com.rhizodelta.core.service.PostService;
import com.rhizodelta.infrastructure.sse.service.SseEventService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostReplyUnitTest {
    private static final int EMBEDDING_DIMENSION = 3;
    private static final float[] VECTOR = new float[] {0.1f, 0.2f, 0.3f};

    @Test
    void postServiceShouldCreateContinueRelationshipForReplyPost() {
        Neo4jClient deepStubClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        HumanPostRepository humanPostRepository = mock(HumanPostRepository.class);
        PostService postService = new PostService(deepStubClient, humanPostRepository);

        String requestId = "req-reply-1";
        String targetNodeId = UUID.randomUUID().toString();
        UUID persistedNodeId = UUID.randomUUID();
        HumanPost persisted = HumanPost.create(persistedNodeId, "reply content", "user-1", requestId);

        when(deepStubClient.query(argThat((String query) -> query != null && query.contains("RETURN toString(post.node_id)")) )
                .bind(eq(requestId)).to(eq("requestId"))
                .fetchAs(String.class)
                .one()).thenReturn(Optional.empty());
        when(deepStubClient.query(argThat((String query) -> query != null && query.contains("MATCH (user:UserAccount")) )
                .bind(eq("user-1")).to(eq("authorId"))
                .fetch()
                .one()).thenReturn(Optional.of(Map.of("exists", true)));
        when(deepStubClient.query(argThat((String query) -> query != null && query.contains("MATCH (node:GraphNode")) )
                .bind(eq(targetNodeId)).to(eq("targetNodeId"))
                .fetch()
                .one()).thenReturn(Optional.of(Map.of("exists", true)));
        when(deepStubClient.query(argThat((String query) -> query != null && query.contains("MERGE (post:Human_Post")) )
                .bind(eq(requestId)).to(eq("requestId"))
                .bind(any()).to(eq("nodeId"))
                .bind(eq("reply content")).to(eq("content"))
                .bind(eq("user-1")).to(eq("authorId"))
                .bind(eq(targetNodeId)).to(eq("targetNodeId"))
                .bind(any()).to(eq("createdAt"))
                .fetchAs(String.class)
                .one()).thenReturn(Optional.of(persistedNodeId.toString()));
        when(deepStubClient.query(argThat((String query) -> query != null && query.contains("MERGE (author)-[rel:AUTHORED]")) )
                .bind(eq(persistedNodeId.toString())).to(eq("postNodeId"))
                .bind(eq("user-1")).to(eq("authorId"))
                .bind(any()).to(eq("createdAt"))
                .fetch()
                .one()).thenReturn(Optional.of(Map.of("relType", "AUTHORED")));
        when(deepStubClient.query(argThat((String query) -> query != null && query.contains("CONTINUES_FROM")) )
                .bind(eq(persistedNodeId.toString())).to(eq("postNodeId"))
                .bind(eq(targetNodeId)).to(eq("targetNodeId"))
                .bind(eq("HUMAN")).to(eq("operatorType"))
                .bind(eq("user-1")).to(eq("operatorId"))
                .bind(any()).to(eq("createdAt"))
                .bind(eq("user reply")).to(eq("reason"))
                .fetch()
                .one()).thenReturn(Optional.of(Map.of("relType", "CONTINUES_FROM")));
        when(humanPostRepository.findByNodeId(persistedNodeId)).thenReturn(Optional.of(persisted));

        HumanPost result = postService.createHumanPost(
                new PostService.CreateHumanPostCommand(requestId, "user-1", "reply content", targetNodeId)
        ).post();

        assertThat(result.getNodeId()).isEqualTo(persistedNodeId);
        verify(deepStubClient).query(argThat((String query) -> query != null && query.contains("CONTINUES_FROM")));
    }

    @Test
    void postConsumerShouldPublishEdgeCreatedForReplyPost() throws Exception {
        UUID nodeId = UUID.randomUUID();
        String targetNodeId = UUID.randomUUID().toString();
        HumanPost post = HumanPost.create(nodeId, "reply body", "user-2", "req-reply-2");
        RecordingSseEventService sseEventService = new RecordingSseEventService();
        PostConsumer consumer = new PostConsumer(
                new StubPostService(post),
                new EmbeddingModelService(new FixedEmbeddingModel(VECTOR), EMBEDDING_DIMENSION),
                new NoopEmbeddingService(),
                sseEventService
        );

        invokeProcessMessage(consumer, new PostEventMessage(
                "req-reply-2",
                "user-2",
                "reply body",
                targetNodeId,
                "evt-reply-2"
        ));

        assertThat(sseEventService.findEdge("CONTINUES_FROM")).isNotNull();
        assertThat(sseEventService.findEdge("CONTINUES_FROM").source()).isEqualTo(nodeId.toString());
        assertThat(sseEventService.findEdge("CONTINUES_FROM").target()).isEqualTo(targetNodeId);
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

    private static final class NoopEmbeddingService extends EmbeddingService {
        private NoopEmbeddingService() {
            super(null, EMBEDDING_DIMENSION);
        }

        @Override
        public EmbeddingWriteResult writeEmbedding(String nodeId, List<Float> vector) {
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
        private final List<EdgeCreatedPayload> edges = new ArrayList<>();

        private RecordingSseEventService() {
            super(mock(RabbitTemplate.class));
        }

        @Override
        public void publish(SseEventType type, Object payload) {
            if (type == SseEventType.EDGE_CREATED && payload instanceof EdgeCreatedPayload edgePayload) {
                edges.add(edgePayload);
            }
        }

        private EdgeCreatedPayload findEdge(String type) {
            return edges.stream()
                    .filter(payload -> type.equals(payload.type()))
                    .findFirst()
                    .orElse(null);
        }
    }
}
