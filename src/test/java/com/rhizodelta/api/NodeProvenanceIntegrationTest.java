package com.rhizodelta.api;

import com.rhizodelta.query.service.NodeQueryService;
import com.rhizodelta.query.service.NodeQueryService.LineageNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false"
})
class NodeProvenanceIntegrationTest {
    @Container
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5")
            .withAdminPassword("testpassword");

    @DynamicPropertySource
    static void neo4jProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.neo4j.uri", neo4j::getBoltUrl);
        registry.add("spring.neo4j.authentication.username", () -> "neo4j");
        registry.add("spring.neo4j.authentication.password", neo4j::getAdminPassword);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private Neo4jClient neo4jClient;

    @Autowired
    private NodeQueryService nodeQueryService;

    @BeforeEach
    void cleanDatabase() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();
    }

    // --- Service-level (Tasks 4.1 - 4.7) ---

    @Test
    void provenanceForHumanPostWithContinuesFromReturnsParent() {
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        createHumanPost(parentId, "req-parent", "author-parent", "parent post");
        createHumanPost(childId, "req-child", "author-child", "child post");
        createContinuesFrom(childId, parentId);

        List<LineageNode> result = nodeQueryService.getProvenanceSummaries(childId);

        assertThat(result).hasSize(1);
        LineageNode upstream = result.get(0);
        assertThat(upstream.nodeId()).isEqualTo(parentId.toString());
        assertThat(upstream.label()).isEqualTo("Human_Post");
        assertThat(upstream.content()).isEqualTo("parent post");
    }

    @Test
    void provenanceForHumanPostWithBranchedFromReturnsParent() {
        UUID parentId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        createHumanPost(parentId, "req-parent", "author-parent", "parent post");
        createHumanPost(branchId, "req-branch", "author-branch", "branch post");
        createBranchedFrom(branchId, parentId);

        List<LineageNode> result = nodeQueryService.getProvenanceSummaries(branchId);

        assertThat(result).hasSize(1);
        LineageNode upstream = result.get(0);
        assertThat(upstream.nodeId()).isEqualTo(parentId.toString());
        assertThat(upstream.label()).isEqualTo("Human_Post");
    }

    @Test
    void provenanceForRootHumanPostReturnsEmpty() {
        UUID rootId = UUID.randomUUID();
        createHumanPost(rootId, "req-root", "author-root", "root post");

        List<LineageNode> result = nodeQueryService.getProvenanceSummaries(rootId);

        assertThat(result).isEmpty();
    }

    @Test
    void provenanceForResultWithMaterializedFromReturnsOrderedSources() {
        UUID firstSourceId = UUID.randomUUID();
        UUID secondSourceId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        OffsetDateTime earlier = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10);
        OffsetDateTime later = OffsetDateTime.now(ZoneOffset.UTC);
        createHumanPostAt(firstSourceId, "req-first", "author-first", "earlier source", earlier);
        createHumanPostAt(secondSourceId, "req-second", "author-second", "later source", later);
        createResult(resultId, "synthesized result");
        createMaterializedFrom(resultId, firstSourceId);
        createMaterializedFrom(resultId, secondSourceId);

        List<LineageNode> result = nodeQueryService.getProvenanceSummaries(resultId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).nodeId()).isEqualTo(secondSourceId.toString());
        assertThat(result.get(1).nodeId()).isEqualTo(firstSourceId.toString());
        assertThat(result).allMatch(node -> "Human_Post".equals(node.label()));
    }

    @Test
    void provenanceForResultWithoutSourcesReturnsEmpty() {
        UUID resultId = UUID.randomUUID();
        createResult(resultId, "orphan result");

        List<LineageNode> result = nodeQueryService.getProvenanceSummaries(resultId);

        assertThat(result).isEmpty();
    }

    @Test
    void provenanceFiltersSoftDeletedUpstreamForHumanPost() {
        UUID liveParentId = UUID.randomUUID();
        UUID deletedParentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        createHumanPost(liveParentId, "req-live-parent", "author-a", "live parent");
        createHumanPost(deletedParentId, "req-deleted-parent", "author-b", "deleted parent");
        createHumanPost(childId, "req-child", "author-child", "child");
        createBranchedFrom(childId, liveParentId);
        createBranchedFrom(childId, deletedParentId);
        markDeleted(deletedParentId);

        List<LineageNode> result = nodeQueryService.getProvenanceSummaries(childId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).nodeId()).isEqualTo(liveParentId.toString());
    }

    @Test
    void provenanceFiltersSoftDeletedUpstreamForResult() {
        UUID liveSourceId = UUID.randomUUID();
        UUID deletedSourceId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        createHumanPost(liveSourceId, "req-live-src", "author-a", "live source");
        createHumanPost(deletedSourceId, "req-deleted-src", "author-b", "deleted source");
        createResult(resultId, "result");
        createMaterializedFrom(resultId, liveSourceId);
        createMaterializedFrom(resultId, deletedSourceId);
        markDeleted(deletedSourceId);

        List<LineageNode> result = nodeQueryService.getProvenanceSummaries(resultId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).nodeId()).isEqualTo(liveSourceId.toString());
    }

    @Test
    void provenanceForAiConsensusStillReturnsSynthesizedFromSources() {
        UUID sourceId = UUID.randomUUID();
        UUID consensusId = UUID.randomUUID();
        createHumanPost(sourceId, "req-source", "author-source", "source");
        createAiConsensus(consensusId, "consensus summary");
        createSynthesizedFrom(consensusId, sourceId);

        List<LineageNode> result = nodeQueryService.getProvenanceSummaries(consensusId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).nodeId()).isEqualTo(sourceId.toString());
        assertThat(result.get(0).label()).isEqualTo("Human_Post");
    }

    // --- API-level (Tasks 5.1 - 5.3) ---

    @Test
    void httpProvenanceEndpointReturnsParentForHumanPost() {
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        createUserWithProfile("author-parent", "alice", "Alice");
        createHumanPost(parentId, "req-parent", "author-parent", "parent");
        createHumanPost(childId, "req-child", "author-child", "child");
        createContinuesFrom(childId, parentId);

        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/api/nodes/" + childId + "/provenance", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> payload = responseList(response);
        assertThat(payload).hasSize(1);
        Map<String, Object> upstream = payload.get(0);
        assertThat(upstream).containsEntry("node_id", parentId.toString());
        assertThat(upstream).containsEntry("label", "Human_Post");
        assertThat(upstream).containsEntry("author_id", "author-parent");
        assertThat(upstream).containsEntry("author_username", "alice");
        assertThat(upstream).containsEntry("author_display_name", "Alice");
    }

    @Test
    void httpProvenanceEndpointReturnsSourcesForResult() {
        UUID sourceId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        createUserWithProfile("author-src", "bob", "Bob");
        createHumanPost(sourceId, "req-source", "author-src", "source content");
        createResult(resultId, "result content");
        createMaterializedFrom(resultId, sourceId);

        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/api/nodes/" + resultId + "/provenance", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> payload = responseList(response);
        assertThat(payload).hasSize(1);
        Map<String, Object> upstream = payload.get(0);
        assertThat(upstream).containsEntry("node_id", sourceId.toString());
        assertThat(upstream).containsEntry("label", "Human_Post");
        assertThat(upstream).containsEntry("author_id", "author-src");
        assertThat(upstream).containsEntry("author_username", "bob");
        assertThat(upstream).containsEntry("author_display_name", "Bob");
    }

    @Test
    void httpProvenanceEndpointReturnsEmptyForRootHumanPost() {
        UUID rootId = UUID.randomUUID();
        createHumanPost(rootId, "req-root", "author-root", "root content");

        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/api/nodes/" + rootId + "/provenance", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> payload = responseList(response);
        assertThat(payload).isEmpty();
    }

    // --- helpers ---

    private void createHumanPost(UUID nodeId, String requestId, String authorId, String content) {
        createHumanPostAt(nodeId, requestId, authorId, content, OffsetDateTime.now(ZoneOffset.UTC));
    }

    private void createHumanPostAt(UUID nodeId, String requestId, String authorId, String content, OffsetDateTime createdAt) {
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
                .bind(createdAt).to("createdAt")
                .run();
    }

    private void createAiConsensus(UUID nodeId, String summaryContent) {
        neo4jClient.query("""
                CREATE (:AI_Consensus:GraphNode {
                  node_id: $nodeId,
                  summary_content: $summaryContent,
                  agent_version: 'v1',
                  created_at: $createdAt
                })
                """)
                .bind(nodeId.toString()).to("nodeId")
                .bind(summaryContent).to("summaryContent")
                .bind(OffsetDateTime.now(ZoneOffset.UTC)).to("createdAt")
                .run();
    }

    private void createResult(UUID nodeId, String content) {
        neo4jClient.query("""
                CREATE (:Result:GraphNode {
                  node_id: $nodeId,
                  content: $content,
                  created_at: $createdAt
                })
                """)
                .bind(nodeId.toString()).to("nodeId")
                .bind(content).to("content")
                .bind(OffsetDateTime.now(ZoneOffset.UTC)).to("createdAt")
                .run();
    }

    private void createContinuesFrom(UUID childId, UUID parentId) {
        createRelationship(childId, parentId, "CONTINUES_FROM");
    }

    private void createBranchedFrom(UUID childId, UUID parentId) {
        createRelationship(childId, parentId, "BRANCHED_FROM");
    }

    private void createMaterializedFrom(UUID resultId, UUID sourceId) {
        createRelationship(resultId, sourceId, "MATERIALIZED_FROM");
    }

    private void createSynthesizedFrom(UUID consensusId, UUID sourceId) {
        createRelationship(consensusId, sourceId, "SYNTHESIZED_FROM");
    }

    private void createRelationship(UUID fromId, UUID toId, String relType) {
        neo4jClient.query(String.format("""
                MATCH (from:GraphNode {node_id: $fromId}), (to:GraphNode {node_id: $toId})
                CREATE (from)-[:%s {
                    operator_type: 'HUMAN',
                    operator_id: 'tester',
                    created_at: $createdAt,
                    reason: 'test'
                }]->(to)
                """, relType))
                .bind(fromId.toString()).to("fromId")
                .bind(toId.toString()).to("toId")
                .bind(OffsetDateTime.now(ZoneOffset.UTC)).to("createdAt")
                .run();
    }

    private void markDeleted(UUID nodeId) {
        neo4jClient.query("""
                MATCH (n:GraphNode {node_id: $nodeId})
                SET n._deleted = true
                """)
                .bind(nodeId.toString()).to("nodeId")
                .run();
    }

    private void createUserWithProfile(String userId, String username, String displayName) {
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
                .bind(OffsetDateTime.now(ZoneOffset.UTC)).to("createdAt")
                .run();
        neo4jClient.query("""
                MATCH (user:UserAccount {user_id: $userId})
                MERGE (profile:UserProfile {user_id: $userId})
                SET profile.display_name = $displayName,
                    profile.updated_at = $updatedAt
                MERGE (user)-[:HAS_PROFILE]->(profile)
                """)
                .bind(userId).to("userId")
                .bind(displayName).to("displayName")
                .bind(OffsetDateTime.now(ZoneOffset.UTC)).to("updatedAt")
                .run();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> responseList(ResponseEntity<Map> response) {
        return (List<Map<String, Object>>) response.getBody().get("data");
    }
}
