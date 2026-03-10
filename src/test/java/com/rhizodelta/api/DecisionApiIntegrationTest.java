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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DecisionApiIntegrationTest {
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
    void shouldCreateMergeDecisionWithAuditRelationships() {
        UUID sourceNodeId = UUID.randomUUID();
        UUID synthesizedA = UUID.randomUUID();
        UUID synthesizedB = UUID.randomUUID();
        createHumanPostNode(sourceNodeId, "req-source", "author-source", "source content", null);
        createHumanPostNode(synthesizedA, "req-s1", "author-a", "s1", null);
        createHumanPostNode(synthesizedB, "req-s2", "author-b", "s2", null);

        String decisionId = "merge-int-001";
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/decisions/merge",
                mergeRequest(decisionId, sourceNodeId, List.of(synthesizedA, synthesizedB)),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Map<String, Object> data = bodyData(response);
        assertThat(data.get("status")).isEqualTo("QUEUED");

        Collection<Map<String, Object>> mergedInto = neo4jClient.query("""
                MATCH (decision:AI_Consensus {decision_id: $decisionId})-[rel:MERGED_INTO]->(source:GraphNode {node_id: $sourceNodeId})
                RETURN count(rel) AS mergedCount,
                       collect(rel.operator_type) AS operatorTypes,
                       collect(rel.operator_id) AS operatorIds
                """)
                .bind(decisionId).to("decisionId")
                .bind(sourceNodeId.toString()).to("sourceNodeId")
                .fetch().all();

        Collection<Map<String, Object>> synthesized = neo4jClient.query("""
                MATCH (decision:AI_Consensus {decision_id: $decisionId})-[rel:SYNTHESIZED_FROM]->(source:Human_Post)
                RETURN count(rel) AS synthesizedCount,
                       collect(rel.operator_type) AS operatorTypes,
                       collect(toString(source.node_id)) AS sourceIds
                """)
                .bind(decisionId).to("decisionId")
                .fetch().all();

        Map<String, Object> mergedRecord = mergedInto.iterator().next();
        Map<String, Object> synthesizedRecord = synthesized.iterator().next();
        assertThat(asLong(mergedRecord.get("mergedCount"))).isEqualTo(1L);
        assertThat((List<String>) mergedRecord.get("operatorTypes")).contains("AGENT");
        assertThat((List<String>) mergedRecord.get("operatorIds")).contains("agent-1");
        assertThat(asLong(synthesizedRecord.get("synthesizedCount"))).isEqualTo(2L);
        assertThat((List<String>) synthesizedRecord.get("sourceIds"))
                .containsExactlyInAnyOrder(synthesizedA.toString(), synthesizedB.toString());
    }

    @Test
    void shouldBeIdempotentForDuplicateMergeDecisionId() {
        UUID sourceNodeId = UUID.randomUUID();
        UUID synthesizedNodeId = UUID.randomUUID();
        createHumanPostNode(sourceNodeId, "req-source", "author-source", "source", null);
        createHumanPostNode(synthesizedNodeId, "req-s1", "author-1", "s1", null);

        String decisionId = "merge-idempotent-001";
        Map<String, Object> request = mergeRequest(decisionId, sourceNodeId, List.of(synthesizedNodeId));

        ResponseEntity<Map> first = restTemplate.postForEntity("/api/decisions/merge", request, Map.class);
        ResponseEntity<Map> second = restTemplate.postForEntity("/api/decisions/merge", request, Map.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(bodyData(first).get("node_id")).isEqualTo(bodyData(second).get("node_id"));
    }

    @Test
    void shouldRejectMergeRequestWithEmptySynthesizedFrom() {
        UUID sourceNodeId = UUID.randomUUID();
        createHumanPostNode(sourceNodeId, "req-source", "author-source", "source", null);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/decisions/merge",
                mergeRequest("merge-invalid-001", sourceNodeId, List.of()),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo(40001);
    }

    @Test
    void shouldCreateBranchDecisionAndBranchedFromRelationship() {
        UUID sourceNodeId = UUID.randomUUID();
        createHumanPostNode(sourceNodeId, "req-source", "author-source", "source", null);

        String decisionId = "branch-int-001";
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/decisions/branch",
                branchRequest(decisionId, sourceNodeId, "branch content"),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Map<String, Object> record = neo4jClient.query("""
                MATCH (decision:Human_Post {decision_id: $decisionId})-[rel:BRANCHED_FROM]->(source:GraphNode {node_id: $sourceNodeId})
                RETURN count(rel) AS branchedCount,
                       collect(rel.operator_type) AS operatorTypes,
                       labels(decision) AS labels
                """)
                .bind(decisionId).to("decisionId")
                .bind(sourceNodeId.toString()).to("sourceNodeId")
                .fetch().all().iterator().next();

        assertThat(asLong(record.get("branchedCount"))).isEqualTo(1L);
        assertThat((List<String>) record.get("operatorTypes")).contains("HUMAN");
        assertThat((List<String>) record.get("labels")).contains("Human_Post", "GraphNode");
    }

    @Test
    void shouldBeIdempotentForDuplicateBranchDecisionId() {
        UUID sourceNodeId = UUID.randomUUID();
        createHumanPostNode(sourceNodeId, "req-source", "author-source", "source", null);

        Map<String, Object> request = branchRequest("branch-idempotent-001", sourceNodeId, "branch");
        ResponseEntity<Map> first = restTemplate.postForEntity("/api/decisions/branch", request, Map.class);
        ResponseEntity<Map> second = restTemplate.postForEntity("/api/decisions/branch", request, Map.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(bodyData(first).get("node_id")).isEqualTo(bodyData(second).get("node_id"));
    }

    @Test
    void shouldRejectBranchRequestWithBlankContent() {
        UUID sourceNodeId = UUID.randomUUID();
        createHumanPostNode(sourceNodeId, "req-source", "author-source", "source", null);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/decisions/branch",
                branchRequest("branch-invalid-001", sourceNodeId, " "),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo(40001);
    }

    @Test
    void shouldReturnExistingResultWhenBranchDecisionIdAlreadyUsed() {
        UUID sourceNodeId = UUID.randomUUID();
        String decisionId = "branch-existing-001";
        createHumanPostNode(sourceNodeId, "req-existing", "author-existing", "existing content", decisionId);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/decisions/branch",
                branchRequest(decisionId, sourceNodeId, "new content"),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(bodyData(response).get("node_id")).isEqualTo(sourceNodeId.toString());
    }

    @Test
    void shouldExposeCreatedNodesInLineageAndProvenanceQueries() {
        UUID sourceNodeId = UUID.randomUUID();
        UUID synthesizedNodeId = UUID.randomUUID();
        createHumanPostNode(sourceNodeId, "req-source", "author-source", "source", null);
        createHumanPostNode(synthesizedNodeId, "req-synth", "author-synth", "synth", null);

        ResponseEntity<Map> mergeResponse = restTemplate.postForEntity(
                "/api/decisions/merge",
                mergeRequest("merge-lineage-001", sourceNodeId, List.of(synthesizedNodeId)),
                Map.class
        );
        String mergeNodeId = (String) bodyData(mergeResponse).get("node_id");

        ResponseEntity<Map> branchResponse = restTemplate.postForEntity(
                "/api/decisions/branch",
                branchRequest("branch-lineage-001", UUID.fromString(mergeNodeId), "branch from merge"),
                Map.class
        );
        String branchNodeId = (String) bodyData(branchResponse).get("node_id");

        ResponseEntity<Map> provenance = restTemplate.getForEntity("/api/nodes/" + mergeNodeId + "/provenance", Map.class);
        ResponseEntity<Map> lineage = restTemplate.getForEntity("/api/nodes/" + branchNodeId + "/lineage?max_depth=10", Map.class);

        List<Map<String, Object>> provenanceData = (List<Map<String, Object>>) provenance.getBody().get("data");
        Map<String, Object> lineageTopology = (Map<String, Object>) lineage.getBody().get("data");
        List<Map<String, Object>> lineageNodes = (List<Map<String, Object>>) lineageTopology.get("nodes");

        assertThat(provenance.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(provenanceData).extracting(item -> item.get("node_id")).contains(synthesizedNodeId.toString());
        assertThat(lineage.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lineageNodes).extracting(item -> item.get("node_id"))
                .contains(mergeNodeId, sourceNodeId.toString());
    }

    private Map<String, Object> mergeRequest(String decisionId, UUID sourceNodeId, List<UUID> synthesizedFrom) {
        return Map.of(
                "decision_id", decisionId,
                "request_id", "req-" + decisionId,
                "source_node_id", sourceNodeId.toString(),
                "agent_version", "gpt-4.1",
                "summary_content", "summary",
                "synthesized_from", synthesizedFrom.stream().map(UUID::toString).toList(),
                "operator_type", "AGENT",
                "operator_id", "agent-1",
                "reason", "merge"
        );
    }

    private Map<String, Object> branchRequest(String decisionId, UUID sourceNodeId, String content) {
        return Map.of(
                "decision_id", decisionId,
                "request_id", "req-" + decisionId,
                "source_node_id", sourceNodeId.toString(),
                "content", content,
                "author_id", "author-1",
                "operator_type", "HUMAN",
                "operator_id", "human-1",
                "reason", "branch"
        );
    }

    private Map<String, Object> bodyData(ResponseEntity<Map> response) {
        assertThat(response.getBody()).isNotNull();
        return (Map<String, Object>) response.getBody().get("data");
    }

    private void createHumanPostNode(UUID nodeId, String requestId, String authorId, String content, String decisionId) {
        neo4jClient.query("""
                CREATE (:Human_Post:GraphNode {
                  node_id: $nodeId,
                  request_id: $requestId,
                  decision_id: $decisionId,
                  author_id: $authorId,
                  content: $content,
                  created_at: $createdAt
                })
                """)
                .bind(nodeId.toString()).to("nodeId")
                .bind(requestId).to("requestId")
                .bind(decisionId).to("decisionId")
                .bind(authorId).to("authorId")
                .bind(content).to("content")
                .bind(nowUtc()).to("createdAt")
                .run();
    }

    private static long asLong(Object value) {
        return ((Number) value).longValue();
    }

    private static OffsetDateTime nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
