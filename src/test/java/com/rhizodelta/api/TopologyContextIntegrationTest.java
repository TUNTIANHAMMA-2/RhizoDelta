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
class TopologyContextIntegrationTest {
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
    void returnsLineageAndChildrenForMiddleNodeInOneCall() {
        UUID rootId = UUID.randomUUID();
        UUID midId = UUID.randomUUID();
        UUID leafId = UUID.randomUUID();
        createHumanPostNode(rootId, "req-root", "author-root", "root");
        createHumanPostNode(midId, "req-mid", "author-mid", "mid");
        createHumanPostNode(leafId, "req-leaf", "author-leaf", "leaf");
        createBranchedFrom(leafId, midId);
        createBranchedFrom(midId, rootId);

        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/api/nodes/" + midId + "/topology-context",
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = responseData(response);

        Map<String, Object> lineage = topologyAt(data, "lineage");
        assertThat(nodeIds(nodeList(lineage)))
                .as("lineage walks ancestors from mid → root")
                .containsExactlyInAnyOrder(midId.toString(), rootId.toString());
        assertThat(edgeKeys(edgeList(lineage)))
                .containsExactlyInAnyOrder(edgeKey(midId, rootId, "BRANCHED_FROM"));

        Map<String, Object> children = topologyAt(data, "children");
        assertThat(nodeIds(nodeList(children)))
                .as("children walks descendants from mid → leaf, mid included as root")
                .containsExactlyInAnyOrder(midId.toString(), leafId.toString());
        assertThat(edgeKeys(edgeList(children)))
                .containsExactlyInAnyOrder(edgeKey(leafId, midId, "BRANCHED_FROM"));
    }

    @Test
    void leafNodeReturnsEmptyChildrenInsteadOfErroring() {
        // A node that exists but has no descendants. Current /api/nodes/{id}/children would
        // throw NoSuchElementException (mapped to 404) which used to force the frontend to
        // wrap the call in an error handler. The aggregate endpoint must keep lineage and
        // return empty children so leaf-state isn't a fault.
        UUID rootId = UUID.randomUUID();
        UUID midId = UUID.randomUUID();
        createHumanPostNode(rootId, "req-root", "author-root", "root");
        createHumanPostNode(midId, "req-mid", "author-mid", "mid");
        createBranchedFrom(midId, rootId);

        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/api/nodes/" + midId + "/topology-context",
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = responseData(response);

        Map<String, Object> lineage = topologyAt(data, "lineage");
        assertThat(nodeIds(nodeList(lineage))).contains(midId.toString());

        Map<String, Object> children = topologyAt(data, "children");
        assertThat(nodeIds(nodeList(children)))
                .as("a leaf node yields children topology with just itself")
                .containsExactly(midId.toString());
        assertThat(edgeList(children)).as("no children edges for a leaf").isEmpty();
    }

    @Test
    void honorsLineageAndChildrenDepthQueryParams() {
        UUID rootId = UUID.randomUUID();
        UUID midId = UUID.randomUUID();
        UUID leafId = UUID.randomUUID();
        createHumanPostNode(rootId, "req-root", "author-root", "root");
        createHumanPostNode(midId, "req-mid", "author-mid", "mid");
        createHumanPostNode(leafId, "req-leaf", "author-leaf", "leaf");
        createBranchedFrom(leafId, midId);
        createBranchedFrom(midId, rootId);

        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/api/nodes/" + midId + "/topology-context?lineage_depth=1&children_depth=1",
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = responseData(response);

        assertThat(nodeIds(nodeList(topologyAt(data, "lineage"))))
                .as("lineage_depth=1 reaches only the immediate ancestor")
                .containsExactlyInAnyOrder(midId.toString(), rootId.toString());
        assertThat(nodeIds(nodeList(topologyAt(data, "children"))))
                .as("children_depth=1 reaches only the immediate descendant")
                .containsExactlyInAnyOrder(midId.toString(), leafId.toString());
    }

    // ── seed helpers (mirror LineageGraphTopologyIntegrationTest) ───────────────

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
                    reason: 'topology-context-test'
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
    private static Map<String, Object> topologyAt(Map<String, Object> data, String key) {
        return (Map<String, Object>) data.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nodeList(Map<String, Object> topology) {
        return (List<Map<String, Object>>) topology.get("nodes");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> edgeList(Map<String, Object> topology) {
        return (List<Map<String, Object>>) topology.get("edges");
    }

    private static List<String> nodeIds(List<Map<String, Object>> nodes) {
        return nodes.stream()
                .map(n -> (String) n.get("node_id"))
                .toList();
    }

    private static List<String> edgeKeys(List<Map<String, Object>> edges) {
        return edges.stream()
                .map(e -> edgeKey(
                        (String) e.get("source"),
                        (String) e.get("target"),
                        (String) e.get("type")
                ))
                .toList();
    }

    private static String edgeKey(UUID source, UUID target, String type) {
        return source.toString() + "->" + target.toString() + ":" + type;
    }

    private static String edgeKey(String source, String target, String type) {
        return source + "->" + target + ":" + type;
    }
}
