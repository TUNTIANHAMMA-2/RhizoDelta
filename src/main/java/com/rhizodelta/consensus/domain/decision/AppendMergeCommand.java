package com.rhizodelta.consensus.domain.decision;

import com.rhizodelta.consensus.domain.decision.DecisionOperatorType;
import java.util.List;
import java.util.UUID;

public record AppendMergeCommand(
        String decisionId,
        UUID consensusNodeId,
        UUID sourceNodeId,
        List<UUID> synthesizedFrom,
        DecisionOperatorType operatorType,
        String operatorId,
        String reason
) {}
