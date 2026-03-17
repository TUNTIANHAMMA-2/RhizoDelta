package com.rhizodelta.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Component
public class DatabaseInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseInitializer.class);
    private static final String VECTOR_INDEX_NAME = "rhizodelta_graph_node_embedding_idx";

    private static final List<String> SCHEMA_QUERIES = List.of(
            "CREATE CONSTRAINT rhizodelta_graph_node_node_id_unique IF NOT EXISTS FOR (n:GraphNode) REQUIRE n.node_id IS UNIQUE",
            "CREATE CONSTRAINT rhizodelta_human_post_request_id_unique IF NOT EXISTS FOR (n:Human_Post) REQUIRE n.request_id IS UNIQUE",
            "CREATE CONSTRAINT rhizodelta_human_post_decision_id_unique IF NOT EXISTS FOR (n:Human_Post) REQUIRE n.decision_id IS UNIQUE",
            "CREATE CONSTRAINT rhizodelta_ai_consensus_decision_id_unique IF NOT EXISTS FOR (n:AI_Consensus) REQUIRE n.decision_id IS UNIQUE",
            "CREATE CONSTRAINT rhizodelta_result_decision_id_unique IF NOT EXISTS FOR (n:Result) REQUIRE n.decision_id IS UNIQUE",
            "CREATE INDEX rhizodelta_human_post_author_id_idx IF NOT EXISTS FOR (n:Human_Post) ON (n.author_id)",
            "CREATE INDEX rhizodelta_human_post_created_at_idx IF NOT EXISTS FOR (n:Human_Post) ON (n.created_at)",
            "CREATE INDEX rhizodelta_ai_consensus_created_at_idx IF NOT EXISTS FOR (n:AI_Consensus) ON (n.created_at)",
            "CREATE INDEX rhizodelta_result_created_at_idx IF NOT EXISTS FOR (n:Result) ON (n.created_at)",
            "CREATE INDEX rhizodelta_human_post_operation_id_idx IF NOT EXISTS FOR (n:Human_Post) ON (n.operation_id)",
            "CREATE INDEX rhizodelta_conceptual_overlap_association_id_idx IF NOT EXISTS FOR ()-[r:CONCEPTUAL_OVERLAP]-() ON (r.association_id)",
            "CREATE INDEX rhizodelta_relates_to_association_id_idx IF NOT EXISTS FOR ()-[r:RELATES_TO]-() ON (r.association_id)"
    );

    private final Neo4jClient neo4jClient;
    private final int embeddingDimension;

    public DatabaseInitializer(
            Neo4jClient neo4jClient,
            @Value("${rhizodelta.embedding.dimension}") int embeddingDimension
    ) {
        this.neo4jClient = neo4jClient;
        this.embeddingDimension = embeddingDimension;
    }

    @PostConstruct
    void initializeSchema() {
        for (String query : SCHEMA_QUERIES) {
            executeSchemaQuery(query);
        }
        executeSchemaQuery(buildVectorIndexQuery());
        logConstraintStatus();
    }

    private void executeSchemaQuery(String query) {
        try {
            neo4jClient.query(query).run();
            LOGGER.info("Schema query applied: {}", query);
        } catch (Exception exception) {
            LOGGER.error("Failed to apply schema query: {}", query, exception);
            throw new IllegalStateException("Neo4j schema initialization failed", exception);
        }
    }

    private void logConstraintStatus() {
        Collection<Map<String, Object>> constraints = neo4jClient.query("""
                SHOW CONSTRAINTS
                YIELD name
                WHERE name STARTS WITH 'rhizodelta_'
                RETURN name
                ORDER BY name
                """).fetch().all();
        LOGGER.info("Neo4j constraints/indexes verified: {}", constraints);
    }

    private String buildVectorIndexQuery() {
        return """
                CREATE VECTOR INDEX %s IF NOT EXISTS
                FOR (n:GraphNode)
                ON n.embedding
                OPTIONS { indexConfig: {
                  `vector.dimensions`: %d,
                  `vector.similarity_function`: 'cosine'
                }}
                """.formatted(VECTOR_INDEX_NAME, embeddingDimension).trim();
    }
}
