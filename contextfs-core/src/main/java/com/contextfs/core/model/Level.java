package com.contextfs.core.model;

/** 三层加载的层级。 */
public enum Level {
    /** 摘要，约 100 token，用于快速判断相关性 */
    L0,
    /** 概览，约 2k token，用于下钻后理解结构 */
    L1,
    /** 完整原文，按需加载 */
    L2
}
