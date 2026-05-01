package com.rhizodelta.infrastructure.user.repository;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class PreferenceEventRepository {
    private static final String CREATE_EVENT_QUERY = """
            MATCH (u:UserAccount {user_id: $userId})
            OPTIONAL MATCH (t:Topic {topic_id: $topicId})
              WHERE $topicId <> ''
            CREATE (e:PreferenceEvent {
              event_id: $eventId,
              type: $type,
              weight: $weight,
              at: datetime(),
              source_node_id: $sourceNodeId
            })
            CREATE (u)-[:EMITTED]->(e)
            FOREACH (_ IN CASE WHEN t IS NOT NULL THEN [1] ELSE [] END |
              CREATE (e)-[:TOWARD]->(t)
            )
            """;

    private final Neo4jClient neo4jClient;

    public PreferenceEventRepository(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    public void createEvent(String userId, String topicId, String eventId,
                            String type, double weight, String sourceNodeId) {
        neo4jClient.query(CREATE_EVENT_QUERY)
                .bindAll(Map.of(
                        "userId", userId,
                        "topicId", topicId != null ? topicId : "",
                        "eventId", eventId,
                        "type", type,
                        "weight", weight,
                        "sourceNodeId", sourceNodeId
                ))
                .run();
    }
}
