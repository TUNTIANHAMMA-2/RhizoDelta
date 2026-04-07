package com.rhizodelta.infrastructure.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OpenAiApiKeyStartupValidator {
    static final String PLACEHOLDER_API_KEY = "your-placeholder-key";

    private final String chatModelApiKey;
    private final String embeddingModelApiKey;

    public OpenAiApiKeyStartupValidator(
            @Value("${langchain4j.open-ai.chat-model.api-key}") String chatModelApiKey,
            @Value("${langchain4j.open-ai.embedding-model.api-key}") String embeddingModelApiKey
    ) {
        this.chatModelApiKey = chatModelApiKey;
        this.embeddingModelApiKey = embeddingModelApiKey;
    }

    @PostConstruct
    void validate() {
        validateKey(chatModelApiKey, "langchain4j.open-ai.chat-model.api-key");
        validateKey(embeddingModelApiKey, "langchain4j.open-ai.embedding-model.api-key");
    }

    private void validateKey(String apiKey, String propertyName) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(propertyName + " must not be blank");
        }
        if (PLACEHOLDER_API_KEY.equals(apiKey)) {
            throw new IllegalStateException(propertyName + " must not use the placeholder API key");
        }
    }
}
