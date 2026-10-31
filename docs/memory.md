# 记忆管线设计

本文描述 ContextFS 会话记忆管线：会话归档、记忆提取、合并决策与量化评测。架构背景见 [architecture.md](architecture.md)，检索细节见 [retrieval.md](retrieval.md)。

---

## 1. 管线总览

```text
POST /api/v1/sessions/{sessionId}/commit
  -> SessionController（从 X-ContextFS-Trace-Id 头取 traceId，缺省生成 UUID）
  -> MemoryPipelineService.commit（同步）或 commitAsync（异步线程池）
     -> 1. 会话归档：全部消息落库为会话节点
     -> 2. 记忆提取：从 user 消息提取偏好 / 经验 / 事实候选
     -> 3. 合并决策：逐条与已有记忆比较，新建 / 合并 / 跳过
     -> 全程写入 tool_call_log（memory.extract / memory.decide）
  -> 返回 CommitOutcome（archivedUri + changes 明细）
```

开关与模式：`contextfs.memory.enabled`（关闭时仅归档不提取）、`contextfs.memory.async`（异步执行，traceId 在 HTTP 线程捕获后随任务传入，不受 MDC 线程局部性影响）。

---

## 2. 会话归档

- 归档 URI：`ctxfs://session/{sessionId}/archive.md`，节点类型 `DOCUMENT`。
- L2 保存全部对话原文，格式为逐行 `role: content`；L0 / L1 为摘要层级。
- 归档是幂等的：同一 sessionId 重复提交覆盖归档内容。
- 归档 scope 为 `session/{sessionId}`，天然与其他上下文隔离；后续可由 Agent 自主检索历史会话。

---

## 3. 记忆提取

提取器 `MemoryExtractor` 为规则式实现（后续接入 LLM 后替换为语义提取，接口不变）：

- **输入过滤**：仅 `user` 角色消息参与；内容长度 < 6 字符忽略；空白消息忽略。
- **关键词分类**（大小写不敏感）：

| 类型 | 关键词 |
|---|---|
| 偏好 PREFERENCE | 偏好、喜欢、不喜欢、以后、尽量、简洁、详细、风格、主题 |
| 经验 EXPERIENCE | 经验、教训、失败、解决、步骤、报错、修复、排查、踩坑 |
| 事实 FACT | 项目、使用、架构、版本、部署、数据库、环境、团队、基于 |

- **优先级**：一条消息命中多类关键词时按 偏好 -> 经验 -> 事实 归入一类（表达偏好的句子优先按偏好处理）。
- **限流**：单次提交每类最多提取 3 条（`MAX_PER_TYPE`），防止低质量刷屏。

---

## 4. 合并决策

每条候选记忆独立决策，产出一条 `MemoryChange`：

```text
候选记忆
  -> 检索同 scope、同类型下最相似的已有记忆节点
  -> 相似度 >= 阈值：MERGED（合并进已有节点）
  -> 相似度较高但未达合并线：SKIPPED（跳过，保留现状）
  -> 无相似记忆：CREATED（新建节点）
```

- 新建节点路径：`ctxfs://user/{userId}/memories/{type}-{n}.md`，节点类型 `MEMORY`，内容带类型前缀（如「偏好：」）。
- `MemoryChange` 记录：uri（跳过时为最相似已有节点）、type、decision（`CREATED` / `MERGED` / `SKIPPED`）、similarity（0-1，无已有记忆时为 0）。
- 相似度计算复用检索通道（嵌入向量），保证「记忆去重」与「检索命中」口径一致。

### 记忆隔离

| Scope | 说明 |
|---|---|
| `global` | 全局共享记忆 |
| `user/{userId}` | 用户级记忆（默认落点） |
| `agent/{agentId}` | Agent 级资源与技能 |
| `session/{sessionId}` | 会话归档 |

提交时按 `userId` 写入用户级记忆目录，跨用户不可见（scope 隔离由检索引擎保证）。

---

## 5. 可观测接入

每次提交按步骤写入 `tool_call_log`（经 `TraceLogStore` 端口）：

| tool | 时机 | input / output |
|---|---|---|
| `memory.extract` | 提取完成后 | input：消息条数；output：候选类型与内容列表 |
| `memory.decide` | 每条决策后 | input：候选内容；output：uri、decision、similarity |

- 记录 traceId（提交时传入，异步任务同样携带）、spanId、sessionId、agentId、latencyMs、status；
- `contextfs.memory.enabled=false` 时不产生 `memory.extract` / `memory.decide` 记录（仍归档）；
- 配合 `GET /api/v1/traces/{traceId}` 可回放一次提交中每条记忆的提取与决策依据。

---

## 6. 量化评测

### 6.1 评测方法

- 评测集：`MemoryExtractionEvalTest`（contextfs-memory）。
- 样本：15 条标注 user 消息（偏好 / 经验 / 事实 各 5 条）+ 5 条干扰消息（过短、无关键词、非 user 角色、空白）。
- 样本按规则设计避免跨类关键词碰撞：经验样本不含偏好关键词，事实样本不含偏好与经验关键词；逐条提取以规避每类 3 条的限流。

### 6.2 基线结果（规则式提取器）

| 指标 | 数值 |
|---|---|
| 分类准确率 | **1.00**（15/15） |
| 干扰样本忽略率 | **1.00**（5/5） |
| 分类型准确率 | 偏好 5/5，经验 5/5，事实 5/5 |

> 说明：规则式提取在规则覆盖语料上接近全对，这是分类器**下限**而非上限；真实会话的难点在于无关键词命中与语义歧义，属于 LLM 语义提取的演进目标。评测集的标注结构（期望类型 + 干扰样本）保留不变，接入 LLM 后直接复跑对比。

### 6.3 基线文件

每次运行输出 `contextfs-memory/target/memory-eval-accuracy.json`：

```json
{
  "labeledSamples": 15,
  "correct": 15,
  "accuracy": 1.0,
  "distractors": 5,
  "distractorsIgnored": 5,
  "perType": [ ... ],
  "perSample": [ ... ]
}
```

断言门槛：`accuracy >= 0.9`、干扰样本全部忽略，回归时自动校验。
