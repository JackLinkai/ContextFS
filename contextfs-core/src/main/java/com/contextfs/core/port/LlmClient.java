package com.contextfs.core.port;

import com.contextfs.core.model.Level;

/** LLM 客户端端口，由 LangChain4j 适配实现。 */
public interface LlmClient {

    /**
     * 将内容压缩为指定层级的摘要。
     *
     * @param content     原始内容（L2 或下层内容）
     * @param targetLevel 目标层级，仅支持 L0 / L1
     * @return 摘要文本
     */
    String summarize(String content, Level targetLevel);
}
