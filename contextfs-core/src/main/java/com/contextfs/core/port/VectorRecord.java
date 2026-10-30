package com.contextfs.core.port;

import com.contextfs.core.model.Level;
import com.contextfs.core.uri.CtxfsUri;

/**
 * 向量库中的一条记录：某节点某一层的内容与其嵌入向量。
 *
 * @param uri       所属节点 URI
 * @param level     层级
 * @param content   内容文本
 * @param embedding 嵌入向量
 */
public record VectorRecord(CtxfsUri uri, Level level, String content, float[] embedding) {}
