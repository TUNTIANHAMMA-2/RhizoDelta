package com.rhizodelta.ai.quality.service;

import com.rhizodelta.ai.quality.domain.QualityEvaluationCommand;
import com.rhizodelta.ai.quality.domain.QualityScore;
import com.rhizodelta.ai.shared.service.ModelRouterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rhizodelta.ai.shared.domain.ModelPurpose;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

@Service
public class QualityAgentService {
    private static final Logger LOGGER = LoggerFactory.getLogger(QualityAgentService.class);

    private static final String SYSTEM_PROMPT = """
            You are a content quality evaluator for RhizoDelta.
            Evaluate the following post across 4 dimensions, scoring each 0.0 to 1.0:

            Dimensions:
            1. relevance: How relevant is this post to the discussion topic?
            2. density: Information density — does it contain substantive, non-redundant content?
            3. argumentation: Quality of reasoning, evidence citation, and logical structure.
            4. community_value: Overall contribution to the knowledge graph (bridge nodes connecting different topics score higher).

            Calculate overall as the weighted average: relevance*0.25 + density*0.25 + argumentation*0.3 + community_value*0.2

            Respond JSON only:
            {"relevance":0.0,"density":0.0,"argumentation":0.0,"community_value":0.0,"overall":0.0,"reason":"brief explanation"}
            Do not wrap the JSON in markdown.
            """;

    private static final String WRITE_QUALITY_QUERY = """
            MATCH (n:GraphNode {node_id: $nodeId})
            WHERE NOT coalesce(n._deleted, false)
            SET n.quality_relevance = $relevance,
                n.quality_density = $density,
                n.quality_argumentation = $argumentation,
                n.quality_community_value = $communityValue,
                n.quality_overall = $overall,
                n.quality_evaluated_at = datetime()
            RETURN n.node_id AS nodeId
            """;

    private final ModelRouterService modelRouterService;
    private final ObjectMapper objectMapper;
    private final Neo4jClient neo4jClient;

    public QualityAgentService(
            ModelRouterService modelRouterService,
            ObjectMapper objectMapper,
            Neo4jClient neo4jClient
    ) {
        this.modelRouterService = modelRouterService;
        this.objectMapper = objectMapper;
        this.neo4jClient = neo4jClient;
    }

    public QualityScore evaluate(QualityEvaluationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String modelName = modelRouterService.resolveModelName(ModelPurpose.QUALITY);

        LOGGER.info("Quality agent evaluating node_id={} model={}", command.nodeId(), modelName);

        String userPrompt = buildUserPrompt(command);
        ChatLanguageModel model = modelRouterService.getModel(ModelPurpose.QUALITY);
        Response<AiMessage> response = model.generate(List.of(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(userPrompt)
        ));

        if (response == null || response.content() == null || response.content().text() == null) {
            throw new IllegalStateException("quality agent response content is null");
        }

        QualityScore score = parseScore(response.content().text());
        writeQualityScore(command.nodeId().toString(), score);

        LOGGER.info("Quality agent scored node_id={} overall={}", command.nodeId(), score.overall());
        return score;
    }

    private String buildUserPrompt(QualityEvaluationCommand command) {
        StringBuilder sb = new StringBuilder();
        sb.append("Post content:\n").append(command.content()).append("\n\n");
        if (command.contextSnippet() != null && !command.contextSnippet().isBlank()) {
            sb.append("Discussion context (parent/sibling nodes):\n").append(command.contextSnippet()).append("\n\n");
        }
        if (command.positionInfo() != null && !command.positionInfo().isBlank()) {
            sb.append("Graph position info:\n").append(command.positionInfo()).append("\n");
        }
        return sb.toString();
    }

    private QualityScore parseScore(String responseText) {
        try {
            RawQualityScore raw = objectMapper.readValue(responseText, RawQualityScore.class);
            return new QualityScore(
                    clampScore(raw.relevance()),
                    clampScore(raw.density()),
                    clampScore(raw.argumentation()),
                    clampScore(raw.community_value()),
                    clampScore(raw.overall()),
                    raw.reason() != null && !raw.reason().isBlank() ? raw.reason() : "no reason provided"
            );
        } catch (IOException e) {
            LOGGER.error("Quality agent failed to parse response: {}", responseText, e);
            throw new IllegalStateException("failed to parse quality evaluation response", e);
        }
    }

    @Transactional(transactionManager = "transactionManager")
    public void writeQualityScore(String nodeId, QualityScore score) {
        neo4jClient.query(WRITE_QUALITY_QUERY)
                .bind(nodeId).to("nodeId")
                .bind(score.relevance()).to("relevance")
                .bind(score.density()).to("density")
                .bind(score.argumentation()).to("argumentation")
                .bind(score.communityValue()).to("communityValue")
                .bind(score.overall()).to("overall")
                .fetch()
                .one()
                .orElseThrow(() -> new java.util.NoSuchElementException("node not found: " + nodeId));
    }

    private static double clampScore(Double value) {
        if (value == null) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private record RawQualityScore(
            Double relevance,
            Double density,
            Double argumentation,
            Double community_value,
            Double overall,
            String reason
    ) {
    }
}
