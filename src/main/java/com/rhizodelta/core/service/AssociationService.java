package com.rhizodelta.core.service;

import com.rhizodelta.core.validation.DecisionCommandValidation;
import com.rhizodelta.core.domain.association.AssociationInfo;
import com.rhizodelta.core.domain.association.AssociationResult;
import com.rhizodelta.core.domain.association.AssociationType;
import com.rhizodelta.core.domain.association.CreateAssociationCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * 负责维护图谱节点之间的业务关联关系。
 *
 * <p>该服务为 {@code com.rhizodelta.core.service} 中的关系写模型入口，封装了
 * 关联创建、关联删除和关联查询三类能力，并统一处理节点存在性、置信度与返回结构映射。
 *
 * <p><b>关键副作用</b>：
 * <ul>
 *   <li>创建关联会写 Neo4j 关系边及其审计属性。</li>
 *   <li>删除关联会物理删除允许管理的关系类型。</li>
 *   <li>查询接口只读，但会把图关系映射为面向 API 的 {@link AssociationInfo}。</li>
 * </ul>
 *
 * <p><b>隐藏约束</b>：
 * <ul>
 *   <li>当前只允许处理 {@link AssociationType#CONCEPTUAL_OVERLAP} 与 {@link AssociationType#RELATES_TO}。</li>
 *   <li>源节点和目标节点必须不同，且都必须存在且未被软删除。</li>
 * </ul>
 */
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

    /**
     * 创建一条关联关系，或在关系已存在时返回既有结果。
     *
     * <p>该方法存在的意义，是把节点校验、置信度标准化和关系 upsert 收敛为单个事务，
     * 让上层只关注业务语义，不需要感知图数据库的关系模板。
     *
     * <p><b>关键副作用</b>：
     * <ul>
     *   <li>会写 Neo4j 关系边。</li>
     *   <li>会记录创建者、原因、创建时间与置信度。</li>
     * </ul>
     *
     * <p><b>注意事项</b>：
     * <ul>
     *   <li>若关系已存在，返回的 {@code created=false}，但仍会返回完整的关联结果。</li>
     *   <li>浮点置信度会被转换为 {@link Double} 写入数据库，避免精度歧义。</li>
     * </ul>
     *
     * <p>
     *
     * @param command 关联创建命令。
     * @return 关联结果以及是否新建的信息。
     */
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
        params.put("confidence", normalizeConfidence(command.confidence()));

        Map<String, Object> result = neo4jClient.query(resolveCreateQuery(command.type()))
                .bindAll(params)
                .fetch()
                .one()
                .orElseThrow(() -> new IllegalStateException("Failed to create association"));
        return toCreateOutcome(result, command.type());
    }

    /**
     * 删除指定的关联关系。
     *
     * <p>该方法存在，是为了把“按关联 ID 删除图关系”的细节隐藏在服务层，
     * 同时为审计日志和异常语义提供统一出口。
     *
     * <p><b>关键副作用</b>：
     * <ul>
     *   <li>会删除底层关系边。</li>
     *   <li>会输出删除日志，便于追踪人工治理动作。</li>
     * </ul>
     *
     * <p>
     *
     * @param associationId 关联 ID。
     * @return 删除结果。
     */
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

    /**
     * 查询节点当前可见的关联关系列表。
     *
     * <p>该方法存在，是为了给查询层和 API 层提供稳定的关系视图，
     * 由服务层统一处理节点校验、默认分页与图记录到 DTO 的映射。
     *
     * <p><b>关键副作用</b>：
     * <ul>
     *   <li>只读访问 Neo4j，不会修改图数据。</li>
     *   <li>当节点不存在时会抛出 {@link NoSuchElementException}，而不是返回空列表掩盖错误。</li>
     * </ul>
     *
     * <p>
     *
     * @param nodeId 要查询关联的节点 ID。
     * @param type 可选的关联类型过滤条件。
     * @param limit 可选的返回数量上限；为空或非法时会回退到默认值。
     * @return 关联信息列表。
     */
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

    /**
     * 校验关联两端节点是否满足建边前置条件。
     *
     * <p>该校验被独立抽出，是为了把“自引用禁止”和“节点存在性校验”集中在一个地方，
     * 避免不同写操作分支出现不一致的约束语义。
     */
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
        if (value instanceof ZonedDateTime zdt) return zdt.toInstant();
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

    private static Double normalizeConfidence(Float confidence) {
        if (confidence == null) {
            return null;
        }
        return new BigDecimal(confidence.toString()).doubleValue();
    }

    /**
     * 表示关联创建结果。
     *
     * <p>该对象让调用方能同时拿到关联详情和幂等创建标记。
     */
    public record CreateAssociationOutcome(AssociationResult association, boolean created) {
    }

    /**
     * 表示关联删除结果。
     *
     * <p>该对象用于显式表达删除动作是否生效，避免上层自行推断删除状态。
     */
    public record DeleteAssociationOutcome(UUID association_id, boolean deleted) {
    }
}
