package com.rhizodelta.consensus.domain.decision;

import com.rhizodelta.consensus.domain.decision.DecisionOperatorType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.core.validation.DecisionCommandValidation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 表示一次汇合决策命令。
 *
 * <p>该命令用于把多个源节点汇聚成一个新的共识节点，适合多条平行线索最终收敛为统一摘要的场景。
 *
 * <p><b>关键约束</b>：
 * <ul>
 *   <li>{@code source_node_ids} 至少包含两个来源节点。</li>
 *   <li>{@code summary_content} 与 {@code agent_version} 决定最终共识节点的内容和生成版本标识。</li>
 * </ul>
 */
public record JoinDecisionCommand(
        @JsonProperty("decision_id") String decision_id,
        @JsonProperty("request_id") String request_id,
        @JsonProperty("source_node_ids") List<UUID> source_node_ids,
        @JsonProperty("summary_content") String summary_content,
        @JsonProperty("agent_version") String agent_version,
        @JsonProperty("operator_type") DecisionOperatorType operator_type,
        @JsonProperty("operator_id") String operator_id,
        @JsonProperty("reason") String reason
) {
    /**
     * 创建汇合命令并校验来源节点集合。
     *
     * <p>这里强制要求至少两个来源节点，是为了避免把单源共识错误地建模成汇合操作。
     */
    public JoinDecisionCommand {
        decision_id = DecisionCommandValidation.requireText(decision_id, "decision_id");
        request_id = DecisionCommandValidation.requireText(request_id, "request_id");
        if (source_node_ids == null || source_node_ids.size() < 2) {
            throw new IllegalArgumentException("source_node_ids must contain at least 2 entries");
        }
        if (source_node_ids.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("source_node_ids must not contain null");
        }
        source_node_ids = List.copyOf(source_node_ids);
        summary_content = DecisionCommandValidation.requireText(summary_content, "summary_content");
        agent_version = DecisionCommandValidation.requireText(agent_version, "agent_version");
        operator_type = DecisionCommandValidation.requireOperatorType(operator_type);
        operator_id = DecisionCommandValidation.requireText(operator_id, "operator_id");
        reason = DecisionCommandValidation.requireText(reason, "reason");
    }
}
