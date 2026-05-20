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
class MuteApiIntegrationTest {
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
    void shouldCreateMuteForUser() {
        String token = registerUser("alice", "password123", "Alice");
        String targetId = registerAndGetUserId("bob", "password123", "Bob");

        ResponseEntity<Map> response = authorizedPost(token, "/api/users/me/mutes",
                Map.of("target_type", "user", "target_id", targetId, "reason", "spam"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = responseData(response);
        assertThat(data.get("mute_id")).isInstanceOf(String.class);
        assertThat(data.get("status")).isEqualTo("muted");
    }

    @Test
    void shouldCreateMuteForTopic() {
        String token = registerUser("alice", "password123", "Alice");
        String topicId = createTopic("noise");

        ResponseEntity<Map> response = authorizedPost(token, "/api/users/me/mutes",
                Map.of("target_type", "topic", "target_id", topicId, "reason", "irrelevant"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = responseData(response);
        assertThat(data.get("mute_id")).isInstanceOf(String.class);
    }

    @Test
    void nodeSummaryExposesMuteStateForAuthenticatedCaller() {
        String token = registerUser("alice", "password123", "Alice");
        String nodeId = createHumanPost("author-mute-state", "mute state content");

        authorizedPost(token, "/api/users/me/mutes",
                Map.of("target_type", "node", "target_id", nodeId, "reason", "hide"));
        ResponseEntity<Map> response = authorizedGet(token, "/api/nodes/" + nodeId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = responseData(response);
        assertThat(data).containsEntry("is_muted", true);
        assertThat(data.get("mute_id")).isInstanceOf(String.class);
        assertThat(data).containsEntry("is_following", false);
    }

    @Test
    void duplicateMuteReturns409() {
        String token = registerUser("alice", "password123", "Alice");
        String targetId = registerAndGetUserId("bob", "password123", "Bob");
        authorizedPost(token, "/api/users/me/mutes",
                Map.of("target_type", "user", "target_id", targetId));

        ResponseEntity<Map> response = authorizedPost(token, "/api/users/me/mutes",
                Map.of("target_type", "user", "target_id", targetId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void muteNonExistentTargetReturns404() {
        String token = registerUser("alice", "password123", "Alice");

        ResponseEntity<Map> response = authorizedPost(token, "/api/users/me/mutes",
                Map.of("target_type", "user", "target_id", UUID.randomUUID().toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unmuteByMuteIdRemovesEdge() {
        String token = registerUser("alice", "password123", "Alice");
        String targetId = registerAndGetUserId("bob", "password123", "Bob");
        ResponseEntity<Map> created = authorizedPost(token, "/api/users/me/mutes",
                Map.of("target_type", "user", "target_id", targetId));
        String muteId = responseData(created).get("mute_id").toString();

        ResponseEntity<Map> response = authorizedDelete(token, "/api/users/me/mutes/" + muteId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> listResponse = authorizedGet(token, "/api/users/me/mutes");
        assertThat(responseData(listResponse).get("total")).isEqualTo(0);
    }

    @Test
    void unmuteUnknownReturns404() {
        String token = registerUser("alice", "password123", "Alice");

        ResponseEntity<Map> response = authorizedDelete(token,
                "/api/users/me/mutes/" + UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsInvalidTargetType() {
        String token = registerUser("alice", "password123", "Alice");

        ResponseEntity<Map> response = authorizedPost(token, "/api/users/me/mutes",
                Map.of("target_type", "invalid", "target_id", UUID.randomUUID().toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listsMutesWithTotal() {
        String token = registerUser("alice", "password123", "Alice");
        String bobId = registerAndGetUserId("bob", "password123", "Bob");
        String carolId = registerAndGetUserId("carol", "password123", "Carol");
        authorizedPost(token, "/api/users/me/mutes", Map.of("target_type", "user", "target_id", bobId));
        authorizedPost(token, "/api/users/me/mutes", Map.of("target_type", "user", "target_id", carolId));

        ResponseEntity<Map> response = authorizedGet(token, "/api/users/me/mutes");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = responseData(response);
        assertThat(data.get("total")).isEqualTo(2);
        assertThat((List<?>) data.get("items")).hasSize(2);
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
        return neo4jClient.query("MATCH (u:UserAccount {username: $username}) RETURN u.user_id AS userId")
                .bind(username).to("username")
                .fetchAs(String.class)
                .one()
                .orElseThrow();
    }

    private String createTopic(String name) {
        String topicId = UUID.randomUUID().toString();
        neo4jClient.query("CREATE (t:Topic {topic_id: $topicId, name: $name, source_type: 'TEST', created_at: datetime()})")
                .bindAll(Map.of("topicId", topicId, "name", name))
                .run();
        return topicId;
    }

    private String createHumanPost(String authorId, String content) {
        String nodeId = UUID.randomUUID().toString();
        neo4jClient.query("""
                CREATE (:Human_Post:GraphNode {
                  node_id: $nodeId,
                  request_id: $requestId,
                  author_id: $authorId,
                  content: $content,
                  created_at: datetime(),
                  root_id: $nodeId
                })
                """)
                .bindAll(Map.of(
                        "nodeId", nodeId,
                        "requestId", UUID.randomUUID().toString(),
                        "authorId", authorId,
                        "content", content
                ))
                .run();
        return nodeId;
    }

    private ResponseEntity<Map> authorizedGet(String token, String uri) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private ResponseEntity<Map> authorizedPost(String token, String uri, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(uri, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Map> authorizedDelete(String token, String uri) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(uri, HttpMethod.DELETE, new HttpEntity<>(headers), Map.class);
    }

    private Map<String, Object> responseData(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }
}
