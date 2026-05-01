package com.rhizodelta.infrastructure.user.service;

import com.rhizodelta.infrastructure.user.repository.FollowRepository;
import com.rhizodelta.infrastructure.user.repository.MuteRepository;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 个性化 feed 的图遍历查询。
 *
 * <p>三条查询分支共同贡献候选内容：
 * <ol>
 *   <li>{@code UserAccount} 关注：拉取被关注者的全部 AUTHORED 内容。</li>
 *   <li>{@code Topic} 关注：拉取所有携带相应 topic_id 的内容节点。</li>
 *   <li>{@code GraphNode} 关注：拉取被关注节点的 1-2 跳后代。</li>
 * </ol>
 *
 * <p>过滤：剔除 mutedUserIds 中作者的内容、mutedTopicIds 中话题的内容、软删除节点。
 * 用户没有任何关注时，回退全局最新内容；Cypher 异常上抛交给全局处理器。
 *
 * <p>返回字段与 {@code /api/nodes/roots} 同构（{@code GraphNodeDTO} 形状），
 * 便于前端复用现有 RhizomeCard 组件。
 */
@Service
public class FeedService {
    private static final int DEFAULT_PAGE_SIZE = 50;

    private static final String FEED_QUERY = """
            CALL {
                WITH $userId AS userId, $mutedUserIds AS mutedUsers, $mutedTopicIds AS mutedTopics
                MATCH (u:UserAccount {user_id: userId})-[:FOLLOWS]->(target:UserAccount)-[:AUTHORED]->(n)
                WHERE NOT coalesce(n._deleted, false)
                  AND NOT target.user_id IN mutedUsers
                  AND NOT coalesce(n.topic_id, '__none__') IN mutedTopics
                RETURN n
              UNION
                WITH $userId AS userId, $mutedUserIds AS mutedUsers, $mutedTopicIds AS mutedTopics
                MATCH (u:UserAccount {user_id: userId})-[:FOLLOWS]->(t:Topic)
                WHERE NOT t.topic_id IN mutedTopics
                MATCH (n) WHERE n.topic_id = t.topic_id
                  AND (n:Human_Post OR n:AI_Consensus OR n:Result)
                  AND NOT coalesce(n._deleted, false)
                  AND NOT coalesce(n.author_id, '__none__') IN mutedUsers
                RETURN n
              UNION
                WITH $userId AS userId, $mutedUserIds AS mutedUsers, $mutedTopicIds AS mutedTopics
                MATCH (u:UserAccount {user_id: userId})-[:FOLLOWS]->(g:GraphNode)
                MATCH (g)<-[:CONTINUES_FROM|BRANCHED_FROM|MERGED_INTO*1..2]-(n)
                WHERE NOT coalesce(n._deleted, false)
                  AND NOT coalesce(n.author_id, '__none__') IN mutedUsers
                  AND NOT coalesce(n.topic_id, '__none__') IN mutedTopics
                RETURN n
            }
            WITH DISTINCT n
            OPTIONAL MATCH (author:UserAccount {user_id: n.author_id})
            OPTIONAL MATCH (author)-[:HAS_PROFILE]->(authorProfile:UserProfile)
            WITH n, author, authorProfile, labels(n) AS nodeLabels
            ORDER BY n.created_at DESC
            SKIP $skip LIMIT $limit
            RETURN n.node_id AS node_id,
                   CASE WHEN 'Human_Post' IN nodeLabels THEN 'Human_Post'
                        WHEN 'Result' IN nodeLabels THEN 'Result'
                        ELSE 'AI_Consensus' END AS label,
                   n.content AS content,
                   n.summary_content AS summary_content,
                   n.author_id AS author_id,
                   author.username AS author_username,
                   coalesce(authorProfile.display_name, author.username) AS author_display_name,
                   n.agent_version AS agent_version,
                   toString(n.created_at) AS created_at,
                   n.embedding IS NOT NULL AS has_embedding,
                   n.quality_overall AS quality_overall
            """;

    private static final String GLOBAL_FEED_QUERY = """
            MATCH (n)
            WHERE (n:Human_Post OR n:AI_Consensus OR n:Result)
              AND NOT coalesce(n._deleted, false)
            OPTIONAL MATCH (author:UserAccount {user_id: n.author_id})
            OPTIONAL MATCH (author)-[:HAS_PROFILE]->(authorProfile:UserProfile)
            WITH n, author, authorProfile, labels(n) AS nodeLabels
            ORDER BY n.created_at DESC
            SKIP $skip LIMIT $limit
            RETURN n.node_id AS node_id,
                   CASE WHEN 'Human_Post' IN nodeLabels THEN 'Human_Post'
                        WHEN 'Result' IN nodeLabels THEN 'Result'
                        ELSE 'AI_Consensus' END AS label,
                   n.content AS content,
                   n.summary_content AS summary_content,
                   n.author_id AS author_id,
                   author.username AS author_username,
                   coalesce(authorProfile.display_name, author.username) AS author_display_name,
                   n.agent_version AS agent_version,
                   toString(n.created_at) AS created_at,
                   n.embedding IS NOT NULL AS has_embedding,
                   n.quality_overall AS quality_overall
            """;

    private final Neo4jClient neo4jClient;
    private final MuteRepository muteRepository;
    private final FollowRepository followRepository;

    public FeedService(Neo4jClient neo4jClient,
                       MuteRepository muteRepository,
                       FollowRepository followRepository) {
        this.neo4jClient = neo4jClient;
        this.muteRepository = muteRepository;
        this.followRepository = followRepository;
    }

    public List<Map<String, Object>> getFeed(String userId, int page, int size) {
        int resolvedSize = size > 0 ? size : DEFAULT_PAGE_SIZE;
        int skip = Math.max(page, 0) * resolvedSize;

        long followCount = followRepository.countFollows(userId);
        if (followCount == 0L) {
            return runQuery(GLOBAL_FEED_QUERY, Map.of("skip", skip, "limit", resolvedSize));
        }

        List<String> mutedUserIds = muteRepository.getMutedUserIds(userId);
        List<String> mutedTopicIds = muteRepository.getMutedTopicIds(userId);

        return runQuery(FEED_QUERY, Map.of(
                "userId", userId,
                "mutedUserIds", mutedUserIds,
                "mutedTopicIds", mutedTopicIds,
                "skip", skip,
                "limit", resolvedSize
        ));
    }

    private List<Map<String, Object>> runQuery(String cypher, Map<String, Object> params) {
        List<Map<String, Object>> raw = new ArrayList<>(neo4jClient.query(cypher).bindAll(params).fetch().all());
        // Normalize each row into a fresh ordered map so the response shape stays stable
        // even if Neo4j driver returns property maps with shared references.
        List<Map<String, Object>> normalized = new ArrayList<>(raw.size());
        for (Map<String, Object> row : raw) {
            normalized.add(new LinkedHashMap<>(row));
        }
        return normalized;
    }
}
