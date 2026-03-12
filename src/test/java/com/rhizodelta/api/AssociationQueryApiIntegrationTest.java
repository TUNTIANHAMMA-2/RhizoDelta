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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AssociationQueryApiIntegrationTest {
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
    void shouldReturnBothIncomingAndOutgoingAssociationsWithDirection() {
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();
        UUID nodeC = UUID.randomUUID();
        createHumanPostNode(nodeA, "req-a", "author-a", "A");
        createHumanPostNode(nodeB, "req-b", "author-b", "B");
        createHumanPostNode(nodeC, "req-c", "author-c", "C");
        createAssociation(nodeA, nodeB, "CONCEPTUAL_OVERLAP");
        createAssociation(nodeC, nodeA, "RELATES_TO");

        ResponseEntity<Map> response = restTemplate.getForEntity("/api/nodes/" + nodeA + "/associations", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.getBody().get("data");
        assertThat(data).hasSize(2);
        assertThat(data).extracting(item -> item.get("direction"))
                .containsExactlyInAnyOrder("OUTGOING", "INCOMING");
        assertThat(data).extracting(item -> ((Map<String, Object>) item.get("related_node")).get("label"))
                .contains("Human_Post");
    }

    @Test
    void shouldApplyTypeFilterWhenQueryingAssociations() {
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();
        UUID nodeC = UUID.randomUUID();
        createHumanPostNode(nodeA, "req-a", "author-a", "A");
        createHumanPostNode(nodeB, "req-b", "author-b", "B");
        createHumanPostNode(nodeC, "req-c", "author-c", "C");
        createAssociation(nodeA, nodeB, "CONCEPTUAL_OVERLAP");
        createAssociation(nodeC, nodeA, "RELATES_TO");

        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/api/nodes/" + nodeA + "/associations?type=CONCEPTUAL_OVERLAP",
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.getBody().get("data");
        assertThat(data).hasSize(1);
        assertThat(data.get(0).get("type")).isEqualTo("CONCEPTUAL_OVERLAP");
        assertThat(data.get(0).get("direction")).isEqualTo("OUTGOING");
    }

    private void createAssociation(UUID sourceNodeId, UUID targetNodeId, String type) {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/associations",
                associationRequest(sourceNodeId, targetNodeId, type),
                Map.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private Map<String, Object> associationRequest(UUID sourceNodeId, UUID targetNodeId, String type) {
        Map<String, Object> request = new HashMap<>();
        request.put("source_node_id", sourceNodeId.toString());
        request.put("target_node_id", targetNodeId.toString());
        request.put("type", type);
        request.put("creator_id", "creator-1");
        request.put("reason", "query test");
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
