package com.rhizodelta.service;

import com.rhizodelta.domain.decision.BranchDecisionCommand;
import com.rhizodelta.domain.decision.DecisionOperatorType;
import com.rhizodelta.domain.decision.DecisionResult;
import com.rhizodelta.domain.decision.MergeDecisionCommand;
import com.rhizodelta.domain.node.HumanPost;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AiRoutingExecutionService {
    private static final String AI_OPERATOR_ID = "ai-routing-orchestrator";

    private final DecisionService decisionService;
    private final String agentVersion;

    public AiRoutingExecutionService(
            DecisionService decisionService,
            @Value("${langchain4j.open-ai.chat-model.model-name}") String agentVersion
    ) {
        this.decisionService = decisionService;
        this.agentVersion = agentVersion;
    }

    public RoutingExecutionResult execute(RoutingExecutionCommand command) {
        return switch (command.action()) {
            case "MERGE" -> new RoutingExecutionResult("MERGE", decisionService.executeMerge(toMergeCommand(command)));
            case "BRANCH" -> new RoutingExecutionResult("BRANCH", decisionService.executeBranch(toBranchCommand(command)));
            default -> throw new IllegalArgumentException("unsupported routing action: " + command.action());
        };
    }

    private MergeDecisionCommand toMergeCommand(RoutingExecutionCommand command) {
        return new MergeDecisionCommand(
                buildDecisionId(command.eventId(), "merge"),
                command.requestId(),
                UUID.fromString(command.sourceNodeId()),
                agentVersion,
                command.post().getContent(),
                List.of(command.post().getNodeId()),
                DecisionOperatorType.AGENT,
                AI_OPERATOR_ID,
                command.reason()
        );
    }

    private BranchDecisionCommand toBranchCommand(RoutingExecutionCommand command) {
        return new BranchDecisionCommand(
                buildDecisionId(command.eventId(), "branch"),
                command.requestId(),
                UUID.fromString(command.sourceNodeId()),
                command.post().getContent(),
                command.post().getAuthorId(),
                DecisionOperatorType.AGENT,
                AI_OPERATOR_ID,
                command.reason()
        );
    }

    private String buildDecisionId(String eventId, String suffix) {
        return eventId + ":" + suffix.toLowerCase(Locale.ROOT);
    }

    public record RoutingExecutionCommand(
            String requestId,
            String eventId,
            String sourceNodeId,
            String action,
            String reason,
            HumanPost post
    ) {
    }

    public record RoutingExecutionResult(
            String action,
            DecisionResult decisionResult
    ) {
    }
}
