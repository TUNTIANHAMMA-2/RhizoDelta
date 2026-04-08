package com.rhizodelta.infrastructure.persistence.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

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
            "CREATE CONSTRAINT rhizodelta_user_account_username_unique IF NOT EXISTS FOR (n:UserAccount) REQUIRE n.username IS UNIQUE"
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
        for (String query : SCHEMA_QUERIES) {
            executeSchemaQuery(query);
        }
        executeSchemaQuery(buildVectorIndexQuery());
        logConstraintStatus();
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

    /**
     * 输出当前 RhizoDelta 相关约束和索引状态。
     */
    private void logConstraintStatus() {
        Collection<Map<String, Object>> constraints = neo4jClient.query("""
                SHOW CONSTRAINTS
                YIELD name
                WHERE name STARTS WITH 'rhizodelta_'
                RETURN name
                ORDER BY name
                """).fetch().all();
        LOGGER.info("Neo4j constraints/indexes verified: {}", constraints);
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
