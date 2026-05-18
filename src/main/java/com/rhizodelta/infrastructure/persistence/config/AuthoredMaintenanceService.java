package com.rhizodelta.infrastructure.persistence.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 历史 {@code AUTHORED} 边的运维入口。
 *
 * <p>提供两类操作：
 * <ul>
 *   <li><b>回填</b>：把缺少 canonical {@code AUTHORED} 边、但 {@code author_id} 指向真实
 *       {@code UserAccount} 的历史帖子，按批次补齐 {@code AUTHORED} 边并写入 {@code authored_id}
 *       复合键，让关系唯一约束接管后续并发去重。</li>
 *   <li><b>漂移审计</b>：除了缺边，还能识别"同一帖子有多条 AUTHORED"
 *       "AUTHORED 来自非声明作者"等漂移情形，避免审计在 false-positive
 *       上通过。</li>
 * </ul>
 *
 * <p>对外是显式的运维动作（非启动期），单批游标分页 + 重复运行幂等，
 * 支持在大数据量下分段执行而不形成长事务。
 */
@Service
public class AuthoredMaintenanceService {
    static final int DEFAULT_BATCH_SIZE = 500;
    private static final int MAX_LOOP_ITERATIONS = 10_000;
    private static final int DRIFT_SAMPLE_LIMIT = 100;

    /**
     * 单批回填查询：
     * <ul>
     *   <li>WHERE 子句同时排除"已经有正确边"和"声明作者不存在"两种情形，
     *       保证每批扫描出的帖子都是真正可修复的。</li>
     *   <li>{@code authored_id} 采用 {@code authorId:postNodeId} 复合键，
     *       与关系唯一约束 {@code rhizodelta_authored_id_unique} 对齐，
     *       并发回填或并发写帖也最多保留一条 AUTHORED。</li>
     * </ul>
     */
    private static final String BACKFILL_BATCH_QUERY = """
            MATCH (p:Human_Post)
            WHERE p.author_id IS NOT NULL
              AND EXISTS { MATCH (:UserAccount {user_id: p.author_id}) }
              AND NOT EXISTS { MATCH (:UserAccount {user_id: p.author_id})-[:AUTHORED]->(p) }
            WITH p LIMIT $batchSize
            MATCH (u:UserAccount {user_id: p.author_id})
            MERGE (u)-[r:AUTHORED]->(p)
              ON CREATE SET r.created_at = coalesce(p.created_at, datetime()),
                            r.authored_id = u.user_id + ':' + p.node_id
            RETURN sum(CASE WHEN r.authored_id IS NULL THEN 0 ELSE 1 END) AS created
            """.trim();

    /**
     * 单批可修复待回填计数：用于循环退出与外部进度展示。
     */
    private static final String FIXABLE_PENDING_COUNT_QUERY = """
            MATCH (p:Human_Post)
            WHERE p.author_id IS NOT NULL
              AND EXISTS { MATCH (:UserAccount {user_id: p.author_id}) }
              AND NOT EXISTS { MATCH (:UserAccount {user_id: p.author_id})-[:AUTHORED]->(p) }
            RETURN count(p) AS pending
            """.trim();

    /**
     * 漂移审计：返回每条不健康 Human_Post 的 totalAuthoredEdges 与
     * matchingAuthoredEdges，让调用方按需分类。
     */
    private static final String DRIFT_SAMPLE_QUERY = """
            MATCH (p:Human_Post)
            WHERE p.author_id IS NOT NULL
            OPTIONAL MATCH (u:UserAccount)-[:AUTHORED]->(p)
            WITH p,
                 count(u) AS total,
                 sum(CASE WHEN u IS NOT NULL AND u.user_id = p.author_id THEN 1 ELSE 0 END) AS matching
            WHERE matching <> 1 OR total <> matching
            RETURN p.node_id  AS nodeId,
                   p.author_id AS authorId,
                   total       AS totalAuthoredEdges,
                   matching    AS matchingAuthoredEdges
            LIMIT $limit
            """.trim();

    /**
     * 漂移审计汇总：四桶分类（缺边/重复匹配/无匹配外加边/纯外加边），
     * 让运维一次性看到不同漂移类型的体量。
     */
    private static final String DRIFT_SUMMARY_QUERY = """
            MATCH (p:Human_Post)
            WHERE p.author_id IS NOT NULL
            OPTIONAL MATCH (u:UserAccount)-[:AUTHORED]->(p)
            WITH p,
                 count(u) AS total,
                 sum(CASE WHEN u IS NOT NULL AND u.user_id = p.author_id THEN 1 ELSE 0 END) AS matching
            RETURN
              sum(CASE WHEN matching = 1 AND total = 1                    THEN 1 ELSE 0 END) AS healthyCount,
              sum(CASE WHEN matching = 0 AND total = 0                    THEN 1 ELSE 0 END) AS missingCount,
              sum(CASE WHEN matching >= 2                                 THEN 1 ELSE 0 END) AS duplicateMatchingCount,
              sum(CASE WHEN matching = 1 AND total > 1                    THEN 1 ELSE 0 END) AS extraneousAlongsideMatchCount,
              sum(CASE WHEN matching = 0 AND total > 0                    THEN 1 ELSE 0 END) AS extraneousWithoutMatchCount
            """.trim();

    private final Neo4jClient neo4jClient;
    private final int batchSize;

    public AuthoredMaintenanceService(
            Neo4jClient neo4jClient,
            @Value("${rhizodelta.authored.backfill.batch-size:" + DEFAULT_BATCH_SIZE + "}") int batchSize
    ) {
        this.neo4jClient = neo4jClient;
        this.batchSize = batchSize <= 0 ? DEFAULT_BATCH_SIZE : batchSize;
    }

