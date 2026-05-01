package com.rhizodelta.api;

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
class ChildrenExpansionIntegrationTest {
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

    @BeforeEach
    void cleanDatabase() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();
    }

    @Test
    void shouldReturnChildrenForDownstreamBranches() {
        UUID rootId = UUID.randomUUID();
        UUID leftId = UUID.randomUUID();
        UUID rightId = UUID.randomUUID();
        createHumanPostNode(rootId, "req-root", "author-root", "root");
        createHumanPostNode(leftId, "req-left", "author-left", "left");
        createHumanPostNode(rightId, "req-right", "author-right", "right");
        createBranchedFrom(leftId, rootId);
        createBranchedFrom(rightId, rootId);

        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/api/nodes/" + rootId + "/children",
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = responseData(response);
        List<Map<String, Object>> nodes = nodeList(data);
        List<Map<String, Object>> edges = edgeList(data);

        assertThat(nodeIds(nodes))
                .containsExactlyInAnyOrder(rootId.toString(), leftId.toString(), rightId.toString());
        assertThat(edgeKeys(edges))
                .containsExactlyInAnyOrder(
                        edgeKey(leftId, rootId, "BRANCHED_FROM"),
                        edgeKey(rightId, rootId, "BRANCHED_FROM")
                );
    }

    @Test
    void shouldReturnSingleNodeForLeaf() {
        UUID leafId = UUID.randomUUID();
        createHumanPostNode(leafId, "req-leaf", "author-leaf", "leaf");

        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/api/nodes/" + leafId + "/children",
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = responseData(response);
        List<Map<String, Object>> nodes = nodeList(data);
        List<Map<String, Object>> edges = edgeList(data);

        assertThat(nodeIds(nodes)).containsExactly(leafId.toString());
        assertThat(edges).isEmpty();
    }

    private void createHumanPostNode(UUID nodeId, String requestId, String authorId, String content) {
        neo4jClient.query("""
                CREATE (:Human_Post:GraphNode {
                  node_id: $nodeId,
                  request_id: $requestId,
                  author_id: $authorId,
                  content: $content,
                  created_at: $createdAt
                })
                """)
                .bind(nodeId.toString()).to("nodeId")
                .bind(requestId).to("requestId")
                .bind(authorId).to("authorId")
                .bind(content).to("content")
                .bind(nowUtc()).to("createdAt")
                .run();
    }

    private void createBranchedFrom(UUID childId, UUID ancestorId) {
        neo4jClient.query("""
                MATCH (child:GraphNode {node_id: $childId}), (ancestor:GraphNode {node_id: $ancestorId})
                CREATE (child)-[:BRANCHED_FROM {
                    operator_type: 'HUMAN',
                    operator_id: 'tester',
                    created_at: $createdAt,
                    reason: 'children'
                }]->(ancestor)
                """)
                .bind(childId.toString()).to("childId")
                .bind(ancestorId.toString()).to("ancestorId")
                .bind(nowUtc()).to("createdAt")
                .run();
    }

    private static OffsetDateTime nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> responseData(ResponseEntity<Map> response) {
        assertThat(response.getBody()).isNotNull();
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nodeList(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("nodes");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> edgeList(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("edges");
    }

    private static List<String> nodeIds(List<Map<String, Object>> nodes) {
        return nodes.stream()
                .map(node -> (String) node.get("node_id"))
                .toList();
    }

    private static List<String> edgeKeys(List<Map<String, Object>> edges) {
        return edges.stream()
                .map(ChildrenExpansionIntegrationTest::edgeKey)
                .toList();
    }

    private static String edgeKey(Map<String, Object> edge) {
        return edgeKey(
                (String) edge.get("source"),
                (String) edge.get("target"),
                (String) edge.get("type")
        );
    }

    private static String edgeKey(UUID source, UUID target, String type) {
        return edgeKey(source.toString(), target.toString(), type);
    }

    private static String edgeKey(String source, String target, String type) {
        return source + "->" + target + ":" + type;
    }
}
