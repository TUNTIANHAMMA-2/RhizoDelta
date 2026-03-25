package com.rhizodelta.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rhizodelta.domain.DecisionCommandValidation;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
            You must return JSON only with this schema:
            {"action":"MERGE|BRANCH|REVIEW","confidence":0.0,"reason":"short explanation"}
            Do not wrap the JSON in markdown.
            """;

    private final ChatLanguageModel chatLanguageModel;
    private final ObjectMapper objectMapper;
    private final double reviewThreshold;

    public AiRoutingEvaluatorService(
            ChatLanguageModel chatLanguageModel,
            ObjectMapper objectMapper,
            @Value("${rhizodelta.ai.confidence.review-threshold}") double reviewThreshold
    ) {
        this.chatLanguageModel = chatLanguageModel;
        this.objectMapper = objectMapper;
        this.reviewThreshold = reviewThreshold;
    }

    public RoutingEvaluation evaluate(RoutingEvaluationCommand command) {
        String modelName = resolveModelName();
        String postContent = DecisionCommandValidation.requireText(command.postContent(), "post_content");
        List<dev.langchain4j.data.message.ChatMessage> messages =
                buildMessages(postContent, command.routingContext(), command.targetNodeId());
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

    private List<dev.langchain4j.data.message.ChatMessage> buildMessages(
            String postContent,
            String routingContext,
            String targetNodeId
    ) {
        String userPrompt = """
                target_node_id: %s
                post_content:
                %s

                recalled_context:
                %s
                """.formatted(blankToEmpty(targetNodeId), postContent, blankToEmpty(routingContext));
        return List.of(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(userPrompt));
    }

    private String invokeModel(List<dev.langchain4j.data.message.ChatMessage> messages, String modelName) {
        Response<AiMessage> response = chatLanguageModel.generate(messages);
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
        if (chatLanguageModel instanceof OpenAiChatModel openAiChatModel) {
            return openAiChatModel.modelName();
        }
        return chatLanguageModel.getClass().getSimpleName();
    }

    private String previewText(String value) {
        String normalized = blankToEmpty(value).replace('\n', ' ').trim();
        if (normalized.length() <= RESPONSE_PREVIEW_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, RESPONSE_PREVIEW_LIMIT) + "...";
    }

    public record RoutingEvaluationCommand(
            String postContent,
            String routingContext,
            String targetNodeId
    ) {
    }

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
