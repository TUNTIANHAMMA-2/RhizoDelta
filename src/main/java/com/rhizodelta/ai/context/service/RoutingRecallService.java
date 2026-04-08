package com.rhizodelta.ai.context.service;

import com.rhizodelta.ai.context.domain.embedding.PrunedContext;
import com.rhizodelta.ai.context.domain.embedding.SimilaritySearchResult;
import com.rhizodelta.ai.shared.service.EmbeddingModelService;
import com.rhizodelta.ai.context.service.EmbeddingService;
import com.rhizodelta.ai.context.service.ContextPruner;
import com.rhizodelta.core.service.GraphRootLocatorService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 负责为 AI 路由流程召回上下文候选。
 *
 * <p>该服务把“文本转向量”“按根节点限制检索”“候选裁剪”三个步骤封装在一起，
 * 为路由工作流提供一个稳定的召回入口。
 */
@Service
public class RoutingRecallService {
    private final EmbeddingModelService embeddingModelService;
    private final EmbeddingService embeddingService;
    private final GraphRootLocatorService graphRootLocatorService;
    private final ContextPruner contextPruner;

    public RoutingRecallService(
            EmbeddingModelService embeddingModelService,
            EmbeddingService embeddingService,
            GraphRootLocatorService graphRootLocatorService,
            ContextPruner contextPruner
    ) {
        this.embeddingModelService = embeddingModelService;
        this.embeddingService = embeddingService;
        this.graphRootLocatorService = graphRootLocatorService;
        this.contextPruner = contextPruner;
    }

    /**
     * 基于帖子内容召回路由候选上下文。
     *
     * <p>该方法会先调用 {@link EmbeddingModelService} 生成向量，再使用
     * {@link EmbeddingService} 在向量索引中搜索，最后交给 {@link ContextPruner} 做裁剪。
     *
     * <p>
     *
     * @param content 待路由内容。
     * @param targetNodeId 可选目标节点 ID。
     * @return 裁剪后的召回上下文。
     */
    public PrunedContext recall(String content, String targetNodeId) {
        Objects.requireNonNull(content, "content must not be null");
        List<Float> vector = embeddingModelService.embed(content);
        List<SimilaritySearchResult> candidates = embeddingService.searchSimilar(
                vector,
                null,
                resolveRootId(targetNodeId)
        );
        return contextPruner.prune(candidates, targetNodeId);
    }

    private String resolveRootId(String targetNodeId) {
        if (targetNodeId == null || targetNodeId.isBlank()) {
            return null;
        }
        return graphRootLocatorService.resolveRootId(targetNodeId);
    }
}
