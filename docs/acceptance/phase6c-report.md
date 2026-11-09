# Phase 6c 验收报告：Q04 高血压诊断流程

- 基线：`main` @ `0c91218`（Phase 6b 验收后）
- 分支：`phase6c`
- 代码提交：`f436365`（本报告随后续文档提交）
- 范围：只修 Q04 的“高血压诊断流程”信息错误；不改 gold、QA 数量、双栏 layout、R2 fixed 口径、MCP/API 语义、Phase 6b batching/cache。
- 结论：**Q04 已闭环**。heading 与 fixed 均为 rank 1，top-1 指向 `4.2.2 诊断流程`，内容包含“当首诊诊室血压≥140/90 mmHg…明确诊断/转诊/白大衣/隐蔽性”。40 QA 无 hit@5 回归；heading hit@5 `0.9714`、MRR `0.8748`，fixed hit@5 `0.7429`、MRR `0.7143`。

## 1. 方法与泛化

改动集中在检索融合后的一个可解释小信号，不针对 Q04 的问题 id / docKey / clauseId 做特判：

1. `SectionTitleMatcher.normalizeQuestion()`：去掉标点、空白和问句噪声（`是什么/是多少/如何/哪些/多少/的/吗/呢/吧` 等），例如
   `高血压的诊断流程是什么？` → `高血压诊断流程`。
2. `SectionTitleMatcher.boost()`：只取候选 `clause_path` 的**叶子标题**（如 `4.2.2  诊断流程`），去掉编号后：
   - 标题长度必须在 3–20 字之间（过滤“总则”这类过短标题和企业 `第X条 …` 长条款文本）；
   - 只有该叶子标题**完整出现**在规范化 query 中才给 boost；
   - boost = `0.0005 × 标题字数`，上限 `0.003`；4 字标题即 `0.002`。
3. `RrfFusion` 用这个 boost 参与 top-K 截断**之前**的排序，因此原本在向量 top-K 边缘的 section 可以进入返回窗口；**但暴露的 `score` 仍是原始 RRF 分**（boost 只影响排序，不污染 API 契约）。3 参 `fuse()` 保留原行为（boost=0）。
4. `SearchService` 只把 `query.query()` 规范化一次，再传入 boost 函数；不新增 SQL、不改变两路召回、不改默认配置。

泛化性：
- 不依赖“诊断流程”这个具体词：任何“query 点名了某个 section 标题”的问题都会得到同一信号。本轮实际受益的还有 fixed Q01（`诊断标准` rank3→1）、heading Q33（`降压目标` rank2→1）、heading Q34（`随访频率` rank2→1）。
- 不匹配父标题和长条款文本，因此不会因为“高血压诊断标准”这类父路径把整章都抬起来；Q05、Q29–Q32、Q35 的 top-1 没有被这个信号改变。
- 没有硬编码 question id、docKey、clauseId；没有改 `eval/qa.jsonl`、gold、匹配规则或 QA 数量。

没有采用“关键词 query 规范化/OR 拆分”的原因：当前 keyword 分支是整句 `ILIKE`，自然问句几乎 0 命中；改成 term-level 会同时改变 40 题的排序，回归面大。本轮先用更窄、可解释的 heading 信号闭环 Q04，term-level keyword 留作后续独立项。

## 2. Q04 before / after

### heading（目标策略）

| 时点 | rank | top-1 | top-5（按名次） |
|---|---:|---|---|
| before (`0c91218`) | **0（miss）** | `4.3 初诊评估` | `4.3 初诊评估`、`9.1 筛查方式`、`2 基层高血压诊疗管理流程`、`3 诊疗关键点`、`4.2.1 诊断标准` |
| after (`phase6c`) | **1** | `4.2.2 诊断流程` | `4.2.2 诊断流程`、`4.3 初诊评估`、`9.1 筛查方式`、`2 基层高血压诊疗管理流程`、`3 诊疗关键点` |

