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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=true",
        "langchain4j.open-ai.chat-model.api-key=test-chat-key",
        "langchain4j.open-ai.embedding-model.api-key=test-embedding-key"
})
class AsyncPostPipelineIntegrationTest {
    private static final String RABBITMQ_USERNAME = "testuser";
    private static final String RABBITMQ_PASSWORD = "testpass";
    private static final Duration CREATE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);
    private static final int RABBITMQ_AMQP_PORT = 5672;

    @Container
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5")
            .withAdminPassword("testpassword");

    @Container
    static GenericContainer<?> rabbitmq = new GenericContainer<>("rabbitmq:3-management")
            .withEnv("RABBITMQ_DEFAULT_USER", RABBITMQ_USERNAME)
            .withEnv("RABBITMQ_DEFAULT_PASS", RABBITMQ_PASSWORD)
            .withExposedPorts(RABBITMQ_AMQP_PORT);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.neo4j.uri", neo4j::getBoltUrl);
        registry.add("spring.neo4j.authentication.username", () -> "neo4j");
        registry.add("spring.neo4j.authentication.password", neo4j::getAdminPassword);
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", () -> rabbitmq.getMappedPort(RABBITMQ_AMQP_PORT));
        registry.add("spring.rabbitmq.username", () -> RABBITMQ_USERNAME);
        registry.add("spring.rabbitmq.password", () -> RABBITMQ_PASSWORD);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private Neo4jClient neo4jClient;

    @BeforeEach
    void cleanDatabase() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();
        createUserAccount("test-operator");
    }

    @Test
    void shouldCreateHumanPostFromAsyncConsumer() {
        Map<String, Object> request = Map.of(
                "request_id", "req-async-001",
                "author_id", "author-async",
                "content", "async post"
        );

        ResponseEntity<Map> response = restTemplate.postForEntity("/api/posts", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        awaitHumanPost("req-async-001");
        assertThat(countAuthoredRelationships("req-async-001", "test-operator")).isEqualTo(1L);
    }

    @Test
    void shouldUseJwtSubjectAsAuthorId() {
        Map<String, Object> request = Map.of(
                "request_id", "req-async-authenticated-author",
                "author_id", "forged-author",
                "content", "post author should come from jwt"
        );

        ResponseEntity<Map> response = restTemplate.postForEntity("/api/posts", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        awaitHumanPost("req-async-authenticated-author");
        assertThat(findAuthorId("req-async-authenticated-author")).isEqualTo("test-operator");
        assertThat(countAuthoredRelationships("req-async-authenticated-author", "test-operator")).isEqualTo(1L);
    }

    @Test
    void shouldNotCreateDuplicateNodesForRedeliveredMessage() {
        Map<String, Object> request = Map.of(
                "request_id", "req-async-dup",
                "author_id", "author-dup",
                "content", "duplicate post"
        );

        restTemplate.postForEntity("/api/posts", request, Map.class);
        restTemplate.postForEntity("/api/posts", request, Map.class);

        awaitHumanPost("req-async-dup");
        assertThat(countHumanPosts("req-async-dup")).isEqualTo(1L);
        assertThat(countAuthoredRelationships("req-async-dup", "test-operator")).isEqualTo(1L);
    }

    private long countAuthoredRelationships(String requestId, String authorId) {
        return neo4jClient.query("""
                MATCH (:UserAccount {user_id: $authorId})-[rel:AUTHORED]->(:Human_Post {request_id: $requestId})
                RETURN count(rel) AS count
                """)
                .bind(authorId).to("authorId")
                .bind(requestId).to("requestId")
                .fetchAs(Long.class)
                .one()
                .orElse(0L);
    }

    private void awaitHumanPost(String requestId) {
        long deadline = System.currentTimeMillis() + CREATE_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (countHumanPosts(requestId) == 1L) {
                return;
            }
            sleep(POLL_INTERVAL);
        }
        throw new AssertionError("Timed out waiting for Human_Post with request_id=" + requestId);
    }

    private long countHumanPosts(String requestId) {
        return neo4jClient.query("""
                MATCH (post:Human_Post {request_id: $requestId})
                RETURN count(post) AS count
                """)
                .bind(requestId).to("requestId")
                .fetchAs(Long.class)
                .one()
                .orElse(0L);
    }

    private String findAuthorId(String requestId) {
        return neo4jClient.query("""
                MATCH (post:Human_Post {request_id: $requestId})
                RETURN post.author_id AS authorId
                """)
                .bind(requestId).to("requestId")
                .fetchAs(String.class)
                .one()
                .orElseThrow(() -> new AssertionError("Missing Human_Post for request_id=" + requestId));
    }

    private void createUserAccount(String userId) {
        neo4jClient.query("""
                CREATE (:UserAccount {
                  username: $username,
                  user_id: $userId,
                  password_hash: 'hash',
                  roles: ['USER'],
                  status: 'ACTIVE',
                  created_at: datetime()
                })
                """)
                .bind(userId).to("userId")
                .bind("user-" + userId).to("username")
                .run();
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for async post", exception);
        }
    }
}
