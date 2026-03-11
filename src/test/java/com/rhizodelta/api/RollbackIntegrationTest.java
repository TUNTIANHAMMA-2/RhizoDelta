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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RollbackIntegrationTest {
    private static final int EMBEDDING_DIMENSION = 3;

    @Container
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5")
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
    void rollbackShouldRemoveMergeDecisionAndDisappearFromAuditList() {
        UUID sourceNodeId = UUID.randomUUID();
        UUID synthesizedA = UUID.randomUUID();
        createHumanPostNode(sourceNodeId, "req-source", "author-source", "source");
        createHumanPostNode(synthesizedA, "req-s1", "author-a", "s1");

        ResponseEntity<Map> mergeResponse = restTemplate.postForEntity(
                "/api/decisions/merge", mergeRequest("rollback-merge-001", sourceNodeId, List.of(synthesizedA)), Map.class);
        assertThat(mergeResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<Map> rollbackResponse = restTemplate.exchange(
                "/api/decisions/rollback-merge-001/rollback", HttpMethod.POST, HttpEntity.EMPTY, Map.class);
        assertThat(rollbackResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> rollbackData = bodyData(rollbackResponse);
        assertThat(((Number) rollbackData.get("relationships_removed")).longValue()).isGreaterThan(0L);

        Map<String, Object> nodeCount = neo4jClient.query("""
                MATCH (n:GraphNode {decision_id: $decisionId})
                RETURN count(n) AS cnt
                """)
                .bind("rollback-merge-001").to("decisionId")
                .fetch().all().iterator().next();
        assertThat(((Number) nodeCount.get("cnt")).longValue()).isEqualTo(0L);

        ResponseEntity<Map> listResponse = restTemplate.getForEntity("/api/audit/decisions", Map.class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> records = bodyList(listResponse);
        assertThat(records).extracting(row -> row.get("decision_id")).doesNotContain("rollback-merge-001");
    }

    @Test
    void rollbackShouldReturn409WhenDownstreamDependentsExist() {
        UUID rootNodeId = UUID.randomUUID();
        createHumanPostNode(rootNodeId, "req-root", "author-root", "root");

        ResponseEntity<Map> aResponse = restTemplate.postForEntity(
                "/api/decisions/branch", branchRequest("rollback-branch-a", rootNodeId, "A"), Map.class);
        assertThat(aResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID nodeA = UUID.fromString((String) bodyData(aResponse).get("node_id"));

        ResponseEntity<Map> bResponse = restTemplate.postForEntity(
                "/api/decisions/branch", branchRequest("rollback-branch-b", nodeA, "B"), Map.class);
        assertThat(bResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String nodeB = (String) bodyData(bResponse).get("node_id");

        ResponseEntity<Map> rollbackAResponse = restTemplate.exchange(
                "/api/decisions/rollback-branch-a/rollback", HttpMethod.POST, HttpEntity.EMPTY, Map.class);
        assertThat(rollbackAResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rollbackAResponse.getBody().get("code")).isEqualTo(40901);
        Map<String, Object> data = bodyData(rollbackAResponse);
        assertThat((List<String>) data.get("dependent_node_ids")).contains(nodeB);
    }

    @Test
    void rollbackShouldBeIdempotentAndHideNodeFromReads() {
        UUID rootNodeId = UUID.randomUUID();
        createHumanPostNode(rootNodeId, "req-idemp-root", "author-root", "root");

        ResponseEntity<Map> branchResponse = restTemplate.postForEntity(
                "/api/decisions/branch", branchRequest("rollback-idempotent-001", rootNodeId, "idempotent"), Map.class);
        assertThat(branchResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID nodeId = UUID.fromString((String) bodyData(branchResponse).get("node_id"));

        UUID otherNodeId = UUID.randomUUID();
        createHumanPostNode(otherNodeId, "req-idemp-other", "author-other", "other");
        writeEmbedding(nodeId, List.of(1.0f, 0.0f, 0.0f));
        writeEmbedding(otherNodeId, List.of(0.0f, 1.0f, 0.0f));

        ResponseEntity<Map> firstRollback = restTemplate.exchange(
                "/api/decisions/rollback-idempotent-001/rollback", HttpMethod.POST, HttpEntity.EMPTY, Map.class);
        assertThat(firstRollback.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bodyData(firstRollback).get("soft_deleted")).isEqualTo(true);
        Object deletedAt = fetchDeletedAt(nodeId);
        assertThat(deletedAt).isNotNull();

        ResponseEntity<Map> getResponse = restTemplate.getForEntity("/api/nodes/" + nodeId, Map.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> searchResponse = restTemplate.postForEntity(
                "/api/nodes/search/similar", Map.of("vector", List.of(1.0f, 0.0f, 0.0f), "top_k", 5), Map.class);
        assertThat(searchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> results = (List<Map<String, Object>>) searchResponse.getBody().get("data");
        assertThat(results).extracting(row -> row.get("node_id")).doesNotContain(nodeId.toString());

        ResponseEntity<Map> secondRollback = restTemplate.exchange(
                "/api/decisions/rollback-idempotent-001/rollback", HttpMethod.POST, HttpEntity.EMPTY, Map.class);
        assertThat(secondRollback.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bodyData(secondRollback).get("soft_deleted")).isEqualTo(true);
        Object deletedAtAfter = fetchDeletedAt(nodeId);
        assertThat(deletedAtAfter.toString()).isEqualTo(deletedAt.toString());
    }

    @Test
    void rollbackShouldSucceedWhenDependentsAlreadyDeleted() {
        UUID rootNodeId = UUID.randomUUID();
        createHumanPostNode(rootNodeId, "req-success-root", "author-root", "root");

        ResponseEntity<Map> aResponse = restTemplate.postForEntity(
                "/api/decisions/branch", branchRequest("rollback-success-a", rootNodeId, "A"), Map.class);
        assertThat(aResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID nodeA = UUID.fromString((String) bodyData(aResponse).get("node_id"));

        ResponseEntity<Map> bResponse = restTemplate.postForEntity(
                "/api/decisions/branch", branchRequest("rollback-success-b", nodeA, "B"), Map.class);
        assertThat(bResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<Map> rollbackB = restTemplate.exchange(
                "/api/decisions/rollback-success-b/rollback", HttpMethod.POST, HttpEntity.EMPTY, Map.class);
        assertThat(rollbackB.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> rollbackA = restTemplate.exchange(
                "/api/decisions/rollback-success-a/rollback", HttpMethod.POST, HttpEntity.EMPTY, Map.class);
        assertThat(rollbackA.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void rollbackShouldAlsoRemoveSemanticAssociations() {
        UUID sourceNodeId = UUID.randomUUID();
        UUID relatedNodeId = UUID.randomUUID();
        createHumanPostNode(sourceNodeId, "req-source", "author-source", "source");
        createHumanPostNode(relatedNodeId, "req-related", "author-related", "related");

        ResponseEntity<Map> branchResponse = restTemplate.postForEntity(
                "/api/decisions/branch", branchRequest("rollback-semantic-001", sourceNodeId, "semantic branch"), Map.class);
        assertThat(branchResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String decisionNodeId = (String) bodyData(branchResponse).get("node_id");

        ResponseEntity<Map> associationResponse = restTemplate.postForEntity(
                "/api/associations", associationRequest(UUID.fromString(decisionNodeId), relatedNodeId), Map.class);
        assertThat(associationResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String associationId = (String) bodyData(associationResponse).get("association_id");

        ResponseEntity<Map> rollbackResponse = restTemplate.exchange(
                "/api/decisions/rollback-semantic-001/rollback", HttpMethod.POST, HttpEntity.EMPTY, Map.class);
        assertThat(rollbackResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> assocCount = neo4jClient.query("""
                MATCH ()-[rel]->()
                WHERE rel.association_id = $associationId
                RETURN count(rel) AS cnt
                """)
                .bind(associationId).to("associationId")
                .fetch().all().iterator().next();
        assertThat(((Number) assocCount.get("cnt")).longValue()).isEqualTo(0L);
    }

    private Map<String, Object> mergeRequest(String decisionId, UUID sourceNodeId, List<UUID> synthesizedFrom) {
        return Map.of("decision_id", decisionId, "request_id", "req-" + decisionId,
                "source_node_id", sourceNodeId.toString(), "agent_version", "gpt-4.1", "summary_content", "summary",
                "synthesized_from", synthesizedFrom.stream().map(UUID::toString).toList(),
                "operator_type", "AGENT", "operator_id", "agent-1", "reason", "merge");
    }

    private Map<String, Object> branchRequest(String decisionId, UUID sourceNodeId, String content) {
        return Map.of("decision_id", decisionId, "request_id", "req-" + decisionId,
                "source_node_id", sourceNodeId.toString(), "content", content, "author_id", "author-1",
                "operator_type", "HUMAN", "operator_id", "human-1", "reason", "branch");
    }

    private Map<String, Object> associationRequest(UUID sourceNodeId, UUID targetNodeId) {
        return Map.of("source_node_id", sourceNodeId.toString(), "target_node_id", targetNodeId.toString(),
                "type", "RELATES_TO", "creator_id", "creator-1", "reason", "semantic link");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> bodyList(ResponseEntity<Map> response) {
        return (List<Map<String, Object>>) bodyData(response).get("records");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bodyData(ResponseEntity<Map> response) {
        assertThat(response.getBody()).isNotNull();
        return (Map<String, Object>) response.getBody().get("data");
    }

    private void writeEmbedding(UUID nodeId, List<Float> vector) {
        restTemplate.exchange("/api/nodes/" + nodeId + "/embedding",
                HttpMethod.PUT, new HttpEntity<>(Map.of("vector", vector)), Map.class);
    }

    private Object fetchDeletedAt(UUID nodeId) {
        return neo4jClient.query("MATCH (n:GraphNode {node_id: $nodeId}) RETURN n._deleted_at AS deletedAt")
                .bind(nodeId.toString()).to("nodeId").fetch().one().orElseThrow().get("deletedAt");
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
