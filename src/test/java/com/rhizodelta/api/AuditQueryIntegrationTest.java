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
class AuditQueryIntegrationTest {
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
    void shouldListAuditRecordsWithFiltersAndCursorPagination() {
        UUID sourceNodeId = UUID.randomUUID();
        UUID synthesizedA = UUID.randomUUID();
        UUID synthesizedB = UUID.randomUUID();
        createHumanPostNode(sourceNodeId, "req-source", "author-source", "source");
        createHumanPostNode(synthesizedA, "req-s1", "author-a", "s1");
        createHumanPostNode(synthesizedB, "req-s2", "author-b", "s2");

        ResponseEntity<Map> mergeResponse = restTemplate.postForEntity(
                "/api/decisions/merge",
                mergeRequest("merge-audit-001", sourceNodeId, List.of(synthesizedA, synthesizedB), "agent-42"),
                Map.class
        );
        ResponseEntity<Map> branchResponse = restTemplate.postForEntity(
                "/api/decisions/branch",
                branchRequest("branch-audit-001", sourceNodeId, "branch", "human-7"),
                Map.class
        );
        assertThat(mergeResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(branchResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        OffsetDateTime since = nowUtc().minusMinutes(5);
        OffsetDateTime until = nowUtc().plusMinutes(5);
        ResponseEntity<Map> filteredResponse = restTemplate.getForEntity(
                "/api/audit/decisions?type=MERGE&operator_id=agent-42&since=" + since + "&until=" + until,
                Map.class
        );

        assertThat(filteredResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> filteredRecords = bodyList(filteredResponse);
        assertThat(filteredRecords).isNotEmpty();
        assertThat(filteredRecords).allSatisfy(record -> {
            assertThat(record.get("decision_type")).isEqualTo("MERGE");
            assertThat(record.get("operator_id")).isEqualTo("agent-42");
        });

        ResponseEntity<Map> firstPageResponse = restTemplate.getForEntity("/api/audit/decisions?limit=1", Map.class);
        assertThat(firstPageResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> firstPageData = bodyData(firstPageResponse);
        List<Map<String, Object>> firstPageRecords = bodyList(firstPageResponse);
        assertThat(firstPageRecords).hasSize(1);
        String nextCursor = (String) firstPageData.get("next_cursor");
        assertThat(nextCursor).isNotBlank();

        ResponseEntity<Map> secondPageResponse = restTemplate.getForEntity(
                "/api/audit/decisions?limit=1&after=" + nextCursor,
                Map.class
        );
        assertThat(secondPageResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> secondPageRecords = bodyList(secondPageResponse);
        assertThat(secondPageRecords).hasSize(1);
        assertThat(secondPageRecords.get(0).get("decision_id"))
                .isNotEqualTo(firstPageRecords.get(0).get("decision_id"));
    }

    @Test
    void shouldReturnBranchDecisionDetailWithEmptySynthesizedFrom() {
        UUID sourceNodeId = UUID.randomUUID();
        createHumanPostNode(sourceNodeId, "req-source", "author-source", "source");

        ResponseEntity<Map> branchResponse = restTemplate.postForEntity(
                "/api/decisions/branch",
                branchRequest("branch-detail-001", sourceNodeId, "branch detail", "human-11"),
                Map.class
        );
        assertThat(branchResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<Map> detailResponse = restTemplate.getForEntity(
                "/api/audit/decisions/branch-detail-001",
                Map.class
        );
        assertThat(detailResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> detail = bodyData(detailResponse);
        assertThat(detail.get("decision_type")).isEqualTo("BRANCH");
        assertThat(detail.get("source_node_id")).isEqualTo(sourceNodeId.toString());
        assertThat((List<?>) detail.get("synthesized_from")).isEmpty();
    }

    private Map<String, Object> mergeRequest(
            String decisionId,
            UUID sourceNodeId,
            List<UUID> synthesizedFrom,
            String operatorId
    ) {
        return Map.of(
                "decision_id", decisionId,
                "request_id", "req-" + decisionId,
                "source_node_id", sourceNodeId.toString(),
                "agent_version", "gpt-4.1",
                "summary_content", "summary",
                "synthesized_from", synthesizedFrom.stream().map(UUID::toString).toList(),
                "operator_type", "AGENT",
                "operator_id", operatorId,
                "reason", "merge"
        );
    }

    private Map<String, Object> branchRequest(
            String decisionId,
            UUID sourceNodeId,
            String content,
            String operatorId
    ) {
        return Map.of(
                "decision_id", decisionId,
                "request_id", "req-" + decisionId,
                "source_node_id", sourceNodeId.toString(),
                "content", content,
                "author_id", "author-1",
                "operator_type", "HUMAN",
                "operator_id", operatorId,
                "reason", "branch"
        );
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
