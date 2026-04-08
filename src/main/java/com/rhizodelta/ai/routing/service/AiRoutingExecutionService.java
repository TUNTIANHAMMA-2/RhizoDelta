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

/**
 * 负责把 AI 路由动作落为真实决策执行。
 *
 * <p>该服务是 AI 路由与 {@link DecisionService} 之间的桥接层：
 * 它把路由动作转换成明确的共识命令，并补齐 AI 操作者身份。
 */
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

    /**
     * 执行路由动作。
     *
     * <p>当前只支持把路由结果落为 {@code MERGE} 或 {@code BRANCH}，
     * 其他动作会在更上层被转为人工复核，而不会走到这里。
     *
     * <p>
     *
     * @param command 路由执行命令。
     * @return 执行结果。
     */
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

    /**
     * 表示一次待执行的路由动作。
     */
    public record RoutingExecutionCommand(
            String requestId,
            String eventId,
            String sourceNodeId,
            String action,
            String reason,
            HumanPost post
    ) {
    }

    /**
     * 表示路由动作执行结果。
     */
    public record RoutingExecutionResult(
            String action,
            DecisionResult decisionResult
    ) {
    }
}
