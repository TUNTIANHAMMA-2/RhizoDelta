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
import java.util.Map;
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

        AuthoredMaintenanceService.AuthoredBackfillResult result = authoredMaintenanceService.runAuthoredBackfill();

        assertThat(countAuthoredEdges(matchedPostId, "author-a")).isEqualTo(1L);
        assertThat(countAuthoredEdges(missingPostId, "ghost-author")).isEqualTo(0L);
        assertThat(result.touchedCount()).isEqualTo(1L);
        assertThat(result.missingSamples())
                .contains(new AuthoredMaintenanceService.MissingAuthoredEdge(
                        missingPostId.toString(),
                        "ghost-author"
                ));
    }

    @Test
    void runAuthoredBackfillShouldBeIdempotentAcrossRepeatedRuns() {
        UUID postId = UUID.randomUUID();
        createUserAccount("author-b", "bob");
        createHumanPost(postId, "req-repeat", "author-b", "repeat content");

        authoredMaintenanceService.runAuthoredBackfill();
        authoredMaintenanceService.runAuthoredBackfill();

        assertThat(countAuthoredEdges(postId, "author-b")).isEqualTo(1L);
    }

    @Test
    void findMissingAuthoredEdgesShouldReportOnlyPostsWithoutCanonicalEdge() {
        UUID missingEdgePostId = UUID.randomUUID();
        UUID validEdgePostId = UUID.randomUUID();
        createUserAccount("author-c", "carol");
        createHumanPost(missingEdgePostId, "req-missing-edge", "author-c", "needs edge");
        createHumanPost(validEdgePostId, "req-valid-edge", "author-c", "already linked");
        createAuthoredEdge(validEdgePostId, "author-c");

        List<AuthoredMaintenanceService.MissingAuthoredEdge> missingEdges =
                authoredMaintenanceService.findMissingAuthoredEdges();

        assertThat(missingEdges)
                .contains(new AuthoredMaintenanceService.MissingAuthoredEdge(
                        missingEdgePostId.toString(),
                        "author-c"
                ))
                .doesNotContain(new AuthoredMaintenanceService.MissingAuthoredEdge(
                        validEdgePostId.toString(),
                        "author-c"
                ));
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
        neo4jClient.query("""
                MATCH (user:UserAccount {user_id: $authorId})
                MATCH (post:Human_Post {node_id: $nodeId})
                MERGE (user)-[:AUTHORED {created_at: $createdAt}]->(post)
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
