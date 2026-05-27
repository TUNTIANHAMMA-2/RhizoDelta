package com.rhizodelta.infrastructure.user.service;

import com.rhizodelta.infrastructure.user.repository.FollowRepository;
import com.rhizodelta.infrastructure.user.repository.MuteRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedServiceUnitTest {

    @Test
    void flagOffSendsOriginalCypherToNeo4jClient() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        FollowRepository followRepository = mock(FollowRepository.class);
        MuteRepository muteRepository = mock(MuteRepository.class);
        Environment env = environmentWith(false, false);

        when(followRepository.hasFollows("alice")).thenReturn(true);
        when(muteRepository.getMuteFilters("alice")).thenReturn(MuteRepository.MuteFilters.empty());
        when(neo4jClient.query(anyString()).bindAll(any(Map.class)).fetch().all()).thenReturn(List.of());

        FeedService service = new FeedService(neo4jClient, muteRepository, followRepository, env, emptyMeterRegistryProvider());
        service.getFeed("alice", 0, 20);

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(neo4jClient, atLeastOnce()).query(queryCaptor.capture());
        assertThat(queryCaptor.getAllValues())
                .anyMatch(q -> q.equals(FeedService.FEED_QUERY))
                .noneMatch(q -> q.equals(FeedService.FEED_QUERY_WITH_PREFERS_RANKING));
    }

    @Test
    void flagOnSendsPrefersAwareCypher() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        FollowRepository followRepository = mock(FollowRepository.class);
        MuteRepository muteRepository = mock(MuteRepository.class);
        Environment env = environmentWith(true, true);

        when(followRepository.hasFollows("alice")).thenReturn(true);
        when(muteRepository.getMuteFilters("alice")).thenReturn(MuteRepository.MuteFilters.empty());
        when(neo4jClient.query(anyString()).bindAll(any(Map.class)).fetch().all()).thenReturn(List.of());

        FeedService service = new FeedService(neo4jClient, muteRepository, followRepository, env, emptyMeterRegistryProvider());
        service.getFeed("alice", 0, 20);

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(neo4jClient, atLeastOnce()).query(queryCaptor.capture());
        assertThat(queryCaptor.getAllValues())
                .anyMatch(q -> q.equals(FeedService.FEED_QUERY_WITH_PREFERS_RANKING))
                .noneMatch(q -> q.equals(FeedService.FEED_QUERY));
    }

    @Test
    void zeroFollowsRoutesToGlobalQueryRegardlessOfFlag() {
        // When the user has no FOLLOWS, FeedService falls back to GLOBAL_FEED_QUERY by design
        // (FeedService.java:140). PREFERS ranking does not enter that branch — verify it stays so.
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        FollowRepository followRepository = mock(FollowRepository.class);
        MuteRepository muteRepository = mock(MuteRepository.class);
        Environment env = environmentWith(true, true);

        when(followRepository.hasFollows("alice")).thenReturn(false);
        when(muteRepository.getMuteFilters("alice")).thenReturn(MuteRepository.MuteFilters.empty());
        when(neo4jClient.query(anyString()).bindAll(any(Map.class)).fetch().all()).thenReturn(List.of());

        FeedService service = new FeedService(neo4jClient, muteRepository, followRepository, env, emptyMeterRegistryProvider());
        service.getFeed("alice", 0, 20);

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(neo4jClient, atLeastOnce()).query(queryCaptor.capture());
        assertThat(queryCaptor.getAllValues())
                .noneMatch(q -> q.equals(FeedService.FEED_QUERY))
                .noneMatch(q -> q.equals(FeedService.FEED_QUERY_WITH_PREFERS_RANKING));
    }

    @Test
    void flagOffRecordsVariantPlain() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FeedService service = serviceWithRegistry(registry, /*ranking=*/false, /*followCount=*/1L, List.of());

        service.getFeed("alice", 0, 20);

        assertThat(registry.find(FeedService.METRIC_FEED_QUERY).tag("variant", "plain").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find(FeedService.METRIC_FEED_QUERY).tag("variant", "prefers").counter())
                .as("prefers variant should not be incremented when flag is off")
                .isNull();
    }

    @Test
    void flagOnRecordsVariantPrefers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FeedService service = serviceWithRegistry(registry, /*ranking=*/true, /*followCount=*/1L, List.of());

        service.getFeed("alice", 0, 20);

        assertThat(registry.find(FeedService.METRIC_FEED_QUERY).tag("variant", "prefers").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find(FeedService.METRIC_FEED_QUERY).tag("variant", "plain").counter())
                .isNull();
    }

    @Test
    void noFollowsRecordsVariantGlobal() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FeedService service = serviceWithRegistry(registry, /*ranking=*/true, /*followCount=*/0L, List.of());

        service.getFeed("alice", 0, 20);

        assertThat(registry.find(FeedService.METRIC_FEED_QUERY).tag("variant", "global").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void prefersWeightStrippedFromResponse() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<Map<String, Object>> rows = List.of(
                Map.of("node_id", "n1", "prefers_weight", 5.0),
                Map.of("node_id", "n2", "prefers_weight", 0.0)
        );
        FeedService service = serviceWithRegistry(registry, /*ranking=*/true, /*followCount=*/1L, rows);

        List<Map<String, Object>> response = service.getFeed("alice", 0, 20);

        assertThat(response).hasSize(2);
        assertThat(response).allSatisfy(row -> assertThat(row).doesNotContainKey("prefers_weight"));
        assertThat(response.get(0)).containsEntry("node_id", "n1");
        assertThat(response.get(1)).containsEntry("node_id", "n2");
    }

    @Test
    void prefersVariantCountsRowsWithAndWithoutWeight() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<Map<String, Object>> rows = List.of(
                Map.of("node_id", "hot1", "prefers_weight", 7.5),
                Map.of("node_id", "hot2", "prefers_weight", 0.001),  // still > 0
                Map.of("node_id", "cold1", "prefers_weight", 0.0),
                Map.of("node_id", "cold2", "prefers_weight", 0.0)
        );
        FeedService service = serviceWithRegistry(registry, /*ranking=*/true, /*followCount=*/1L, rows);

        service.getFeed("alice", 0, 20);

        assertThat(registry.find(FeedService.METRIC_FEED_ITEMS).tag("has_prefers_weight", "true").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.find(FeedService.METRIC_FEED_ITEMS).tag("has_prefers_weight", "false").counter().count())
                .isEqualTo(2.0);
    }

    @Test
    void plainVariantDoesNotEmitItemCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<Map<String, Object>> rows = List.of(
                Map.of("node_id", "n1"),
                Map.of("node_id", "n2")
        );
        FeedService service = serviceWithRegistry(registry, /*ranking=*/false, /*followCount=*/1L, rows);

        service.getFeed("alice", 0, 20);

        // feed_items_returned_total is PREFERS-only by design — never registered in plain mode.
        assertThat(registry.find(FeedService.METRIC_FEED_ITEMS).counters()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private FeedService serviceWithRegistry(
            MeterRegistry registry,
            boolean rankingEnabled,
            long followCount,
            List<Map<String, Object>> rows
    ) {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        FollowRepository followRepository = mock(FollowRepository.class);
        MuteRepository muteRepository = mock(MuteRepository.class);

        when(followRepository.hasFollows("alice")).thenReturn(followCount > 0L);
        when(muteRepository.getMuteFilters("alice")).thenReturn(MuteRepository.MuteFilters.empty());
        when(neo4jClient.query(anyString()).bindAll(any(Map.class)).fetch().all()).thenReturn((List) rows);

        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);

        return new FeedService(neo4jClient, muteRepository, followRepository,
                environmentWith(rankingEnabled, rankingEnabled), provider);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> emptyMeterRegistryProvider() {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private Environment environmentWith(boolean rankingEnabled, boolean aggregationEnabled) {
        Environment env = mock(Environment.class);
        when(env.getProperty(eq(FeedService.FLAG_FEED_RANKING_KEY), eq(Boolean.class), eq(Boolean.FALSE)))
                .thenReturn(rankingEnabled);
        when(env.getProperty(eq(FeedService.FLAG_AGGREGATION_KEY), eq(Boolean.class), eq(Boolean.FALSE)))
                .thenReturn(aggregationEnabled);
        return env;
    }
}
