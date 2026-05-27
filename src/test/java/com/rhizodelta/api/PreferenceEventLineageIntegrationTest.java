package com.rhizodelta.api;

import com.rhizodelta.ai.shared.service.EmbeddingModelService;
import com.rhizodelta.ai.summary.service.SummaryAgentService;
import com.rhizodelta.infrastructure.user.service.PrefersAggregationJob;
import com.rhizodelta.infrastructure.user.service.PrefersAggregationOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for spec 2 personalization closure: a real, authenticated
 * {@code GET /api/nodes/{id}} call must (1) record a {@code (:PreferenceEvent)-[:TOWARD]->(:Topic)}
 * edge derived from the node's {@code topic_id}, and (2) feed into
 * {@link PrefersAggregationJob#runOnce()} so that the resulting {@code PREFERS} edge actually exists.
 *
 * <p>Existing {@code PrefersAggregationIntegrationTest} seeds {@code (e)-[:TOWARD]->(t)} edges
 * directly, which bypasses the {@code NodeQueryController → PreferenceEventService → PreferenceEventRepository}
 * pipeline and masks the bug where {@code NodeQueryController} passes {@code topicId=null}. This
 * test refuses to take that shortcut: every {@code TOWARD} edge in the database is produced by the
 * controller path, with the only manipulated parameter being the authenticated identity.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "rhizodelta.feature.prefers-aggregation.enabled=true",
        "rhizodelta.preference.half-life-days=30",
        "rhizodelta.preference.window-hours=24"
})
@Import(PreferenceEventLineageIntegrationTest.SynchronousPreferenceEventExecutorConfig.class)
class PreferenceEventLineageIntegrationTest {

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

    @Autowired
    private PrefersAggregationJob job;

    @MockBean
    private EmbeddingModelService embeddingModelService;

    @MockBean
    private SummaryAgentService summaryAgentService;

