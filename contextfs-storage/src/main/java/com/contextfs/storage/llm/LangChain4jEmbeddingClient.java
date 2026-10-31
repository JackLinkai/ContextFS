package com.contextfs.storage.llm;

import com.contextfs.core.port.EmbeddingClient;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import java.util.Objects;

/** 基于 LangChain4j 的 {@link EmbeddingClient} 实现：走 OpenAI 兼容嵌入接口。 */
public class LangChain4jEmbeddingClient implements EmbeddingClient {

    private final EmbeddingModel embeddingModel;

    public LangChain4jEmbeddingClient(String baseUrl, String apiKey, String model) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(apiKey, "apiKey");
        Objects.requireNonNull(model, "model");
        this.embeddingModel =
                OpenAiEmbeddingModel.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .modelName(model)
                        .build();
    }

    @Override
    public float[] embed(String text) {
        Objects.requireNonNull(text, "text");
        return embeddingModel.embed(text).content().vector();
    }
}
