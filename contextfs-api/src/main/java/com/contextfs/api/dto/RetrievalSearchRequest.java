package com.contextfs.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 检索请求体：POST /api/v1/retrieval/search。
 *
 * @param scope 检索范围 scope，缺省 {@code global}；与 {@code X-ContextFS-Scope} 头冲突时返回 403
 * @param query 查询文本（SEARCH 为语义查询，FIND 为关键词）
 * @param mode 检索模式 {@code search} / {@code find}，缺省 {@code search}
 * @param topKDir 首轮目录向量检索条数覆盖
 * @param maxLevel 结果内容加载最高层级 {@code L0} / {@code L1} / {@code L2} 覆盖
 * @param traceId 链路追踪 ID，缺省自动生成
 * @param scoreThreshold 首轮命中分数阈值覆盖
 * @param tokenBudget token 预算覆盖
 * @param maxDepth 目录下钻最大层数覆盖
 */
public record RetrievalSearchRequest(
        String scope,
        @NotBlank String query,
        String mode,
        Integer topKDir,
        String maxLevel,
        String traceId,
        Double scoreThreshold,
        Integer tokenBudget,
        Integer maxDepth) {}