    @BeforeEach
    void cleanDatabase() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();
    }

    @Test
    void authenticatedNodeReadCreatesTowardEdgeDerivedFromNodeTopic() {
        // Register a user. The auth flow returns a JWT we can use as a bearer token
        // exactly as the frontend would, so this exercises JwtAuthenticationFilter end-to-end.
        UserCredentials user = registerUser("personalization-tester");
        UUID topicId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        seedTopicAndPost(topicId, nodeId, "react-19");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(user.token);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/nodes/" + nodeId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // After the read, exactly one (:PreferenceEvent)-[:TOWARD]->(:Topic) edge must exist —
        // and the Topic on the far side must be the one referenced by the Human_Post's topic_id.
        Map<String, Object> projection = neo4jClient.query("""
                        MATCH (u:UserAccount {user_id: $userId})-[:EMITTED]->(e:PreferenceEvent)-[:TOWARD]->(t:Topic)
                        RETURN count(e) AS events, collect(distinct t.topic_id) AS topics
                        """)
                .bind(user.userId).to("userId")
                .fetch().one().orElseThrow(() -> new AssertionError("TOWARD edge missing"));

        long events = ((Number) projection.get("events")).longValue();
        assertThat(events).as("real GET /api/nodes/{id} must produce one TOWARD edge").isEqualTo(1L);
        assertThat(projection.get("topics")).isEqualTo(java.util.List.of(topicId.toString()));
    }

    @Test
    void replayedAggregationProducesPrefersEdgeFromControllerInducedEvent() {
        UserCredentials user = registerUser("aggregation-tester");
        UUID topicId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        seedTopicAndPost(topicId, nodeId, "spring-boot-3");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(user.token);
        restTemplate.exchange("/api/nodes/" + nodeId, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        PrefersAggregationOutcome outcome = job.runOnce();
        assertThat(outcome.status())
                .as("aggregation must succeed when at least one TOWARD edge exists")
                .isEqualTo(PrefersAggregationOutcome.Status.OK);
        assertThat(outcome.result().eventsProcessed())
                .as("the single VIEW event from the controller must be processed")
                .isEqualTo(1L);
        assertThat(outcome.result().edgesUpserted()).isEqualTo(1L);

        double weight = readEdgeWeight(user.userId, topicId.toString());
        // VIEW base weight is 0.5; the event is fresh ⇒ decay ≈ 1.0 ⇒ weight ≈ 0.5.
        assertThat(weight).isGreaterThan(0.4).isLessThanOrEqualTo(0.5);
    }

    @Test
    void readingNodeWithoutTopicIdDoesNotCreateTowardEdge() {
        // Mirror the legacy behaviour: a node missing topic_id (e.g. test fixtures or
        // pre-migration data) must still record an EMITTED PreferenceEvent but skip TOWARD.
        // This protects against a regression where the new fallback would attach events
        // to a wrong topic via some other coincidence.
        UserCredentials user = registerUser("topicless-tester");
        UUID nodeId = UUID.randomUUID();
        seedTopiclessPost(nodeId);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(user.token);
        restTemplate.exchange("/api/nodes/" + nodeId, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        long emitted = neo4jClient.query("""
                        MATCH (u:UserAccount {user_id: $userId})-[:EMITTED]->(e:PreferenceEvent)
                        RETURN count(e) AS n
                        """)
                .bind(user.userId).to("userId")
                .fetch().one().map(r -> ((Number) r.get("n")).longValue()).orElse(0L);
        assertThat(emitted).as("PreferenceEvent must still be recorded even without a topic").isEqualTo(1L);

        long toward = neo4jClient.query("""
                        MATCH (:PreferenceEvent)-[r:TOWARD]->(:Topic)
                        RETURN count(r) AS n
                        """)
                .fetch().one().map(r -> ((Number) r.get("n")).longValue()).orElse(-1L);
        assertThat(toward).as("no TOWARD edge when source node has no topic_id").isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private record UserCredentials(String userId, String token) {
    }

    private UserCredentials registerUser(String usernamePrefix) {
        String username = usernamePrefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> request = Map.of(
                "username", username,
                "password", "password123!",
                "display_name", "Tester " + usernamePrefix
        );
        ResponseEntity<Map> response = restTemplate.postForEntity("/api/auth/register", request, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        Map<String, Object> userBody = (Map<String, Object>) data.get("user");
        return new UserCredentials(userBody.get("user_id").toString(), data.get("token").toString());
    }

    private void seedTopicAndPost(UUID topicId, UUID nodeId, String topicName) {
        neo4jClient.query("""
                        CREATE (t:Topic {topic_id: $topicId, name: $topicName})
                        CREATE (n:Human_Post:GraphNode {
                          node_id: $nodeId,
                          topic_id: $topicId,
                          content: $content,
                          request_id: $requestId,
                          author_id: $authorId,
                          created_at: $createdAt
                        })
                        """)
                .bindAll(Map.of(
                        "topicId", topicId.toString(),
                        "topicName", topicName,
                        "nodeId", nodeId.toString(),
                        "content", "post about " + topicName,
                        "requestId", "req-" + UUID.randomUUID(),
                        "authorId", "author-" + UUID.randomUUID(),
                        "createdAt", OffsetDateTime.now(ZoneOffset.UTC)
                ))
                .run();
    }

    private void seedTopiclessPost(UUID nodeId) {
        neo4jClient.query("""
                        CREATE (n:Human_Post:GraphNode {
                          node_id: $nodeId,
                          content: 'orphan post',
                          request_id: $requestId,
                          author_id: 'author-orphan',
                          created_at: $createdAt
                        })
                        """)
                .bindAll(Map.of(
                        "nodeId", nodeId.toString(),
                        "requestId", "req-" + UUID.randomUUID(),
                        "createdAt", OffsetDateTime.now(ZoneOffset.UTC)
                ))
                .run();
    }

    private double readEdgeWeight(String userId, String topicId) {
        return neo4jClient.query("""
                        MATCH (u:UserAccount {user_id: $userId})-[r:PREFERS]->(t:Topic {topic_id: $topicId})
                        RETURN r.weight AS weight
                        """)
                .bind(userId).to("userId")
                .bind(topicId).to("topicId")
                .fetch().one().map(r -> ((Number) r.get("weight")).doubleValue())
                .orElseThrow(() -> new AssertionError("PREFERS edge not present for user=" + userId + " topic=" + topicId));
    }

    /**
     * 在该集成测试里把 {@code preferenceEventExecutor} 覆盖为同步 executor，
     * 让 {@code GET /api/nodes/{id}} 返回时偏好事件一定已经落库。
     *
     * <p>生产环境保持异步，这样读路径不会被 Neo4j 写事务拖慢——只是本测试在 HTTP 调用
     * 之后立即查询 TOWARD 边，端到端验证需要同步语义。
     */
    @TestConfiguration
    static class SynchronousPreferenceEventExecutorConfig {
        @Bean(name = "preferenceEventExecutor")
        @Primary
        Executor preferenceEventExecutor() {
            return Runnable::run;
        }
    }
}
