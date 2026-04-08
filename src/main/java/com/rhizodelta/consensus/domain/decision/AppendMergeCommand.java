package com.rhizodelta.consensus.domain.decision;

import com.rhizodelta.consensus.domain.decision.DecisionOperatorType;
import java.util.List;
import java.util.UUID;

/**
 * 表示向既有共识节点追加来源的命令。
 *
 * <p>该命令与普通 {@link MergeDecisionCommand} 的区别在于：
 * 它不会新建共识节点，而是把新的来源节点追加到一个已存在的共识节点上。
 */
public record AppendMergeCommand(
        String decisionId,
        UUID consensusNodeId,
        UUID sourceNodeId,
        List<UUID> synthesizedFrom,
        DecisionOperatorType operatorType,
        String operatorId,
        String reason
) {}
