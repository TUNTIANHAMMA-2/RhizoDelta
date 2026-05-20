package com.rhizodelta.infrastructure.user.repository;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * FOLLOWS 关系的图层访问。
 *
 * <p>关系上挂 {@code follow_id} 作为稳定标识，DELETE 接口可凭它做幂等寻址，
 * 也方便后续在边上扩展通知/优先级等属性。
 */
@Repository
public class FollowRepository {

    private static final String CREATE_FOLLOW_QUERY = """
            MATCH (u:UserAccount {user_id: $userId})
            CALL {
              WITH $targetType AS targetType, $targetId AS targetId
              MATCH (target:Topic {topic_id: targetId})
              WHERE targetType = 'topic'
              RETURN target
            UNION
              WITH $targetType AS targetType, $targetId AS targetId
              MATCH (target:GraphNode {node_id: targetId})
              WHERE targetType = 'node'
              RETURN target
            UNION
              WITH $targetType AS targetType, $targetId AS targetId
              MATCH (target:UserAccount {user_id: targetId})
              WHERE targetType = 'user'
              RETURN target
            }
            OPTIONAL MATCH (u)-[existing:FOLLOWS]->(target)
            WITH u, target, existing
            FOREACH (_ IN CASE WHEN existing IS NULL THEN [1] ELSE [] END |
              CREATE (u)-[r:FOLLOWS {follow_id: $followId, since: datetime()}]->(target)
            )
            WITH u, target, existing
            OPTIONAL MATCH (u)-[r:FOLLOWS]->(target)
            RETURN r.follow_id AS follow_id,
                   toString(r.since) AS since,
                   existing IS NOT NULL AS already_existed
            """;

    private static final String EXISTS_FOLLOW_QUERY = """
            MATCH (u:UserAccount {user_id: $userId})
            CALL {
              WITH $targetType AS targetType, $targetId AS targetId
              MATCH (target:Topic {topic_id: targetId})
              WHERE targetType = 'topic'
              RETURN target
            UNION
              WITH $targetType AS targetType, $targetId AS targetId
              MATCH (target:GraphNode {node_id: targetId})
              WHERE targetType = 'node'
              RETURN target
            UNION
              WITH $targetType AS targetType, $targetId AS targetId
              MATCH (target:UserAccount {user_id: targetId})
              WHERE targetType = 'user'
              RETURN target
            }
            MATCH (u)-[r:FOLLOWS]->(target)
            RETURN r.follow_id AS follow_id
            """;

    private static final String LIST_FOLLOWS_QUERY = """
            MATCH (u:UserAccount {user_id: $userId})-[r:FOLLOWS]->(target)
            OPTIONAL MATCH (target)-[:HAS_PROFILE]->(profile:UserProfile)
            RETURN
              r.follow_id AS follow_id,
              toString(r.since) AS since,
              labels(target) AS labels,
              CASE
                WHEN 'Topic'       IN labels(target) THEN 'topic'
                WHEN 'UserAccount' IN labels(target) THEN 'user'
                WHEN 'GraphNode'   IN labels(target) THEN 'node'
              END AS target_type,
              CASE
                WHEN 'Topic'       IN labels(target) THEN target.topic_id
                WHEN 'UserAccount' IN labels(target) THEN target.user_id
                WHEN 'GraphNode'   IN labels(target) THEN target.node_id
              END AS target_id,
              CASE
                WHEN 'Topic'       IN labels(target) THEN target.name
                WHEN 'UserAccount' IN labels(target) THEN coalesce(profile.display_name, target.username)
                WHEN 'GraphNode'   IN labels(target) THEN coalesce(target.summary_content, target.content)
              END AS target_display_name
            ORDER BY r.since DESC
            SKIP $skip LIMIT $limit
            """;

    private static final String COUNT_FOLLOWS_QUERY = """
            MATCH (u:UserAccount {user_id: $userId})-[r:FOLLOWS]->()
            RETURN count(r) AS total
            """;

    private static final String DELETE_FOLLOW_BY_ID_QUERY = """
            MATCH (:UserAccount {user_id: $userId})-[r:FOLLOWS {follow_id: $followId}]->()
            DELETE r
            RETURN count(r) AS deleted
            """;

    private final Neo4jClient neo4jClient;

    public FollowRepository(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /**
     * 创建 FOLLOWS 关系。
     *
     * @param followId 调用方先生成的 UUID
     * @return 包含 {@code follow_id}, {@code since}, {@code already_existed} 的记录；
     *         {@code already_existed=true} 时 {@code follow_id} 来自已有边而非新值
     */
    public Optional<Map<String, Object>> create(String userId, String targetType, String targetId, String followId) {
        return neo4jClient.query(CREATE_FOLLOW_QUERY)
                .bindAll(Map.of(
                        "userId", userId,
                        "targetType", targetType,
                        "targetId", targetId,
                        "followId", followId
                ))
                .fetch()
                .one();
    }

    public Optional<String> findExistingFollowId(String userId, String targetType, String targetId) {
        return neo4jClient.query(EXISTS_FOLLOW_QUERY)
                .bindAll(Map.of(
                        "userId", userId,
                        "targetType", targetType,
                        "targetId", targetId
                ))
                .fetch()
                .one()
                .map(record -> {
                    Object value = record.get("follow_id");
                    return value == null ? null : value.toString();
                });
    }

    public List<Map<String, Object>> listFollows(String userId, long skip, int limit) {
        return new ArrayList<>(neo4jClient.query(LIST_FOLLOWS_QUERY)
                .bindAll(Map.of("userId", userId, "skip", skip, "limit", limit))
                .fetch()
                .all());
    }

    public long countFollows(String userId) {
        return neo4jClient.query(COUNT_FOLLOWS_QUERY)
                .bind(userId).to("userId")
                .fetch()
                .one()
                .map(record -> ((Number) record.get("total")).longValue())
                .orElse(0L);
    }

    public boolean deleteById(String userId, String followId) {
        return neo4jClient.query(DELETE_FOLLOW_BY_ID_QUERY)
                .bindAll(Map.of("userId", userId, "followId", followId))
                .fetch()
                .one()
                .map(record -> ((Number) record.get("deleted")).longValue() > 0)
                .orElse(false);
    }
}
