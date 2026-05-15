package com.rhizodelta.infrastructure.user.service;

import com.rhizodelta.infrastructure.user.repository.FollowRepository;
import com.rhizodelta.infrastructure.user.repository.MuteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
 *
 * <p><b>PREFERS 排序扩展</b>：当 {@code rhizodelta.feature.prefers-feed-ranking.enabled=true}
 * 时，候选集合在 DISTINCT 之后会 OPTIONAL MATCH 当前用户到候选项目对应 Topic 的
 * PREFERS 边，并按 {@code coalesce(prefers.weight, 0) DESC, n.created_at DESC} 排序——
 * 用户高频互动过的 Topic 下的内容会浮到前面。零权重的候选（用户尚未互动过的 Topic）仍然
 * 出现，只是在按 created_at 排序之后。Flag 关闭时执行原始 Cypher，行为与今天完全一致。
 */
@Service
public class FeedService {
    private static final Logger LOGGER = LoggerFactory.getLogger(FeedService.class);
    private static final int DEFAULT_PAGE_SIZE = 50;

    public static final String FLAG_FEED_RANKING_KEY = "rhizodelta.feature.prefers-feed-ranking.enabled";
    public static final String FLAG_AGGREGATION_KEY = "rhizodelta.feature.prefers-aggregation.enabled";

    /**
     * 三条候选分支，每条都用具体标签 MATCH 而不是 (n) WHERE n:Label OR ...
     * 后者会让 planner 退化为 AllNodesScan；具体标签可以走 NodeByLabelScan
     * 或者带索引的 NodeIndexSeek。
     */
    static final String FEED_QUERY = """
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
                CALL {
                    WITH t
                    MATCH (n:Human_Post {topic_id: t.topic_id}) RETURN n
                  UNION
                    WITH t
                    MATCH (n:AI_Consensus {topic_id: t.topic_id}) RETURN n
                  UNION
                    WITH t
                    MATCH (n:Result {topic_id: t.topic_id}) RETURN n
                }
                WITH n, mutedUsers
                WHERE NOT coalesce(n._deleted, false)
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

    /**
     * Flag-on 变体：在 DISTINCT 之后挂一层到当前用户 PREFERS 边的 OPTIONAL MATCH，
     * 把 {@code coalesce(prefers.weight, 0)} 作为主排序键。其余结构与 {@link #FEED_QUERY} 等价，
     * 便于在 flag 翻转时切换 Cypher 而不发生别的副作用。
     */
    static final String FEED_QUERY_WITH_PREFERS_RANKING = """
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
                CALL {
                    WITH t
                    MATCH (n:Human_Post {topic_id: t.topic_id}) RETURN n
                  UNION
                    WITH t
                    MATCH (n:AI_Consensus {topic_id: t.topic_id}) RETURN n
                  UNION
                    WITH t
                    MATCH (n:Result {topic_id: t.topic_id}) RETURN n
                }
                WITH n, mutedUsers
                WHERE NOT coalesce(n._deleted, false)
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
            OPTIONAL MATCH (ranker:UserAccount {user_id: $userId})-[prefers:PREFERS]->(prefTopic:Topic {topic_id: n.topic_id})
            OPTIONAL MATCH (author:UserAccount {user_id: n.author_id})
            OPTIONAL MATCH (author)-[:HAS_PROFILE]->(authorProfile:UserProfile)
            WITH n, author, authorProfile, labels(n) AS nodeLabels,
                 coalesce(prefers.weight, 0) AS prefersWeight
            ORDER BY prefersWeight DESC, n.created_at DESC
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
            CALL {
                MATCH (n:Human_Post) WHERE NOT coalesce(n._deleted, false) RETURN n
              UNION
                MATCH (n:AI_Consensus) WHERE NOT coalesce(n._deleted, false) RETURN n
              UNION
                MATCH (n:Result) WHERE NOT coalesce(n._deleted, false) RETURN n
            }
            WITH n
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
    private final Environment environment;

    /**
     * 防止"prefers-feed-ranking=on 而 prefers-aggregation=off"组合反复输出 WARN，
     * 用 AtomicBoolean 在 JVM 生命周期内最多输出一次。
     */
    private final AtomicBoolean mismatchedFlagsWarnedOnce = new AtomicBoolean(false);

    public FeedService(Neo4jClient neo4jClient,
                       MuteRepository muteRepository,
                       FollowRepository followRepository,
                       Environment environment) {
        this.neo4jClient = neo4jClient;
        this.muteRepository = muteRepository;
        this.followRepository = followRepository;
        this.environment = environment;
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

        String cypher = selectFeedCypher();
        return runQuery(cypher, Map.of(
                "userId", userId,
                "mutedUserIds", mutedUserIds,
                "mutedTopicIds", mutedTopicIds,
                "skip", skip,
                "limit", resolvedSize
        ));
    }

    /**
     * Flag 路由：on → PREFERS 变体；off → 原始 Cypher（行为与 flag 引入前完全一致）。
     * "ranking on 而 aggregation off"是禁止组合，命中时 WARN 一次。
     */
    private String selectFeedCypher() {
        boolean rankingOn = isEnabled(FLAG_FEED_RANKING_KEY);
        if (!rankingOn) {
            return FEED_QUERY;
        }
        if (!isEnabled(FLAG_AGGREGATION_KEY) && mismatchedFlagsWarnedOnce.compareAndSet(false, true)) {
            LOGGER.warn(
                    "Mismatched feature flags: {}=true but {}=false. Feed ranking will read stale PREFERS edges. "
                            + "See docs/runbooks/prefers-aggregation.md for the recommended rollout order.",
                    FLAG_FEED_RANKING_KEY, FLAG_AGGREGATION_KEY);
        }
        return FEED_QUERY_WITH_PREFERS_RANKING;
    }

    private boolean isEnabled(String key) {
        return Boolean.TRUE.equals(environment.getProperty(key, Boolean.class, Boolean.FALSE));
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