after top-1：`clauseId=4.2.2`、`pageNo=4`、`pageEnd=4`，content 含 `诊断流程`、`明确诊断`、`白大衣`、`隐蔽性`；citation 为
`《hypertension-2025》v2025（2025-01-01 生效） > 4  高血压诊断与评估 > 4.2  高血压诊断标准 > 4.2.2  诊断流程 > 第4页`。

### fixed

| 时点 | rank | top-1 | top-5（按名次） |
|---|---:|---|---|
| before (`0c91218`) | 5 | `9.1 筛查方式` | 4.2.2 已在第 5 位（top-1 不是诊断流程） |
| after (`phase6c`) | **1** | `4.2.2 诊断流程` | `4.2.2 诊断流程`、`9.1 筛查方式`、`4.3 初诊评估`、`3 诊疗关键点`、`4.2.1 诊断标准` |

Q04 的 fixed 原本已 hit@5，本轮提升到 rank 1；不是靠改 pageRange/fixed 逻辑，而是同一个 heading 信号。

## 3. 40 QA 指标（topK=5，2026-11-09）

| chunkStrategy | 时点 | hit@1 | hit@3 | hit@5 | MRR | p50(ms) | p95(ms) | errors |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| fixed | before | 0.6571 | 0.7143 | 0.7429 | 0.6867 | 98 | 306 | 0 |
| fixed | **after** | **0.6857** | **0.7429** | **0.7429** | **0.7143** | 74 | 248 | 0 |
| heading | before | 0.7714 | 0.8857 | 0.9429 | 0.8319 | 23 | 28 | 0 |
| heading | **after** | **0.8286** | **0.9143** | **0.9714** | **0.8748** | 24 | 28 | 0 |

- heading hit@5 `0.9714` = **34/35**，达到“最好 0.9714”目标；MRR `0.8748` 高于基线 `0.8319`。
- fixed hit@5 保持 `0.7429`；MRR 从 `0.6867` 升到 `0.7143`。
- unanswerable 指标未变：falsePositiveRate=1、noAnswerAccuracy=0（Phase 1–6 的既有口径，不在本任务范围）。

逐题 rank 变化（其余题不变）：

| strategy | id | before → after | 说明 |
|---|---|---:|---|
| heading | Q04 | 0 → 1 | 目标修复 |
| heading | Q12 | 1 → 2 | 仍 hit@5；top-1 变为父节 `5.4.3 药物治疗方案` |
| heading | Q33 | 2 → 1 | `降压目标` 叶子标题命中 |
| heading | Q34 | 2 → 1 | `随访频率` 叶子标题命中 |
| fixed | Q01 | 3 → 1 | `诊断标准` 叶子标题命中 |
| fixed | Q04 | 5 → 1 | 目标修复 |
| fixed | Q12 | 1 → 2 | 与 heading 同因 |

没有从 hit@5 掉出的题；Q05 的 heading top-1 仍是 `5.4.7 特殊情况的处理`，内容仍是白大衣/隐蔽性定义，未改变。fixed Q05 top-1 仍是 `4.2.3 血压分类`（Phase 6 既有现象，不是本轮回归；5.4.7 在其后）。

## 4. 测试与验证

| 项目 | 结果 |
|---|---|
| `mvn clean test` | **BUILD SUCCESS**；Tests run: **228**, Failures: 0, Errors: 0, Skipped: 1 |
| `McpIntegrationTest` | **14 / 0 / 0** |
| `SearchIntegrationTest` | 新增 Q04 集成断言后 9/0/0 |
| `SectionTitleMatcherTest` | 3/0/0（规范化、叶子标题 boost、长条款/短标题忽略） |
| `RrfFusionTest` | 5/0/0（boost 重排；暴露 score 保持 raw RRF） |
| `SearchServiceTest` | 7/0/0（新增 Q04-like 查询重排） |
| `validate-gold.ps1` | **35/35** locator 存在 |
| `eval/qa.jsonl` sha256 | `1487e261b728debd22a876d31016f376efeb40932d8888d45d2d15b91a15af4c`（未变） |
| 证据 | [phase6c-eval.json](phase6c-eval.json)、[phase6c-concurrency.json](phase6c-concurrency.json) |

