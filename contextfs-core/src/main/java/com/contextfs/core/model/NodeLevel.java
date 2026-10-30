package com.contextfs.core.model;

/**
 * 节点某一层的内容。
 *
 * @param level      层级
 * @param content    内容文本
 * @param tokenCount token 估算数
 */
public record NodeLevel(Level level, String content, int tokenCount) {

    public NodeLevel {
        if (content == null) {
            content = "";
        }
    }
}
