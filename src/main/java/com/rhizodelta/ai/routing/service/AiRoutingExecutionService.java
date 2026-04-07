package com.rhizodelta.ai.routing.service;

import com.rhizodelta.consensus.domain.decision.DecisionOperatorType;
import com.rhizodelta.consensus.domain.decision.DecisionResult;
import com.rhizodelta.consensus.domain.decision.MergeDecisionCommand;
import com.rhizodelta.core.domain.node.HumanPost;
import com.rhizodelta.consensus.service.DecisionService;
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
            case "MERGE" -> {
                DecisionService.MergeOrAppendResult result =
                        decisionService.mergeOrAppend(toMergeCommand(command));
                yield new RoutingExecutionResult("MERGE", result.decisionResult());
            }
            case "BRANCH" -> new RoutingExecutionResult("BRANCH", linkBranch(command));
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

    private DecisionResult linkBranch(RoutingExecutionCommand command) {
        return decisionService.linkBranch(
                buildDecisionId(command.eventId(), "branch"),
                command.post().getNodeId(),
                UUID.fromString(command.sourceNodeId()),
                DecisionOperatorType.AGENT,
                AI_OPERATOR_ID,
                command.reason(),
                List.of(command.post().getNodeId())
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
