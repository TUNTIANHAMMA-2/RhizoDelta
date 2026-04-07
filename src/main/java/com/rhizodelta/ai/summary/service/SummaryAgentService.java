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

    @Transactional(transactionManager = "transactionManager")
    public void writeSummary(UUID nodeId, String summary) {
        neo4jClient.query(UPDATE_SUMMARY_QUERY)
                .bind(nodeId.toString()).to("nodeId")
                .bind(summary).to("summary")
                .fetch()
                .one()
                .orElseThrow(() -> new NoSuchElementException("node not found: " + nodeId));
    }

    private void updateEmbedding(UUID nodeId, String summary) {
        try {
            List<Float> vector = embeddingModelService.embed(summary);
            embeddingService.writeEmbedding(nodeId.toString(), vector);
            LOGGER.info("Summary embedding updated for node_id={}", nodeId);
        } catch (Exception e) {
            LOGGER.error("Failed to update embedding after summary generation node_id={}", nodeId, e);
        }
    }

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
