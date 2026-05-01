package com.rhizodelta.infrastructure.user.repository;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;

@Repository
public class TopicRepository {
    private static final String UPSERT_TOPIC_QUERY = """
            MERGE (t:Topic {name: $name})
            ON CREATE SET
              t.topic_id = $topicId,
              t.source_type = $sourceType,
              t.created_at = datetime()
            RETURN t.topic_id AS topicId, t.name AS name, t.source_type AS sourceType, t.created_at AS createdAt
            """;
    private static final String FIND_BY_ID_QUERY = """
            MATCH (t:Topic {topic_id: $topicId})
            RETURN t.topic_id AS topicId, t.name AS name, t.source_type AS sourceType, t.created_at AS createdAt
            """;
    private static final String FIND_BY_NAME_QUERY = """
            MATCH (t:Topic {name: $name})
            RETURN t.topic_id AS topicId, t.name AS name, t.source_type AS sourceType, t.created_at AS createdAt
            """;

    private final Neo4jClient neo4jClient;

    public TopicRepository(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    public String upsert(String topicId, String name, String sourceType) {
        return neo4jClient.query(UPSERT_TOPIC_QUERY)
                .bindAll(Map.of("topicId", topicId, "name", name, "sourceType", sourceType))
                .fetch()
                .one()
                .map(record -> record.get("topicId").toString())
                .orElseThrow(() -> new IllegalStateException("failed to upsert topic"));
    }

    public Optional<Map<String, Object>> findById(String topicId) {
        return neo4jClient.query(FIND_BY_ID_QUERY)
                .bind(topicId).to("topicId")
                .fetch()
                .one();
    }

    public Optional<Map<String, Object>> findByName(String name) {
        return neo4jClient.query(FIND_BY_NAME_QUERY)
                .bind(name).to("name")
                .fetch()
                .one();
    }
}
