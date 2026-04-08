package com.rhizodelta.ai.context.service;

import com.rhizodelta.ai.context.domain.embedding.PrunedContext;
import com.rhizodelta.ai.context.domain.embedding.SimilaritySearchResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 负责对召回候选进行裁剪。
 *
 * <p>该服务存在的意义，是在向量召回结果过多时保留最有价值的一小部分候选，
 * 同时优先保证目标节点本身不会因为相似度排序被误删。
 */
@Service
public class ContextPruner {
    private final int topN;

    public ContextPruner(@Value("${rhizodelta.ai.rerank.top-n:3}") int topN) {
        this.topN = topN;
    }

    /**
     * 从候选列表中选出最终保留的上下文。
     *
     * <p><b>裁剪策略</b>：
     * <ul>
     *   <li>先按相似度从高到低排序。</li>
     *   <li>若目标节点本身出现在候选里，则优先保留它。</li>
     *   <li>其余位置再按得分补齐到 {@code topN}。</li>
     * </ul>
     *
     * <p>
     *
     * @param candidates 原始候选列表。
     * @param targetNodeId 可选目标节点 ID。
     * @return 裁剪后的上下文。
     */
    public PrunedContext prune(List<SimilaritySearchResult> candidates, String targetNodeId) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        if (candidates.isEmpty()) {
            return new PrunedContext(List.of(), false, 0);
        }
        UUID targetUuid = parseTargetNodeId(targetNodeId);
        List<SimilaritySearchResult> sorted = candidates.stream()
                .sorted(Comparator.comparing(SimilaritySearchResult::score, Comparator.nullsLast(Double::compareTo)).reversed())
                .toList();
        SimilaritySearchResult targetCandidate = findTargetCandidate(sorted, targetUuid);
        List<SimilaritySearchResult> selected = new ArrayList<>();
        if (targetCandidate != null) {
            selected.add(targetCandidate);
        }
        for (SimilaritySearchResult candidate : sorted) {
            if (selected.size() >= topN) {
                break;
            }
            if (targetCandidate != null && candidate.node_id().equals(targetCandidate.node_id())) {
                continue;
            }
            selected.add(candidate);
        }
        return new PrunedContext(
                selected,
                targetCandidate != null && !selected.isEmpty() && selected.get(0).node_id().equals(targetCandidate.node_id()),
                Math.max(candidates.size() - selected.size(), 0)
        );
    }

    private SimilaritySearchResult findTargetCandidate(List<SimilaritySearchResult> candidates, UUID targetUuid) {
        if (targetUuid == null) {
            return null;
        }
        return candidates.stream()
                .filter(candidate -> targetUuid.equals(candidate.node_id()))
                .findFirst()
                .orElse(null);
    }

    private UUID parseTargetNodeId(String targetNodeId) {
        if (targetNodeId == null || targetNodeId.isBlank()) {
            return null;
        }
        return UUID.fromString(targetNodeId);
    }
}
