package com.rhizodelta.consensus.domain.decision;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.core.validation.DecisionCommandValidation;

import java.util.UUID;

/**
 * 表示单条决策回滚后的结果。
 *
 * <p>该对象用于确认回滚影响到的节点、删除了多少条关系，以及是否已完成软删除。
 */
public record RollbackResult(
        @JsonProperty("decision_id") String decision_id,
        @JsonProperty("rolled_back_node_id") UUID rolled_back_node_id,
        @JsonProperty("relationships_removed") long relationships_removed,
        @JsonProperty("soft_deleted") boolean soft_deleted
) {
    /**
     * 创建回滚结果并校验计数合法性。
     */
    public RollbackResult {
        decision_id = DecisionCommandValidation.requireText(decision_id, "decision_id");
        rolled_back_node_id = DecisionCommandValidation.requireUuid(rolled_back_node_id, "rolled_back_node_id");
        if (relationships_removed < 0) {
            throw new IllegalArgumentException("relationships_removed must be >= 0");
        }
    }
}
