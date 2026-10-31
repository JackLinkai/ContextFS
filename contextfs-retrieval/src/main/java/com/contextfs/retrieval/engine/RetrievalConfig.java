package com.contextfs.retrieval.engine;

import com.contextfs.core.model.Level;

/**
 * 检索配置：剪枝、下钻与预算控制参数。
 *
 * @param topKDir 首轮目录（L0）向量检索条数上限
 * @param maxLevel 结果内容加载的最高层级（缺失时向下降级）
 * @param scoreThreshold 首轮命中分数阈值，低于该值的目录不再下钻
 * @param tokenBudget 返回内容的 token 预算上限，0 表示不限制
 * @param maxDepth 目录下钻最大层数（命中目录的直接子层为 1）
 */
public record RetrievalConfig(
        int topKDir, Level maxLevel, double scoreThreshold, int tokenBudget, int maxDepth) {}
