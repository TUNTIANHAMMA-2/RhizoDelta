package com.rhizodelta.infrastructure.persistence.config;

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
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false"
})
class AuthoredMaintenanceServiceIntegrationTest {
    private static final DockerImageName RABBIT_IMAGE = DockerImageName.parse("rabbitmq:3.13");

    @Container
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5")
            .withAdminPassword("testpassword");

    @Container
    static final RabbitMQContainer rabbitMq = new RabbitMQContainer(RABBIT_IMAGE);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.neo4j.uri", neo4j::getBoltUrl);
        registry.add("spring.neo4j.authentication.username", () -> "neo4j");
        registry.add("spring.neo4j.authentication.password", neo4j::getAdminPassword);
        registry.add("spring.rabbitmq.host", rabbitMq::getHost);
        registry.add("spring.rabbitmq.port", rabbitMq::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> "guest");
        registry.add("spring.rabbitmq.password", () -> "guest");
    }

    @Autowired
    private AuthoredMaintenanceService authoredMaintenanceService;

    @Autowired
    private Neo4jClient neo4jClient;

    @BeforeEach
    void cleanDatabase() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();
    }

    @Test
    void runAuthoredBackfillShouldCreateEdgesForExistingAuthorsAndReportMissingOnes() {
        UUID matchedPostId = UUID.randomUUID();
        UUID missingPostId = UUID.randomUUID();
        createUserAccount("author-a", "alice");
        createHumanPost(matchedPostId, "req-matched", "author-a", "matched content");
        createHumanPost(missingPostId, "req-missing", "ghost-author", "missing content");

        AuthoredMaintenanceService.AuthoredBackfillResult result =
                authoredMaintenanceService.runAuthoredBackfill();

        assertThat(countAuthoredEdges(matchedPostId, "author-a")).isEqualTo(1L);
        assertThat(countAuthoredEdges(missingPostId, "ghost-author")).isEqualTo(0L);
        assertThat(result.createdCount()).isEqualTo(1L);
        // ghost-author 没有对应 UserAccount 仍然是 MISSING 样本，但不可修复。
        assertThat(result.driftSummary().missingCount()).isEqualTo(1L);
        assertThat(result.driftSamples())
                .anySatisfy(drift -> {
                    assertThat(drift.nodeId()).isEqualTo(missingPostId.toString());
                    assertThat(drift.authorId()).isEqualTo("ghost-author");
                    assertThat(drift.kind())
                            .isEqualTo(AuthoredMaintenanceService.AuthoredDriftKind.MISSING);
                });
    }

    @Test
    void runAuthoredBackfillShouldBeIdempotentAcrossRepeatedRuns() {
        UUID postId = UUID.randomUUID();
        createUserAccount("author-b", "bob");
        createHumanPost(postId, "req-repeat", "author-b", "repeat content");

        authoredMaintenanceService.runAuthoredBackfill();
        long createdSecondRun =
                authoredMaintenanceService.runAuthoredBackfill().createdCount();

        assertThat(countAuthoredEdges(postId, "author-b")).isEqualTo(1L);
        assertThat(createdSecondRun).isZero();
    }

    @Test
    void runAuthoredBackfillShouldWriteAuthoredIdCompositeKey() {
        UUID postId = UUID.randomUUID();
        createUserAccount("author-d", "dora");
        createHumanPost(postId, "req-keyed", "author-d", "with authored_id");

        authoredMaintenanceService.runAuthoredBackfill();

        String authoredId = neo4jClient.query("""
                        MATCH (:UserAccount {user_id: $authorId})-[r:AUTHORED]->(:Human_Post {node_id: $nodeId})
                        RETURN r.authored_id AS authoredId
                        """)
                .bind("author-d").to("authorId")
                .bind(postId.toString()).to("nodeId")
                .fetchAs(String.class)
                .one()
                .orElse(null);
        assertThat(authoredId).isEqualTo("author-d:" + postId);
    }

    @Test
    void auditDriftShouldDetectExtraneousAuthoredEdge() {
        UUID postId = UUID.randomUUID();
        createUserAccount("author-c", "carol");
        createUserAccount("intruder", "mallory");
        createHumanPost(postId, "req-drift", "author-c", "drifted post");
        // 健康边
        createAuthoredEdge(postId, "author-c");
        // 不该存在的额外边：作者错配漂移
        createAuthoredEdge(postId, "intruder");

        AuthoredMaintenanceService.AuthoredDriftSummary summary =
                authoredMaintenanceService.auditDrift();
        List<AuthoredMaintenanceService.AuthoredEdgeDrift> samples =
                authoredMaintenanceService.findDriftSamples(10);

        assertThat(summary.extraneousAlongsideMatchCount()).isEqualTo(1L);
        assertThat(samples)
                .anySatisfy(drift -> {
                    assertThat(drift.nodeId()).isEqualTo(postId.toString());
                    assertThat(drift.totalAuthoredEdges()).isEqualTo(2L);
                    assertThat(drift.matchingAuthoredEdges()).isEqualTo(1L);
                    assertThat(drift.kind())
                            .isEqualTo(AuthoredMaintenanceService.AuthoredDriftKind.EXTRANEOUS_ALONGSIDE_MATCH);
                });
    }

    @Test
    void auditDriftShouldDetectWrongAuthorOnlyEdge() {
        UUID postId = UUID.randomUUID();
        createUserAccount("declared-author", "declared");
        createUserAccount("wrong-author", "wrong");
        createHumanPost(postId, "req-wrong", "declared-author", "wrong-author edge");
        // 缺正确边，且有错的来源边
        createAuthoredEdge(postId, "wrong-author");

        AuthoredMaintenanceService.AuthoredDriftSummary summary =
                authoredMaintenanceService.auditDrift();

        assertThat(summary.extraneousWithoutMatchCount()).isEqualTo(1L);
    }

    private void createUserAccount(String userId, String username) {
        neo4jClient.query("""
                        CREATE (:UserAccount {
                          username: $username,
                          user_id: $userId,
                          password_hash: 'hash',
                          roles: ['USER'],
                          status: 'ACTIVE',
                          created_at: $createdAt
                        })
                        """)
                .bind(username).to("username")
                .bind(userId).to("userId")
                .bind(nowUtc()).to("createdAt")
                .run();
    }

    private void createHumanPost(UUID nodeId, String requestId, String authorId, String content) {
        neo4jClient.query("""
                        CREATE (:Human_Post:GraphNode {
                          node_id: $nodeId,
                          request_id: $requestId,
                          author_id: $authorId,
                          root_id: $rootId,
                          content: $content,
                          created_at: $createdAt
                        })
                        """)
                .bind(nodeId.toString()).to("nodeId")
                .bind(requestId).to("requestId")
                .bind(authorId).to("authorId")
                .bind(nodeId.toString()).to("rootId")
                .bind(content).to("content")
                .bind(nowUtc()).to("createdAt")
                .run();
    }

    private void createAuthoredEdge(UUID nodeId, String authorId) {
        // 直接拼一个非冲突的 authored_id，避免与回填生成的复合键碰撞。
        neo4jClient.query("""
                        MATCH (user:UserAccount {user_id: $authorId})
                        MATCH (post:Human_Post {node_id: $nodeId})
                        MERGE (user)-[r:AUTHORED]->(post)
                          ON CREATE SET r.created_at = $createdAt,
                                        r.authored_id = $authorId + ':' + $nodeId
                        """)
                .bind(authorId).to("authorId")
                .bind(nodeId.toString()).to("nodeId")
                .bind(nowUtc()).to("createdAt")
                .run();
    }

    private long countAuthoredEdges(UUID nodeId, String authorId) {
        return neo4jClient.query("""
                        MATCH (:UserAccount {user_id: $authorId})-[rel:AUTHORED]->(:Human_Post {node_id: $nodeId})
                        RETURN count(rel) AS count
                        """)
                .bind(authorId).to("authorId")
                .bind(nodeId.toString()).to("nodeId")
                .fetchAs(Long.class)
                .one()
                .orElse(0L);
    }

    private static OffsetDateTime nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
