package com.rhizodelta.api;

import com.rhizodelta.ai.shared.service.EmbeddingModelService;
import com.rhizodelta.ai.summary.service.SummaryAgentService;
import com.rhizodelta.infrastructure.user.repository.FollowRepository;
import com.rhizodelta.infrastructure.user.repository.MuteRepository;
import com.rhizodelta.infrastructure.user.service.FeedService;
import com.rhizodelta.infrastructure.user.service.PrefersAggregationJob;
import com.rhizodelta.infrastructure.user.service.PreferenceEventService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * IT for prefers-aggregation-job §8 task 8.5 / 8.6.
 *
 * <p>Both flags are enabled by {@code @TestPropertySource} so the autowired {@link FeedService}
 * uses the PREFERS-aware Cypher. The "flag off" scenario constructs a fresh {@link FeedService}
 * with a mock {@link Environment} that returns {@code false}, sharing the autowired Neo4j client
 * so the underlying data is identical.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "rhizodelta.feature.prefers-aggregation.enabled=true",
        "rhizodelta.feature.prefers-feed-ranking.enabled=true"
})
class FeedRankingIntegrationTest {

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
    private FeedService feedService;

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private MuteRepository muteRepository;

    @Autowired
    private Neo4jClient neo4jClient;

    @Autowired
    private PrefersAggregationJob aggregationJob;

    @Autowired
    private PreferenceEventService preferenceEventService;

    @MockBean
    private EmbeddingModelService embeddingModelService;

    @MockBean
    private SummaryAgentService summaryAgentService;

