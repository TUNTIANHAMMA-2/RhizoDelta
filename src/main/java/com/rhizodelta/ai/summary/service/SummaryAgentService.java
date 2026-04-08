package com.rhizodelta.ai.summary.service;

import com.rhizodelta.ai.summary.domain.SummaryRequest;
import com.rhizodelta.ai.summary.domain.SummaryResult;
import com.rhizodelta.ai.shared.domain.ModelPurpose;
import com.rhizodelta.ai.shared.service.ModelRouterService;
import com.rhizodelta.consensus.repository.AIConsensusRepository;
import com.rhizodelta.core.repository.HumanPostRepository;
import com.rhizodelta.ai.context.service.BranchContextService;
import com.rhizodelta.ai.shared.service.EmbeddingModelService;
import com.rhizodelta.ai.context.service.EmbeddingService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * 负责生成和更新共识节点摘要。
 *
 * <p>该服务会收集来源帖子、拼接上下文、调用摘要模型生成文本，
 * 再把摘要正文与对应 embedding 写回图节点。
 *
 * <p><b>关键副作用</b>：
 * <ul>
 *   <li>{@link #generate(UUID)} 和 {@link #regenerateIncremental(UUID, List)} 都会调用外部模型。</li>
 *   <li>会写 Neo4j 的 {@code summary_content} 字段。</li>
 *   <li>会尝试更新摘要 embedding；embedding 失败只记录日志，不回滚摘要写入。</li>
 * </ul>
 */
@Service
public class SummaryAgentService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SummaryAgentService.class);

    private static final String SYSTEM_PROMPT = """
            You are a knowledge summarizer for RhizoDelta.
            Given the following source posts that have been merged into a consensus node,
            generate a concise, comprehensive summary that:
            1. Captures the core consensus across all sources.
            2. Notes any nuances or caveats that distinguish individual contributions.
            3. Is written in the same language as the source posts.

            If an existing summary is provided, update it to incorporate the new source material.

            If an existing summary and new contributions are provided,
            your task is to incorporate the new material into the existing summary.
            Do NOT rewrite from scratch — preserve the structure of the existing summary
            and weave in the new information where appropriate.

            Respond with the summary text only, no JSON wrapper.
            """;

    private static final String UPDATE_SUMMARY_QUERY = """
            MATCH (n:GraphNode {node_id: $nodeId})
            WHERE NOT coalesce(n._deleted, false)
            SET n.summary_content = $summary
            RETURN n.node_id AS nodeId
            """;

    private final ModelRouterService modelRouterService;
    private final AIConsensusRepository aiConsensusRepository;
    private final Neo4jClient neo4jClient;
    private final EmbeddingModelService embeddingModelService;
    private final EmbeddingService embeddingService;
    private final BranchContextService branchContextService;
    private final HumanPostRepository humanPostRepository;
    private final int maxSourceTokens;

    public SummaryAgentService(
            ModelRouterService modelRouterService,
            AIConsensusRepository aiConsensusRepository,
            Neo4jClient neo4jClient,
            EmbeddingModelService embeddingModelService,
            EmbeddingService embeddingService,
            BranchContextService branchContextService,
            HumanPostRepository humanPostRepository,
            @Value("${rhizodelta.ai.summary.max-source-tokens:4096}") int maxSourceTokens
    ) {
        this.modelRouterService = modelRouterService;
        this.aiConsensusRepository = aiConsensusRepository;
        this.neo4jClient = neo4jClient;
        this.embeddingModelService = embeddingModelService;
        this.embeddingService = embeddingService;
        this.branchContextService = branchContextService;
        this.humanPostRepository = humanPostRepository;
        this.maxSourceTokens = maxSourceTokens;
    }

    /**
     * 为指定共识节点生成完整摘要。
     *
     * <p>该方法会读取所有来源帖子，并在必要时参考既有摘要和分支上下文，再生成新的摘要正文。
     *
     * <p>
     *
     * @param nodeId 共识节点 ID。
     * @return 摘要结果。
     */
    public SummaryResult generate(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");

        List<String> sourceContents = aiConsensusRepository.findProvenanceContents(nodeId).stream()
                .filter(Objects::nonNull)
                .toList();
        if (sourceContents.isEmpty()) {
            throw new IllegalStateException("no source posts found for node " + nodeId);
        }

        String existingSummary = aiConsensusRepository.findSummaryContentByNodeId(nodeId)
                .orElse(null);

        // Build branch context for the MERGED_INTO target
        String branchContext = buildBranchContextForConsensus(nodeId);

        SummaryRequest request = new SummaryRequest(nodeId, sourceContents, existingSummary);
        String modelName = modelRouterService.resolveModelName(ModelPurpose.SUMMARY);

        LOGGER.info("Summary agent generating summary node_id={} source_count={} model={}",
                nodeId, sourceContents.size(), modelName);

        String summary = invokeLlm(request, branchContext, modelName);
        writeSummary(nodeId, summary);
        updateEmbedding(nodeId, summary);

        LOGGER.info("Summary agent completed node_id={} summary_length={}", nodeId, summary.length());
        return new SummaryResult(summary, sourceContents.size(), modelName);
    }

    /**
     * 基于新增来源增量更新摘要。
     *
     * <p>若当前节点还没有既有摘要，则会回退到完整生成流程。
     *
     * <p>
     *
     * @param consensusNodeId 共识节点 ID。
     * @param newContributorIds 新增来源节点列表。
     * @return 增量更新后的摘要结果。
     */
    public SummaryResult regenerateIncremental(UUID consensusNodeId, List<UUID> newContributorIds) {
        Objects.requireNonNull(consensusNodeId, "consensusNodeId must not be null");
        Objects.requireNonNull(newContributorIds, "newContributorIds must not be null");

        // 1. Read existing summary (lightweight — no embedding deserialization)
        String oldSummary = aiConsensusRepository.findSummaryContentByNodeId(consensusNodeId)
                .orElse(null);
        if (oldSummary == null || oldSummary.isBlank()) {
            LOGGER.warn("No existing summary for consensus={}, falling back to full generate", consensusNodeId);
            return generate(consensusNodeId);
        }

        // 2. Fetch only the new contributor contents (lightweight — no embedding deserialization)
        List<String> newContents = humanPostRepository.findContentsByNodeIdIn(newContributorIds).stream()
                .filter(Objects::nonNull)
                .toList();
        if (newContents.isEmpty()) {
            LOGGER.warn("No new contributor content found for consensus={}, skipping", consensusNodeId);
            return new SummaryResult(oldSummary, 0, modelRouterService.resolveModelName(ModelPurpose.SUMMARY));
        }

        // 3. Build branch context
        String branchContext = buildBranchContextForConsensus(consensusNodeId);

        // 4. Build incremental prompt
        String modelName = modelRouterService.resolveModelName(ModelPurpose.SUMMARY);
        int charBudget = maxSourceTokens * 4;
        int newContentBudget = charBudget / 2;       // 50% for new posts
        int branchContextBudget = charBudget / 4;     // 25% for ancestors

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Existing summary:\n").append(oldSummary).append("\n\n");
        userPrompt.append("New contributions to incorporate:\n");
        int used = 0;
        for (int i = 0; i < newContents.size(); i++) {
            String entry = "[New %d] %s\n".formatted(i + 1, newContents.get(i));
            if (used + entry.length() > newContentBudget) {
                userPrompt.append("[... %d more contributions truncated]\n".formatted(newContents.size() - i));
                break;
            }
            userPrompt.append(entry);
            used += entry.length();
        }

        if (!branchContext.isEmpty()) {
            String truncatedContext = branchContext.length() > branchContextBudget
                    ? branchContext.substring(0, branchContextBudget) + "\n[... truncated]"
                    : branchContext;
            userPrompt.append("\nBranch context:\n").append(truncatedContext);
        }

        LOGGER.info("Summary agent regenerating incrementally consensus={} new_contributors={} model={}",
                consensusNodeId, newContents.size(), modelName);

        ChatLanguageModel model = modelRouterService.getModel(ModelPurpose.SUMMARY);
        Response<AiMessage> response = model.generate(List.of(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(userPrompt.toString())
        ));
        if (response == null || response.content() == null || response.content().text() == null) {
            throw new IllegalStateException("summary agent response content is null");
        }
        String summary = response.content().text().trim();

        writeSummary(consensusNodeId, summary);
        updateEmbedding(consensusNodeId, summary);

        // sourceCount = total provenance, not just new (lightweight count)
        int totalSources = (int) aiConsensusRepository.countProvenanceByNodeId(consensusNodeId);
        LOGGER.info("Incremental summary completed consensus={} summary_length={}", consensusNodeId, summary.length());
        return new SummaryResult(summary, totalSources, modelName);
    }

    /**
     * 调用模型生成摘要正文。
     *
     * <p>该方法只负责提示词构造与模型调用，不直接落库。
     */
    private String invokeLlm(SummaryRequest request, String branchContext, String modelName) {
        String sourcesText = buildSourcesText(request.sourceContents());
        String userPrompt = "Sources:\n" + sourcesText;
        if (request.existingSummary() != null && !request.existingSummary().isBlank()) {
            userPrompt += "\n\nExisting summary:\n" + request.existingSummary();
        }
        if (branchContext != null && !branchContext.isBlank()) {
            userPrompt += "\n\nBranch context:\n" + branchContext;
        }

        ChatLanguageModel model = modelRouterService.getModel(ModelPurpose.SUMMARY);
        Response<AiMessage> response = model.generate(List.of(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(userPrompt)
        ));

        if (response == null || response.content() == null || response.content().text() == null) {
            throw new IllegalStateException("summary agent response content is null");
        }
        return response.content().text().trim();
    }

    /**
     * 将来源文本压缩为模型可接受的提示词片段。
     *
     * <p>超出预算时会显式写入截断提示，而不是悄悄丢掉尾部内容。
     */
    private String buildSourcesText(List<String> sourceContents) {
        StringBuilder sb = new StringBuilder();
        int charBudget = maxSourceTokens * 4; // rough chars-per-token estimate
        for (int i = 0; i < sourceContents.size(); i++) {
            String content = sourceContents.get(i);
            String entry = "[Source %d]\n%s\n\n".formatted(i + 1, content);
            if (sb.length() + entry.length() > charBudget) {
                sb.append("[... %d more sources truncated]\n".formatted(sourceContents.size() - i));
                break;
            }
            sb.append(entry);
        }
        return sb.toString();
    }

    /**
     * 将摘要正文写回图节点。
     *
     * <p>这是一个真实写库入口，不是内存更新。
     *
     * <p>
     *
     * @param nodeId 节点 ID。
     * @param summary 摘要正文。
     */
    @Transactional(transactionManager = "transactionManager")
    public void writeSummary(UUID nodeId, String summary) {
        neo4jClient.query(UPDATE_SUMMARY_QUERY)
                .bind(nodeId.toString()).to("nodeId")
                .bind(summary).to("summary")
                .fetch()
                .one()
                .orElseThrow(() -> new NoSuchElementException("node not found: " + nodeId));
    }

    /**
     * 依据摘要正文更新对应 embedding。
     *
     * <p>embedding 更新失败不会回滚摘要正文，避免把“摘要可用”错误降级成整体失败。
     */
    private void updateEmbedding(UUID nodeId, String summary) {
        try {
            List<Float> vector = embeddingModelService.embed(summary);
            embeddingService.writeEmbedding(nodeId.toString(), vector);
            LOGGER.info("Summary embedding updated for node_id={}", nodeId);
        } catch (Exception e) {
            LOGGER.error("Failed to update embedding after summary generation node_id={}", nodeId, e);
        }
    }

    /**
     * 为共识节点解析分支上下文。
     *
     * <p>该上下文用于帮助摘要模型理解当前共识挂接在哪条讨论线上。
     */
    private String buildBranchContextForConsensus(UUID consensusNodeId) {
        return aiConsensusRepository.findMergedIntoTargetId(consensusNodeId)
                .map(targetIdStr -> {
                    UUID targetId = UUID.fromString(targetIdStr);
                    BranchContextService.BranchContext ctx =
                            branchContextService.buildContext(targetId, targetId);
                    return branchContextService.formatForSummary(ctx);
                })
                .orElse("");
    }
}
