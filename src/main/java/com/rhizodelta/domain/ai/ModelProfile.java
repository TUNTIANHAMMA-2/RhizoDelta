package com.rhizodelta.domain.ai;

public record ModelProfile(
        String name,
        String baseUrl,
        String apiKey,
        ModelPurpose purpose,
        int maxTokens
) {
    public ModelProfile {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("model name must not be blank");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("base URL must not be blank");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API key must not be blank");
        }
        if (purpose == null) {
            throw new IllegalArgumentException("purpose must not be null");
        }
        if (maxTokens <= 0) {
            maxTokens = 4096;
        }
    }
}
