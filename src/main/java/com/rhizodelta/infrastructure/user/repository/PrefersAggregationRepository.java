package com.rhizodelta.infrastructure.user.repository;

import com.rhizodelta.infrastructure.user.service.PrefersAggregationResult;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 把 PreferenceEvent 聚合成 PREFERS 投影边的 Cypher 入口。
 *
 * <p>该仓库刻意保持 thin：聚合逻辑、类型权重、衰减常量都不在这里——它们由
 * {@link com.rhizodelta.infrastructure.user.service.PrefersAggregationPolicy}
 * 持有，并通过参数传入。这里只负责把数值塞进 Cypher 并把结果映射回 record。
 *
 * <p><b>Cypher 形态</b>（详见 change {@code prefers-aggregation-job} 的 design.md §D3）：
 * <ul>
 *   <li>{@code MATCH (u:UserAccount)-[:EMITTED]->(e:PreferenceEvent)-[:TOWARD]->(t:Topic)}
 *       在窗口内累加 (per-type 基础权重 × 时间衰减)。</li>
 *   <li>{@code MERGE (u)-[:PREFERS]->(t)} upsert 权重；{@code ON CREATE} 写 {@code created_at}。</li>
 *   <li>零权重桶不创建边；权重大于上限被 clamp。</li>
 * </ul>
 *
 * <p>该 Cypher 内联了五种 PreferenceEventType 的基础权重数值。当
 * {@link com.rhizodelta.infrastructure.user.service.PrefersAggregationPolicy}
 * 的常量变更时，本 Cypher 也需要同步——已由
 * {@code PrefersAggregationPolicyCypherContractTest} 守护。
 */
@Repository
public class PrefersAggregationRepository {

    /**
     * 单次往返完成全量聚合。
     *
     * <p>参数化：{@code $windowStart} 是聚合窗口下界；{@code $halfLifeDays} 是衰减半衰期；
     * {@code $weightCeiling} 是 clamp 上限。三者都来自策略，便于测试。
     */
    static final String AGGREGATE_QUERY = """
            MATCH (u:UserAccount)-[:EMITTED]->(e:PreferenceEvent)-[:TOWARD]->(t:Topic)
            WHERE e.at >= $windowStart
            WITH u, t,
                 sum(
                   CASE e.type
                     WHEN 'VIEW'   THEN 0.5
                     WHEN 'EXPAND' THEN 1.0
                     WHEN 'DWELL'  THEN 1.5
                     WHEN 'LIKE'   THEN 2.0
                     WHEN 'SHARE'  THEN 3.0
                     ELSE 0.0
                   END *
                   (0.5 ^ (duration.inSeconds(e.at, datetime()).seconds / (86400.0 * $halfLifeDays)))
                 ) AS rawWeight,
                 max(e.at) AS lastEventAt,
                 count(e) AS eventCount
            WITH u, t,
                 CASE WHEN rawWeight > $weightCeiling THEN $weightCeiling ELSE rawWeight END AS clampedWeight,
                 lastEventAt,
                 eventCount
            WHERE clampedWeight > 0
            MERGE (u)-[r:PREFERS]->(t)
            ON CREATE SET r.created_at = datetime()
            SET r.weight        = clampedWeight,
                r.last_event_at = lastEventAt,
                r.updated_at    = datetime()
            RETURN count(r) AS edgesUpserted, sum(eventCount) AS eventsProcessed
            """;

    private final Neo4jClient neo4jClient;

    public PrefersAggregationRepository(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /**
     * 执行一次聚合。
     *
     * @param windowStart    聚合窗口下界（含），早于该时间点的事件不参与本轮聚合
     * @param halfLifeDays   时间衰减半衰期（天）
     * @param weightCeiling  PREFERS.weight 的上限
     * @param runStartedAt   Job 进入逻辑的时间点（用于 result 携带；不影响 Cypher）
     * @return 本轮聚合的吞吐与产出统计
     * @throws IllegalArgumentException 当 windowStart 为 null、为未来、或在 Unix 元年之前
     */
    public PrefersAggregationResult runAggregation(
            Instant windowStart,
            double halfLifeDays,
            double weightCeiling,
            Instant runStartedAt
    ) {
        Objects.requireNonNull(windowStart, "windowStart must not be null");
        Objects.requireNonNull(runStartedAt, "runStartedAt must not be null");
        if (windowStart.isAfter(Instant.now())) {
            throw new IllegalArgumentException("windowStart must not be in the future: " + windowStart);
        }
        if (windowStart.isBefore(Instant.EPOCH)) {
            throw new IllegalArgumentException("windowStart must not predate the Unix epoch: " + windowStart);
        }

        Optional<Map<String, Object>> row = neo4jClient.query(AGGREGATE_QUERY)
                .bindAll(Map.of(
                        "windowStart", OffsetDateTime.ofInstant(windowStart, ZoneOffset.UTC),
                        "halfLifeDays", halfLifeDays,
                        "weightCeiling", weightCeiling
                ))
                .fetch()
                .one();

        long edgesUpserted = 0L;
        long eventsProcessed = 0L;
        if (row.isPresent()) {
            Map<String, Object> r = row.get();
            edgesUpserted = readLong(r.get("edgesUpserted"));
            eventsProcessed = readLong(r.get("eventsProcessed"));
        }

        return new PrefersAggregationResult(eventsProcessed, edgesUpserted, windowStart, runStartedAt);
    }

    private static long readLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }
}
