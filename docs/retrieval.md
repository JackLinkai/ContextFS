# 检索设计

本文描述 ContextFS 检索引擎的模式、算法、配置与量化评测基线。架构背景见 [architecture.md](architecture.md)，记忆管线见 [memory.md](memory.md)。

---

## 1. 三层上下文模型

每个节点可挂载三层内容，检索时按需逐层加载：

| 层级 | 内容 | 典型用途 | 加载时机 |
|---|---|---|---|
| `L0` | 摘要（约 100 token） | 向量召回与相关性初筛 | 召回阶段必然加载 |
| `L1` | 概览（约 2k token） | 理解结构与要点 | `maxLevel >= L1` 时加载 |
| `L2` | 完整原文 | 最终上下文注入 | `maxLevel = L2` 时加载 |

目录节点只有 L0 / L1（聚合子节点概览）；`maxLevel` 装配时目录始终降级返回 L0，避免目录聚合内容撑爆预算。

---

## 2. 两种检索模式

### 2.1 SEARCH（语义检索 + 目录下钻）

```text
query
  -> EmbeddingClient 向量化
  -> VectorStore 检索 L0 向量，召回高分节点（含目录）
  -> 命中目录：沿目录树递归下钻子节点（深度受 maxDepth 限制）
  -> 三层内容装配：文档按 maxLevel 返回 L1/L2 原文，目录降级 L0
  -> 分数阈值剪枝（minScore）
  -> token 预算截断：首条必保留，其余累计不超过 tokenBudget
  -> 按分数降序返回
```

设计动机：扁平向量检索只返回孤立 chunk，无法回答"这个领域有哪些资料"。目录定位 + 逐层下钻让一次检索既能命中具体文档，也能命中其所属目录，为 Agent 提供可继续探索的结构。

### 2.2 FIND（精确匹配）

不打向量，按关键词匹配：

| 命中方式 | 分数 |
|---|---|
| URI 关键词命中 | 1.0 |
| L0 内容关键词命中 | 0.5 |

适合已知文件名或确切术语的查找场景。

---

## 3. 降级策略

- **向量依赖缺失**（VectorStore / EmbeddingClient 未装配或不可用）：SEARCH 自动降级为词法检索，按 URI 与 L0 内容关键词打分，保证基础可用性。
- **层级生成失败**：写入时 LLM 层级生成失败不阻塞主流程，L0 降级为占位摘要并在后续写入重试。

---

## 4. 配置

引擎级配置 `RetrievalConfig`（代码构造，支持请求级覆盖）：

| 参数 | 含义 | 评测默认值 |
|---|---|---|
| `topN` | 返回条数上限 | 5 |
| `maxLevel` | 文档加载层级上限 | L1 |
| `minScore` | 分数阈值，低于则剪枝 | 0.2 |
| `tokenBudget` | 注入 token 预算（首条除外累计截断） | 4096 |
| `maxDepth` | 目录下钻最大深度 | 3 |

API 请求体支持 `scope`、`query`、`mode`（`search` / `find`）、`topKDir`、`maxLevel`、`traceId`。

---

## 5. 可观测接入

每次 `retrieve()` 写入一条 `retrieval_log`（经 `TraceLogStore` 端口）：

- 记录字段：traceId、spanId、sessionId、agentId、scope、query、mode、retrievedUris、levels、latencyMs、promptTokens、status、error；
- 成功与失败均记录（失败记 ERROR 后原样抛出）；
- `TraceLogStore` 为空（未装配）时静默跳过，不影响检索主流程。

配合 `GET /api/v1/traces/{traceId}` 可回放命中集合、加载层级与耗时（见 [architecture.md 5.3](architecture.md)）。

---

## 6. 量化评测基线

### 6.1 评测方法

- 评测集：`RetrievalEvalTest`（contextfs-retrieval）。
- 语料：10 节点（3 目录 + 7 文档），文档 L2 为约 220 token 的真实长文档、L1 为约 30 token 概览；嵌入使用 `HashingEmbeddingClient`（字符 unigram + bigram 哈希），查询复用语料关键短语。
- 指标口径：
  - **recall@3**：6 组查询的期望文档是否出现在 top-3（目录命中算加分项，不计入召回率）；
  - **token 降幅**：同一命中集合下，「三层按需加载注入 token」对比「直接注入各命中节点 L2 原文 token」的下降比例；
  - **延迟**：预热 1 轮后每查询执行 30 次，取 P50 / P95。

### 6.2 基线结果（本地评测，HashingEmbeddingClient）

| 指标 | 数值 |
|---|---|
| recall@3 | **1.00**（6/6） |
| 平均加载层级 | 0.76 |
| 平均加载 token | 128.7 |
| 直接注入 L2 平均 token | 1002.8 |
| **token 降幅（vs 直接注入 L2）** | **87.2%** |
| 延迟 P50 / P95 | < 1 ms（InMemory 实现亚毫秒级） |

> 结论：在命中质量不受损的前提下，三层按需加载将注入 token 降低约 87%。该结果基于哈希嵌入与固定语料，用于验证检索链路的**相对收益与质量下限**；接入真实嵌入模型后语料规模与指标将同步更新。

### 6.3 基线文件

每次运行输出 `contextfs-retrieval/target/retrieval-eval-baseline.json`：

```json
{
  "queries": 6,
  "recallAt3": 1.0,
  "avgLoadedLevel": 0.76,
  "avgLoadedTokens": 128.7,
  "avgL2FullTokens": 1002.8,
  "tokenReductionVsL2": 0.872,
  "latencyP50Ms": 0,
  "latencyP95Ms": 0,
  "perQuery": [ ... ]
}
```

断言门槛：`recall@3 >= 0.6`、`tokenReductionVsL2 >= 0.4`，回归时自动校验。
