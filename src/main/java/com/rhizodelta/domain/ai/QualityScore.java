package com.rhizodelta.domain.ai;

public record QualityScore(
        double relevance,
        double density,
        double argumentation,
        double communityValue,
        double overall,
        String reason
) {
    public QualityScore {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }

    public static double computeOverall(double relevance, double density, double argumentation, double communityValue) {
        return relevance * 0.25 + density * 0.25 + argumentation * 0.3 + communityValue * 0.2;
    }
}
