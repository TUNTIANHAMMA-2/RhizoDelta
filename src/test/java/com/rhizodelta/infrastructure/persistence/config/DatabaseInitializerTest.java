package com.rhizodelta.infrastructure.persistence.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Answers;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseInitializerTest {
    private static final int EMBEDDING_DIMENSION = 1024;
    private static final String FIRST_SCHEMA_QUERY =
            "CREATE CONSTRAINT rhizodelta_graph_node_node_id_unique IF NOT EXISTS FOR (n:GraphNode) REQUIRE n.node_id IS UNIQUE";

    @Test
    void initializeSchemaShouldCreateAssociationRelationshipIndexes() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString()).fetch().all()).thenReturn(List.<Map<String, Object>>of());

        DatabaseInitializer initializer = new DatabaseInitializer(neo4jClient, EMBEDDING_DIMENSION);

        initializer.initializeSchema();

        verify(neo4jClient).query(
                "CREATE INDEX rhizodelta_conceptual_overlap_association_id_idx IF NOT EXISTS FOR ()-[r:CONCEPTUAL_OVERLAP]-() ON (r.association_id)"
        );
        verify(neo4jClient).query(
                "CREATE INDEX rhizodelta_relates_to_association_id_idx IF NOT EXISTS FOR ()-[r:RELATES_TO]-() ON (r.association_id)"
        );
    }

    @Test
    void initializeSchemaShouldCreateVectorIndex() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString()).fetch().all()).thenReturn(List.<Map<String, Object>>of());

        DatabaseInitializer initializer = new DatabaseInitializer(neo4jClient, EMBEDDING_DIMENSION);

        initializer.initializeSchema();

        verify(neo4jClient).query(argThat((String query) ->
                query != null
                        && query.contains("CREATE VECTOR INDEX rhizodelta_graph_node_embedding_idx")
                        && query.contains("vector.dimensions")
                        && query.contains(String.valueOf(EMBEDDING_DIMENSION))
                        && query.contains("vector.similarity_function")
                        && query.contains("cosine")
        ));
    }

    @Test
    void initializeSchemaShouldVerifyUserIdIntegrityBeforeSchemaQueries() {
        Neo4jClient neo4jClient = mockNeo4jClient(List.of(), List.of());

        DatabaseInitializer initializer = new DatabaseInitializer(neo4jClient, EMBEDDING_DIMENSION);

        initializer.initializeSchema();

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(neo4jClient, org.mockito.Mockito.atLeast(1)).query(queryCaptor.capture());

        List<String> queries = queryCaptor.getAllValues().stream()
                .filter(query -> query != null && !query.isBlank())
                .toList();
        assertThat(queries).hasSizeGreaterThanOrEqualTo(3);
        int blankQueryIndex = findQueryIndex(queries, "WHERE u.user_id IS NULL OR trim(u.user_id) = ''");
        int duplicateQueryIndex = findQueryIndex(
                queries,
                "WITH u.user_id AS uid, collect(u.username) AS names, count(*) AS n"
        );
        int schemaQueryIndex = queries.indexOf(FIRST_SCHEMA_QUERY);

        assertThat(blankQueryIndex).isNotNegative();
        assertThat(duplicateQueryIndex).isNotNegative();
        assertThat(schemaQueryIndex).isGreaterThan(duplicateQueryIndex);
        assertThat(queries.get(blankQueryIndex))
                .contains("MATCH (u:UserAccount)")
                .contains("RETURN u.username AS username LIMIT 20");
        assertThat(queries.get(duplicateQueryIndex))
                .contains("MATCH (u:UserAccount)")
                .contains("WHERE n > 1")
                .contains("RETURN uid, names LIMIT 20");
        assertReadOnly(queries.get(blankQueryIndex));
        assertReadOnly(queries.get(duplicateQueryIndex));
    }

    @Test
    void initializeSchemaShouldFailWhenUserIdIsBlank() {
        Neo4jClient neo4jClient = mockNeo4jClient(
                List.of(Map.of("username", "alice")),
                List.of()
        );
        DatabaseInitializer initializer = new DatabaseInitializer(neo4jClient, EMBEDDING_DIMENSION);

        assertThatThrownBy(initializer::initializeSchema)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank user_id usernames")
                .hasMessageContaining("alice");
        verify(neo4jClient, never()).query(FIRST_SCHEMA_QUERY);
    }

    @Test
    void initializeSchemaShouldFailWhenUserIdIsDuplicated() {
        Neo4jClient neo4jClient = mockNeo4jClient(
                List.of(),
                List.of(Map.of("uid", "dup-1", "names", List.of("alice", "bob")))
        );
        DatabaseInitializer initializer = new DatabaseInitializer(neo4jClient, EMBEDDING_DIMENSION);

        assertThatThrownBy(initializer::initializeSchema)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate user_id entries")
                .hasMessageContaining("dup-1")
                .hasMessageContaining("alice")
                .hasMessageContaining("bob");
        verify(neo4jClient, never()).query(FIRST_SCHEMA_QUERY);
    }

    @Test
    void initializeSchemaShouldAbortWhenIntegrityPreCheckQueryFails() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(argThat((String query) ->
                query != null && query.contains("WHERE u.user_id IS NULL OR trim(u.user_id) = ''")
        )).fetch().all()).thenThrow(new RuntimeException("boom"));
        DatabaseInitializer initializer = new DatabaseInitializer(neo4jClient, EMBEDDING_DIMENSION);

        assertThatThrownBy(initializer::initializeSchema)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Neo4j user_id integrity pre-check failed")
                .hasRootCauseMessage("boom");
        verify(neo4jClient, never()).query(FIRST_SCHEMA_QUERY);
    }

    private static void assertReadOnly(String query) {
        assertThat(query)
                .doesNotContain(" SET ")
                .doesNotContain(" MERGE ")
                .doesNotContain(" CREATE ")
                .doesNotContain(" DELETE ");
    }

    private static int findQueryIndex(List<String> queries, String marker) {
        for (int index = 0; index < queries.size(); index++) {
            if (queries.get(index).contains(marker)) {
                return index;
            }
        }
        return -1;
    }

    private static Neo4jClient mockNeo4jClient(
            List<Map<String, Object>> blankViolations,
            List<Map<String, Object>> duplicateViolations
    ) {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString()).fetch().all()).thenReturn(List.of());
        when(neo4jClient.query(argThat((String query) ->
                query != null && query.contains("WHERE u.user_id IS NULL OR trim(u.user_id) = ''")
        )).fetch().all()).thenReturn(blankViolations);
        when(neo4jClient.query(argThat((String query) ->
                query != null && query.contains("WITH u.user_id AS uid, collect(u.username) AS names, count(*) AS n")
        )).fetch().all()).thenReturn(duplicateViolations);
        return neo4jClient;
    }
}
