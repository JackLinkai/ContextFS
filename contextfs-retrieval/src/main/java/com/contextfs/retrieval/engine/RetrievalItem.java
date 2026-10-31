package com.contextfs.retrieval.engine;

import com.contextfs.core.model.Level;

/**
 * 检索结果条目。
 *
 * @param uri 命中节点 URI 字符串
 * @param level 实际加载的层级（可能低于配置的 maxLevel，按需降级）
 * @param content 该层内容文本
 * @param score 相关性分数，越大越相关
 */
public record RetrievalItem(String uri, Level level, String content, double score) {}
