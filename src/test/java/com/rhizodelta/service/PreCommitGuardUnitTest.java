package com.rhizodelta.service;

import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class PreCommitGuardUnitTest {

    @Test
    void shouldReportUnchangedGraphWhenSourceAndTargetDidNotAdvance() {
        Neo4jClient neo4jClient = mockNeo4jClient(
                Optional.of(Map.of("sourcePresent", true, "sourceAdvanced", false)),
                Optional.of(Map.of("targetAdvanced", false))
        );
        PreCommitGuard guard = new PreCommitGuard(neo4jClient);

        PreCommitGuard.PreCommitGuardResult result = guard.evaluate(
                "source-1",
                Instant.parse("2026-03-23T00:00:00Z"),
                "target-1"
        );

        assertThat(result.stale()).isFalse();
        assertThat(result.reason()).isEqualTo("graph unchanged");
    }

    @Test
    void shouldReportStaleWhenSourceAdvanced() {
        Neo4jClient neo4jClient = mockNeo4jClient(
                Optional.of(Map.of("sourcePresent", true, "sourceAdvanced", true)),
                Optional.empty()
        );
        PreCommitGuard guard = new PreCommitGuard(neo4jClient);

        PreCommitGuard.PreCommitGuardResult result = guard.evaluate(
                "source-1",
                Instant.parse("2026-03-23T00:00:00Z"),
                null
        );

        assertThat(result.stale()).isTrue();
        assertThat(result.reason()).isEqualTo("source branch advanced during workflow");
    }

    @Test
    void shouldReportStaleWhenSourceMissing() {
        Neo4jClient neo4jClient = mockNeo4jClient(
                Optional.of(Map.of("sourcePresent", false, "sourceAdvanced", false)),
                Optional.empty()
        );
        PreCommitGuard guard = new PreCommitGuard(neo4jClient);

        PreCommitGuard.PreCommitGuardResult result = guard.evaluate(
                "source-1",
                Instant.parse("2026-03-23T00:00:00Z"),
                null
        );

        assertThat(result.stale()).isTrue();
        assertThat(result.reason()).isEqualTo("source node missing");
    }

    private static Neo4jClient mockNeo4jClient(
            Optional<Map<String, Object>> sourceResult,
            Optional<Map<String, Object>> targetResult
    ) {
        Neo4jClient neo4jClient = org.mockito.Mockito.mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString())
                .bind(org.mockito.ArgumentMatchers.any())
                .to(org.mockito.ArgumentMatchers.anyString())
                .bind(org.mockito.ArgumentMatchers.any())
                .to(org.mockito.ArgumentMatchers.anyString())
                .fetch()
                .one())
                .thenReturn(sourceResult, targetResult);
        return neo4jClient;
    }
}
