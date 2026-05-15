package com.rhizodelta.infrastructure.user.service;

import com.rhizodelta.infrastructure.user.repository.FollowRepository;
import com.rhizodelta.infrastructure.user.repository.MuteRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
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

        when(followRepository.countFollows("alice")).thenReturn(1L);
        when(muteRepository.getMutedUserIds("alice")).thenReturn(List.of());
        when(muteRepository.getMutedTopicIds("alice")).thenReturn(List.of());
        when(neo4jClient.query(anyString()).bindAll(any(Map.class)).fetch().all()).thenReturn(List.of());

        FeedService service = new FeedService(neo4jClient, muteRepository, followRepository, env);
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

        when(followRepository.countFollows("alice")).thenReturn(1L);
        when(muteRepository.getMutedUserIds("alice")).thenReturn(List.of());
        when(muteRepository.getMutedTopicIds("alice")).thenReturn(List.of());
        when(neo4jClient.query(anyString()).bindAll(any(Map.class)).fetch().all()).thenReturn(List.of());

        FeedService service = new FeedService(neo4jClient, muteRepository, followRepository, env);
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

        when(followRepository.countFollows("alice")).thenReturn(0L);
        when(neo4jClient.query(anyString()).bindAll(any(Map.class)).fetch().all()).thenReturn(List.of());

        FeedService service = new FeedService(neo4jClient, muteRepository, followRepository, env);
        service.getFeed("alice", 0, 20);

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(neo4jClient, atLeastOnce()).query(queryCaptor.capture());
        assertThat(queryCaptor.getAllValues())
                .noneMatch(q -> q.equals(FeedService.FEED_QUERY))
                .noneMatch(q -> q.equals(FeedService.FEED_QUERY_WITH_PREFERS_RANKING));
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
