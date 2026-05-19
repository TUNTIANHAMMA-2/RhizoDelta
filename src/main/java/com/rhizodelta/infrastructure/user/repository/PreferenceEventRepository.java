package com.rhizodelta.infrastructure.user.repository;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class PreferenceEventRepository {
    // Resolve TOWARD topic in priority order:
    //   1. explicit $topicId (callers that already know the topic, e.g. FeedRankingIntegrationTest)
    //   2. derived from source node's topic_id property (real /api/nodes/{id} read path,
    //      where NodeQueryController does not pre-resolve topic).
    // Without this fallback, real user reads never produced TOWARD edges and the
    // PREFERS aggregation job was effectively a no-op.
    private static final String CREATE_EVENT_QUERY = """
            MATCH (u:UserAccount {user_id: $userId})
            OPTIONAL MATCH (src {node_id: $sourceNodeId})
              WHERE $sourceNodeId <> ''
            WITH u, src,
                 CASE
                   WHEN $topicId <> '' THEN $topicId
                   WHEN src IS NOT NULL AND src.topic_id IS NOT NULL THEN src.topic_id
                   ELSE NULL
                 END AS resolvedTopicId
            OPTIONAL MATCH (t:Topic {topic_id: resolvedTopicId})
              WHERE resolvedTopicId IS NOT NULL
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
                        "sourceNodeId", sourceNodeId != null ? sourceNodeId : ""
                ))
                .run();
    }
}