    @BeforeEach
    void cleanDatabase() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();
    }

    @Test
    void flagOnElevatesPostsFromEngagedTopicAboveNewerPostsFromUntouchedTopic() {
        String userId = "alice-" + UUID.randomUUID();
        String topicEngagedId = UUID.randomUUID().toString();
        String topicUntouchedId = UUID.randomUUID().toString();
        UUID engagedPostId = UUID.randomUUID();
        UUID untouchedPostId = UUID.randomUUID();

        seedUserFollowsBothTopics(userId, topicEngagedId, topicUntouchedId);
        // Untouched topic post is the most recent. Without PREFERS it would win on created_at.
        Instant earlier = Instant.now().minus(2L, ChronoUnit.HOURS);
        Instant later = Instant.now().minus(30L, ChronoUnit.MINUTES);
        seedHumanPost(engagedPostId, topicEngagedId, earlier);
        seedHumanPost(untouchedPostId, topicUntouchedId, later);
        // High-weight PREFERS edge from alice to the engaged topic.
        seedPrefersEdge(userId, topicEngagedId, 25.0);

        List<Map<String, Object>> feed = feedService.getFeed(userId, 0, 50);

        assertThat(feed).hasSizeGreaterThanOrEqualTo(2);
        // Engaged-topic post should come before the newer untouched-topic post.
        int engagedIndex = indexOf(feed, engagedPostId);
        int untouchedIndex = indexOf(feed, untouchedPostId);
        assertThat(engagedIndex).as("engaged-topic post index").isLessThan(untouchedIndex);
    }

    @Test
    void flagOffRestoresCreatedAtOrderingEvenWhenPrefersEdgesExist() {
        String userId = "bob-" + UUID.randomUUID();
        String topicEngagedId = UUID.randomUUID().toString();
        String topicUntouchedId = UUID.randomUUID().toString();
        UUID engagedPostId = UUID.randomUUID();
        UUID untouchedPostId = UUID.randomUUID();

        seedUserFollowsBothTopics(userId, topicEngagedId, topicUntouchedId);
        Instant earlier = Instant.now().minus(2L, ChronoUnit.HOURS);
        Instant later = Instant.now().minus(30L, ChronoUnit.MINUTES);
        seedHumanPost(engagedPostId, topicEngagedId, earlier);
        seedHumanPost(untouchedPostId, topicUntouchedId, later);
        seedPrefersEdge(userId, topicEngagedId, 25.0);

        // Construct a FeedService with flag-off Environment, sharing the real dependencies.
        FeedService flagOffFeed = new FeedService(neo4jClient, muteRepository, followRepository, flagOffEnvironment(), nullMeterRegistryProvider());

        List<Map<String, Object>> feed = flagOffFeed.getFeed(userId, 0, 50);

        // Without ranking flag, ordering reverts to created_at DESC.
        int engagedIndex = indexOf(feed, engagedPostId);
        int untouchedIndex = indexOf(feed, untouchedPostId);
        assertThat(untouchedIndex).as("untouched-topic post (newer) should come first").isLessThan(engagedIndex);
    }

    @Test
    void newTopicWithoutPrefersEdgeStillAppearsInFeed() {
        String userId = "carol-" + UUID.randomUUID();
        String engagedId = UUID.randomUUID().toString();
        String newId = UUID.randomUUID().toString();
        UUID engagedPostId = UUID.randomUUID();
        UUID newPostId = UUID.randomUUID();

        seedUserFollowsBothTopics(userId, engagedId, newId);
        seedHumanPost(engagedPostId, engagedId, Instant.now().minus(2L, ChronoUnit.HOURS));
        seedHumanPost(newPostId, newId, Instant.now().minus(30L, ChronoUnit.MINUTES));
        seedPrefersEdge(userId, engagedId, 25.0);

        List<Map<String, Object>> feed = feedService.getFeed(userId, 0, 50);

        // Spec requirement: untouched followed topic must still surface (coalesce to 0, ranked after engaged).
        assertThat(indexOf(feed, newPostId)).isNotEqualTo(-1);
        assertThat(indexOf(feed, engagedPostId)).isNotEqualTo(-1);
    }

    @Test
    void endToEndSmokePreferenceEventToPrefersToFeedReorder() {
        String userId = "dave-" + UUID.randomUUID();
        String hotTopicId = UUID.randomUUID().toString();
        String coldTopicId = UUID.randomUUID().toString();
        UUID hotPostId = UUID.randomUUID();
        UUID coldPostId = UUID.randomUUID();

        seedUserFollowsBothTopics(userId, hotTopicId, coldTopicId);
        seedHumanPost(hotPostId, hotTopicId, Instant.now().minus(2L, ChronoUnit.HOURS));
        seedHumanPost(coldPostId, coldTopicId, Instant.now().minus(30L, ChronoUnit.MINUTES));

        // Emit several preference events via the production write path.
        for (int i = 0; i < 5; i++) {
            preferenceEventService.recordEvent(userId, hotTopicId, "LIKE", 1.0, hotPostId.toString());
        }

        // Trigger an immediate aggregation tick (bypassing the 5-minute scheduler).
        aggregationJob.runOnce();

        // Now the feed should elevate hot topic over the newer cold post.
        List<Map<String, Object>> feed = feedService.getFeed(userId, 0, 50);
        int hotIndex = indexOf(feed, hotPostId);
        int coldIndex = indexOf(feed, coldPostId);
        assertThat(hotIndex).isLessThan(coldIndex);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void seedUserFollowsBothTopics(String userId, String topicAId, String topicBId) {
        neo4jClient.query("""
                CREATE (u:UserAccount {user_id: $userId, username: $username, status: 'ACTIVE'})
                CREATE (a:Topic {topic_id: $topicAId, name: $aName})
                CREATE (b:Topic {topic_id: $topicBId, name: $bName})
                CREATE (u)-[:FOLLOWS {since: datetime()}]->(a)
                CREATE (u)-[:FOLLOWS {since: datetime()}]->(b)
                """)
                .bindAll(Map.of(
                        "userId", userId,
                        "username", userId,
                        "topicAId", topicAId,
                        "aName", "topic-a",
                        "topicBId", topicBId,
                        "bName", "topic-b"
                ))
                .run();
    }

    private void seedHumanPost(UUID nodeId, String topicId, Instant createdAt) {
        neo4jClient.query("""
                CREATE (n:Human_Post:GraphNode {
                  node_id: $nodeId,
                  request_id: $requestId,
                  author_id: $authorId,
                  content: $content,
                  topic_id: $topicId,
                  created_at: $createdAt
                })
                """)
                .bindAll(Map.of(
                        "nodeId", nodeId.toString(),
                        "requestId", "req-" + nodeId.toString().substring(0, 8),
                        "authorId", "seed-author",
                        "content", "post-in-" + topicId.substring(0, 8),
                        "topicId", topicId,
                        "createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC)
                ))
                .run();
    }

    private void seedPrefersEdge(String userId, String topicId, double weight) {
        neo4jClient.query("""
                MATCH (u:UserAccount {user_id: $userId}), (t:Topic {topic_id: $topicId})
                MERGE (u)-[r:PREFERS]->(t)
                ON CREATE SET r.created_at = datetime()
                SET r.weight = $weight,
                    r.last_event_at = datetime(),
                    r.updated_at = datetime()
                """)
                .bindAll(Map.of(
                        "userId", userId,
                        "topicId", topicId,
                        "weight", weight
                ))
                .run();
    }

    private int indexOf(List<Map<String, Object>> feed, UUID nodeId) {
        for (int i = 0; i < feed.size(); i++) {
            if (nodeId.toString().equals(feed.get(i).get("node_id"))) {
                return i;
            }
        }
        return -1;
    }

    private Environment flagOffEnvironment() {
        Environment env = mock(Environment.class);
        when(env.getProperty(eq(FeedService.FLAG_FEED_RANKING_KEY), eq(Boolean.class), eq(Boolean.FALSE)))
                .thenReturn(Boolean.FALSE);
        when(env.getProperty(eq(FeedService.FLAG_AGGREGATION_KEY), eq(Boolean.class), eq(Boolean.FALSE)))
                .thenReturn(Boolean.FALSE);
        return env;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<MeterRegistry> nullMeterRegistryProvider() {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
