package com.rhizodelta.service;

import com.rhizodelta.domain.DecisionCommandValidation;
import com.rhizodelta.domain.association.AssociationInfo;
import com.rhizodelta.domain.association.AssociationResult;
import com.rhizodelta.domain.association.AssociationType;
import com.rhizodelta.domain.association.CreateAssociationCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

@Service
public class AssociationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AssociationService.class);

    private static final String NODE_EXISTS_QUERY = """
            MATCH (node:GraphNode {node_id: $nodeId})
            WHERE NOT coalesce(node._deleted, false)
            RETURN count(node) > 0 AS exists
            """;

    private static final String CREATE_ASSOCIATION_QUERY_TEMPLATE = """
            MATCH (source:GraphNode {node_id: $sourceNodeId})
            MATCH (target:GraphNode {node_id: $targetNodeId})
            MERGE (source)-[association:%s]->(target)
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
                   association.created_at AS createdAt,
                   association.confidence AS confidence,
                   association.reason AS reason,
                   association.creator_id AS creatorId
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

    private static final String FIND_ASSOCIATIONS_QUERY = """
            MATCH (node:GraphNode {node_id: $nodeId})
            WHERE NOT coalesce(node._deleted, false)
            MATCH (node)-[association:CONCEPTUAL_OVERLAP|RELATES_TO]-(related:GraphNode)
            WHERE NOT coalesce(related._deleted, false)
              AND ($associationType IS NULL OR type(association) = $associationType)
            RETURN association.association_id AS associationId,
                   type(association) AS associationType,
                   CASE WHEN startNode(association) = node THEN 'OUTGOING' ELSE 'INCOMING' END AS direction,
                   related.node_id AS relatedNodeId,
                   CASE WHEN 'Human_Post' IN labels(related) THEN 'Human_Post' ELSE 'AI_Consensus' END AS relatedLabel,
                   related.content AS relatedContent,
                   related.summary_content AS relatedSummaryContent,
                   association.confidence AS confidence,
                   association.reason AS reason,
                   association.creator_id AS creatorId,
                   association.created_at AS createdAt
            ORDER BY createdAt DESC
            LIMIT $limit
            """;

    static final int DEFAULT_ASSOCIATION_LIMIT = 100;

    private final Neo4jClient neo4jClient;

    public AssociationService(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    @Transactional(transactionManager = "transactionManager")
    public CreateAssociationOutcome createAssociation(CreateAssociationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        DecisionCommandValidation.validateConfidence(command.confidence());
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

    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public List<AssociationInfo> findAssociationsByNodeId(UUID nodeId, AssociationType type, Integer limit) {
        UUID validatedNodeId = DecisionCommandValidation.requireUuid(nodeId, "node_id");
        if (!nodeExists(validatedNodeId)) {
            throw new NoSuchElementException("node_id not found: " + validatedNodeId);
        }
        String associationType = type == null ? null : type.name();
        int effectiveLimit = (limit != null && limit > 0) ? limit : DEFAULT_ASSOCIATION_LIMIT;
        Collection<Map<String, Object>> records = neo4jClient.query(FIND_ASSOCIATIONS_QUERY)
                .bind(validatedNodeId.toString()).to("nodeId")
                .bind(associationType).to("associationType")
                .bind(effectiveLimit).to("limit")
                .fetch()
                .all();
        return records.stream().map(AssociationService::toAssociationInfo).toList();
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
        return String.format(CREATE_ASSOCIATION_QUERY_TEMPLATE, type.name());
    }

    private static CreateAssociationOutcome toCreateOutcome(Map<String, Object> result, AssociationType type) {
        AssociationResult associationResult = new AssociationResult(
                UUID.fromString((String) result.get("associationId")),
                UUID.fromString((String) result.get("sourceNodeId")),
                UUID.fromString((String) result.get("targetNodeId")),
                type,
                toFloat(result.get("confidence")),
                (String) result.get("reason"),
                (String) result.get("creatorId"),
                toInstant(result.get("createdAt"))
        );
        boolean created = Boolean.TRUE.equals(result.get("created"));
        return new CreateAssociationOutcome(associationResult, created);
    }

    private static AssociationInfo toAssociationInfo(Map<String, Object> record) {
        AssociationInfo.RelatedNode relatedNode = new AssociationInfo.RelatedNode(
                UUID.fromString((String) record.get("relatedNodeId")),
                (String) record.get("relatedLabel"),
                (String) record.get("relatedContent"),
                (String) record.get("relatedSummaryContent")
        );
        return new AssociationInfo(
                UUID.fromString((String) record.get("associationId")),
                AssociationType.valueOf((String) record.get("associationType")),
                AssociationInfo.Direction.valueOf((String) record.get("direction")),
                relatedNode,
                toFloat(record.get("confidence")),
                (String) record.get("reason"),
                (String) record.get("creatorId"),
                toInstant(record.get("createdAt"))
        );
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime odt) return odt.toInstant();
        return null;
    }

    private static Float toFloat(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.floatValue();
        }
        throw new IllegalArgumentException("Unsupported confidence type: " + value.getClass().getName());
    }

    public record CreateAssociationOutcome(AssociationResult association, boolean created) {
    }

    public record DeleteAssociationOutcome(UUID association_id, boolean deleted) {
    }
}
