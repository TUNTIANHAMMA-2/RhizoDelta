package com.rhizodelta.api;

import com.rhizodelta.consensus.domain.decision.DecisionOperatorType;
import com.rhizodelta.consensus.service.AuditRelationService;
import com.rhizodelta.consensus.service.DecisionMetadataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 13.1 — 闭环验证：审计关系 REVIEWED / OPERATED 在 Neo4j 中真实持久化。
 *
 * <p>REVIEWED 只关联已有 Decision，避免复核先到时创建 {@code decision_type=PENDING} 占位节点。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false"
})
class AuditRelationIntegrationTest {

    @Container
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5")
            .withAdminPassword("testpassword");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.neo4j.uri", neo4j::getBoltUrl);
        registry.add("spring.neo4j.authentication.username", () -> "neo4j");
        registry.add("spring.neo4j.authentication.password", neo4j::getAdminPassword);
    }

    @Autowired
    private AuditRelationService auditRelationService;

    @Autowired
    private DecisionMetadataService decisionMetadataService;

    @Autowired
    private Neo4jClient neo4jClient;

    @BeforeEach
    void cleanDatabase() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();
    }

    @Test
    void recordReviewCreatesReviewedEdgeForExistingDecisionNode() {
        String reviewerId = UUID.randomUUID().toString();
        String decisionId = UUID.randomUUID().toString();
        seedUser(reviewerId, "reviewer1");
        seedDecision(decisionId, "MERGE");

        auditRelationService.recordReview(reviewerId, decisionId, "APPROVED");

        long edgeCount = neo4jClient.query("""
                MATCH (:UserAccount {user_id: $reviewerId})-[r:REVIEWED]->(:Decision {decision_id: $decisionId})
                RETURN count(r) AS total
                """)
                .bindAll(Map.of("reviewerId", reviewerId, "decisionId", decisionId))
                .fetch()
                .one()
                .map(record -> ((Number) record.get("total")).longValue())
                .orElse(0L);
        assertThat(edgeCount).isEqualTo(1L);
    }

    @Test
    void recordReviewIsIdempotent() {
        String reviewerId = UUID.randomUUID().toString();
        String decisionId = UUID.randomUUID().toString();
        seedUser(reviewerId, "reviewer2");
        seedDecision(decisionId, "MERGE");

        auditRelationService.recordReview(reviewerId, decisionId, "APPROVED");
        auditRelationService.recordReview(reviewerId, decisionId, "REJECTED");

        long edgeCount = neo4jClient.query("""
                MATCH (:UserAccount {user_id: $reviewerId})-[r:REVIEWED]->(:Decision {decision_id: $decisionId})
                RETURN count(r) AS total, collect(r.outcome) AS outcomes
                """)
                .bindAll(Map.of("reviewerId", reviewerId, "decisionId", decisionId))
                .fetch()
                .one()
                .map(record -> ((Number) record.get("total")).longValue())
                .orElse(0L);
        assertThat(edgeCount).as("MERGE should not duplicate edges").isEqualTo(1L);

        List<Map<String, Object>> history = auditRelationService.getReviewHistory(decisionId);
        assertThat(history).hasSize(1);
        // 后写覆盖前写：outcome 应为最新
        assertThat(history.get(0).get("outcome")).isEqualTo("REJECTED");
    }

    @Test
    void recordOperationCreatesOperatedEdge() {
        String operatorId = UUID.randomUUID().toString();
        String nodeId = UUID.randomUUID().toString();
        String operationId = UUID.randomUUID().toString();
        seedUser(operatorId, "operator1");
        seedGraphNode(nodeId);

        auditRelationService.recordOperation(operatorId, nodeId, operationId);

        long edgeCount = neo4jClient.query("""
                MATCH (:UserAccount {user_id: $operatorId})-[r:OPERATED]->(:GraphNode {node_id: $nodeId})
                RETURN count(r) AS total
                """)
                .bindAll(Map.of("operatorId", operatorId, "nodeId", nodeId))
                .fetch()
                .one()
                .map(record -> ((Number) record.get("total")).longValue())
                .orElse(0L);
        assertThat(edgeCount).isEqualTo(1L);

        List<Map<String, Object>> history = auditRelationService.getOperationHistory(nodeId);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("operation_id")).isEqualTo(operationId);
    }

    @Test
    void reviewHistoryReturnsDisplayName() {
        String reviewerId = UUID.randomUUID().toString();
        String decisionId = UUID.randomUUID().toString();
        seedUserWithProfile(reviewerId, "alice", "Alice Wonderland");
        seedDecision(decisionId, "MERGE");

        auditRelationService.recordReview(reviewerId, decisionId, "APPROVED");

        List<Map<String, Object>> history = auditRelationService.getReviewHistory(decisionId);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("reviewer_display_name")).isEqualTo("Alice Wonderland");
    }

    @Test
    void reviewBeforeMetadataDoesNotLeavePendingDecisionType() {
        String reviewerId = UUID.randomUUID().toString();
        String decisionId = UUID.randomUUID().toString();
        String targetNodeId = UUID.randomUUID().toString();
        seedUser(reviewerId, "reviewer3");
        seedGraphNode(targetNodeId);

        auditRelationService.recordReview(reviewerId, decisionId, "APPROVED");

        long placeholderCount = neo4jClient.query(
                "MATCH (d:Decision {decision_id: $decisionId}) RETURN count(d) AS total")
                .bindAll(Map.of("decisionId", decisionId))
                .fetch()
                .one()
                .map(record -> ((Number) record.get("total")).longValue())
                .orElse(0L);
        assertThat(placeholderCount).isZero();

        decisionMetadataService.recordDecision(
                decisionId,
                "MERGE",
                DecisionOperatorType.AGENT,
                "agent-1",
                UUID.fromString(targetNodeId),
                "reason",
                OffsetDateTime.now()
        );

        String decisionType = neo4jClient.query("""
                MATCH (d:Decision {decision_id: $decisionId})
                RETURN d.decision_type AS decisionType
                """)
                .bindAll(Map.of("decisionId", decisionId))
                .fetch()
                .one()
                .map(record -> record.get("decisionType").toString())
                .orElseThrow();
        assertThat(decisionType).isEqualTo("MERGE");
    }

    private void seedUser(String userId, String username) {
        neo4jClient.query("""
                CREATE (u:UserAccount {user_id: $userId, username: $username, status: 'ACTIVE', created_at: datetime()})
                """)
                .bindAll(Map.of("userId", userId, "username", username))
                .run();
    }

    private void seedUserWithProfile(String userId, String username, String displayName) {
        neo4jClient.query("""
                CREATE (u:UserAccount {user_id: $userId, username: $username, status: 'ACTIVE', created_at: datetime()})
                CREATE (p:UserProfile {user_id: $userId, display_name: $displayName, updated_at: datetime()})
                CREATE (u)-[:HAS_PROFILE]->(p)
                """)
                .bindAll(Map.of("userId", userId, "username", username, "displayName", displayName))
                .run();
    }

    private void seedGraphNode(String nodeId) {
        neo4jClient.query("""
                CREATE (n:Human_Post:GraphNode {
                    node_id: $nodeId,
                    content: 'fixture',
                    created_at: datetime()
                })
                """)
                .bindAll(Map.of("nodeId", nodeId))
                .run();
    }

    private void seedDecision(String decisionId, String decisionType) {
        neo4jClient.query("""
                CREATE (:Decision {
                    decision_id: $decisionId,
                    decision_type: $decisionType,
                    created_at: datetime()
                })
                """)
                .bindAll(Map.of("decisionId", decisionId, "decisionType", decisionType))
                .run();
    }
}
