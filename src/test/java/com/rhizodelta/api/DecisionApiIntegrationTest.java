package com.rhizodelta.api;

import com.rhizodelta.ai.shared.service.EmbeddingModelService;
import com.rhizodelta.ai.summary.service.SummaryAgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
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
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "rhizodelta.jwt.secret=test-jwt-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256-signing",
        "langchain4j.open-ai.chat-model.api-key=test-chat-key",
        "langchain4j.open-ai.embedding-model.api-key=test-embedding-key"
})
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

    @MockBean
    private SummaryAgentService summaryAgentService;

    @MockBean
    private EmbeddingModelService embeddingModelService;

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

    // --- Inject ---

    @Test
    void shouldCreateInjectDecisionWithContinuesFromRelationship() {
        UUID sourceNodeId = UUID.randomUUID();
        createHumanPostNode(sourceNodeId, "req-source", "author-source", "source", null);

        String decisionId = "inject-int-001";
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/decisions/inject",
                injectRequest(decisionId, sourceNodeId, "injected content"),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(bodyData(response).get("status")).isEqualTo("QUEUED");

        Map<String, Object> record = neo4jClient.query("""
                MATCH (decision:Human_Post {decision_id: $decisionId})-[rel:CONTINUES_FROM]->(source:GraphNode {node_id: $sourceNodeId})
                RETURN count(rel) AS relCount,
                       collect(rel.operator_type) AS operatorTypes,
                       labels(decision) AS labels
                """)
                .bind(decisionId).to("decisionId")
                .bind(sourceNodeId.toString()).to("sourceNodeId")
                .fetch().all().iterator().next();

        assertThat(asLong(record.get("relCount"))).isEqualTo(1L);
        assertThat((List<String>) record.get("operatorTypes")).contains("HUMAN");
        assertThat((List<String>) record.get("labels")).contains("Human_Post", "GraphNode");
    }

    // --- Materialize ---

    @Test
    void shouldCreateMaterializeDecisionWithResultNode() {
        UUID sourceNodeId = UUID.randomUUID();
        createHumanPostNode(sourceNodeId, "req-source", "author-source", "source", null);

        String decisionId = "materialize-int-001";
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/decisions/materialize",
                materializeRequest(decisionId, sourceNodeId, "materialized content"),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(bodyData(response).get("status")).isEqualTo("QUEUED");
        String resultNodeId = (String) bodyData(response).get("node_id");

        Map<String, Object> record = neo4jClient.query("""
                MATCH (result:Result {decision_id: $decisionId})-[rel:MATERIALIZED_FROM]->(source:GraphNode {node_id: $sourceNodeId})
                RETURN count(rel) AS relCount,
                       labels(result) AS labels,
                       toString(result.node_id) AS resultNodeId
                """)
                .bind(decisionId).to("decisionId")
                .bind(sourceNodeId.toString()).to("sourceNodeId")
                .fetch().all().iterator().next();

        assertThat(asLong(record.get("relCount"))).isEqualTo(1L);
        assertThat((List<String>) record.get("labels")).contains("Result", "GraphNode");
        assertThat(record.get("resultNodeId")).isEqualTo(resultNodeId);
    }

    @Test
    void shouldLookupResultNodeById() {
        UUID sourceNodeId = UUID.randomUUID();
        createHumanPostNode(sourceNodeId, "req-source", "author-source", "source", null);

        ResponseEntity<Map> matResponse = restTemplate.postForEntity(
                "/api/decisions/materialize",
                materializeRequest("mat-lookup-001", sourceNodeId, "materialized"),
                Map.class
        );
        String resultNodeId = (String) bodyData(matResponse).get("node_id");

        ResponseEntity<Map> nodeResponse = restTemplate.getForEntity("/api/nodes/" + resultNodeId, Map.class);
        assertThat(nodeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> nodeData = (Map<String, Object>) nodeResponse.getBody().get("data");
        assertThat(nodeData.get("label")).isEqualTo("Result");
    }

    // --- Fork ---

    @Test
    void shouldCreateForkWithMultipleBranches() {
        UUID sourceNodeId = UUID.randomUUID();
        createHumanPostNode(sourceNodeId, "req-source", "author-source", "source", null);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/decisions/fork",
                forkRequest("fork-int-op-001", sourceNodeId),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(bodyData(response).get("status")).isEqualTo("QUEUED");
        assertThat(bodyData(response).get("operation_id")).isEqualTo("fork-int-op-001");

        // Verify all branches have BRANCHED_FROM relationship
        long branchCount = neo4jClient.query("""
                MATCH (n:Human_Post {operation_id: $operationId})-[:BRANCHED_FROM]->(source:GraphNode {node_id: $sourceNodeId})
                RETURN count(n) AS branchCount
                """)
                .bind("fork-int-op-001").to("operationId")
                .bind(sourceNodeId.toString()).to("sourceNodeId")
                .fetchAs(Long.class).one().orElse(0L);

        assertThat(branchCount).isEqualTo(2L);
    }

    // --- CrossSynth ---

    @Test
    void shouldCreateCrossSynthFromMultipleResults() {
        UUID sourceNodeId = UUID.randomUUID();
        createHumanPostNode(sourceNodeId, "req-source", "author-source", "source", null);

        // Create two Result nodes via materialize
        ResponseEntity<Map> mat1 = restTemplate.postForEntity(
                "/api/decisions/materialize",
                materializeRequest("cs-mat-001", sourceNodeId, "result 1"),
                Map.class
        );
        ResponseEntity<Map> mat2 = restTemplate.postForEntity(
                "/api/decisions/materialize",
                materializeRequest("cs-mat-002", sourceNodeId, "result 2"),
                Map.class
        );
        UUID resultId1 = UUID.fromString((String) bodyData(mat1).get("node_id"));
        UUID resultId2 = UUID.fromString((String) bodyData(mat2).get("node_id"));

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/decisions/cross-synth",
                crossSynthRequest("cs-int-001", List.of(resultId1, resultId2)),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(bodyData(response).get("status")).isEqualTo("QUEUED");

        long crossSynthCount = neo4jClient.query("""
                MATCH (r:Result {decision_id: $decisionId})-[:CROSS_SYNTHESIZED_FROM]->(source:Result)
                RETURN count(source) AS crossSynthCount
                """)
                .bind("cs-int-001").to("decisionId")
                .fetchAs(Long.class).one().orElse(0L);

        assertThat(crossSynthCount).isEqualTo(2L);
    }

    // --- Join ---

    @Test
    void shouldCreateJoinDecisionWithConvergedFromRelationships() {
        UUID sourceA = UUID.randomUUID();
        UUID sourceB = UUID.randomUUID();
        createHumanPostNode(sourceA, "req-a", "author-a", "content a", null);
        createHumanPostNode(sourceB, "req-b", "author-b", "content b", null);

        String decisionId = "join-int-001";
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/decisions/join",
                joinRequest(decisionId, List.of(sourceA, sourceB)),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(bodyData(response).get("status")).isEqualTo("QUEUED");

        Map<String, Object> record = neo4jClient.query("""
                MATCH (decision:AI_Consensus {decision_id: $decisionId})-[rel:CONVERGED_FROM]->(source:GraphNode)
                RETURN count(rel) AS convergedCount,
                       collect(toString(source.node_id)) AS sourceIds
                """)
                .bind(decisionId).to("decisionId")
                .fetch().all().iterator().next();

        assertThat(asLong(record.get("convergedCount"))).isEqualTo(2L);
        assertThat((List<String>) record.get("sourceIds"))
                .containsExactlyInAnyOrder(sourceA.toString(), sourceB.toString());
    }

    // --- Lineage with new relationship types ---

    @Test
    void lineageShouldIncludeInjectAndJoinRelationships() {
        UUID rootNodeId = UUID.randomUUID();
        createHumanPostNode(rootNodeId, "req-root", "author-root", "root", null);

        // Inject from root
        ResponseEntity<Map> injectResponse = restTemplate.postForEntity(
                "/api/decisions/inject",
                injectRequest("lineage-inject-001", rootNodeId, "injected"),
                Map.class
        );
        String injectNodeId = (String) bodyData(injectResponse).get("node_id");

        // Verify lineage from injected node includes root
        ResponseEntity<Map> lineage = restTemplate.getForEntity(
                "/api/nodes/" + injectNodeId + "/lineage?max_depth=10", Map.class);

        assertThat(lineage.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> topology = (Map<String, Object>) lineage.getBody().get("data");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) topology.get("nodes");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) topology.get("edges");

        assertThat(nodes).extracting(n -> n.get("node_id"))
                .contains(injectNodeId, rootNodeId.toString());
        assertThat(edges).extracting(e -> e.get("type"))
                .contains("CONTINUES_FROM");
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

    private Map<String, Object> injectRequest(String decisionId, UUID sourceNodeId, String content) {
        return Map.of(
                "decision_id", decisionId,
                "request_id", "req-" + decisionId,
                "source_node_id", sourceNodeId.toString(),
                "content", content,
                "author_id", "author-1",
                "operator_type", "HUMAN",
                "operator_id", "human-1",
                "reason", "inject"
        );
    }

    private Map<String, Object> materializeRequest(String decisionId, UUID sourceNodeId, String content) {
        return Map.of(
                "decision_id", decisionId,
                "request_id", "req-" + decisionId,
                "source_node_id", sourceNodeId.toString(),
                "content", content,
                "operator_type", "AGENT",
                "operator_id", "agent-1",
                "reason", "materialize"
        );
    }

    private Map<String, Object> forkRequest(String operationId, UUID sourceNodeId) {
        return Map.of(
                "operation_id", operationId,
                "request_id", "req-" + operationId,
                "source_node_id", sourceNodeId.toString(),
                "branches", List.of(
                        Map.of("decision_id", "fork-b1", "content", "branch A", "author_id", "author-a"),
                        Map.of("decision_id", "fork-b2", "content", "branch B", "author_id", "author-b")
                ),
                "operator_type", "AGENT",
                "operator_id", "agent-1",
                "reason", "fork"
        );
    }

    private Map<String, Object> crossSynthRequest(String decisionId, List<UUID> sourceResultIds) {
        return Map.of(
                "decision_id", decisionId,
                "request_id", "req-" + decisionId,
                "source_result_ids", sourceResultIds.stream().map(UUID::toString).toList(),
                "content", "cross-synthesized content",
                "operator_type", "AGENT",
                "operator_id", "agent-1",
                "reason", "cross-synth"
        );
    }

    private Map<String, Object> joinRequest(String decisionId, List<UUID> sourceNodeIds) {
        return Map.of(
                "decision_id", decisionId,
                "request_id", "req-" + decisionId,
                "source_node_ids", sourceNodeIds.stream().map(UUID::toString).toList(),
                "summary_content", "joined summary",
                "agent_version", "gpt-4.1",
                "operator_type", "AGENT",
                "operator_id", "agent-1",
                "reason", "join"
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
