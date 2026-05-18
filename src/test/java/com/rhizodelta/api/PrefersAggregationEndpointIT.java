package com.rhizodelta.api;

import com.rhizodelta.ai.shared.service.EmbeddingModelService;
import com.rhizodelta.ai.summary.service.SummaryAgentService;
import com.rhizodelta.infrastructure.user.service.PreferenceEventService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT for the {@code POST /actuator/prefers-aggregation} replay endpoint.
 *
 * <p>Covers three concerns:
 * <ul>
 *   <li><b>Authentication</b> — unauthenticated POST must be rejected with 401.</li>
 *   <li><b>Authorization</b> — non-ADMIN authenticated POST must be rejected with 403.</li>
 *   <li><b>Functional</b> — ADMIN POST drives {@code PrefersAggregationJob.runOnce()}; the response
 *       reflects the job outcome (SKIPPED when flag is off; OK with event counts when flag is on
 *       and there are PreferenceEvents in the window).</li>
 * </ul>
 *
 * <p>Flag toggling between tests uses {@link DynamicPropertyRegistry#add(String,
 * org.springframework.test.context.DynamicPropertyRegistry.PropertyProvider) DynamicPropertyRegistry.add}'s
 * supplier semantics — the supplier is invoked on each {@code Environment.getProperty} call,
 * so {@link #flagOverride} can be flipped from inside individual tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false"
})
class PrefersAggregationEndpointIT {

    @Container
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5")
            .withAdminPassword("testpassword");

    /**
     * Default false; individual tests flip it via {@link AtomicBoolean#set(boolean)} before
     * issuing the POST. Because Spring re-evaluates the supplier on every property read,
     * the value reaches {@code PrefersAggregationJob} live.
     */
    static final AtomicBoolean flagOverride = new AtomicBoolean(false);

    @DynamicPropertySource
    static void neo4jProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.neo4j.uri", neo4j::getBoltUrl);
        registry.add("spring.neo4j.authentication.username", () -> "neo4j");
        registry.add("spring.neo4j.authentication.password", neo4j::getAdminPassword);
        registry.add("rhizodelta.feature.prefers-aggregation.enabled",
                () -> Boolean.toString(flagOverride.get()));
    }

    @Autowired
    private TestRestTemplate restTemplate;  // pre-configured with ADMIN bearer token

    @Autowired
    private Neo4jClient neo4jClient;

    @Autowired
    private PreferenceEventService preferenceEventService;

    @Value("${rhizodelta.jwt.secret}")
    private String jwtSecret;

    @org.springframework.boot.test.web.server.LocalServerPort
    private int localPort;

    @MockBean
    private EmbeddingModelService embeddingModelService;

    @MockBean
    private SummaryAgentService summaryAgentService;

    @BeforeEach
    void cleanDatabase() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();
    }

    @AfterEach
    void resetFlag() {
        flagOverride.set(false);
    }

    @Test
    void rejectsUnauthenticated() {
        // Fresh TestRestTemplate has no Authorization interceptor → no bearer token.
        ResponseEntity<Map> response = new TestRestTemplate()
                .exchange(
                        absoluteUrl(),
                        HttpMethod.POST,
                        new HttpEntity<>(new HttpHeaders()),
                        Map.class
                );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsNonAdminRole() {
        String userToken = mintTokenWithRoles(List.of("USER"));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userToken);

        ResponseEntity<Map> response = new TestRestTemplate()
                .exchange(
                        absoluteUrl(),
                        HttpMethod.POST,
                        new HttpEntity<>(headers),
                        Map.class
                );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void returnsSkippedWhenAggregationFlagOff() {
        flagOverride.set(false);

        ResponseEntity<Map> response = restTemplate.postForEntity(url(), null, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo("SKIPPED");
        assertThat(body.get("invoked_at")).isInstanceOf(String.class);
        assertThat((Map<?, ?>) body.get("result")).isEmpty();
        assertThat(body.get("error_message")).isEqualTo("");
    }

    @Test
    void returnsOkAndProcessesEventsWhenFlagOn() {
        flagOverride.set(true);

        String userId = "alice-" + UUID.randomUUID();
        String topicId = UUID.randomUUID().toString();
        seedUserAndTopic(userId, topicId);
        for (int i = 0; i < 3; i++) {
            preferenceEventService.recordEvent(userId, topicId, "LIKE", 1.0,
                    UUID.randomUUID().toString());
        }

        ResponseEntity<Map> response = restTemplate.postForEntity(url(), null, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo("OK");
        assertThat(body.get("invoked_at")).isInstanceOf(String.class);
        Map<?, ?> result = (Map<?, ?>) body.get("result");
        assertThat(result).isNotEmpty();
        assertThat(((Number) result.get("events_processed")).longValue()).isGreaterThanOrEqualTo(3L);
        assertThat(((Number) result.get("edges_upserted")).longValue()).isGreaterThanOrEqualTo(1L);
        assertThat(result.get("window_start")).isInstanceOf(String.class);
        assertThat(body.get("error_message")).isEqualTo("");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String url() {
        return "/actuator/prefers-aggregation";
    }

    private String absoluteUrl() {
        return "http://localhost:" + localPort + url();
    }

    private void seedUserAndTopic(String userId, String topicId) {
        neo4jClient.query("""
                CREATE (u:UserAccount {user_id: $userId, username: $userId, status: 'ACTIVE'})
                CREATE (t:Topic {topic_id: $topicId, name: $topicName, source_type: 'TEST', created_at: datetime()})
                """)
                .bindAll(Map.of(
                        "userId", userId,
                        "topicId", topicId,
                        "topicName", "topic-" + topicId.substring(0, 8)
                ))
                .run();
    }

    private String mintTokenWithRoles(List<String> roles) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("user-" + UUID.randomUUID())
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofHours(1L))))
                .signWith(key)
                .compact();
    }
}
