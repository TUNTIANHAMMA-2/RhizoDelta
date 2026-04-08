package com.rhizodelta.core.domain.association;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.core.validation.DecisionCommandValidation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 表示节点关联的查询视图。
 *
 * <p>该模型不是写命令，而是面向读取场景的展示结果，用于告诉调用方：
 * 当前节点与另一个节点之间存在什么关系、方向如何、由谁创建、何时创建。
 *
 * <p><b>设计意图</b>：
 * <ul>
 *   <li>用 {@link Direction} 显式区分“从当前节点出发”还是“指向当前节点”。</li>
 *   <li>用 {@link RelatedNode} 承载关联另一端的最小可展示信息，避免上层再做额外查询拼装。</li>
 * </ul>
 */
public record AssociationInfo(
        @JsonProperty("association_id") UUID association_id,
        @JsonProperty("type") AssociationType type,
        @JsonProperty("direction") Direction direction,
        @JsonProperty("related_node") RelatedNode related_node,
        @JsonProperty("confidence") Float confidence,
        @JsonProperty("reason") String reason,
        @JsonProperty("creator_id") String creator_id,
        @JsonProperty("created_at") Instant created_at
) {
    /**
     * 创建关联查询视图并执行基础完整性校验。
     *
     * <p>校验放在模型层，是为了保证无论数据来自数据库映射还是测试构造，都不会生成缺字段的半成品对象。
     */
    public AssociationInfo {
        association_id = DecisionCommandValidation.requireUuid(association_id, "association_id");
        type = Objects.requireNonNull(type, "type must not be null");
        direction = Objects.requireNonNull(direction, "direction must not be null");
        related_node = Objects.requireNonNull(related_node, "related_node must not be null");
        reason = DecisionCommandValidation.requireText(reason, "reason");
        creator_id = DecisionCommandValidation.requireText(creator_id, "creator_id");
        created_at = Objects.requireNonNull(created_at, "created_at must not be null");
        DecisionCommandValidation.validateConfidence(confidence);
    }

    /**
     * 表示关联相对当前节点的方向。
     *
     * <p><b>注意</b>：方向是查询视角概念，不等同于关系类型本身的业务含义。
     */
    public enum Direction {
        OUTGOING,
        INCOMING
    }

    /**
     * 表示关联另一端节点的最小展示信息。
     *
     * <p>该对象刻意只保留上层展示和判断所需的字段，避免把完整节点模型耦合进关联查询结果。
     */
    public record RelatedNode(
            @JsonProperty("node_id") UUID node_id,
            @JsonProperty("label") String label,
            @JsonProperty("content") String content,
            @JsonProperty("summary_content") String summary_content
    ) {
        /**
         * 创建关联另一端节点的展示视图。
         *
         * <p>这里要求 {@code node_id} 与 {@code label} 必填，以保证调用方至少能识别节点身份和类型。
         */
        public RelatedNode {
            node_id = DecisionCommandValidation.requireUuid(node_id, "node_id");
            label = DecisionCommandValidation.requireText(label, "label");
        }
    }
}
