package com.rhizodelta.api;

import com.rhizodelta.ai.shared.service.EmbeddingModelService;
import com.rhizodelta.ai.summary.service.SummaryAgentService;
import com.rhizodelta.infrastructure.user.observability.PrefersAggregationMetrics;
import com.rhizodelta.infrastructure.user.repository.PrefersAggregationRepository;
import com.rhizodelta.infrastructure.user.service.PrefersAggregationJob;
import com.rhizodelta.infrastructure.user.service.PrefersAggregationPolicy;
import com.rhizodelta.infrastructure.user.service.PrefersAggregationResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.env.Environment;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * IT 覆盖 prefers-aggregation-job 变更 §8 任务：
 * <ul>
 *   <li>8.1 / 8.2 三事件聚合 + 幂等</li>
 *   <li>8.3 24 小时窗口边界</li>
 *   <li>8.4 flag 关闭即跳过</li>
 * </ul>
 *
 * <p>测试 profile 默认开启聚合 flag，Job 通过 {@link Environment} 在每次 tick 读取，
 * 因此可以在不重启 context 的情况下手工构造关闭路径。
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "rhizodelta.feature.prefers-aggregation.enabled=true",
        "rhizodelta.preference.half-life-days=30",
        "rhizodelta.preference.window-hours=24"
})
class PrefersAggregationIntegrationTest {

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
    private PrefersAggregationJob job;

    @Autowired
    private PrefersAggregationRepository repository;

    @Autowired
    private PrefersAggregationPolicy policy;

    @Autowired
    private PrefersAggregationMetrics metrics;

    @Autowired
    private Neo4jClient neo4jClient;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    @MockBean
    private EmbeddingModelService embeddingModelService;

    @MockBean
    private SummaryAgentService summaryAgentService;

