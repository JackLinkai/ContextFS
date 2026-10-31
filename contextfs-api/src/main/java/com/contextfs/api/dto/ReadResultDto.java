package com.contextfs.api.dto;

import com.contextfs.core.model.Level;

/**
 * 读取节点某一层内容的响应。
 *
 * <p>generated=false 表示该层摘要尚未生成（阶段 3 为 L0/L1 占位），content 为占位文本。
 */
public record ReadResultDto(
        String uri, Level level, String content, int tokenCount, boolean generated) {}
