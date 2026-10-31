package com.contextfs.storage.llm;

import com.contextfs.core.model.Level;
import com.contextfs.core.port.LlmClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.time.Duration;
import java.util.Objects;

/**
 * 基于 LangChain4j 的 {@link LlmClient} 实现：走 OpenAI 兼容接口（baseUrl / 模型均可配置）。
 *
 * <p>仅支持生成 L0（~100 token 摘要）与 L1（~2k token 概览）。
 */
public class LangChain4jLlmClient implements LlmClient {

    static final String L0_PROMPT =
            "请为以下内容生成一段不超过 100 token 的中文摘要，" + "用于快速判断该内容与查询的相关性，只输出摘要本身：\n\n%s";

    static final String L1_PROMPT =
            "请为以下内容生成一段不超过 2000 token 的中文概览，" + "保留关键结构、要点与细节，只输出概览本身：\n\n%s";

    private final ChatModel chatModel;

    public LangChain4jLlmClient(String baseUrl, String apiKey, String model, Duration timeout) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(apiKey, "apiKey");
        Objects.requireNonNull(model, "model");
        this.chatModel =
                OpenAiChatModel.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .modelName(model)
                        .timeout(timeout == null ? Duration.ofSeconds(60) : timeout)
                        .temperature(0.1)
                        .build();
    }

    @Override
    public String summarize(String content, Level targetLevel) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(targetLevel, "targetLevel");
        if (targetLevel == Level.L2) {
            throw new IllegalArgumentException("summarize 仅支持 L0/L1，L2 应直接使用原文");
        }
        String prompt =
                targetLevel == Level.L0
                        ? String.format(L0_PROMPT, content)
                        : String.format(L1_PROMPT, content);
        return chatModel.chat(prompt);
    }
}
