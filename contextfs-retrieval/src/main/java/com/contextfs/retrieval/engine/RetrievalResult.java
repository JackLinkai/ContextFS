package com.contextfs.retrieval.engine;

import java.util.List;

/**
 * 检索结果（REST 响应体，裸 JSON 无 envelope）。
 *
 * @param traceId 链路追踪 ID
 * @param mode 实际使用的检索模式
 * @param items 按分数降序的结果条目
 * @param tokenUsage token 消耗统计
 * @param latencyMs 服务端总耗时（毫秒）
 */
public record RetrievalResult(
        String traceId,
        RetrievalMode mode,
        List<RetrievalItem> items,
        TokenUsage tokenUsage,
        long latencyMs) {

    /**
     * token 消耗统计。
     *
     * @param promptTokens 返回内容合计 token 数（作为后续 LLM 上下文的估算成本）
     * @param completionTokens 检索阶段无生成动作，恒为 0
     */
    public record TokenUsage(int promptTokens, int completionTokens) {}
}
