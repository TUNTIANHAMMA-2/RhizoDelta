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
    private static final String USER_ACCOUNT_USER_ID_UNIQUE_QUERY =
            "CREATE CONSTRAINT rhizodelta_user_account_user_id_unique IF NOT EXISTS FOR (n:UserAccount) REQUIRE n.user_id IS UNIQUE";
    private static final String USER_ACCOUNT_STATUS_INDEX_QUERY =
            "CREATE INDEX rhizodelta_user_account_status_idx IF NOT EXISTS FOR (n:UserAccount) ON (n.status)";
    private static final String AUTHORED_CREATED_AT_INDEX_QUERY =
            "CREATE INDEX rhizodelta_authored_created_at_idx IF NOT EXISTS FOR ()-[r:AUTHORED]-() ON (r.created_at)";
    private static final String USER_PROFILE_USER_ID_UNIQUE_QUERY =
            "CREATE CONSTRAINT rhizodelta_user_profile_user_id_unique IF NOT EXISTS FOR (n:UserProfile) REQUIRE n.user_id IS UNIQUE";

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
    void initializeSchemaShouldCreatePrefersRelationshipIndexes() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString()).fetch().all()).thenReturn(List.<Map<String, Object>>of());

        DatabaseInitializer initializer = new DatabaseInitializer(neo4jClient, EMBEDDING_DIMENSION);

        initializer.initializeSchema();

        verify(neo4jClient).query(
                "CREATE INDEX rhizodelta_prefers_weight_idx IF NOT EXISTS FOR ()-[r:PREFERS]-() ON (r.weight)"
        );
        verify(neo4jClient).query(
                "CREATE INDEX rhizodelta_prefers_updated_at_idx IF NOT EXISTS FOR ()-[r:PREFERS]-() ON (r.updated_at)"
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
    void initializeSchemaShouldCreateUserAccountConstraintAndStatusIndex() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString()).fetch().all()).thenReturn(List.<Map<String, Object>>of());
        DatabaseInitializer initializer = new DatabaseInitializer(neo4jClient, EMBEDDING_DIMENSION);

        initializer.initializeSchema();

        verify(neo4jClient).query(USER_ACCOUNT_USER_ID_UNIQUE_QUERY);
        verify(neo4jClient).query(USER_ACCOUNT_STATUS_INDEX_QUERY);
        verify(neo4jClient).query(USER_PROFILE_USER_ID_UNIQUE_QUERY);
        verify(neo4jClient, never()).query(
                "CREATE INDEX rhizodelta_user_account_user_id_idx IF NOT EXISTS FOR (n:UserAccount) ON (n.user_id)"
        );
    }

    @Test
    void initializeSchemaShouldCreateAuthoredCreatedAtIndex() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString()).fetch().all()).thenReturn(List.<Map<String, Object>>of());

        DatabaseInitializer initializer = new DatabaseInitializer(neo4jClient, EMBEDDING_DIMENSION);

        initializer.initializeSchema();

        verify(neo4jClient).query(AUTHORED_CREATED_AT_INDEX_QUERY);
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
                "WITH u.user_id AS uid, collect(u.username)[0..20] AS names, count(*) AS n"
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
                .contains("RETURN uid, names, n LIMIT 20");
        assertReadOnly(queries.get(blankQueryIndex));
        assertReadOnly(queries.get(duplicateQueryIndex));
    }

    @Test
    void initializeSchemaShouldInspectIndexesDuringVerificationLogging() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString()).fetch().all()).thenReturn(List.<Map<String, Object>>of());
        DatabaseInitializer initializer = new DatabaseInitializer(neo4jClient, EMBEDDING_DIMENSION);

        initializer.initializeSchema();

        verify(neo4jClient).query(argThat((String query) ->
                query != null
                        && query.contains("SHOW CONSTRAINTS")
                        && query.contains("WHERE name STARTS WITH 'rhizodelta_'")
        ));
        verify(neo4jClient).query(argThat((String query) ->
                query != null
                        && query.contains("SHOW INDEXES")
                        && query.contains("WHERE name STARTS WITH 'rhizodelta_'")
        ));
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
                List.of(Map.of("uid", "dup-1", "names", List.of("alice", "bob"), "n", 2L))
        );
        DatabaseInitializer initializer = new DatabaseInitializer(neo4jClient, EMBEDDING_DIMENSION);

        assertThatThrownBy(initializer::initializeSchema)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate user_id entries")
                .hasMessageContaining("dup-1")
                .hasMessageContaining("alice")
                .hasMessageContaining("bob")
                .hasMessageContaining("total=2");
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

    @Test
    void initializeSchemaShouldRunUserProfileBackfillUntilPendingReachesZero() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString()).fetch().all()).thenReturn(List.<Map<String, Object>>of());
        when(neo4jClient.query(argThat((String query) ->
                query != null && query.contains("RETURN count(u) AS pending")
        )).fetch().one())
                .thenReturn(java.util.Optional.of(Map.<String, Object>of("pending", 5L)))
                .thenReturn(java.util.Optional.of(Map.<String, Object>of("pending", 0L)));
        when(neo4jClient.query(argThat((String query) ->
                query != null && query.contains("MERGE (p:UserProfile {user_id: u.user_id})")
                        && query.contains("REMOVE u.display_name")
        )).fetch().one())
                .thenReturn(java.util.Optional.of(Map.<String, Object>of("migrated", 3L, "skipped", 2L)));

        DatabaseInitializer initializer = new DatabaseInitializer(neo4jClient, EMBEDDING_DIMENSION);
        initializer.initializeSchema();

        verify(neo4jClient).query(argThat((String query) ->
                query != null && query.contains("MERGE (p:UserProfile {user_id: u.user_id})")
                        && query.contains("WITH u LIMIT 500")
                        && query.contains("REMOVE u.display_name")
        ));
    }

    @Test
    void authoredBackfillAndAuditQueriesShouldMatchPhaseTwoContract() {
        assertThat(DatabaseInitializer.authoredBackfillQuery())
                .contains("MATCH (p:Human_Post)")
                .contains("WHERE p.author_id IS NOT NULL")
                .contains("MATCH (u:UserAccount {user_id: p.author_id})")
                .contains("MERGE (u)-[r:AUTHORED]->(p)")
                .contains("ON CREATE SET r.created_at = p.created_at");

        assertThat(DatabaseInitializer.authoredAuditQuery())
                .contains("OPTIONAL MATCH (u:UserAccount {user_id: p.author_id})-[:AUTHORED]->(p)")
                .contains("WHERE u IS NULL")
                .contains("RETURN p.node_id AS nodeId, p.author_id AS authorId")
                .contains("LIMIT 100");
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
                query != null && query.contains("WITH u.user_id AS uid, collect(u.username)[0..20] AS names, count(*) AS n")
        )).fetch().all()).thenReturn(duplicateViolations);
        return neo4jClient;
    }
}
