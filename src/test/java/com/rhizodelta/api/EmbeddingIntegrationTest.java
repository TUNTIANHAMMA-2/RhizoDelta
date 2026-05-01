package com.rhizodelta.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class EmbeddingIntegrationTest {
    private static final int EMBEDDING_DIMENSION = 3;

    @Container
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5.26")
            .withAdminPassword("testpassword");

    @DynamicPropertySource
    static void neo4jProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.neo4j.uri", neo4j::getBoltUrl);
        registry.add("spring.neo4j.authentication.username", () -> "neo4j");
        registry.add("spring.neo4j.authentication.password", neo4j::getAdminPassword);
        registry.add("rhizodelta.embedding.dimension", () -> EMBEDDING_DIMENSION);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private Neo4jClient neo4jClient;

    @BeforeEach
    void cleanDatabase() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();
    }

    @Test
    void writeEmbeddingShouldExposeHasEmbedding() {
        UUID nodeId = UUID.randomUUID();
        createHumanPostNode(nodeId, "req-emb-1", "author-1", "content-1");

        ResponseEntity<Map> putResponse = restTemplate.exchange(
                "/api/nodes/" + nodeId + "/embedding",
                HttpMethod.PUT,
                new HttpEntity<>(embeddingRequest(List.of(1.0f, 0.0f, 0.0f))),
                Map.class
        );

        assertThat(putResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> putData = bodyData(putResponse);
        assertThat(putData.get("node_id")).isEqualTo(nodeId.toString());
        assertThat(((Number) putData.get("dimension")).intValue()).isEqualTo(EMBEDDING_DIMENSION);

        ResponseEntity<Map> getResponse = restTemplate.getForEntity(
                "/api/nodes/" + nodeId,
                Map.class
        );

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> getData = bodyData(getResponse);
        assertThat(getData.get("has_embedding")).isEqualTo(true);
        assertThat(getData.containsKey("embedding")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchSimilarShouldReturnOrderedResultsAndNeighbors() {
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();
        UUID nodeC = UUID.randomUUID();
        createHumanPostNode(nodeA, "req-a", "author-a", "content-a");
        createHumanPostNode(nodeB, "req-b", "author-b", "content-b");
        createHumanPostNode(nodeC, "req-c", "author-c", "content-c");

        writeEmbedding(nodeA, List.of(1.0f, 0.0f, 0.0f));
        writeEmbedding(nodeB, List.of(0.0f, 1.0f, 0.0f));

        createVersionRelation(nodeA, nodeB, "MERGED_INTO");
        createSemanticRelation(nodeA, nodeC, "CONCEPTUAL_OVERLAP");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/nodes/search/similar",
                searchRequest(List.of(1.0f, 0.0f, 0.0f), 5),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> results = bodyList(response);
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).get("node_id")).isEqualTo(nodeA.toString());

        if (results.size() > 1) {
            double firstScore = ((Number) results.get(0).get("score")).doubleValue();
            double secondScore = ((Number) results.get(1).get("score")).doubleValue();
            assertThat(firstScore).isGreaterThanOrEqualTo(secondScore);
        }

        List<Map<String, Object>> neighbors = (List<Map<String, Object>>) results.get(0).get("neighbors");
        assertThat(neighbors).extracting(item -> item.get("node_id"))
                .contains(nodeB.toString());
        assertThat(neighbors).extracting(item -> item.get("relationship_type"))
                .doesNotContain("CONCEPTUAL_OVERLAP", "RELATES_TO");
    }

    @Test
    void searchSimilarShouldReturnEmptyListWhenNoEmbeddings() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/nodes/search/similar",
                searchRequest(List.of(1.0f, 0.0f, 0.0f), null),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> results = bodyList(response);
        assertThat(results).isEmpty();
    }

    private void writeEmbedding(UUID nodeId, List<Float> vector) {
        restTemplate.exchange(
                "/api/nodes/" + nodeId + "/embedding",
                HttpMethod.PUT,
                new HttpEntity<>(embeddingRequest(vector)),
                Map.class
        );
    }

    private void createHumanPostNode(UUID nodeId, String requestId, String authorId, String content) {
        neo4jClient.query("""
                CREATE (:Human_Post:GraphNode {
                  node_id: $nodeId,
                  request_id: $requestId,
                  author_id: $authorId,
                  content: $content,
                  created_at: $createdAt
                })
                """)
                .bind(nodeId.toString()).to("nodeId")
                .bind(requestId).to("requestId")
                .bind(authorId).to("authorId")
                .bind(content).to("content")
                .bind(nowUtc()).to("createdAt")
                .run();
    }

    // test-only: relationship types cannot be parameterized in Cypher
    private void createVersionRelation(UUID sourceNodeId, UUID targetNodeId, String type) {
        neo4jClient.query("""
                MATCH (source:GraphNode {node_id: $sourceNodeId}), (target:GraphNode {node_id: $targetNodeId})
                CREATE (source)-[rel:%s {created_at: $createdAt}]->(target)
                RETURN type(rel) AS relType
                """.formatted(type))
                .bind(sourceNodeId.toString()).to("sourceNodeId")
                .bind(targetNodeId.toString()).to("targetNodeId")
                .bind(nowUtc()).to("createdAt")
                .fetch().all();
    }

    // test-only: relationship types cannot be parameterized in Cypher
    private void createSemanticRelation(UUID sourceNodeId, UUID targetNodeId, String type) {
        neo4jClient.query("""
                MATCH (source:GraphNode {node_id: $sourceNodeId}), (target:GraphNode {node_id: $targetNodeId})
                CREATE (source)-[rel:%s {association_id: $associationId}]->(target)
                RETURN type(rel) AS relType
                """.formatted(type))
                .bind(sourceNodeId.toString()).to("sourceNodeId")
                .bind(targetNodeId.toString()).to("targetNodeId")
                .bind("assoc-" + sourceNodeId).to("associationId")
                .fetch().all();
    }

    private static Map<String, Object> embeddingRequest(List<Float> vector) {
        Map<String, Object> request = new HashMap<>();
        request.put("vector", vector);
        return request;
    }

    private static Map<String, Object> searchRequest(List<Float> vector, Integer topK) {
        Map<String, Object> request = new HashMap<>();
        request.put("vector", vector);
        if (topK != null) {
            request.put("top_k", topK);
        }
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyData(ResponseEntity<Map> response) {
        assertThat(response.getBody()).isNotNull();
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> bodyList(ResponseEntity<Map> response) {
        assertThat(response.getBody()).isNotNull();
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    private static OffsetDateTime nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
