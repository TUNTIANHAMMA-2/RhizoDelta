package com.rhizodelta.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Component
public class DatabaseInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseInitializer.class);

    private static final List<String> SCHEMA_QUERIES = List.of(
            "CREATE CONSTRAINT rhizodelta_human_post_node_id_unique IF NOT EXISTS FOR (n:Human_Post) REQUIRE n.node_id IS UNIQUE",
            "CREATE CONSTRAINT rhizodelta_human_post_request_id_unique IF NOT EXISTS FOR (n:Human_Post) REQUIRE n.request_id IS UNIQUE",
            "CREATE CONSTRAINT rhizodelta_ai_consensus_node_id_unique IF NOT EXISTS FOR (n:AI_Consensus) REQUIRE n.node_id IS UNIQUE",
            "CREATE INDEX rhizodelta_human_post_author_id_idx IF NOT EXISTS FOR (n:Human_Post) ON (n.author_id)",
            "CREATE INDEX rhizodelta_human_post_created_at_idx IF NOT EXISTS FOR (n:Human_Post) ON (n.created_at)",
            "CREATE INDEX rhizodelta_ai_consensus_created_at_idx IF NOT EXISTS FOR (n:AI_Consensus) ON (n.created_at)"
    );

    private final Neo4jClient neo4jClient;

    public DatabaseInitializer(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    @PostConstruct
    void initializeSchema() {
        for (String query : SCHEMA_QUERIES) {
            executeSchemaQuery(query);
        }
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
}
