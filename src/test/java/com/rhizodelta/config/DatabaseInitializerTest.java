package com.rhizodelta.config;

import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseInitializerTest {
    @Test
    void initializeSchemaShouldCreateDecisionIdRelationshipIndexes() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString()).fetch().all()).thenReturn(List.<Map<String, Object>>of());

        DatabaseInitializer initializer = new DatabaseInitializer(neo4jClient);

        initializer.initializeSchema();

        verify(neo4jClient).query(
                "CREATE INDEX rhizodelta_merged_into_decision_id_idx IF NOT EXISTS FOR ()-[r:MERGED_INTO]-() ON (r.decision_id)"
        );
        verify(neo4jClient).query(
                "CREATE INDEX rhizodelta_branched_from_decision_id_idx IF NOT EXISTS FOR ()-[r:BRANCHED_FROM]-() ON (r.decision_id)"
        );
    }
}
