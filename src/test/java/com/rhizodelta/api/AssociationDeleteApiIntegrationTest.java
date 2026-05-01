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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class AssociationDeleteApiIntegrationTest {
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
    void shouldDeleteAssociationAndExcludeFromSubsequentQuery() {
        UUID sourceNodeId = UUID.randomUUID();
        UUID targetNodeId = UUID.randomUUID();
        createHumanPostNode(sourceNodeId, "req-source", "author-source", "source");
        createHumanPostNode(targetNodeId, "req-target", "author-target", "target");
        String associationId = createAssociation(sourceNodeId, targetNodeId, "RELATES_TO");

        ResponseEntity<Map> deleteResponse = restTemplate.exchange(
                "/api/associations/" + associationId,
                org.springframework.http.HttpMethod.DELETE,
                null,
                Map.class
        );

        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> deleteData = bodyData(deleteResponse);
        assertThat(deleteData.get("association_id")).isEqualTo(associationId);
        assertThat(deleteData.get("deleted")).isEqualTo(true);

        Map<String, Object> dbRecord = neo4jClient.query("""
                MATCH ()-[rel]->()
                WHERE rel.association_id = $associationId
                RETURN count(rel) AS relCount
                """)
                .bind(associationId).to("associationId")
                .fetch().all().iterator().next();
        assertThat(((Number) dbRecord.get("relCount")).longValue()).isEqualTo(0L);

        ResponseEntity<Map> queryResponse = restTemplate.getForEntity(
                "/api/nodes/" + sourceNodeId + "/associations",
                Map.class
        );
        assertThat(queryResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> associations = (List<Map<String, Object>>) queryResponse.getBody().get("data");
        assertThat(associations).isEmpty();
    }

    @Test
    void shouldReturnNotFoundWhenDeletingUnknownAssociationId() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/associations/" + UUID.randomUUID(),
                org.springframework.http.HttpMethod.DELETE,
                null,
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo(40401);
    }

    private String createAssociation(UUID sourceNodeId, UUID targetNodeId, String type) {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/associations",
                associationRequest(sourceNodeId, targetNodeId, type),
                Map.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) bodyData(response).get("association_id");
    }

    private Map<String, Object> associationRequest(UUID sourceNodeId, UUID targetNodeId, String type) {
        Map<String, Object> request = new HashMap<>();
        request.put("source_node_id", sourceNodeId.toString());
        request.put("target_node_id", targetNodeId.toString());
        request.put("type", type);
        request.put("creator_id", "creator-1");
        request.put("reason", "delete test");
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
