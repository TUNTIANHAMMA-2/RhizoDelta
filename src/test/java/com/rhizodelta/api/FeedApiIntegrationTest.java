package com.rhizodelta.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 13.3 — Feed 端到端：三分支返回内容、屏蔽生效、零关注 fallback 全局。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false"
})
class FeedApiIntegrationTest {
    @Container
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5")
            .withAdminPassword("testpassword");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
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
    void emptyDatabaseReturnsEmptyFeed() {
        String token = registerUser("alice", "password123", "Alice");

        ResponseEntity<Map> response = authorizedGet(token, "/api/users/me/feed");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> items = (List<?>) responseData(response).get("items");
        assertThat(items).isEmpty();
    }

    @Test
    void zeroFollowsReturnsGlobalRecentContent() {
        String token = registerUser("alice", "password123", "Alice");
        createContentNode("global-news", null);
        createContentNode("global-other", null);

        ResponseEntity<Map> response = authorizedGet(token, "/api/users/me/feed");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> items = (List<?>) responseData(response).get("items");
        assertThat(items).hasSize(2);
    }

    @Test
    void followedTopicYieldsContentWithMatchingTopicId() {
        String token = registerUser("alice", "password123", "Alice");
        String topicId = createTopic("science");
        createFollow("alice", "topic", topicId);
        createContentNode("science-content", topicId);

        ResponseEntity<Map> response = authorizedGet(token, "/api/users/me/feed");

        List<?> items = (List<?>) responseData(response).get("items");
        assertThat(items).isNotEmpty();
        Map<String, Object> first = (Map<String, Object>) items.get(0);
        assertThat(first.get("content")).isEqualTo("science-content");
        assertThat(first.get("label")).isEqualTo("Human_Post");
    }

