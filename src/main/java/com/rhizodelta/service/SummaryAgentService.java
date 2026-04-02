package com.rhizodelta.service;

import com.rhizodelta.domain.ai.ModelPurpose;
import com.rhizodelta.domain.ai.SummaryRequest;
import com.rhizodelta.domain.ai.SummaryResult;
import com.rhizodelta.domain.node.HumanPost;
import com.rhizodelta.repository.AIConsensusRepository;
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
    private final int maxSourceTokens;

    public SummaryAgentService(
            ModelRouterService modelRouterService,
            AIConsensusRepository aiConsensusRepository,
            Neo4jClient neo4jClient,
            EmbeddingModelService embeddingModelService,
            EmbeddingService embeddingService,
            @Value("${rhizodelta.ai.summary.max-source-tokens:4096}") int maxSourceTokens
    ) {
        this.modelRouterService = modelRouterService;
        this.aiConsensusRepository = aiConsensusRepository;
        this.neo4jClient = neo4jClient;
        this.embeddingModelService = embeddingModelService;
        this.embeddingService = embeddingService;
        this.maxSourceTokens = maxSourceTokens;
    }

    public SummaryResult generate(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");

        List<HumanPost> sources = aiConsensusRepository.findProvenance(nodeId);
        if (sources.isEmpty()) {
            throw new IllegalStateException("no source posts found for node " + nodeId);
        }

        String existingSummary = aiConsensusRepository.findActiveByNodeId(nodeId)
                .map(c -> c.getSummaryContent())
                .orElse(null);

        List<String> sourceContents = sources.stream()
                .map(HumanPost::getContent)
                .filter(Objects::nonNull)
                .toList();

        SummaryRequest request = new SummaryRequest(nodeId, sourceContents, existingSummary);
        String modelName = modelRouterService.resolveModelName(ModelPurpose.SUMMARY);

        LOGGER.info("Summary agent generating summary node_id={} source_count={} model={}",
                nodeId, sourceContents.size(), modelName);

        String summary = invokeLlm(request, modelName);
        writeSummary(nodeId, summary);
        updateEmbedding(nodeId, summary);

        LOGGER.info("Summary agent completed node_id={} summary_length={}", nodeId, summary.length());
        return new SummaryResult(summary, sourceContents.size(), modelName);
    }

    private String invokeLlm(SummaryRequest request, String modelName) {
        String sourcesText = buildSourcesText(request.sourceContents());
        String userPrompt = "Sources:\n" + sourcesText;
        if (request.existingSummary() != null && !request.existingSummary().isBlank()) {
            userPrompt += "\n\nExisting summary:\n" + request.existingSummary();
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
}
