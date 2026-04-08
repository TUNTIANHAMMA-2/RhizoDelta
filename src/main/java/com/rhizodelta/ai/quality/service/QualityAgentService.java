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

/**
 * 负责调用模型评估帖子质量并写回评分。
 *
 * <p>该服务不是纯函数评分器，而是一个带副作用的 AI 评估入口：
 * 它会构造提示词、调用聊天模型、解析 JSON 结果，并把质量字段写回图节点。
 *
 * <p><b>关键副作用</b>：
 * <ul>
 *   <li>{@link #evaluate(QualityEvaluationCommand)} 会调用外部模型。</li>
 *   <li>成功解析后会通过 {@link #writeQualityScore(String, QualityScore)} 写 Neo4j。</li>
 *   <li>模型响应格式非法时会抛出异常，而不是静默吞掉错误。</li>
 * </ul>
 */
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

    /**
     * 评估指定节点内容的质量并写回评分字段。
     *
     * <p>该方法是质量评估链路的主入口：先调用模型得到结构化分数，再把分数持久化到节点上。
     *
     * <p>
     *
     * @param command 评估命令。
     * @return 结构化评分结果。
     */
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

    /**
     * 将评估命令转换为模型用户提示词。
     *
     * <p>该方法会按需拼接正文、讨论上下文和图位置描述，避免在无上下文时引入无意义噪音。
     */
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

    /**
     * 解析模型返回的 JSON 评分结果。
     *
     * <p>该方法会对分值做截断归一化，避免异常模型输出直接污染持久化字段。
     */
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

    /**
     * 将质量分写回图节点。
     *
     * <p>这是一个真实写库入口，不是内存缓存更新。
     *
     * <p>
     *
     * @param nodeId 节点 ID。
     * @param score 质量评分。
     */
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
