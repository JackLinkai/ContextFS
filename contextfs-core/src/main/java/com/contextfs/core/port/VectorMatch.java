package com.contextfs.core.port;

import com.contextfs.core.model.Level;
import com.contextfs.core.uri.CtxfsUri;

/**
 * 向量检索命中项。
 *
 * @param uri     命中节点 URI
 * @param level   命中层级
 * @param content 命中内容
 * @param score   相似度分数，越大越相关
 */
public record VectorMatch(CtxfsUri uri, Level level, String content, float score) {}
