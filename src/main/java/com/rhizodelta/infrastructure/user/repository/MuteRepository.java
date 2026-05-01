package com.rhizodelta.infrastructure.user.repository;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class MuteRepository {

    private static final String CREATE_MUTE_QUERY = """
            MATCH (u:UserAccount {user_id: $userId})
            MATCH (target) WHERE
              ($targetType = 'topic' AND target:Topic       AND target.topic_id = $targetId) OR
              ($targetType = 'user'  AND target:UserAccount AND target.user_id  = $targetId)
            OPTIONAL MATCH (u)-[existing:MUTED]->(target)
            WITH u, target, existing
            FOREACH (_ IN CASE WHEN existing IS NULL THEN [1] ELSE [] END |
              CREATE (u)-[r:MUTED {mute_id: $muteId, since: datetime(), reason: $reason}]->(target)
            )
            WITH u, target, existing
            OPTIONAL MATCH (u)-[r:MUTED]->(target)
            RETURN r.mute_id AS mute_id,
                   toString(r.since) AS since,
                   r.reason AS reason,
                   existing IS NOT NULL AS already_existed
            """;

    private static final String LIST_MUTES_QUERY = """
            MATCH (u:UserAccount {user_id: $userId})-[r:MUTED]->(target)
            OPTIONAL MATCH (target)-[:HAS_PROFILE]->(profile:UserProfile)
            RETURN
              r.mute_id AS mute_id,
              toString(r.since) AS since,
              r.reason AS reason,
              labels(target) AS labels,
              CASE
                WHEN 'Topic'       IN labels(target) THEN 'topic'
                WHEN 'UserAccount' IN labels(target) THEN 'user'
              END AS target_type,
              CASE
                WHEN 'Topic'       IN labels(target) THEN target.topic_id
                WHEN 'UserAccount' IN labels(target) THEN target.user_id
              END AS target_id,
              CASE
                WHEN 'Topic'       IN labels(target) THEN target.name
                WHEN 'UserAccount' IN labels(target) THEN coalesce(profile.display_name, target.username)
              END AS target_display_name
            ORDER BY r.since DESC
            SKIP $skip LIMIT $limit
            """;

    private static final String COUNT_MUTES_QUERY = """
            MATCH (u:UserAccount {user_id: $userId})-[r:MUTED]->()
            RETURN count(r) AS total
            """;

    private static final String DELETE_MUTE_BY_ID_QUERY = """
            MATCH (:UserAccount {user_id: $userId})-[r:MUTED {mute_id: $muteId}]->()
            DELETE r
            RETURN count(r) AS deleted
            """;

    private static final String GET_MUTED_USER_IDS_QUERY = """
            MATCH (u:UserAccount {user_id: $userId})-[:MUTED]->(muted:UserAccount)
            RETURN collect(muted.user_id) AS mutedUserIds
            """;

    private static final String GET_MUTED_TOPIC_IDS_QUERY = """
            MATCH (u:UserAccount {user_id: $userId})-[:MUTED]->(muted:Topic)
            RETURN collect(muted.topic_id) AS mutedTopicIds
            """;

    private final Neo4jClient neo4jClient;

    public MuteRepository(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    public Optional<Map<String, Object>> create(String userId, String targetType, String targetId, String muteId, String reason) {
        return neo4jClient.query(CREATE_MUTE_QUERY)
                .bindAll(Map.of(
                        "userId", userId,
                        "targetType", targetType,
                        "targetId", targetId,
                        "muteId", muteId,
                        "reason", reason == null ? "" : reason
                ))
                .fetch()
                .one();
    }

    public List<Map<String, Object>> listMutes(String userId, int skip, int limit) {
        return new ArrayList<>(neo4jClient.query(LIST_MUTES_QUERY)
                .bindAll(Map.of("userId", userId, "skip", skip, "limit", limit))
                .fetch()
                .all());
    }

    public long countMutes(String userId) {
        return neo4jClient.query(COUNT_MUTES_QUERY)
                .bind(userId).to("userId")
                .fetch()
                .one()
                .map(record -> ((Number) record.get("total")).longValue())
                .orElse(0L);
    }

    public boolean deleteById(String userId, String muteId) {
        return neo4jClient.query(DELETE_MUTE_BY_ID_QUERY)
                .bindAll(Map.of("userId", userId, "muteId", muteId))
                .fetch()
                .one()
                .map(record -> ((Number) record.get("deleted")).longValue() > 0)
                .orElse(false);
    }

    @SuppressWarnings("unchecked")
    public List<String> getMutedUserIds(String userId) {
        return neo4jClient.query(GET_MUTED_USER_IDS_QUERY)
                .bind(userId).to("userId")
                .fetch()
                .one()
                .map(record -> (List<String>) record.get("mutedUserIds"))
                .orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    public List<String> getMutedTopicIds(String userId) {
        return neo4jClient.query(GET_MUTED_TOPIC_IDS_QUERY)
                .bind(userId).to("userId")
                .fetch()
                .one()
                .map(record -> (List<String>) record.get("mutedTopicIds"))
                .orElse(List.of());
    }
}
