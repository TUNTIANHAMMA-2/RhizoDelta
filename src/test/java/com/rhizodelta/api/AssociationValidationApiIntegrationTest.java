package com.rhizodelta.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
class AssociationValidationApiIntegrationTest {
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
    void shouldRejectWhenSourceNodeNotFound() {
        UUID targetNodeId = UUID.randomUUID();
        createHumanPostNode(targetNodeId, "req-target", "author-target", "target");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/associations",
                associationRequest(UUID.randomUUID(), targetNodeId, "CONCEPTUAL_OVERLAP", null),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message").toString()).contains("source_node_id not found");
    }

    @Test
    void shouldRejectWhenTargetNodeNotFound() {
        UUID sourceNodeId = UUID.randomUUID();
        createHumanPostNode(sourceNodeId, "req-source", "author-source", "source");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/associations",
                associationRequest(sourceNodeId, UUID.randomUUID(), "CONCEPTUAL_OVERLAP", null),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message").toString()).contains("target_node_id not found");
    }

    @Test
    void shouldRejectSelfAssociation() {
        UUID nodeId = UUID.randomUUID();
        createHumanPostNode(nodeId, "req-node", "author-node", "content");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/associations",
                associationRequest(nodeId, nodeId, "CONCEPTUAL_OVERLAP", null),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message").toString()).contains("must be different");
    }

    @Test
    void shouldRejectInvalidAssociationType() {
        UUID sourceNodeId = UUID.randomUUID();
        UUID targetNodeId = UUID.randomUUID();
        createHumanPostNode(sourceNodeId, "req-a", "author-a", "a");
        createHumanPostNode(targetNodeId, "req-b", "author-b", "b");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/associations",
                associationRequest(sourceNodeId, targetNodeId, "INVALID", null),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo(40001);
    }

    @Test
    void shouldRejectConfidenceOutOfRange() {
        UUID sourceNodeId = UUID.randomUUID();
        UUID targetNodeId = UUID.randomUUID();
        createHumanPostNode(sourceNodeId, "req-a", "author-a", "a");
        createHumanPostNode(targetNodeId, "req-b", "author-b", "b");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/associations",
                associationRequest(sourceNodeId, targetNodeId, "RELATES_TO", 1.5f),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message").toString()).contains("confidence");
    }

    private Map<String, Object> associationRequest(UUID sourceNodeId, UUID targetNodeId, String type, Float confidence) {
        Map<String, Object> request = new HashMap<>();
        request.put("source_node_id", sourceNodeId.toString());
        request.put("target_node_id", targetNodeId.toString());
        request.put("type", type);
        request.put("creator_id", "creator-1");
        request.put("reason", "validation");
        if (confidence != null) {
            request.put("confidence", confidence);
        }
        return request;
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
