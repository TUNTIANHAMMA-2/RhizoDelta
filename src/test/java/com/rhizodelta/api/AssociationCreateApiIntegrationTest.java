package com.rhizodelta.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.neo4j.core.Neo4jClient;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class AssociationCreateApiIntegrationTest {
    @Container
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5")
            .withAdminPassword("testpassword");

    @DynamicPropertySource
    static void neo4jProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.neo4j.uri", neo4j::getBoltUrl);
        registry.add("spring.neo4j.authentication.username", () -> "neo4j");
        registry.add("spring.neo4j.authentication.password", neo4j::getAdminPassword);
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
    void shouldCreateConceptualOverlapWithMetadata() {
        UUID sourceNodeId = UUID.randomUUID();
        UUID targetNodeId = UUID.randomUUID();
        createHumanPostNode(sourceNodeId, "req-source", "author-a", "source content");
        createHumanPostNode(targetNodeId, "req-target", "author-b", "target content");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/associations",
                associationRequest(sourceNodeId, targetNodeId, "CONCEPTUAL_OVERLAP", 0.85f),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> data = bodyData(response);
        String associationId = (String) data.get("association_id");
        assertThat(data.get("type")).isEqualTo("CONCEPTUAL_OVERLAP");

        Map<String, Object> record = neo4jClient.query("""
                MATCH (:GraphNode {node_id: $sourceNodeId})-[rel:CONCEPTUAL_OVERLAP]->(:GraphNode {node_id: $targetNodeId})
                RETURN rel.association_id AS associationId,
                       rel.creator_id AS creatorId,
                       rel.reason AS reason,
                       rel.confidence AS confidence
                """)
                .bind(sourceNodeId.toString()).to("sourceNodeId")
                .bind(targetNodeId.toString()).to("targetNodeId")
                .fetch().all().iterator().next();

        assertThat(record.get("associationId")).isEqualTo(associationId);
        assertThat(record.get("creatorId")).isEqualTo("test-operator");
        assertThat(record.get("reason")).isEqualTo("semantic overlap");
        assertThat(((Number) record.get("confidence")).doubleValue()).isEqualTo(0.85d);
    }

    @Test
    void shouldCreateRelatesToBetweenHumanPosts() {
        UUID sourceNodeId = UUID.randomUUID();
        UUID targetNodeId = UUID.randomUUID();
        createHumanPostNode(sourceNodeId, "req-a", "author-a", "a");
        createHumanPostNode(targetNodeId, "req-b", "author-b", "b");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/associations",
                associationRequest(sourceNodeId, targetNodeId, "RELATES_TO", null),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> record = neo4jClient.query("""
                MATCH (:Human_Post {node_id: $sourceNodeId})-[rel:RELATES_TO]->(:Human_Post {node_id: $targetNodeId})
                RETURN count(rel) AS relCount
                """)
                .bind(sourceNodeId.toString()).to("sourceNodeId")
                .bind(targetNodeId.toString()).to("targetNodeId")
                .fetch().all().iterator().next();
        assertThat(((Number) record.get("relCount")).longValue()).isEqualTo(1L);
    }

    @Test
    void shouldReturnExistingAssociationForDuplicateSourceTargetType() {
        UUID sourceNodeId = UUID.randomUUID();
        UUID targetNodeId = UUID.randomUUID();
        createHumanPostNode(sourceNodeId, "req-a", "author-a", "a");
        createHumanPostNode(targetNodeId, "req-b", "author-b", "b");
        Map<String, Object> request = associationRequest(sourceNodeId, targetNodeId, "CONCEPTUAL_OVERLAP", null);

        ResponseEntity<Map> first = restTemplate.postForEntity("/api/associations", request, Map.class);
        ResponseEntity<Map> second = restTemplate.postForEntity("/api/associations", request, Map.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bodyData(first).get("association_id")).isEqualTo(bodyData(second).get("association_id"));

        Map<String, Object> record = neo4jClient.query("""
                MATCH (:GraphNode {node_id: $sourceNodeId})-[rel:CONCEPTUAL_OVERLAP]->(:GraphNode {node_id: $targetNodeId})
                RETURN count(rel) AS relCount
                """)
                .bind(sourceNodeId.toString()).to("sourceNodeId")
                .bind(targetNodeId.toString()).to("targetNodeId")
                .fetch().all().iterator().next();
        assertThat(((Number) record.get("relCount")).longValue()).isEqualTo(1L);
    }

    private Map<String, Object> associationRequest(UUID sourceNodeId, UUID targetNodeId, String type, Float confidence) {
        Map<String, Object> request = new HashMap<>();
        request.put("source_node_id", sourceNodeId.toString());
        request.put("target_node_id", targetNodeId.toString());
        request.put("type", type);
        request.put("creator_id", "creator-1");
        request.put("reason", "semantic overlap");
        if (confidence != null) {
            request.put("confidence", confidence);
        }
        return request;
    }

    private Map<String, Object> bodyData(ResponseEntity<Map> response) {
        assertThat(response.getBody()).isNotNull();
        return (Map<String, Object>) response.getBody().get("data");
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

    private static OffsetDateTime nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
