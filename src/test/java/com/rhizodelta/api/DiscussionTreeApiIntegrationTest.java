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
class DiscussionTreeApiIntegrationTest {
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
    void shouldReturnNestedDiscussionTreeJson() {
        UUID rootId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        UUID consensusId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        createUserWithProfile("author-root", "alice", "Alice");
        createHumanPost(rootId, "req-root", "author-root", "root content", atMinute(0));
        createHumanPost(childId, "req-child", "author-child", "child content", atMinute(1));
        createAiConsensus(consensusId, "summary body", "agent-v1", atMinute(2));
        createResult(resultId, "result body", "agent-v2", atMinute(3));
        createRelationship(childId, rootId, "CONTINUES_FROM");
        createRelationship(consensusId, rootId, "MERGED_INTO");
        createRelationship(consensusId, childId, "SYNTHESIZED_FROM");
        createRelationship(resultId, rootId, "MATERIALIZED_FROM");

        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/api/nodes/" + rootId + "/discussion-tree?max_depth=5&limit=20",
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = responseData(response);
        Map<String, Object> root = nestedMap(data, "root");
        Map<String, Object> meta = nestedMap(data, "meta");
        assertThat(root).containsEntry("node_id", rootId.toString());
        assertThat(root).containsKeys("created_at", "parent_id", "has_more_children", "total_children_count");
        assertThat(root.get("parent_id")).isNull();
        assertThat(nestedMap(root, "author")).containsEntry("display_name", "Alice");
        assertThat(list(root, "children")).singleElement().satisfies(child -> {
            assertThat(child).containsEntry("node_id", childId.toString());
            assertThat(child).containsEntry("parent_id", rootId.toString());
            assertThat(child).containsEntry("depth", 1);
        });
        assertThat(list(root, "artifacts"))
                .extracting(item -> item.get("kind"))
                .containsExactly("CONSENSUS", "RESULT");
        assertThat(list(root, "artifacts").get(0)).containsEntry("source_count", 1);
        assertThat(meta).containsEntry("root_node_id", rootId.toString());
        assertThat(meta).containsEntry("max_depth", 5);
        assertThat(meta).containsEntry("limit", 20);
        assertThat(meta).containsEntry("next_cursor", null);
    }

    @Test
    void shouldReturn404ForUnknownRoot() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/api/nodes/" + UUID.randomUUID() + "/discussion-tree",
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", 40401);
    }

    @Test
    void shouldReturn401WithoutJwt() {
        UUID rootId = UUID.randomUUID();
        createHumanPost(rootId, "req-root", "author-root", "root", atMinute(0));

        ResponseEntity<Map> response = new TestRestTemplate().getForEntity(
                restTemplate.getRootUri() + "/api/nodes/" + rootId + "/discussion-tree",
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("code", 40101);
    }

    private void createUserWithProfile(String userId, String username, String displayName) {
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
                .bind(atMinute(0)).to("createdAt")
                .run();
        neo4jClient.query("""
                MATCH (user:UserAccount {user_id: $userId})
                MERGE (profile:UserProfile {user_id: $userId})
                SET profile.display_name = $displayName,
                    profile.updated_at = $updatedAt
                MERGE (user)-[:HAS_PROFILE]->(profile)
                """)
                .bind(userId).to("userId")
                .bind(displayName).to("displayName")
                .bind(atMinute(0)).to("updatedAt")
                .run();
    }

    private void createHumanPost(UUID nodeId, String requestId, String authorId, String content, OffsetDateTime createdAt) {
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
                .bind(createdAt).to("createdAt")
                .run();
    }

    private void createAiConsensus(UUID nodeId, String summaryContent, String agentVersion, OffsetDateTime createdAt) {
        neo4jClient.query("""
                CREATE (:AI_Consensus:GraphNode {
                  node_id: $nodeId,
                  summary_content: $summaryContent,
                  agent_version: $agentVersion,
                  created_at: $createdAt
                })
                """)
                .bind(nodeId.toString()).to("nodeId")
                .bind(summaryContent).to("summaryContent")
                .bind(agentVersion).to("agentVersion")
                .bind(createdAt).to("createdAt")
                .run();
    }

    private void createResult(UUID nodeId, String content, String agentVersion, OffsetDateTime createdAt) {
        neo4jClient.query("""
                CREATE (:Result:GraphNode {
                  node_id: $nodeId,
                  content: $content,
                  agent_version: $agentVersion,
                  created_at: $createdAt
                })
                """)
                .bind(nodeId.toString()).to("nodeId")
                .bind(content).to("content")
                .bind(agentVersion).to("agentVersion")
                .bind(createdAt).to("createdAt")
                .run();
    }

    private void createRelationship(UUID sourceId, UUID targetId, String relationshipType) {
        neo4jClient.query(String.format("""
                MATCH (source:GraphNode {node_id: $sourceId}), (target:GraphNode {node_id: $targetId})
                CREATE (source)-[:%s {created_at: $createdAt}]->(target)
                """, relationshipType))
                .bind(sourceId.toString()).to("sourceId")
                .bind(targetId.toString()).to("targetId")
                .bind(atMinute(0)).to("createdAt")
                .run();
    }

    private static OffsetDateTime atMinute(int minute) {
        return OffsetDateTime.of(2026, 5, 20, 11, minute, 0, 0, ZoneOffset.UTC);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> responseData(ResponseEntity<Map> response) {
        assertThat(response.getBody()).isNotNull();
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nestedMap(Map<String, Object> data, String key) {
        return (Map<String, Object>) data.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> data, String key) {
        return (List<Map<String, Object>>) data.get(key);
    }
}