    @Test
    void unfollowedTopicContentIsExcluded() {
        String token = registerUser("alice", "password123", "Alice");
        String followedTopic = createTopic("science");
        String unfollowedTopic = createTopic("noise");
        createFollow("alice", "topic", followedTopic);
        createContentNode("followed-content", followedTopic);
        createContentNode("unfollowed-content", unfollowedTopic);

        ResponseEntity<Map> response = authorizedGet(token, "/api/users/me/feed");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) responseData(response).get("items");
        for (Map<String, Object> item : items) {
            assertThat(item.get("content")).isNotEqualTo("unfollowed-content");
        }
        assertThat(items).extracting(i -> i.get("content"))
                .contains("followed-content");
    }

    @Test
    void mutedTopicContentIsExcluded() {
        String token = registerUser("alice", "password123", "Alice");
        String bobId = registerAndGetUserId("bob", "password123", "Bob");
        String mutedTopic = createTopic("politics");

        createFollow("alice", "user", bobId);
        createMute("alice", "topic", mutedTopic);
        createAuthoredContent(bobId, "muted-topic-content", mutedTopic);
        createAuthoredContent(bobId, "neutral-content", null);

        ResponseEntity<Map> response = authorizedGet(token, "/api/users/me/feed");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) responseData(response).get("items");
        assertThat(items).extracting(i -> i.get("content"))
                .contains("neutral-content")
                .doesNotContain("muted-topic-content");
    }

    @Test
    void mutedUserContentIsExcluded() {
        String token = registerUser("alice", "password123", "Alice");
        String topicId = createTopic("general");
        createFollow("alice", "topic", topicId);
        createContentNode("visible-content", topicId);
        String mutedUserId = registerAndGetUserId("bob", "password123", "Bob");
        createMute("alice", "user", mutedUserId);
        createAuthoredContent(mutedUserId, "blocked-from-muted-author", topicId);

        ResponseEntity<Map> response = authorizedGet(token, "/api/users/me/feed");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) responseData(response).get("items");
        assertThat(items).extracting(i -> i.get("content"))
                .contains("visible-content")
                .doesNotContain("blocked-from-muted-author");
    }

    @Test
    void supportsPaginationInFeed() {
        String token = registerUser("alice", "password123", "Alice");
        String topicId = createTopic("updates");
        createFollow("alice", "topic", topicId);
        for (int i = 0; i < 5; i++) {
            createContentNode("update-" + i, topicId);
        }

        ResponseEntity<Map> page0 = authorizedGet(token, "/api/users/me/feed?page=0&size=2");
        List<?> items0 = (List<?>) responseData(page0).get("items");
        assertThat(items0).hasSize(2);

        ResponseEntity<Map> page1 = authorizedGet(token, "/api/users/me/feed?page=1&size=2");
        List<?> items1 = (List<?>) responseData(page1).get("items");
        assertThat(items1).hasSize(2);
    }

    @Test
    void shouldRequireAuthenticationForFeed() {
        ResponseEntity<Map> response = new TestRestTemplate()
                .getForEntity(restTemplate.getRootUri() + "/api/users/me/feed", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private String registerUser(String username, String password, String displayName) {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/auth/register",
                Map.of("username", username, "password", password, "display_name", displayName),
                Map.class
        );
        return ((Map<String, Object>) response.getBody().get("data")).get("token").toString();
    }

    private String registerAndGetUserId(String username, String password, String displayName) {
        registerUser(username, password, displayName);
        return findUserId(username);
    }

    private String findUserId(String username) {
        return neo4jClient.query("MATCH (u:UserAccount {username: $username}) RETURN u.user_id AS userId")
                .bind(username).to("username")
                .fetchAs(String.class)
                .one()
                .orElseThrow();
    }

    private String createTopic(String name) {
        String topicId = UUID.randomUUID().toString();
        neo4jClient.query(
                "CREATE (t:Topic {topic_id: $topicId, name: $name, source_type: 'TEST', created_at: datetime()})")
                .bindAll(Map.of("topicId", topicId, "name", name))
                .run();
        return topicId;
    }

    private void createFollow(String username, String targetType, String targetId) {
        String userId = findUserId(username);
        neo4jClient.query("""
                MATCH (u:UserAccount {user_id: $userId})
                MATCH (target) WHERE
                  ($targetType = 'topic' AND target:Topic       AND target.topic_id = $targetId) OR
                  ($targetType = 'user'  AND target:UserAccount AND target.user_id  = $targetId) OR
                  ($targetType = 'node'  AND target:GraphNode   AND target.node_id  = $targetId)
                MERGE (u)-[r:FOLLOWS]->(target)
                  ON CREATE SET r.follow_id = randomUUID(), r.since = datetime()
                """)
                .bindAll(Map.of("userId", userId, "targetType", targetType, "targetId", targetId))
                .run();
    }

    private void createMute(String username, String targetType, String targetId) {
        String userId = findUserId(username);
        neo4jClient.query("""
                MATCH (u:UserAccount {user_id: $userId})
                MATCH (target) WHERE
                  ($targetType = 'user'  AND target:UserAccount AND target.user_id  = $targetId) OR
                  ($targetType = 'topic' AND target:Topic       AND target.topic_id = $targetId)
                MERGE (u)-[r:MUTED]->(target)
                  ON CREATE SET r.mute_id = randomUUID(), r.since = datetime()
                """)
                .bindAll(Map.of("userId", userId, "targetType", targetType, "targetId", targetId))
                .run();
    }

    private void createContentNode(String label, String topicId) {
        Map<String, Object> params = new HashMap<>();
        params.put("nodeId", UUID.randomUUID().toString());
        params.put("topicId", topicId);
        params.put("content", label);
        neo4jClient.query("""
                CREATE (n:Human_Post:GraphNode {
                  node_id: $nodeId,
                  topic_id: $topicId,
                  content: $content,
                  author_id: 'system',
                  created_at: datetime()
                })
                """)
                .bindAll(params)
                .run();
    }

    private void createAuthoredContent(String authorUserId, String label, String topicId) {
        Map<String, Object> params = new HashMap<>();
        params.put("authorId", authorUserId);
        params.put("nodeId", UUID.randomUUID().toString());
        params.put("topicId", topicId);
        params.put("content", label);
        neo4jClient.query("""
                MATCH (u:UserAccount {user_id: $authorId})
                CREATE (n:Human_Post:GraphNode {
                  node_id: $nodeId,
                  topic_id: $topicId,
                  content: $content,
                  author_id: $authorId,
                  created_at: datetime()
                })
                CREATE (u)-[:AUTHORED {created_at: datetime()}]->(n)
                """)
                .bindAll(params)
                .run();
    }

    private ResponseEntity<Map> authorizedGet(String token, String uri) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private Map<String, Object> responseData(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }
}
