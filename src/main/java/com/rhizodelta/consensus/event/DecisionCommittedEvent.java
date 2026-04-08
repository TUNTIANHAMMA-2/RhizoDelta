package com.rhizodelta.consensus.event;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 定义决策成功提交后的领域事件。
 *
 * <p>这些事件只在对应事务成功提交后才会被监听器消费，
 * 用于驱动 embedding 生成、摘要更新和 SSE 通知等后置动作。
 */
public sealed interface DecisionCommittedEvent {

    /**
     * 表示一次合并决策已经提交完成。
     */
    record MergeCompleted(
            String decisionId,
            UUID nodeId,
            UUID sourceNodeId,
            List<UUID> synthesizedFrom,
            String summaryContent,
            OffsetDateTime relationshipCreatedAt
    ) implements DecisionCommittedEvent {
    }

    /**
     * 表示一次分支决策已经提交完成。
     */
    record BranchCompleted(
            String decisionId,
            UUID nodeId,
            UUID sourceNodeId,
            List<UUID> contributorNodeIds,
            OffsetDateTime relationshipCreatedAt
    ) implements DecisionCommittedEvent {
    }

    /**
     * 表示一次注入决策已经提交完成。
     */
    record InjectCompleted(
            String decisionId,
            UUID nodeId,
            UUID sourceNodeId,
            String content,
            OffsetDateTime relationshipCreatedAt
    ) implements DecisionCommittedEvent {
    }

    /**
     * 表示一次物化决策已经提交完成。
     */
    record MaterializeCompleted(
            String decisionId,
            UUID nodeId,
            UUID sourceNodeId,
            String content,
            OffsetDateTime relationshipCreatedAt
    ) implements DecisionCommittedEvent {
    }

    /**
     * 表示一次分叉操作已经提交完成。
     */
    record ForkCompleted(
            String operationId,
            List<UUID> nodeIds,
            UUID sourceNodeId,
            OffsetDateTime relationshipCreatedAt
    ) implements DecisionCommittedEvent {
    }

    /**
     * 表示一次跨结果综合决策已经提交完成。
     */
    record CrossSynthCompleted(
            String decisionId,
            UUID nodeId,
            List<UUID> sourceResultIds,
            String content,
            OffsetDateTime relationshipCreatedAt
    ) implements DecisionCommittedEvent {
    }

    /**
     * 表示一次汇合决策已经提交完成。
     */
    record JoinCompleted(
            String decisionId,
            UUID nodeId,
            List<UUID> sourceNodeIds,
            String summaryContent,
            OffsetDateTime relationshipCreatedAt
    ) implements DecisionCommittedEvent {
    }

    /**
     * 表示一次向既有共识追加来源的操作已经提交完成。
     */
    record MergeAppended(
            String decisionId,
            UUID nodeId,
            UUID sourceNodeId,
            List<UUID> synthesizedFrom,
            OffsetDateTime relationshipCreatedAt
    ) implements DecisionCommittedEvent {
    }
}