    @BeforeEach
    void cleanDatabase() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();
    }

    @Test
    void aggregatesMultipleEventTypesIntoSinglePrefersEdge() {
        UUID userId = UUID.randomUUID();
        UUID topicId = UUID.randomUUID();
        seedUserAndTopic(userId, topicId);
        // Three events all very recent ⇒ decay ≈ 1.0.
        // Sum of base weights: VIEW(0.5) + LIKE(2.0) + SHARE(3.0) = 5.5.
        seedEvent(userId, topicId, "VIEW", Instant.now().minus(60L, ChronoUnit.SECONDS));
        seedEvent(userId, topicId, "LIKE", Instant.now().minus(30L, ChronoUnit.SECONDS));
        Instant mostRecent = Instant.now().minus(5L, ChronoUnit.SECONDS);
        seedEvent(userId, topicId, "SHARE", mostRecent);

        job.runOnce();

        Map<String, Object> edge = neo4jClient.query("""
                MATCH (u:UserAccount {user_id: $userId})-[r:PREFERS]->(t:Topic {topic_id: $topicId})
                RETURN r.weight AS weight, toString(r.last_event_at) AS lastEventAt
                """)
                .bind(userId.toString()).to("userId")
                .bind(topicId.toString()).to("topicId")
                .fetch().one().orElseThrow(() -> new AssertionError("PREFERS edge not created"));

        double weight = ((Number) edge.get("weight")).doubleValue();
        assertThat(weight).isCloseTo(5.5, within(0.05));

        // last_event_at should equal the SHARE event (the most recent contributor).
        // We compare to second-precision to avoid driver/Neo4j datetime round-trip flakiness.
        String lastEventAt = (String) edge.get("lastEventAt");
        Instant parsed = OffsetDateTime.parse(lastEventAt).toInstant();
        assertThat(parsed).isCloseTo(mostRecent, within(2L, ChronoUnit.SECONDS));
    }

    @Test
    void runningTwiceWithoutNewEventsIsIdempotent() {
        UUID userId = UUID.randomUUID();
        UUID topicId = UUID.randomUUID();
        seedUserAndTopic(userId, topicId);
        seedEvent(userId, topicId, "LIKE", Instant.now().minus(60L, ChronoUnit.SECONDS));

        job.runOnce();
        double weightAfterFirst = readEdgeWeight(userId, topicId);
        long countAfterFirst = countPrefers();

        job.runOnce();
        double weightAfterSecond = readEdgeWeight(userId, topicId);
        long countAfterSecond = countPrefers();

        assertThat(weightAfterSecond).isCloseTo(weightAfterFirst, within(0.001));
        assertThat(countAfterSecond).isEqualTo(countAfterFirst).isEqualTo(1L);
    }

    @Test
    void onlyEventsInsideWindowContributeButOlderEventsAreNotMutated() {
        UUID userId = UUID.randomUUID();
        UUID topicId = UUID.randomUUID();
        seedUserAndTopic(userId, topicId);
        Instant insideWindow = Instant.now().minus(1L, ChronoUnit.HOURS);
        Instant outsideWindow = Instant.now().minus(25L, ChronoUnit.HOURS);
        seedEvent(userId, topicId, "LIKE", insideWindow);   // inside 24h
        seedEvent(userId, topicId, "SHARE", outsideWindow); // outside 24h ⇒ excluded

        job.runOnce();

        double weight = readEdgeWeight(userId, topicId);
        // Expect ~LIKE(2.0) only, since SHARE is outside the window.
        // The 1h-old LIKE has decay ≈ 0.5 ^ (3600 / 86400 / 30) ≈ 0.999 ⇒ weight ≈ 2.0.
        assertThat(weight).isCloseTo(2.0, within(0.01));

        long eventsRemaining = neo4jClient.query("MATCH (e:PreferenceEvent) RETURN count(e) AS n")
                .fetch().one().map(r -> ((Number) r.get("n")).longValue()).orElse(-1L);
        assertThat(eventsRemaining).as("Aggregation must not delete or mutate events").isEqualTo(2L);
    }

    @Test
    void flagOffSkipsAggregationAndProducesNoEdges() {
        // Build a fresh job with a mock Environment that returns FALSE for the flag,
        // sharing the real repository / policy / metrics so seed data is visible.
        Environment env = mock(Environment.class);
        when(env.getProperty(
                org.mockito.ArgumentMatchers.eq("rhizodelta.feature.prefers-aggregation.enabled"),
                org.mockito.ArgumentMatchers.eq(Boolean.class),
                org.mockito.ArgumentMatchers.eq(Boolean.FALSE)
        )).thenReturn(Boolean.FALSE);
        PrefersAggregationJob disabledJob = new PrefersAggregationJob(repository, policy, metrics, env);

        UUID userId = UUID.randomUUID();
        UUID topicId = UUID.randomUUID();
        seedUserAndTopic(userId, topicId);
        seedEvent(userId, topicId, "LIKE", Instant.now().minus(60L, ChronoUnit.SECONDS));

        double skippedBefore = skippedRunCount();
        disabledJob.runOnce();
        double skippedAfter = skippedRunCount();

        assertThat(countPrefers()).isZero();
        assertThat(skippedAfter - skippedBefore).isEqualTo(1.0);
    }

    @Test
    void emptyDatabaseProducesNoEdgesAndNoErrors() {
        // Sanity boundary: aggregation against a fresh DB returns gracefully with zero work.
        job.runOnce();

        assertThat(countPrefers()).isZero();
    }

    @Test
    void repositoryDirectInvocationProducesIdenticalShape() {
        // Bypass the job to keep the repository's contract under direct test pressure
        // — exercises PrefersAggregationResult mapping independently of Job wiring.
        UUID userId = UUID.randomUUID();
        UUID topicId = UUID.randomUUID();
        seedUserAndTopic(userId, topicId);
        seedEvent(userId, topicId, "EXPAND", Instant.now().minus(2L, ChronoUnit.HOURS));

        Instant runStartedAt = Instant.now();
        PrefersAggregationResult result = repository.runAggregation(
                runStartedAt.minus(24L, ChronoUnit.HOURS),
                policy.halfLifeDays(),
                policy.weightCeiling(),
                runStartedAt
        );

        assertThat(result.eventsProcessed()).isEqualTo(1L);
        assertThat(result.edgesUpserted()).isEqualTo(1L);
        assertThat(result.runStartedAt()).isEqualTo(runStartedAt);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void seedUserAndTopic(UUID userId, UUID topicId) {
        neo4jClient.query("""
                CREATE (u:UserAccount {user_id: $userId, username: $username, status: 'ACTIVE'})
                CREATE (t:Topic {topic_id: $topicId, name: $topicName})
                """)
                .bind(userId.toString()).to("userId")
                .bind("user-" + userId.toString().substring(0, 8)).to("username")
                .bind(topicId.toString()).to("topicId")
                .bind("topic-" + topicId.toString().substring(0, 8)).to("topicName")
                .run();
    }

    private void seedEvent(UUID userId, UUID topicId, String type, Instant at) {
        neo4jClient.query("""
                MATCH (u:UserAccount {user_id: $userId})
                MATCH (t:Topic {topic_id: $topicId})
                CREATE (e:PreferenceEvent {
                  event_id: $eventId,
                  type: $type,
                  weight: 1.0,
                  at: $at,
                  source_node_id: ''
                })
                CREATE (u)-[:EMITTED]->(e)
                CREATE (e)-[:TOWARD]->(t)
                """)
                .bindAll(Map.of(
                        "userId", userId.toString(),
                        "topicId", topicId.toString(),
                        "eventId", UUID.randomUUID().toString(),
                        "type", type,
                        "at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC)
                ))
                .run();
    }

    private double readEdgeWeight(UUID userId, UUID topicId) {
        return neo4jClient.query("""
                MATCH (u:UserAccount {user_id: $userId})-[r:PREFERS]->(t:Topic {topic_id: $topicId})
                RETURN r.weight AS weight
                """)
                .bind(userId.toString()).to("userId")
                .bind(topicId.toString()).to("topicId")
                .fetch().one().map(r -> ((Number) r.get("weight")).doubleValue())
                .orElseThrow(() -> new AssertionError("PREFERS edge not present"));
    }

    private long countPrefers() {
        return neo4jClient.query("MATCH ()-[r:PREFERS]->() RETURN count(r) AS n")
                .fetch().one().map(r -> ((Number) r.get("n")).longValue()).orElse(0L);
    }

    private double skippedRunCount() {
        if (meterRegistry == null) {
            return 0.0;
        }
        return meterRegistry.counter("prefers_aggregation_run_total", "outcome", "skipped").count();
    }
}
