package com.rhizodelta.core.domain.association;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.core.validation.DecisionCommandValidation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 表示关联创建或写操作后的结果。
 *
 * <p>与 {@link AssociationInfo} 不同，该模型更偏向写后确认语义，
 * 用来回传一条已经被接受的关系边的标识、两端节点以及审计属性。
 *
 * <p><b>适用场景</b>：
 * <ul>
 *   <li>控制器在创建关联后直接返回给调用方。</li>
 *   <li>上层需要获知最终生效的 {@code association_id} 和落库时间。</li>
 * </ul>
 */
public record AssociationResult(
        @JsonProperty("association_id") UUID association_id,
        @JsonProperty("source_node_id") UUID source_node_id,
        @JsonProperty("target_node_id") UUID target_node_id,
        @JsonProperty("type") AssociationType type,
        @JsonProperty("confidence") Float confidence,
        @JsonProperty("reason") String reason,
        @JsonProperty("creator_id") String creator_id,
        @JsonProperty("created_at") Instant created_at
) {
    /**
     * 创建写操作结果并校验关键字段。
     *
     * <p>这里要求两端节点和结果时间必须完整，以确保返回值可以被安全地用于审计和后续展示。
     */
    public AssociationResult {
        association_id = DecisionCommandValidation.requireUuid(association_id, "association_id");
        source_node_id = DecisionCommandValidation.requireUuid(source_node_id, "source_node_id");
        target_node_id = DecisionCommandValidation.requireUuid(target_node_id, "target_node_id");
        type = Objects.requireNonNull(type, "type must not be null");
        created_at = Objects.requireNonNull(created_at, "created_at must not be null");
    }
}
