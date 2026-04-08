package com.rhizodelta.ai.shared.service;

import com.rhizodelta.core.validation.DecisionCommandValidation;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 负责把文本转换为 embedding 向量。
 *
 * <p>该服务封装了底层 embedding 模型调用，并对返回维度做强校验，
 * 防止错误向量继续流入向量检索与写库链路。
 */
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

    /**
     * 为指定文本生成 embedding 向量。
     *
     * <p>该方法会先校验文本，再调用底层模型；若模型返回维度与系统配置不一致，则直接抛错。
     *
     * <p>
     *
     * @param text 待向量化文本。
     * @return 不可变向量列表。
     */
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

    /**
     * 从模型响应中提取 embedding 内容。
     *
     * <p>缺失内容会被视为严重错误，而不是返回空向量做静默降级。
     */
    private static Embedding requireEmbedding(Response<Embedding> response) {
        if (response == null || response.content() == null) {
            throw new IllegalStateException("embedding response content is null");
        }
        return response.content();
    }
}
