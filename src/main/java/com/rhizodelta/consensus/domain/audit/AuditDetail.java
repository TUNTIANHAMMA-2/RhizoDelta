package com.rhizodelta.consensus.domain.audit;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.core.validation.DecisionCommandValidation;
import com.rhizodelta.consensus.domain.decision.DecisionOperatorType;
import com.rhizodelta.consensus.domain.decision.DecisionType;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 表示单条决策的审计详情。
 *
 * <p>与 {@link AuditRecord} 相比，该模型额外包含 {@code synthesized_from} 等更完整的来源信息，
 * 用于详情页或深入追踪场景。
 *
 * <p><b>注意</b>：该对象描述的是“已经发生过的事实”，不是可再次提交的命令。
 */
public record AuditDetail(
        @JsonProperty("decision_id") String decision_id,
        @JsonProperty("decision_type") DecisionType decision_type,
        @JsonProperty("node_id") UUID node_id,
        @JsonProperty("source_node_id") UUID source_node_id,
        @JsonProperty("operator_type") DecisionOperatorType operator_type,
        @JsonProperty("operator_id") String operator_id,
        @JsonProperty("reason") String reason,
        @JsonProperty("created_at") Instant created_at,
        @JsonProperty("synthesized_from") List<UUID> synthesized_from
) {
    /**
     * 创建审计详情并校验字段完整性。
     *
     * <p>这里强制要求 {@code synthesized_from} 非空列表对象而非 {@code null}，
     * 以避免调用方在渲染详情时再做额外的空值分支处理。
     */
    public AuditDetail {
        decision_id = DecisionCommandValidation.requireText(decision_id, "decision_id");
        decision_type = Objects.requireNonNull(decision_type, "decision_type must not be null");
        node_id = DecisionCommandValidation.requireUuid(node_id, "node_id");
        source_node_id = DecisionCommandValidation.requireUuid(source_node_id, "source_node_id");
        operator_type = DecisionCommandValidation.requireOperatorType(operator_type);
        operator_id = DecisionCommandValidation.requireText(operator_id, "operator_id");
        reason = DecisionCommandValidation.requireText(reason, "reason");
        created_at = Objects.requireNonNull(created_at, "created_at must not be null");
        synthesized_from = validateSynthesizedFrom(synthesized_from);
    }

    private static List<UUID> validateSynthesizedFrom(List<UUID> synthesizedFrom) {
        if (synthesizedFrom == null) {
            throw new IllegalArgumentException("synthesized_from must not be null");
        }
        if (synthesizedFrom.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("synthesized_from must not contain null");
        }
        return List.copyOf(synthesizedFrom);
    }
}
