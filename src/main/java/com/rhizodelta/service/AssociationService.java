package com.rhizodelta.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

@Service
public class AssociationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AssociationService.class);

    private static final String NODE_EXISTS_QUERY = """
            MATCH (node:GraphNode {node_id: $nodeId})
            RETURN count(node) > 0 AS exists
            """;

    private static final String CREATE_CONCEPTUAL_OVERLAP_QUERY = """
            MATCH (source:GraphNode {node_id: $sourceNodeId})
            MATCH (target:GraphNode {node_id: $targetNodeId})
            MERGE (source)-[association:CONCEPTUAL_OVERLAP]->(target)
            ON CREATE SET
              association.association_id = $associationId,
              association.creator_id = $creatorId,
              association.reason = $reason,
              association.created_at = $createdAt,
              association.confidence = $confidence
            RETURN association.association_id AS associationId,
                   source.node_id AS sourceNodeId,
                   target.node_id AS targetNodeId,
                   association.association_id = $associationId AS created,
                   association.created_at AS createdAt
            """;

    private static final String CREATE_RELATES_TO_QUERY = """
            MATCH (source:GraphNode {node_id: $sourceNodeId})
            MATCH (target:GraphNode {node_id: $targetNodeId})
            MERGE (source)-[association:RELATES_TO]->(target)
            ON CREATE SET
              association.association_id = $associationId,
              association.creator_id = $creatorId,
              association.reason = $reason,
              association.created_at = $createdAt,
              association.confidence = $confidence
            RETURN association.association_id AS associationId,
                   source.node_id AS sourceNodeId,
                   target.node_id AS targetNodeId,
                   association.association_id = $associationId AS created,
                   association.created_at AS createdAt
            """;

    private static final String DELETE_ASSOCIATION_QUERY = """
            MATCH (source:GraphNode)-[association]->(target:GraphNode)
            WHERE association.association_id = $associationId
              AND type(association) IN ['CONCEPTUAL_OVERLAP', 'RELATES_TO']
            WITH source, target, association, type(association) AS associationType
            DELETE association
            RETURN source.node_id AS sourceNodeId,
                   target.node_id AS targetNodeId,
                   associationType AS associationType
            """;

    private final Neo4jClient neo4jClient;

    public AssociationService(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    @Transactional(transactionManager = "transactionManager")
    public CreateAssociationOutcome createAssociation(CreateAssociationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        validateConfidence(command.confidence());
        validateAssociationNodes(command.source_node_id(), command.target_node_id());

        UUID associationId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("sourceNodeId", command.source_node_id().toString());
        params.put("targetNodeId", command.target_node_id().toString());
        params.put("associationId", associationId.toString());
        params.put("creatorId", command.creator_id());
        params.put("reason", command.reason());
        params.put("createdAt", createdAt);
        params.put("confidence", command.confidence());

        Map<String, Object> result = neo4jClient.query(resolveCreateQuery(command.type()))
                .bindAll(params)
                .fetch()
                .one()
                .orElseThrow(() -> new IllegalStateException("Failed to create association"));
        return toCreateOutcome(result, command.type());
    }

    @Transactional(transactionManager = "transactionManager")
    public DeleteAssociationOutcome deleteAssociation(UUID associationId) {
        UUID validatedAssociationId = DecisionCommandValidation.requireUuid(associationId, "association_id");
        Map<String, Object> result = neo4jClient.query(DELETE_ASSOCIATION_QUERY)
                .bind(validatedAssociationId.toString()).to("associationId")
                .fetch()
                .one()
                .orElseThrow(() -> new NoSuchElementException("association_id not found: " + validatedAssociationId));
        String sourceNodeId = (String) result.get("sourceNodeId");
        String targetNodeId = (String) result.get("targetNodeId");
        String associationType = (String) result.get("associationType");
        LOGGER.info("Deleted association association_id={}, source_node_id={}, target_node_id={}, type={}",
                validatedAssociationId, sourceNodeId, targetNodeId, associationType);
        return new DeleteAssociationOutcome(validatedAssociationId, true);
    }

    private void validateAssociationNodes(UUID sourceNodeId, UUID targetNodeId) {
        if (sourceNodeId.equals(targetNodeId)) {
            throw new IllegalArgumentException("source_node_id and target_node_id must be different");
        }
        if (!nodeExists(sourceNodeId)) {
            throw new IllegalArgumentException("source_node_id not found: " + sourceNodeId);
        }
        if (!nodeExists(targetNodeId)) {
            throw new IllegalArgumentException("target_node_id not found: " + targetNodeId);
        }
    }

    private boolean nodeExists(UUID nodeId) {
        Map<String, Object> result = neo4jClient.query(NODE_EXISTS_QUERY)
                .bind(nodeId.toString()).to("nodeId")
                .fetch()
                .one()
                .orElseThrow(() -> new IllegalStateException("Failed to validate node existence"));
        return Boolean.TRUE.equals(result.get("exists"));
    }

    private static String resolveCreateQuery(AssociationType type) {
        return switch (type) {
            case CONCEPTUAL_OVERLAP -> CREATE_CONCEPTUAL_OVERLAP_QUERY;
            case RELATES_TO -> CREATE_RELATES_TO_QUERY;
        };
    }

    private static CreateAssociationOutcome toCreateOutcome(Map<String, Object> result, AssociationType type) {
        AssociationResult associationResult = new AssociationResult(
                UUID.fromString((String) result.get("associationId")),
                UUID.fromString((String) result.get("sourceNodeId")),
                UUID.fromString((String) result.get("targetNodeId")),
                type,
                toInstant(result.get("createdAt"))
        );
        boolean created = Boolean.TRUE.equals(result.get("created"));
        return new CreateAssociationOutcome(associationResult, created);
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime odt) return odt.toInstant();
        return null;
    }

    private static void validateConfidence(Float confidence) {
        if (confidence == null) {
            return;
        }
        if (confidence < 0.0f || confidence > 1.0f) {
            throw new IllegalArgumentException("confidence must be within [0.0,1.0]");
        }
    }

    public record CreateAssociationOutcome(AssociationResult association, boolean created) {
    }

    public record DeleteAssociationOutcome(UUID association_id, boolean deleted) {
    }
}
