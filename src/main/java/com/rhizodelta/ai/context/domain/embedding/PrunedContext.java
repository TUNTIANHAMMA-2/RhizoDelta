package com.rhizodelta.ai.context.domain.embedding;

import java.util.List;

/**
 * 表示裁剪后的召回上下文。
 *
 * <p>该对象用于告诉上层：最终保留了哪些候选、目标节点是否被优先提升、
 * 以及有多少候选在裁剪过程中被丢弃。
 */
public record PrunedContext(
        List<SimilaritySearchResult> selected,
        boolean targetPromoted,
        int droppedCount
) {
    /**
     * 创建裁剪结果并归一化候选集合。
     *
     * <p>这里会把空集合统一收敛为不可变空列表，降低上层判空成本。
     */
    public PrunedContext {
        selected = selected == null ? List.of() : List.copyOf(selected);
    }
}
