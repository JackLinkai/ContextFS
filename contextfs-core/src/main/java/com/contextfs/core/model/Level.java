package com.contextfs.core.model;

import java.util.List;

/** 三层加载的层级。 */
public enum Level {
    /** 摘要，约 100 token，用于快速判断相关性 */
    L0,
    /** 概览，约 2k token，用于下钻后理解结构 */
    L1,
    /** 完整原文，按需加载 */
    L2;

    /** 返回自身及其以下所有层级，从高到低排列（如 L2 → [L2, L1, L0]），用于按需降级加载。 */
    public List<Level> selfAndBelow() {
        return switch (this) {
            case L2 -> List.of(L2, L1, L0);
            case L1 -> List.of(L1, L0);
            case L0 -> List.of(L0);
        };
    }
}
