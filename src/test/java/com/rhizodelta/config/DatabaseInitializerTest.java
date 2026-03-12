package com.rhizodelta.config;

import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseInitializerTest {
    private static final int EMBEDDING_DIMENSION = 1024;

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
}
