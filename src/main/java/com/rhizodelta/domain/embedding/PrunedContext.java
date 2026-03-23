package com.rhizodelta.domain.embedding;

import java.util.List;

public record PrunedContext(
        List<SimilaritySearchResult> selected,
        boolean targetPromoted,
        int droppedCount
) {
    public PrunedContext {
        selected = selected == null ? List.of() : List.copyOf(selected);
    }
}
