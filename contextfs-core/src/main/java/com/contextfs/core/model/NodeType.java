package com.contextfs.core.model;

/** 节点类型。 */
public enum NodeType {
    /** 目录节点，挂载 L0 / L1 摘要 */
    DIRECTORY,
    /** 文档节点，挂载 L0 / L1 / L2 内容 */
    DOCUMENT,
    /** 记忆节点，由记忆管线生成 */
    MEMORY,
    /** 技能节点，描述工具或能力 */
    SKILL
}
