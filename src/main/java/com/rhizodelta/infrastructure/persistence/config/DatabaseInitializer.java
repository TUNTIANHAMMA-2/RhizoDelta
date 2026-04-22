package com.rhizodelta.infrastructure.persistence.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 在启动阶段初始化 Neo4j 约束、索引与向量索引。
 *
 * <p>该组件会在应用启动时主动执行 schema 语句，确保核心节点、关系和向量检索所需的数据库结构已经就绪。
 *
 * <p><b>关键副作用</b>：
 * <ul>
 *   <li>会执行多条 Neo4j DDL 语句。</li>
 *   <li>一旦 schema 初始化失败，应用启动会直接中止。</li>
 * </ul>
 */
@Component
public class DatabaseInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseInitializer.class);
    private static final String VECTOR_INDEX_NAME = "rhizodelta_graph_node_embedding_idx";
    private static final int USER_ID_INTEGRITY_LIMIT = 20;
    private static final int PROFILE_BACKFILL_BATCH_SIZE = 500;
    private static final String NULL_OR_BLANK_USER_ID_QUERY = """
            MATCH (u:UserAccount)
            WHERE u.user_id IS NULL OR trim(u.user_id) = ''
            RETURN u.username AS username LIMIT %d
            """.formatted(USER_ID_INTEGRITY_LIMIT).trim();
    private static final String DUPLICATE_USER_ID_QUERY = """
            MATCH (u:UserAccount)
            WITH u.user_id AS uid, collect(u.username)[0..%d] AS names, count(*) AS n
            WHERE n > 1
            RETURN uid, names, n LIMIT %d
            """.formatted(USER_ID_INTEGRITY_LIMIT, USER_ID_INTEGRITY_LIMIT).trim();
    private static final String PROFILE_BACKFILL_PENDING_QUERY = """
            MATCH (u:UserAccount)
            WHERE u.display_name IS NOT NULL
              AND NOT (u)-[:HAS_PROFILE]->(:UserProfile)
            RETURN count(u) AS pending
            """.trim();
    private static final String PROFILE_BACKFILL_BATCH_QUERY = """
            MATCH (u:UserAccount)
            WHERE u.display_name IS NOT NULL
              AND NOT (u)-[:HAS_PROFILE]->(:UserProfile)
            WITH u LIMIT %d
            OPTIONAL MATCH (existing:UserProfile {user_id: u.user_id})
            WITH u, existing
            MERGE (p:UserProfile {user_id: u.user_id})
              ON CREATE SET p.display_name = u.display_name,
                            p.updated_at = datetime()
            MERGE (u)-[:HAS_PROFILE]->(p)
            REMOVE u.display_name
            RETURN
              sum(CASE WHEN existing IS NULL THEN 1 ELSE 0 END) AS migrated,
              sum(CASE WHEN existing IS NOT NULL THEN 1 ELSE 0 END) AS skipped
            """.formatted(PROFILE_BACKFILL_BATCH_SIZE).trim();
    private static final String SHOW_RHIZODELTA_CONSTRAINTS_QUERY = """
            SHOW CONSTRAINTS
            YIELD name
            WHERE name STARTS WITH 'rhizodelta_'
            RETURN name
            ORDER BY name
            """.trim();
    private static final String SHOW_RHIZODELTA_INDEXES_QUERY = """
            SHOW INDEXES
            YIELD name
            WHERE name STARTS WITH 'rhizodelta_'
            RETURN name
            ORDER BY name
            """.trim();

    private static final List<String> SCHEMA_QUERIES = List.of(
            "CREATE CONSTRAINT rhizodelta_graph_node_node_id_unique IF NOT EXISTS FOR (n:GraphNode) REQUIRE n.node_id IS UNIQUE",
            "CREATE CONSTRAINT rhizodelta_human_post_request_id_unique IF NOT EXISTS FOR (n:Human_Post) REQUIRE n.request_id IS UNIQUE",
            "CREATE CONSTRAINT rhizodelta_human_post_decision_id_unique IF NOT EXISTS FOR (n:Human_Post) REQUIRE n.decision_id IS UNIQUE",
            "CREATE CONSTRAINT rhizodelta_ai_consensus_decision_id_unique IF NOT EXISTS FOR (n:AI_Consensus) REQUIRE n.decision_id IS UNIQUE",
            "CREATE CONSTRAINT rhizodelta_result_decision_id_unique IF NOT EXISTS FOR (n:Result) REQUIRE n.decision_id IS UNIQUE",
            "CREATE INDEX rhizodelta_human_post_author_id_idx IF NOT EXISTS FOR (n:Human_Post) ON (n.author_id)",
            "CREATE INDEX rhizodelta_human_post_created_at_idx IF NOT EXISTS FOR (n:Human_Post) ON (n.created_at)",
            "CREATE INDEX rhizodelta_ai_consensus_created_at_idx IF NOT EXISTS FOR (n:AI_Consensus) ON (n.created_at)",
            "CREATE INDEX rhizodelta_result_created_at_idx IF NOT EXISTS FOR (n:Result) ON (n.created_at)",
            "CREATE INDEX rhizodelta_human_post_operation_id_idx IF NOT EXISTS FOR (n:Human_Post) ON (n.operation_id)",
            "CREATE INDEX rhizodelta_conceptual_overlap_association_id_idx IF NOT EXISTS FOR ()-[r:CONCEPTUAL_OVERLAP]-() ON (r.association_id)",
            "CREATE INDEX rhizodelta_relates_to_association_id_idx IF NOT EXISTS FOR ()-[r:RELATES_TO]-() ON (r.association_id)",
            "CREATE CONSTRAINT rhizodelta_user_account_username_unique IF NOT EXISTS FOR (n:UserAccount) REQUIRE n.username IS UNIQUE",
            "CREATE CONSTRAINT rhizodelta_user_account_user_id_unique IF NOT EXISTS FOR (n:UserAccount) REQUIRE n.user_id IS UNIQUE",
            "CREATE INDEX rhizodelta_user_account_status_idx IF NOT EXISTS FOR (n:UserAccount) ON (n.status)",
            "CREATE CONSTRAINT rhizodelta_user_profile_user_id_unique IF NOT EXISTS FOR (n:UserProfile) REQUIRE n.user_id IS UNIQUE"
    );

    private final Neo4jClient neo4jClient;
    private final int embeddingDimension;

    public DatabaseInitializer(
            Neo4jClient neo4jClient,
            @Value("${rhizodelta.embedding.dimension}") int embeddingDimension
    ) {
        this.neo4jClient = neo4jClient;
        this.embeddingDimension = embeddingDimension;
    }

    /**
     * 在 Bean 初始化后执行 schema 初始化。
     *
     * <p>该流程会先创建常规约束与索引，再创建向量索引，最后输出校验日志。
     */
    @PostConstruct
    void initializeSchema() {
        verifyUserIdIntegrity();
        for (String query : SCHEMA_QUERIES) {
            executeSchemaQuery(query);
        }
        executeSchemaQuery(buildVectorIndexQuery());
        migrateLegacyUserProfile();
        logConstraintStatus();
    }

    private void verifyUserIdIntegrity() {
        List<Map<String, Object>> blankViolations = runReadOnlyIntegrityQuery(NULL_OR_BLANK_USER_ID_QUERY);
        List<Map<String, Object>> duplicateViolations = runReadOnlyIntegrityQuery(DUPLICATE_USER_ID_QUERY);
        if (blankViolations.isEmpty() && duplicateViolations.isEmpty()) {
            return;
        }
        throw new IllegalStateException(buildIntegrityViolationMessage(blankViolations, duplicateViolations));
    }

    /**
     * 把还把 display_name 留在 UserAccount 且没有 HAS_PROFILE 边的账户
     * 逐批迁移到 UserProfile 节点上。本方法幂等：已经迁移完的数据库，该方法是一次 count 查询后即返回。
     */
    private void migrateLegacyUserProfile() {
        long totalMigrated = 0L;
        long totalSkipped = 0L;
        try {
            while (true) {
                long pending = runPendingCountQuery();
                if (pending <= 0L) {
                    break;
                }
                Map<String, Object> batchCounts = runBackfillBatch();
                long migrated = readLong(batchCounts.get("migrated"));
                long skipped = readLong(batchCounts.get("skipped"));
                totalMigrated += migrated;
                totalSkipped += skipped;
                LOGGER.info("UserProfile backfill: migrated={}, skipped={}", migrated, skipped);
                if (migrated + skipped <= 0L) {
                    break;
                }
            }
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("UserProfile backfill failed", exception);
        }
        if (totalMigrated == 0L && totalSkipped == 0L) {
            LOGGER.info("UserProfile backfill: migrated=0, skipped=0");
        }
    }

    private long runPendingCountQuery() {
        try {
            return neo4jClient.query(PROFILE_BACKFILL_PENDING_QUERY)
                    .fetch()
                    .one()
                    .map(record -> readLong(record.get("pending")))
                    .orElse(0L);
        } catch (Exception exception) {
            throw new IllegalStateException("UserProfile backfill pending-count query failed", exception);
        }
    }

    private Map<String, Object> runBackfillBatch() {
        return neo4jClient.query(PROFILE_BACKFILL_BATCH_QUERY)
                .fetch()
                .one()
                .orElse(Map.of("migrated", 0L, "skipped", 0L));
    }

    /**
     * 执行单条 schema 语句。
     *
     * <p>执行失败会被视为启动级错误，而不是可忽略的告警。
     */
    private void executeSchemaQuery(String query) {
        try {
            neo4jClient.query(query).run();
            LOGGER.info("Schema query applied: {}", query);
        } catch (Exception exception) {
            LOGGER.error("Failed to apply schema query: {}", query, exception);
            throw new IllegalStateException("Neo4j schema initialization failed", exception);
        }
    }

    private List<Map<String, Object>> runReadOnlyIntegrityQuery(String query) {
        try {
            return List.copyOf(neo4jClient.query(query).fetch().all());
        } catch (Exception exception) {
            throw new IllegalStateException("Neo4j user_id integrity pre-check failed", exception);
        }
    }

    private String buildIntegrityViolationMessage(
            List<Map<String, Object>> blankViolations,
            List<Map<String, Object>> duplicateViolations
    ) {
        List<String> sections = new ArrayList<>();
        if (!blankViolations.isEmpty()) {
            sections.add("blank user_id usernames=" + formatBlankViolations(blankViolations));
        }
        if (!duplicateViolations.isEmpty()) {
            sections.add("duplicate user_id entries=" + formatDuplicateViolations(duplicateViolations));
        }
        return "UserAccount user_id integrity violation: " + String.join("; ", sections);
    }

    private String formatBlankViolations(List<Map<String, Object>> blankViolations) {
        return blankViolations.stream()
                .map(violation -> readText(violation.get("username"), "<missing username>"))
                .toList()
                .toString();
    }

    private String formatDuplicateViolations(List<Map<String, Object>> duplicateViolations) {
        return duplicateViolations.stream()
                .map(this::formatDuplicateViolation)
                .toList()
                .toString();
    }

    private String formatDuplicateViolation(Map<String, Object> violation) {
        String userId = readText(violation.get("uid"), "<null user_id>");
        String usernames = formatUsernames(violation.get("names"));
        long total = readLong(violation.get("n"));
        return total > 0
                ? userId + " -> " + usernames + " (total=" + total + ")"
                : userId + " -> " + usernames;
    }

    private long readLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private String formatUsernames(Object value) {
        if (!(value instanceof Collection<?> usernames)) {
            return "[]";
        }
        return usernames.stream()
                .map(username -> readText(username, "<missing username>"))
                .toList()
                .toString();
    }

    private String readText(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = value.toString();
        return text.isBlank() ? fallback : text;
    }

    /**
     * 输出当前 RhizoDelta 相关约束和索引状态。
     */
    private void logConstraintStatus() {
        List<Map<String, Object>> schemaEntries = new ArrayList<>();
        schemaEntries.addAll(fetchSchemaEntries(SHOW_RHIZODELTA_CONSTRAINTS_QUERY));
        schemaEntries.addAll(fetchSchemaEntries(SHOW_RHIZODELTA_INDEXES_QUERY));
        LOGGER.info("Neo4j constraints/indexes verified: {}", schemaEntries);
    }

    private Collection<Map<String, Object>> fetchSchemaEntries(String query) {
        return neo4jClient.query(query).fetch().all();
    }

    /**
     * 构造向量索引创建语句。
     *
     * <p>向量维度直接绑定当前系统配置，避免索引维度与模型维度不一致。
     */
    private String buildVectorIndexQuery() {
        return """
                CREATE VECTOR INDEX %s IF NOT EXISTS
                FOR (n:GraphNode)
                ON n.embedding
                OPTIONS { indexConfig: {
                  `vector.dimensions`: %d,
                  `vector.similarity_function`: 'cosine'
                }}
                """.formatted(VECTOR_INDEX_NAME, embeddingDimension).trim();
    }
}
