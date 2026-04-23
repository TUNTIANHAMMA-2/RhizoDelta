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
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false"
})
class NodeQueryAuthorProjectionIntegrationTest {
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
    void shouldExposeAuthorProjectionOnNodeSummaryAndRoots() {
        UUID rootId = UUID.randomUUID();
        createUserWithProfile("author-a", "alice", "Alice");
        createRootHumanPost(rootId, "req-root-a", "author-a", "root content");

        ResponseEntity<Map> summaryResponse = restTemplate.getForEntity("/api/nodes/" + rootId, Map.class);
        ResponseEntity<Map> rootsResponse = restTemplate.getForEntity("/api/nodes/roots", Map.class);

        assertThat(summaryResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> summary = responseData(summaryResponse);
        assertThat(summary).containsEntry("author_id", "author-a");
        assertThat(summary).containsEntry("author_username", "alice");
        assertThat(summary).containsEntry("author_display_name", "Alice");

        assertThat(rootsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> roots = responseList(rootsResponse);
        assertThat(roots).anySatisfy(root -> {
            assertThat(root).containsEntry("node_id", rootId.toString());
            assertThat(root).containsEntry("author_id", "author-a");
            assertThat(root).containsEntry("author_username", "alice");
            assertThat(root).containsEntry("author_display_name", "Alice");
        });
    }

    @Test
    void shouldFallbackAuthorDisplayNameToUsernameWhenProfileMissing() {
        UUID nodeId = UUID.randomUUID();
        createUserAccount("author-b", "bob");
        createRootHumanPost(nodeId, "req-root-b", "author-b", "root fallback");

        ResponseEntity<Map> response = restTemplate.getForEntity("/api/nodes/" + nodeId, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> payload = responseData(response);
        assertThat(payload).containsEntry("author_id", "author-b");
        assertThat(payload).containsEntry("author_username", "bob");
        assertThat(payload).containsEntry("author_display_name", "bob");
    }

    @Test
    void shouldExposeAuthorProjectionInProvenancePayloads() {
        UUID sourceId = UUID.randomUUID();
        UUID consensusId = UUID.randomUUID();
        createUserWithProfile("author-c", "carol", "Carol");
        createRootHumanPost(sourceId, "req-source-c", "author-c", "source content");

        neo4jClient.query("""
                CREATE (:AI_Consensus:GraphNode {
                  node_id: $consensusId,
                  summary_content: 'combined',
                  agent_version: 'v1',
                  created_at: $createdAt
                })
                """)
                .bind(consensusId.toString()).to("consensusId")
                .bind(nowUtc()).to("createdAt")
                .run();
        neo4jClient.query("""
                MATCH (consensus:AI_Consensus {node_id: $consensusId}),
                      (source:Human_Post {node_id: $sourceId})
                CREATE (consensus)-[:SYNTHESIZED_FROM {
                    operator_type: 'AGENT',
                    operator_id: 'agent-1',
                    created_at: $createdAt,
                    reason: 'summary'
                }]->(source)
                """)
                .bind(consensusId.toString()).to("consensusId")
                .bind(sourceId.toString()).to("sourceId")
                .bind(nowUtc()).to("createdAt")
                .run();

        ResponseEntity<Map> response = restTemplate.getForEntity("/api/nodes/" + consensusId + "/provenance", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> provenance = responseList(response);
        assertThat(provenance).anySatisfy(item -> {
            assertThat(item).containsEntry("node_id", sourceId.toString());
            assertThat(item).containsEntry("author_id", "author-c");
            assertThat(item).containsEntry("author_username", "carol");
            assertThat(item).containsEntry("author_display_name", "Carol");
        });
    }

    private void createUserWithProfile(String userId, String username, String displayName) {
        createUserAccount(userId, username);
        neo4jClient.query("""
                MATCH (user:UserAccount {user_id: $userId})
                MERGE (profile:UserProfile {user_id: $userId})
                SET profile.display_name = $displayName,
                    profile.updated_at = $updatedAt
                MERGE (user)-[:HAS_PROFILE]->(profile)
                """)
                .bind(userId).to("userId")
                .bind(displayName).to("displayName")
                .bind(nowUtc()).to("updatedAt")
                .run();
    }

    private void createUserAccount(String userId, String username) {
        neo4jClient.query("""
                CREATE (:UserAccount {
                  username: $username,
                  user_id: $userId,
                  password_hash: 'hash',
                  roles: ['USER'],
                  status: 'ACTIVE',
                  created_at: $createdAt
                })
                """)
                .bind(username).to("username")
                .bind(userId).to("userId")
                .bind(nowUtc()).to("createdAt")
                .run();
    }

    private void createRootHumanPost(UUID nodeId, String requestId, String authorId, String content) {
        neo4jClient.query("""
                CREATE (:Human_Post:GraphNode {
                  node_id: $nodeId,
                  request_id: $requestId,
                  author_id: $authorId,
                  root_id: $rootId,
                  content: $content,
                  created_at: $createdAt
                })
                """)
                .bind(nodeId.toString()).to("nodeId")
                .bind(requestId).to("requestId")
                .bind(authorId).to("authorId")
                .bind(nodeId.toString()).to("rootId")
                .bind(content).to("content")
                .bind(nowUtc()).to("createdAt")
                .run();
    }

    private Map<String, Object> responseData(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    private List<Map<String, Object>> responseList(ResponseEntity<Map> response) {
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    private static OffsetDateTime nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
