package com.rhizodelta.ai.routing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rhizodelta.ai.shared.service.ModelRouterService;
import com.rhizodelta.core.validation.DecisionCommandValidation;
import com.rhizodelta.ai.shared.domain.ModelPurpose;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 负责让模型在召回上下文上做最终路由判断。
 *
 * <p>该服务是 AI 路由链路中的主评估器，会把帖子内容、召回上下文和可选批评反馈组织成提示词，
 * 再调用路由模型输出 {@code MERGE}/{@code BRANCH}/{@code REVIEW} 之一。
 *
 * <p><b>关键副作用</b>：
 * <ul>
 *   <li>会调用外部聊天模型。</li>
 *   <li>不会直接写库，但其输出将驱动后续 {@link DecisionService} 执行或人工复核。</li>
 *   <li>模型返回非法 JSON 时会直接抛出异常。</li>
 * </ul>
 */
@Service
public class AiRoutingEvaluatorService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiRoutingEvaluatorService.class);
    private static final Set<String> ALLOWED_ACTIONS = Set.of("MERGE", "BRANCH", "REVIEW");
    private static final int RESPONSE_PREVIEW_LIMIT = 160;
    private static final String SYSTEM_PROMPT = """
            You are the RhizoDelta routing judge.
            Classify each post into exactly one action:
            - MERGE: only when the new post is materially the same knowledge unit as one recalled candidate and should join that lineage directly.
            - BRANCH: only when the new post is related to a recalled candidate but must remain an independent node because it diverges, extends, or preserves a separate thread.
            - REVIEW: use whenever evidence is missing, multiple candidates compete, the boundary is ambiguous, or confidence is low.

            The recalled context may contain:
            - Candidate nodes with their similarity scores.
            - Branch ancestor chain showing the discussion lineage leading to the source node.
            - Existing consensus summaries already attached to the source node.
            - Sibling replies from other users on the same source node.

            Use the ancestor chain and consensus to understand what topic the branch is discussing.
            Use sibling replies to judge whether the new post adds unique value or duplicates existing contributions.

            You must return JSON only with this schema:
            {"action":"MERGE|BRANCH|REVIEW","confidence":0.0,"reason":"short explanation"}
            Do not wrap the JSON in markdown.
            """;

    private final ModelRouterService modelRouterService;
    private final ObjectMapper objectMapper;
    private final double reviewThreshold;

    public AiRoutingEvaluatorService(
            ModelRouterService modelRouterService,
            ObjectMapper objectMapper,
            @Value("${rhizodelta.ai.confidence.review-threshold}") double reviewThreshold
    ) {
        this.modelRouterService = modelRouterService;
        this.objectMapper = objectMapper;
        this.reviewThreshold = reviewThreshold;
    }

    /**
     * 基于帖子内容和上下文评估路由动作。
     *
     * <p>评估结果会再经过阈值策略处理：低置信度的非 {@code REVIEW} 结论会被下调为人工复核。
     *
     * <p>
     *
     * @param command 路由评估命令。
     * @return 结构化评估结果。
     */
    public RoutingEvaluation evaluate(RoutingEvaluationCommand command) {
        String modelName = resolveModelName();
        String postContent = DecisionCommandValidation.requireText(command.postContent(), "post_content");
        List<dev.langchain4j.data.message.ChatMessage> messages =
                buildMessages(postContent, command.routingContext(), command.targetNodeId(), command.criticFeedback());
        LOGGER.info(
                "AI routing evaluator invoking chat model={} target_node_id={} has_routing_context={} message_count={}",
                modelName,
                blankToEmpty(command.targetNodeId()),
                hasText(command.routingContext()),
                messages.size()
        );
        String responseText = invokeModel(messages, modelName);
        ModelDecision modelDecision = parseDecision(responseText);
        LOGGER.info(
                "AI routing evaluator parsed decision model={} action={} confidence={}",
                modelName,
                modelDecision.action(),
                modelDecision.confidence()
        );
        return applyThreshold(modelDecision);
    }

    /**
     * 构造路由评估提示词消息。
     *
     * <p>若存在来自反思阶段的批评反馈，会把该反馈附加到用户提示词中，引导模型重新判断。
     */
    private List<dev.langchain4j.data.message.ChatMessage> buildMessages(
            String postContent,
            String routingContext,
            String targetNodeId,
            String criticFeedback
    ) {
        String userPrompt = """
                target_node_id: %s
                post_content:
                %s

                recalled_context:
                %s
                """.formatted(blankToEmpty(targetNodeId), postContent, blankToEmpty(routingContext));
        if (criticFeedback != null && !criticFeedback.isBlank()) {
            userPrompt += "\nPrevious critic feedback: " + criticFeedback
                    + "\nPlease reconsider your decision taking this feedback into account.\n";
        }
        return List.of(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(userPrompt));
    }

    /**
     * 调用模型并返回原始文本响应。
     */
    private String invokeModel(List<dev.langchain4j.data.message.ChatMessage> messages, String modelName) {
        ChatLanguageModel model = modelRouterService.getModel(ModelPurpose.ROUTING);
        Response<AiMessage> response = model.generate(messages);
        if (response == null || response.content() == null || response.content().text() == null) {
            throw new IllegalStateException("routing evaluator response content is null");
        }
        String responseText = response.content().text();
        LOGGER.info(
                "AI routing evaluator received response model={} preview={}",
                modelName,
                previewText(responseText)
        );
        return responseText;
    }

    /**
     * 解析模型返回的结构化决策。
     *
     * <p>这里会统一校验动作是否合法、理由是否为空，以及置信度是否落在允许区间内。
     */
    private ModelDecision parseDecision(String responseText) {
        try {
            ModelDecision decision = objectMapper.readValue(responseText, ModelDecision.class);
            String action = normalizeAction(decision.action());
            String reason = DecisionCommandValidation.requireText(decision.reason(), "reason");
            double confidence = requireConfidence(decision.confidence());
            return new ModelDecision(action, confidence, reason);
        } catch (IOException exception) {
            LOGGER.error(
                    "AI routing evaluator failed to parse response model={} response={}",
                    resolveModelName(),
                    previewText(responseText),
                    exception
            );
            throw new IllegalStateException("failed to parse routing evaluator response", exception);
        }
    }

    /**
     * 按系统阈值规则降级低置信度决策。
     *
     * <p>这一步的意义，是把“模型给出一个勉强的合并/分支结论”转成更安全的人工复核。
     */
    private RoutingEvaluation applyThreshold(ModelDecision decision) {
        if (decision.confidence() >= reviewThreshold || "REVIEW".equals(decision.action())) {
            return new RoutingEvaluation(decision.action(), decision.reason(), decision.confidence());
        }
        LOGGER.info(
                "AI routing evaluator downgraded decision action={} confidence={} threshold={}",
                decision.action(),
                decision.confidence(),
                reviewThreshold
        );
        return new RoutingEvaluation(
                "REVIEW",
                "confidence %.2f below threshold %.2f: %s".formatted(
                        decision.confidence(),
                        reviewThreshold,
                        decision.reason()
                ),
                decision.confidence()
        );
    }

    private String normalizeAction(String action) {
        String normalized = DecisionCommandValidation.requireText(action, "action").toUpperCase(Locale.ROOT);
        if (!ALLOWED_ACTIONS.contains(normalized)) {
            throw new IllegalStateException("unsupported routing action: " + normalized);
        }
        return normalized;
    }

    private double requireConfidence(Double confidence) {
        if (confidence == null || confidence < 0.0d || confidence > 1.0d) {
            throw new IllegalStateException("confidence must be between 0.0 and 1.0");
        }
        return confidence;
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String resolveModelName() {
        return modelRouterService.resolveModelName(ModelPurpose.ROUTING);
    }

    private String previewText(String value) {
        String normalized = blankToEmpty(value).replace('\n', ' ').trim();
        if (normalized.length() <= RESPONSE_PREVIEW_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, RESPONSE_PREVIEW_LIMIT) + "...";
    }

    /**
     * 表示一次路由评估请求。
     */
    public record RoutingEvaluationCommand(
            String postContent,
            String routingContext,
            String targetNodeId,
            String criticFeedback
    ) {
        public RoutingEvaluationCommand(String postContent, String routingContext, String targetNodeId) {
            this(postContent, routingContext, targetNodeId, "");
        }
    }

    /**
     * 表示路由模型的最终评估结果。
     */
    public record RoutingEvaluation(
            String action,
            String reason,
            double confidence
    ) {
    }

    private record ModelDecision(
            String action,
            Double confidence,
            String reason
    ) {
    }
}
