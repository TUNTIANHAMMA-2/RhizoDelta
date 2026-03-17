package com.rhizodelta.service;

import com.rhizodelta.exception.DagIntegrityViolationException;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

@Service
public class DagIntegrityService {
    private static final int MAX_ALLOWED_DEPTH = 50;

    private static final String CYCLE_CHECK_QUERY = """
            MATCH (target:GraphNode {node_id: $targetNodeId})
            OPTIONAL MATCH path = shortestPath(
                (target)-[:BRANCHED_FROM|MERGED_INTO|CONTINUES_FROM|CONVERGED_FROM*1..%d]->(source:GraphNode {node_id: $sourceNodeId})
            )
            RETURN path IS NOT NULL AS hasCycle
            """.formatted(MAX_ALLOWED_DEPTH);

    private static final String RESULT_CYCLE_CHECK_QUERY = """
            MATCH (target:Result:GraphNode {node_id: $targetNodeId})
            OPTIONAL MATCH path = shortestPath(
                (target)-[:CROSS_SYNTHESIZED_FROM*1..%d]->(source:Result:GraphNode {node_id: $sourceNodeId})
            )
            RETURN path IS NOT NULL AS hasCycle
            """.formatted(MAX_ALLOWED_DEPTH);

    private final Neo4jClient neo4jClient;

    public DagIntegrityService(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    public void assertNoVersionEvolutionCycle(UUID sourceNodeId, UUID targetNodeId) {
        UUID source = requireNodeId(sourceNodeId, "sourceNodeId");
        UUID target = requireNodeId(targetNodeId, "targetNodeId");

        if (source.equals(target)) {
            throw new DagIntegrityViolationException("source_node_id and target_node_id must not be the same");
        }

        if (hasPathFromTargetToSource(CYCLE_CHECK_QUERY, source, target)) {
            throw new DagIntegrityViolationException("cycle detected for version evolution relationship");
        }
    }

    public void assertNoResultLayerCycle(UUID newResultNodeId, UUID sourceResultNodeId) {
        UUID source = requireNodeId(newResultNodeId, "newResultNodeId");
        UUID target = requireNodeId(sourceResultNodeId, "sourceResultNodeId");

        if (source.equals(target)) {
            throw new DagIntegrityViolationException("result node cannot reference itself via CROSS_SYNTHESIZED_FROM");
        }

        if (hasPathFromTargetToSource(RESULT_CYCLE_CHECK_QUERY, source, target)) {
            throw new DagIntegrityViolationException("cycle detected in Result layer via CROSS_SYNTHESIZED_FROM");
        }
    }

    private boolean hasPathFromTargetToSource(String query, UUID sourceNodeId, UUID targetNodeId) {
        return neo4jClient.query(query)
                .bind(targetNodeId.toString())
                .to("targetNodeId")
                .bind(sourceNodeId.toString())
                .to("sourceNodeId")
                .fetchAs(Boolean.class)
                .one()
                .orElse(Boolean.FALSE);
    }

    private static UUID requireNodeId(UUID value, String fieldName) {
        return Objects.requireNonNull(value, fieldName + " must not be null");
    }

    public static int maxAllowedDepth() {
        return MAX_ALLOWED_DEPTH;
    }
}
