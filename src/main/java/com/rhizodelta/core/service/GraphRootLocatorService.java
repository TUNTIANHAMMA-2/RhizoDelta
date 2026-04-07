package com.rhizodelta.core.service;

import com.rhizodelta.core.validation.DecisionCommandValidation;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

@Service
public class GraphRootLocatorService {
    private static final String ROOT_ID_QUERY = """
            MATCH (node:GraphNode {node_id: $nodeId})
            WHERE NOT coalesce(node._deleted, false)
            RETURN coalesce(node.root_id, node.node_id) AS rootId
            """;

    private final Neo4jClient neo4jClient;

    public GraphRootLocatorService(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    public String resolveRootId(String nodeId) {
        String validatedNodeId = DecisionCommandValidation.requireText(nodeId, "node_id");
        return neo4jClient.query(ROOT_ID_QUERY)
                .bind(validatedNodeId).to("nodeId")
                .fetchAs(String.class)
                .one()
                .orElseThrow(() -> new NoSuchElementException("root_id not found for node_id: " + validatedNodeId));
    }
}
