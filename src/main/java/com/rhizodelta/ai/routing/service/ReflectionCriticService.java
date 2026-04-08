package com.rhizodelta.ai.routing.service;

import com.rhizodelta.ai.routing.domain.ReflectionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rhizodelta.ai.shared.domain.ModelPurpose;
import com.rhizodelta.ai.shared.service.ModelRouterService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * 负责对路由决策执行一次模型化反思校验。
 *
 * <p>该服务不是直接产出最终决策的主评估器，而是作为二次审查者，
 * 判断当前动作和置信度是否合理，并返回确认或修正建议。
 */
@Service
public class ReflectionCriticService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReflectionCriticService.class);
    private static final int RESPONSE_PREVIEW_LIMIT = 160;
    private static final String SYSTEM_PROMPT = """
            You are the RhizoDelta self-critique judge.
            Given a routing decision (action, confidence, reason), the original post content, and the routing context (recalled candidates with scores), \
            critically evaluate whether the decision is well-justified.

            Consider:
            - Is the chosen action (MERGE/BRANCH/REVIEW) consistent with the evidence?
            - Is the confidence level appropriate given the candidate scores and content similarity?
            - Are there overlooked candidates or alternative interpretations?

            You must return JSON only with this schema:
            {"confirmed":true|false,"revisedAction":"MERGE|BRANCH|REVIEW","revisedConfidence":0.0,"criticReason":"explanation"}

            If you agree with the decision, set confirmed=true and keep the same action/confidence.
            If you disagree, set confirmed=false, provide your revised action and confidence, and explain why in criticReason.
            Do not wrap the JSON in markdown.
            """;

    private final ModelRouterService modelRouterService;
    private final ObjectMapper objectMapper;

    public ReflectionCriticService(ModelRouterService modelRouterService, ObjectMapper objectMapper) {
        this.modelRouterService = modelRouterService;
        this.objectMapper = objectMapper;
    }

    /**
     * 对当前路由结论进行反思审查。
     *
     * <p>该方法会再次调用路由模型，并要求模型以结构化 JSON 返回“确认/修正”结论。
     *
     * <p>
     *
     * @param action 当前动作。
     * @param confidence 当前置信度。
     * @param reason 当前理由。
     * @param postContent 原始帖子内容。
     * @param routingContext 当前召回上下文。
     * @return 反思结果。
     */
    public ReflectionResult critique(
            String action,
            double confidence,
            String reason,
            String postContent,
            String routingContext
    ) {
        String modelName = modelRouterService.resolveModelName(ModelPurpose.ROUTING);
        String userPrompt = """
                Current decision:
                action: %s
                confidence: %.2f
                reason: %s

                post_content:
                %s

                routing_context:
                %s
                """.formatted(action, confidence, reason, postContent, blankToEmpty(routingContext));

        List<dev.langchain4j.data.message.ChatMessage> messages = List.of(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(userPrompt)
        );

        LOGGER.info("Reflection critic invoking model={} action={} confidence={}", modelName, action, confidence);

        ChatLanguageModel model = modelRouterService.getModel(ModelPurpose.ROUTING);
        Response<AiMessage> response = model.generate(messages);
        if (response == null || response.content() == null || response.content().text() == null) {
            throw new IllegalStateException("reflection critic response content is null");
        }

        String responseText = response.content().text();
        LOGGER.info("Reflection critic received response model={} preview={}", modelName, previewText(responseText));

        return parseResult(responseText);
    }

    /**
     * 将模型返回解析为 {@link ReflectionResult}。
     *
     * <p>解析失败会直接抛出异常，避免工作流基于非结构化文本继续推进。
     */
    private ReflectionResult parseResult(String responseText) {
        try {
            return objectMapper.readValue(responseText, ReflectionResult.class);
        } catch (IOException exception) {
            LOGGER.error("Reflection critic failed to parse response response={}", previewText(responseText), exception);
            throw new IllegalStateException("failed to parse reflection critic response", exception);
        }
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String previewText(String value) {
        String normalized = blankToEmpty(value).replace('\n', ' ').trim();
        if (normalized.length() <= RESPONSE_PREVIEW_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, RESPONSE_PREVIEW_LIMIT) + "...";
    }
}
