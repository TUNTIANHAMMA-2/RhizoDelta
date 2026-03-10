package com.rhizodelta.service;

import com.rhizodelta.domain.embedding.EmbeddingWriteResult;
import com.rhizodelta.domain.embedding.SimilaritySearchResult;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingServiceUnitTest {
    private static final int EMBEDDING_DIMENSION = 3;
    private static final int DEFAULT_TOP_K = 10;
    private static final int MAX_TOP_K = 50;
    private static final List<Float> VALID_VECTOR = List.of(0.1f, 0.2f, 0.3f);

    @Test
    void writeEmbeddingShouldUpdateNodeEmbedding() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        UUID nodeId = UUID.randomUUID();
        when(neo4jClient.query(anyString())
                .bindAll(anyMap())
                .fetch()
                .one())
                .thenReturn(Optional.of(Map.of("nodeId", nodeId.toString())));
        EmbeddingService service = new EmbeddingService(neo4jClient, EMBEDDING_DIMENSION);

        EmbeddingWriteResult result = service.writeEmbedding(nodeId.toString(), VALID_VECTOR);

        assertThat(result.node_id()).isEqualTo(nodeId);
        assertThat(result.dimension()).isEqualTo(EMBEDDING_DIMENSION);
        verify(neo4jClient).query(argThat((String query) -> query != null && query.contains("SET node.embedding")));
        verify(neo4jClient.query(anyString())).bindAll(argThat(params ->
                nodeId.toString().equals(params.get("nodeId"))
                        && VALID_VECTOR.equals(params.get("embedding"))
        ));
    }

    @Test
    void writeEmbeddingShouldThrowWhenNodeMissing() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString()).bindAll(anyMap()).fetch().one())
                .thenReturn(Optional.empty());
        EmbeddingService service = new EmbeddingService(neo4jClient, EMBEDDING_DIMENSION);

        assertThatThrownBy(() -> service.writeEmbedding(UUID.randomUUID().toString(), VALID_VECTOR))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("node not found");
    }

    @Test
    void writeEmbeddingShouldRejectDimensionMismatch() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        EmbeddingService service = new EmbeddingService(neo4jClient, EMBEDDING_DIMENSION);

        assertThatThrownBy(() -> service.writeEmbedding(UUID.randomUUID().toString(), List.of(0.1f, 0.2f)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected 3")
                .hasMessageContaining("actual 2");
    }

    @Test
    void writeEmbeddingShouldRejectEmptyVector() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        EmbeddingService service = new EmbeddingService(neo4jClient, EMBEDDING_DIMENSION);

        assertThatThrownBy(() -> service.writeEmbedding(UUID.randomUUID().toString(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("vector must not be empty");
    }

    @Test
    void searchSimilarShouldDefaultTopKAndMapResults() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        UUID nodeId = UUID.randomUUID();
        UUID neighborId = UUID.randomUUID();
        when(neo4jClient.query(anyString())
                .bindAll(anyMap())
                .fetch()
                .all())
                .thenReturn(List.of(buildSearchRecord(nodeId, neighborId)));
        EmbeddingService service = new EmbeddingService(neo4jClient, EMBEDDING_DIMENSION);

        List<SimilaritySearchResult> results = service.searchSimilar(VALID_VECTOR, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).neighbors()).hasSize(1);
        verify(neo4jClient).query(argThat((String query) -> query != null && query.contains("db.index.vector.queryNodes")));
        verify(neo4jClient.query(anyString())).bindAll(argThat(params ->
                Integer.valueOf(DEFAULT_TOP_K).equals(params.get("topK"))
                        && VALID_VECTOR.equals(params.get("vector"))
        ));
    }

    @Test
    void searchSimilarShouldCapTopK() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString()).bindAll(anyMap()).fetch().all())
                .thenReturn(List.of());
        EmbeddingService service = new EmbeddingService(neo4jClient, EMBEDDING_DIMENSION);

        service.searchSimilar(VALID_VECTOR, 500);

        verify(neo4jClient.query(anyString())).bindAll(argThat(params ->
                Integer.valueOf(MAX_TOP_K).equals(params.get("topK"))
        ));
    }

    @Test
    void searchSimilarShouldRejectNonPositiveTopK() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        EmbeddingService service = new EmbeddingService(neo4jClient, EMBEDDING_DIMENSION);

        assertThatThrownBy(() -> service.searchSimilar(VALID_VECTOR, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("top_k must be greater than 0");
    }

    @Test
    void searchSimilarShouldRejectDimensionMismatch() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        EmbeddingService service = new EmbeddingService(neo4jClient, EMBEDDING_DIMENSION);

        assertThatThrownBy(() -> service.searchSimilar(List.of(0.1f, 0.2f), 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected 3")
                .hasMessageContaining("actual 2");
    }

    @Test
    void writeEmbeddingShouldRejectNullVectorElement() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        EmbeddingService service = new EmbeddingService(neo4jClient, EMBEDDING_DIMENSION);
        List<Float> vectorWithNull = new ArrayList<>(Arrays.asList(0.1f, null, 0.3f));

        assertThatThrownBy(() -> service.writeEmbedding(UUID.randomUUID().toString(), vectorWithNull))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("vector must not contain null elements");
    }

    @Test
    void writeEmbeddingShouldRejectNaNVectorElement() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        EmbeddingService service = new EmbeddingService(neo4jClient, EMBEDDING_DIMENSION);

        assertThatThrownBy(() -> service.writeEmbedding(UUID.randomUUID().toString(), List.of(0.1f, Float.NaN, 0.3f)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("vector must not contain NaN or infinite values");
    }

    @Test
    void writeEmbeddingShouldRejectInfiniteVectorElement() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        EmbeddingService service = new EmbeddingService(neo4jClient, EMBEDDING_DIMENSION);

        assertThatThrownBy(() -> service.writeEmbedding(UUID.randomUUID().toString(), List.of(0.1f, Float.POSITIVE_INFINITY, 0.3f)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("vector must not contain NaN or infinite values");
    }

    private static Map<String, Object> buildSearchRecord(UUID nodeId, UUID neighborId) {
        return Map.of(
                "nodeId", nodeId.toString(),
                "label", "Human_Post",
                "score", 0.92d,
                "content", "sample content",
                "createdAt", Instant.parse("2026-02-01T00:00:00Z"),
                "neighbors", List.of(Map.of(
                        "nodeId", neighborId.toString(),
                        "label", "AI_Consensus",
                        "relationshipType", "MERGED_INTO"
                ))
        );
    }
}