### 10 并发 60s 冒烟

`scripts/perf-concurrency.ps1`（10 concurrency、60s、20k synthetic chunks、heading/hybrid）：

- requests `2887`，errors `0`，errorRate `0`
- server p50 `137ms`、p95 **`375ms`**、p99 `1043ms`、max `5740ms`
- client p50 `140.14ms`、p95 `388.65ms`、p99 `1631.34ms`、max `5743.25ms`

p95 远低于 800ms，未因新逻辑明显回退；p99/max 长尾与 Phase 6b 的残余一致（本任务不重新攻性能）。

## 5. 残余

1. **Q12 rank 1→2**：query 含 `药物治疗方案`，父节 `5.4.3` 的叶子标题命中并加 `0.003`，超过更具体的 `5.4.3.1`（rank 2，仍 hit@5）。这是本信号唯一的 ranking 回退；如需消除，需要层级感知（父/子标题同时命中时把 boost 给更具体子节），不在本轮范围。
2. **纯词面信号**：query 必须点名 section 标题（如“诊断流程”）才生效；同义改写（如“高血压怎么确诊”）不会获得 boost。后续可做受限同义词/术语扩展，但要单独跑 40 QA。
3. **Q05 fixed top-1 的 clause 标签仍是 4.2.3**：Phase 6 既有现象，本轮未改变；但其 chunk 内容包含“白大衣/隐蔽性高血压”的正确解释，按“信息正确”口径算过，clause 标签仍偏粗。heading 的 Q05 top-1 `5.4.7` 内容正确。
4. **keyword 分支仍是整句 ILIKE**：自然问句 keyword 0 命中，本轮未改，避免大范围排序变化。
5. **全文双栏顺序仍未重建**：R3 验收口径不要求全文顺序，只要求返回信息正确；Q04 已满足。
6. **性能长尾**：p99 1.04s、max 5.74s；p95 达标不等于长尾消失。

## 6. 是否建议合并

**建议合并**。理由：Q04 heading/fixed 均 rank 1 且内容正确；40 QA 无 hit@5 回归，heading 达到 34/35、MRR 提升；改动是一个 3 参数兼容的融合后 boost，无 gold/API/配置变更；全量测试、McpIntegrationTest、validate-gold、并发冒烟均通过。残余 Q12 1→2 与 R3 全文顺序问题已如实记录，不阻塞验收。

Q04 在 R3 中可标记为**已闭环**；R3 的“全文双栏顺序未重建”作为非验收项保留。

---

## 7. 验收方独立复核（2026-11-09）

| 项 | 结果 |
|---|---|
| `mvn clean test` | **228 / 0 / 0 / 1**，BUILD SUCCESS |
| Q04 heading | top-1 = `4.2.2 诊断流程`，第4页；content 含诊断流程/明确诊断/白大衣/隐蔽性 |
| Q04 fixed | top-1 = `4.2.2 诊断流程`；content 含 4.2.2 诊断流程正文 |
| 40 QA | fixed hit@5=0.7429/MRR=0.7143；heading hit@5=**0.9714 (34/35)**/MRR=0.8748；0 errors |
| gold | `validate-gold.ps1` 35/35；`eval/qa.jsonl` sha256 不变 |
| Q05 fixed | clause 标签 `4.2.3`，但 chunk 内容包含白大衣/隐蔽性正确解释；按信息正确口径算过，标签偏粗 |
| Q12 | heading rank 1→2，仍 hit@5；唯一 ranking 回退，已记录 |
| API score | **验收方修正**：boost 只参与排序，`score` 保持原始 RRF；`RrfFusionTest` 锁定 raw score，docs/02 契约不变 |
| 性能 | 实现方 10 并发 60s p95=375/388.65ms，errors=0；Phase 6b 的 6/6 结果不受 ranking 改动影响 |
| 结论 | **Q04 信息错误已闭环；R3 可关闭（Q12 rank2/Q05 fixed 标签为残余，不是信息错误）** |
