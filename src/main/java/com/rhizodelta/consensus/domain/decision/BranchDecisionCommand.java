package com.rhizodelta.consensus.domain.decision;

import com.rhizodelta.consensus.domain.decision.DecisionOperatorType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.core.validation.DecisionCommandValidation;

import java.util.List;
import java.util.UUID;

/**
 * 表示一次分支决策命令。
 *
 * <p>该命令用于在既有节点基础上创建一条新的帖子分支，保留原始内容线索但明确不直接并入现有共识。
 *
 * <p><b>关键约束</b>：
 * <ul>
 *   <li>{@code source_node_id} 指向要分叉出的源节点。</li>
 *   <li>{@code content} 与 {@code author_id} 用于构造新的分支帖子节点。</li>
 *   <li>{@code contributor_node_ids} 可选，用于补充分支的来源节点上下文。</li>
 * </ul>
 */
public record BranchDecisionCommand(
        @JsonProperty("decision_id") String decision_id,
        @JsonProperty("request_id") String request_id,
        @JsonProperty("source_node_id") UUID source_node_id,
        @JsonProperty("content") String content,
        @JsonProperty("author_id") String author_id,
        @JsonProperty("operator_type") DecisionOperatorType operator_type,
        @JsonProperty("operator_id") String operator_id,
        @JsonProperty("reason") String reason,
        @JsonProperty("contributor_node_ids") List<UUID> contributor_node_ids
) {
    /**
     * 创建分支命令并校验基础字段。
     *
     * <p>这里允许 {@code contributor_node_ids} 为空，以兼容只基于单个帖子创建分支的场景。
     */
    public BranchDecisionCommand {
        decision_id = DecisionCommandValidation.requireText(decision_id, "decision_id");
        request_id = DecisionCommandValidation.requireText(request_id, "request_id");
        source_node_id = DecisionCommandValidation.requireUuid(source_node_id, "source_node_id");
        content = DecisionCommandValidation.requireText(content, "content");
        author_id = DecisionCommandValidation.requireText(author_id, "author_id");
        operator_type = DecisionCommandValidation.requireOperatorType(operator_type);
        operator_id = DecisionCommandValidation.requireText(operator_id, "operator_id");
        reason = DecisionCommandValidation.requireText(reason, "reason");
        if (contributor_node_ids == null) {
            contributor_node_ids = List.of();
        }
    }

    /**
     * 创建一个不显式携带贡献者节点列表的分支命令。
     *
     * <p>该重载主要用于兼容旧调用方，让它们在未感知新增字段时仍能构造合法命令。
     */
    public BranchDecisionCommand(
            String decision_id, String request_id, UUID source_node_id,
            String content, String author_id,
            DecisionOperatorType operator_type, String operator_id, String reason
    ) {
        this(decision_id, request_id, source_node_id, content, author_id,
                operator_type, operator_id, reason, List.of());
    }
}
