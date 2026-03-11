package com.rhizodelta.service;

import com.rhizodelta.domain.DecisionCommandValidation;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class EmbeddingModelService {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddingModelService.class);

    private final EmbeddingModel embeddingModel;
    private final int embeddingDimension;

    public EmbeddingModelService(
            EmbeddingModel embeddingModel,
            @Value("${rhizodelta.embedding.dimension}") int embeddingDimension
    ) {
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel must not be null");
        this.embeddingDimension = embeddingDimension;
    }

    public List<Float> embed(String text) {
        String validatedText = DecisionCommandValidation.requireText(text, "text");
        Response<Embedding> response = embeddingModel.embed(validatedText);
        Embedding embedding = requireEmbedding(response);
        int actualDimension = embedding.dimension();
        if (actualDimension != embeddingDimension) {
            LOGGER.error("Embedding dimension mismatch: expected {}, actual {}", embeddingDimension, actualDimension);
            throw new IllegalStateException(String.format(
                    "embedding dimension mismatch: expected %d, actual %d",
                    embeddingDimension,
                    actualDimension
            ));
        }
        return List.copyOf(embedding.vectorAsList());
    }

    private static Embedding requireEmbedding(Response<Embedding> response) {
        if (response == null || response.content() == null) {
            throw new IllegalStateException("embedding response content is null");
        }
        return response.content();
    }
}
