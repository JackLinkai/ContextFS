package com.contextfs.core.port;

/** 嵌入向量端口，由 LangChain4j 适配实现。 */
public interface EmbeddingClient {

    /**
     * 将文本转换为嵌入向量。
     *
     * @param text 输入文本
     * @return 嵌入向量
     */
    float[] embed(String text);
}