    /**
     * 分批回填所有可修复的历史帖子，循环直到 fixable pending = 0 或没有进度。
     *
     * <p>每批是独立事务（标注在 {@link #runBackfillBatch()} 上），避免大事务长锁。
     * 单次 service 调用退出条件：
     * <ul>
     *   <li>无 fixable pending；或</li>
     *   <li>本批 createdCount = 0（理论上不该发生，做防御性退出避免死循环）；或</li>
     *   <li>循环超过 {@link #MAX_LOOP_ITERATIONS}（同样是防御性上限）。</li>
     * </ul>
     */
    public AuthoredBackfillResult runAuthoredBackfill() {
        long totalCreated = 0L;
        int iterations = 0;
        while (iterations++ < MAX_LOOP_ITERATIONS) {
            long pending = countFixablePending();
            if (pending <= 0L) {
                break;
            }
            long created = runBackfillBatch();
            totalCreated += created;
            if (created <= 0L) {
                break;
            }
        }
        AuthoredDriftSummary summary = summarizeDrift();
        List<AuthoredEdgeDrift> driftSamples = findDriftSamples(DRIFT_SAMPLE_LIMIT);
        return new AuthoredBackfillResult(totalCreated, summary, driftSamples);
    }

    /**
     * 只读漂移审计，不写入；用于运维探测当前图状态。
     */
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public AuthoredDriftSummary auditDrift() {
        return summarizeDrift();
    }

    /**
     * 与 audit 配合：返回前 {@code limit} 条漂移样本，便于人工调查。
     */
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public List<AuthoredEdgeDrift> findDriftSamples(int limit) {
        int bounded = limit <= 0 ? DRIFT_SAMPLE_LIMIT : Math.min(limit, DRIFT_SAMPLE_LIMIT);
        Map<String, Object> params = Map.of("limit", bounded);
        return neo4jClient.query(DRIFT_SAMPLE_QUERY)
                .bindAll(params)
                .fetch()
                .all()
                .stream()
                .map(AuthoredMaintenanceService::toDrift)
                .toList();
    }

    @Transactional(transactionManager = "transactionManager")
    long runBackfillBatch() {
        Map<String, Object> params = Map.of("batchSize", batchSize);
        return neo4jClient.query(BACKFILL_BATCH_QUERY)
                .bindAll(params)
                .fetch()
                .one()
                .map(record -> readLong(record.get("created")))
                .orElse(0L);
    }

    @Transactional(transactionManager = "transactionManager", readOnly = true)
    long countFixablePending() {
        return neo4jClient.query(FIXABLE_PENDING_COUNT_QUERY)
                .fetch()
                .one()
                .map(record -> readLong(record.get("pending")))
                .orElse(0L);
    }

    private AuthoredDriftSummary summarizeDrift() {
        Map<String, Object> record = neo4jClient.query(DRIFT_SUMMARY_QUERY)
                .fetch()
                .one()
                .orElse(emptySummaryRecord());
        return new AuthoredDriftSummary(
                readLong(record.get("healthyCount")),
                readLong(record.get("missingCount")),
                readLong(record.get("duplicateMatchingCount")),
                readLong(record.get("extraneousAlongsideMatchCount")),
                readLong(record.get("extraneousWithoutMatchCount"))
        );
    }

    private static Map<String, Object> emptySummaryRecord() {
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("healthyCount", 0L);
        empty.put("missingCount", 0L);
        empty.put("duplicateMatchingCount", 0L);
        empty.put("extraneousAlongsideMatchCount", 0L);
        empty.put("extraneousWithoutMatchCount", 0L);
        return Collections.unmodifiableMap(empty);
    }

    private static AuthoredEdgeDrift toDrift(Map<String, Object> record) {
        long total = readLong(record.get("totalAuthoredEdges"));
        long matching = readLong(record.get("matchingAuthoredEdges"));
        return new AuthoredEdgeDrift(
                String.valueOf(record.get("nodeId")),
                String.valueOf(record.get("authorId")),
                total,
                matching,
                classifyDrift(total, matching)
        );
    }

    private static AuthoredDriftKind classifyDrift(long total, long matching) {
        if (matching == 0 && total == 0) {
            return AuthoredDriftKind.MISSING;
        }
        if (matching >= 2) {
            return AuthoredDriftKind.DUPLICATE_MATCHING;
        }
        if (matching == 1 && total > 1) {
            return AuthoredDriftKind.EXTRANEOUS_ALONGSIDE_MATCH;
        }
        if (matching == 0 && total > 0) {
            return AuthoredDriftKind.EXTRANEOUS_WITHOUT_MATCH;
        }
        return AuthoredDriftKind.HEALTHY;
    }

    private static long readLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    public record AuthoredBackfillResult(
            long createdCount,
            AuthoredDriftSummary driftSummary,
            List<AuthoredEdgeDrift> driftSamples
    ) {
    }

    public record AuthoredDriftSummary(
            long healthyCount,
            long missingCount,
            long duplicateMatchingCount,
            long extraneousAlongsideMatchCount,
            long extraneousWithoutMatchCount
    ) {
        public long totalDriftingCount() {
            return missingCount + duplicateMatchingCount
                    + extraneousAlongsideMatchCount + extraneousWithoutMatchCount;
        }
    }

    public record AuthoredEdgeDrift(
            String nodeId,
            String authorId,
            long totalAuthoredEdges,
            long matchingAuthoredEdges,
            AuthoredDriftKind kind
    ) {
    }

    public enum AuthoredDriftKind {
        HEALTHY,
        MISSING,
        DUPLICATE_MATCHING,
        EXTRANEOUS_ALONGSIDE_MATCH,
        EXTRANEOUS_WITHOUT_MATCH
    }
}
