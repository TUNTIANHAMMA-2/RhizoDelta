package com.rhizodelta.consensus.domain.decision;

import com.rhizodelta.consensus.domain.decision.DecisionOperatorType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.core.validation.DecisionCommandValidation;

import java.util.List;
import java.util.UUID;

/**
 * 表示一次批量分叉决策命令。
 *
 * <p>该命令用于从一个源节点派生出一组新的分支帖子节点，并为这些分支共享同一个
 * {@code operation_id}，便于后续一起追踪和回滚。
 *
 * <p><b>注意事项</b>：
 * <ul>
 *   <li>当前允许只创建一个分支，不再强制至少两个分支。</li>
 *   <li>一个分叉操作中的所有分支共享相同的源节点和操作者信息。</li>
 * </ul>
 */
public record ForkDecisionCommand(
        @JsonProperty("operation_id") String operation_id,
        @JsonProperty("request_id") String request_id,
        @JsonProperty("source_node_id") UUID source_node_id,
        @JsonProperty("branches") List<ForkBranchSpec> branches,
        @JsonProperty("operator_type") DecisionOperatorType operator_type,
        @JsonProperty("operator_id") String operator_id,
        @JsonProperty("reason") String reason
) {
    /**
     * 创建分叉命令并校验分支集合。
     *
     * <p>模型层只保证分支集合非空，真正的拓扑意义由服务层执行时落到图谱关系中。
     */
    public ForkDecisionCommand {
        operation_id = DecisionCommandValidation.requireText(operation_id, "operation_id");
        request_id = DecisionCommandValidation.requireText(request_id, "request_id");
        source_node_id = DecisionCommandValidation.requireUuid(source_node_id, "source_node_id");
        if (branches == null || branches.isEmpty()) {
            throw new IllegalArgumentException("branches must not be empty");
        }
        // [MVP Design Note]: Previously required branches.size() >= 2.
        // Changed to allow size >= 1 to support real-world interactions where a user
        // creates a single branch (alternative) at a time based on a specific node.
        // The topological relationship (NewNode -[:BRANCHED_FROM]-> SourceNode) is
        // intentionally preserved in the database to maintain temporal causality and provenance.
        // The UI (frontend) is responsible for rendering these causal children as
        // logical siblings on parallel tracks.
        branches = List.copyOf(branches);
        operator_type = DecisionCommandValidation.requireOperatorType(operator_type);
        operator_id = DecisionCommandValidation.requireText(operator_id, "operator_id");
        reason = DecisionCommandValidation.requireText(reason, "reason");
    }

    /**
     * 描述单个待创建的分支规格。
     *
     * <p>该对象只保留分支节点创建所需的最小字段：决策标识、正文内容和作者信息。
     */
    public record ForkBranchSpec(
            @JsonProperty("decision_id") String decision_id,
            @JsonProperty("content") String content,
            @JsonProperty("author_id") String author_id
    ) {
        /**
         * 创建单个分支规格并校验输入。
         */
        public ForkBranchSpec {
            decision_id = DecisionCommandValidation.requireText(decision_id, "decision_id");
            content = DecisionCommandValidation.requireText(content, "content");
            author_id = DecisionCommandValidation.requireText(author_id, "author_id");
        }
    }
}
