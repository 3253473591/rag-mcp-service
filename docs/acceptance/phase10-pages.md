# Phase 10 验收报告：页面（page）粒度 vs 块（chunk）粒度检索 A/B

- 生成时间：2026-11-10 22:00:41
- 分支/提交：`f1304dc00a70427083229681ee6b191e210207e4` (dirty=True)
- 数据集：`eval/qa.jsonl` (sha256=1487e261b728debd22a876d31016f376efeb40932d8888d45d2d15b91a15af4c)，40 条（35 可回答 + 5 不可回答）
- 运行参数：topK=5，默认 asOf=2026-11-09，includeSuperseded=False，chunkStrategy=heading
- 两路请求：chunk = `POST /api/v1/search`（hybrid + RRF + rerank，`chunkStrategy=heading`）；page = `POST /api/v1/pages/search`（页面向量路，余弦）
- 数据集、gold、asOf、topK 完全相同；本脚本不修改 `eval/qa.jsonl` 或任何匹配规则。

## 1. 匹配口径（两种页面口径并列，不挑有利的）

| 路线 | 口径 | 规则 |
|---|---|---|
| chunk | eval.ps1 原规则 | `docKey` 相同，且 `clauseId == gold.clauseId`；结果无 `clauseId` 时用页码相交；不同 `clauseId` 视为未命中 |
| page | **loose** | `gold.clauseId ∈ page.clauseIds` **或** 页面页码区间与 `gold.pageRange` 相交 |
| page | **strict** | 页面自身叶子标题对应的 `leafClauseId == gold.clauseId`；仅当 gold 无 `clauseId` 时退化为页码相交 |

**strict 比 loose 严格。** 页面天然覆盖更多 clause，loose 的"页面里恰好包含 gold clause"或"页码相交"都会命中，
因此 loose 容易虚高；strict 要求页面自己的叶子 clause 就是 gold clause，不允许靠包含关系得分。
两套口径使用同一批响应与同一批题目，下面并列展示。

## 2. 页面统计（来自 `GET /api/v1/admin/pages/stats`）

| docKey | version | status | 页面数（每文档） | 叶子小节数 | 平均 token | 最大 token |
|---|---|---|---:|---:|---:|---:|
| hypertension-guideline | 2024 | SUPERSEDED | 6 | 6 | 248.2 | 584 |
| hypertension-guideline | 2025 | ACTIVE | 70 | 56 | 519.0 | 1200 |
| enterprise-internal-control | 2026 | ACTIVE | 49 | 49 | 117.4 | 706 |
| **合计** | | | **125** | **111** | **348.6** | **1200** |

说明：页面数按行统计（`part` 切分后每行算一页）；叶子小节数是切分前的 `page_key` 数；平均 token = 总 token / 页面数。数据集共 3 篇文档、125 条页面。

## 3. 四指标（同一 asOf、topK=5、同一 40 题）

| 路线 | 口径 | 可回答题数 | hit@1 | hit@3 | hit@5 | MRR | p50(ms) | p95(ms) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| chunk | eval.ps1 原规则 | 35 | 0.8286 | 0.9429 | 0.9714 | 0.8819 | 23 | 34 |
| chunk（**诊断用**：vector only） | eval.ps1 原规则 | 35 | 0.8286 | 0.9429 | 0.9714 | 0.8771 | 22 | 25 |
| page | loose | 35 | 0.8857 | 0.9714 | 1 | 0.9343 | 18 | 20 |
| page | **strict** | 35 | 0.7429 | 0.9143 | 0.9714 | 0.8367 | 18 | 20 |

差值（page − chunk）：loose hit@1 0.0571 / hit@3 0.0285 / hit@5 0.0286 / MRR 0.0524；strict hit@1 -0.0857 / hit@3 -0.0286 / hit@5 0.0000 / MRR -0.0452。

补充（不是主 A/B 行）：chunk 只走向量路时 hit@1=0.8286 / hit@3=0.9429 / hit@5=0.9714 / MRR=0.8771。
页面路由是纯向量路，主 chunk 行是 hybrid+RRF+rerank，所以 page−chunk 的差值同时包含"粒度"与"检索管线"两个因素；vector-only 这一行用于把粒度因素单独看出来。

## 4. 逐题差异（只列与 chunk 不同的题）

| 题 | gold | chunk 名次 | page loose 名次 | page strict 名次 | loose vs chunk | strict vs chunk |
|---|---|---:|---:|---:|---|---|
| Q04 | gold clauseId=4.2.2 | 1 | 1 | 3 |  | 下降 1 -> 3 |
| Q05 | gold clauseId=4.2 | 0 | 2 | 0 | 新增命中 (0 -> 2) |  |
| Q11 | gold clauseId=5.4.2 | 3 | 2 | 2 | 提升 3 -> 2 | 提升 3 -> 2 |
| Q13 | gold clauseId=5.4.6.1 | 2 | 1 | 2 | 提升 2 -> 1 |  |
| Q15 | gold clauseId=8.2 | 2 | 1 | 1 | 提升 2 -> 1 | 提升 2 -> 1 |
| Q21 | gold 无 clauseId，strict 退化为页码相交 | 5 | 1 | 1 | 提升 5 -> 1 | 提升 5 -> 1 |
| Q24 | gold 无 clauseId，strict 退化为页码相交 | 3 | 5 | 5 | 下降 3 -> 5 | 下降 3 -> 5 |
| Q29 | gold clauseId=4.2.1 | 1 | 1 | 2 |  | 下降 1 -> 2 |
| Q31 | gold clauseId=4.2.1 | 1 | 1 | 2 |  | 下降 1 -> 2 |
| Q32 | gold clauseId=4.2.2 | 1 | 1 | 4 |  | 下降 1 -> 4 |
| Q33 | gold clauseId=5.2 | 1 | 2 | 2 | 下降 1 -> 2 | 下降 1 -> 2 |

汇总：loose 更好 5 题、更差 2 题；strict 更好 3 题、更差 6 题；loose 命中但 strict 未命中的题：Q05。

### 4.1 loose 虚高（loose 命中、strict 未命中）明细

| 题 | gold | loose 名次 | strict 名次 | loose 命中页自身 leafClauseId | loose 命中页 clauseIds | loose 命中页页码 |
|---|---|---:|---:|---|---|---|
| Q05 | gold clauseId=4.2 | 2 | 0 | 4.2.2 | 4.2.2 | 4 |

## 5. 不可回答题（单独报告，不计入 hit@k）

- chunk 路：4/5 题被拒答（abstain），其中可回答题被误拒答 0 题。
- page 路：5/5 题返回了非空页面结果。页面端点**没有拒答守卫**，这是刻意的：本阶段只回答"页面粒度检索是否更好"，
  不把页面层接入主检索，也不改动 Phase 7 的拒答契约。页面路由一旦产品化，必须补同样的拒答策略。

## 6. 自动结论（数据事实）

- chunk（heading，hybrid+RRF+rerank）：hit@1=0.8286、hit@3=0.9429、hit@5=0.9714、MRR=0.8819。
- page（loose）：hit@1=0.8857、hit@3=0.9714、hit@5=1、MRR=0.9343。
- page（strict）：hit@1=0.7429、hit@3=0.9143、hit@5=0.9714、MRR=0.8367。
- loose 口径下页面至少有一项指标高于 chunk；这不等于页面粒度更好，见 4.1 的包含/页码相交虚高。
- **strict 口径下页面明显更差**：hit@5 与/或 MRR 低于 chunk。按验收要求如实报告，不做美化；原因分析见第 7 节。

`MANUAL:START` 之后为人工分析。重跑本脚本时，只要该标记仍在旧报告里，旧的人工分析会被原样保留。

<!-- MANUAL:START -->
## 7. 结论、原因分析与风险

### 7.1 结论

**页面粒度检索没有比 chunk 粒度更好。** 在 strict（页面自身叶子 clause == gold clause）口径下：

| 指标 | chunk（产品链路） | chunk（vector only，诊断） | page（loose） | page（strict） |
|---|---:|---:|---:|---:|
| hit@1 | 0.8286 | 0.8286 | 0.8857 | **0.7429** |
| hit@3 | 0.9429 | 0.9429 | 0.9714 | **0.9143** |
| hit@5 | 0.9714 | 0.9714 | 1.0000 | 0.9714 |
| MRR | 0.8819 | 0.8771 | 0.9343 | **0.8367** |

- strict 相对产品 chunk 路：hit@1 **−0.0857**（29/35 → 26/35，即 3 题）、hit@3 −0.0286、hit@5 0、MRR −0.0452。
- loose 的三项提升不能当成"页面更好"：Q05 的 loose 命中是**页码巧合**（gold 4.2，命中的页面是 4.2.2，仅因为都在第 4 页），Q29/Q31/Q32 的 loose 第 1 名是**无 clause id 的"测试用摘编"页**（第 1 页）。剔除这些几何命中后，strict 才是页面粒度的真实上限。
- 补充诊断行（chunk 只走向量路）与产品 chunk 路的 hit@1/3/5 完全相同、MRR 只差 0.0048。**所以 strict 的差距不是"页面路由少了 hybrid+RRF+rerank"造成的，而是页面这个检索单元本身**。

### 7.2 strict 变差的原因（按证据强弱排序）

1. **页面 embedding 的文本不等于 chunk embedding 的文本。** 页面文本按设计是 `title + "\n" + body`，chunk 只有 body。2024/2026 两篇文档的页面与 chunk 是 1:1（2024 平均 248.2 token/页 = chunk 平均 248.2；2026 117.4 = 117.4），页面没有任何"聚合更多内容"的优势，但排序仍然变了：Q29/Q31/Q32 的 chunk vector-only 名次都是 1，page strict 却是 2/2/4；企业文档 Q21 从 chunk 第 5 升到第 1，Q24 从第 3 掉到第 5。同一批正文、只改 embedding 文本，说明**标题前缀本身就会重排**，方向取决于标题与 query 的匹配，不是稳定的收益。
2. **长叶小节把答案"稀释"了。** 2025 文档 91 个 heading chunk 聚成 56 个叶子页，再切成 70 行；页面平均 519.0 token（chunk 平均 399.2），最大 1200。Q04（gold 4.2.2 诊断流程）的 4.2.2 页排第 3，被 4.3 初诊评估（0.7274）和 9.1 筛查方式（0.7108）压过（4.2.2 为 0.7081）；Q32 的 4.2.2 页排第 4。一个 625 token 的页面向量要同时代表"诊断流程"和该节其他内容，定位精度必然低于更小的 chunk。
3. **无 clause id 的"hub 页"占用 top5 名额，却永远无法 strict 命中。** 125 页里有 4 页 `clause_ids` 为空：2024 的"测试用摘编"（307 token）、2025 的 `__root__` 两段（1111+1092 token）、2026 的文档首页（706 token）。2024 的这页在 Q29/Q31/Q32 里排第 1（loose 靠第 1 页页码相交命中，strict 正确忽略它）。这正是页面层额外引入的"整篇/前言"粒度，与"更可读的中文档单元"目标是矛盾的。
4. **父小节自身正文很小。** 4.2 页只有 56 token（内容主要在 4.2.1/4.2.2/4.2.3 子节），Q05 的 gold 是父节 4.2：chunk 与 page strict 都进不了 top5。页面层没有修复"父节问题"，因为聚合键是叶子 `clause_path`，父节与子节本来就是不同的页。

**需要澄清：本次实现并没有"把父子小节合并"，所以"合并导致 clause 粒度丢失"这个假设不是本次 strict 变差的原因。** 证据：121/125 页的 `clauseIds` 恰好只有 1 个元素，4 页为空，**0 页包含 2 个及以上 clause**；分组键就是叶子 `clause_path`（4.2 与 4.2.1/4.2.2/4.2.3 是四页）。真正的损失来自"一个叶子页 = 该叶子的全部正文"的**节内稀释**、标题前缀重排、以及 hub/root 页。

### 7.3 loose 的虚高在哪里

| 题 | gold | loose 命中 | 实际情况 |
|---|---|---|---|
| Q05 | 4.2 | 第 2 名 | 命中页是 **4.2.2**（clauseIds 只有 4.2.2），因为 pageRange 都是第 4 页；leaf 不等于 gold |
| Q29/Q31/Q32 | 4.2.1 / 4.2.2 | 第 1 名 | 命中页是 2024 的"测试用摘编"（无 clause id），靠第 1 页相交；strict 正确地把它排除，正确页在 2/2/4 名 |

loose 也不是 chunk 命中的超集：Q24、Q33 在 loose 下反而比 chunk 差。它只是换了一套更宽松的几何规则，不能单独用来证明页面更好。

### 7.4 页面确实更好的题（如实记录）

- Q15（8.2 随访内容）：chunk 第 2 → page 第 1（509 token 的页面）。
- Q11（5.4.2 降压药物选择，2 段）：chunk 第 3 → page 第 2。
- Q21（企业文档，gold 无 clauseId，按页码）：chunk 第 5 → page 第 1。
- Q13：loose 第 1 是**5.4.6.2**（与 gold 5.4.6.1 同在 pageRange [9,9]，靠页码相交命中），strict 与 chunk 一样是第 2；不能算页面收益。

35 题里 strict 更好 3 题、更差 6 题，净 −3 题。有 1 题的波动幅度（2.86pp）与 40 题样本的随机波动同量级，因此结论应表述为"**没有证据支持页面更好；strict 下指标更差，且变差方向在 hit@1/hit@3/MRR 上一致**"。

### 7.5 关键风险

1. **样本与显著性**：35 道可回答题，1 题 = 2.86pp；hit@1 的差距是 3 题。方向一致但不足以做统计推断，换语料/换 embedding 必须重跑。
2. **页面路由是"半成品"**：没有拒答守卫（5/5 不可回答题都返回非空页面）、没有关键词路与 rerank。本阶段刻意不接入主检索；如果直接产品化，会同时失去 chunk 路的拒答与词法救回能力。
3. **上传热路径**：页面聚合在文档事务提交后**同步**执行（best effort，失败只记日志）。本机重建 125 页耗时约 6s；单篇文档上传会额外承担该文档全部页面的 embedding。失败时上传成功但页面缺失/陈旧，只能靠 `POST /api/v1/admin/pages/rebuild` 修复，当前没有 staleness 指标。后续应改为异步任务/outbox。
4. **页面单元不均匀**：2024/2026 的页面与 chunk 是 1:1（没有聚合），2025 才有真正的多 chunk 聚合与 14 个 part。所谓 A/B 实际上是"页面 vs chunk"和"仅标题前缀差异"的混合，这也是 7.2 第 1 条能成立的原因。更干净的实验需要强制聚合（例如把同级兄弟小节合并到最小 token 数）并单独消融标题前缀。
5. **part 切分的副作用**：同一 section 切多段后共享 `leafClauseId`，strict 会把任一段算作命中；10.2 节切成 6 段，理论上可占满 top5 并挤掉别的 section（本语料未观测到，但结构上存在）。同时 14 个 part 行让"页面数"统计偏高。
6. **依赖上游 heading 质量**：只有 `chunk_strategy=heading` 的 chunk 才产生页面；只有 fixed 策略的文档页面数为 0。标题无法抽出 clause id 的合法小节（"测试用摘编"、企业文档首页）永远无法 strict 命中，只能 loose/页码命中。
7. **文件名污染**：root 页的 title 取文档标题，本语料里就是文件名（`hypertension-2025`），embedding 文本中出现 "2025" 这类非正文 token，可能影响"2024 版指南"类查询。该项未单独量化，列为风险。

### 7.6 建议

- **不要把页面层作为主检索单元**。若需要"可读的中文档粒度"，更稳的做法是：保留现有 chunk 检索与排序，命中后把 chunk 扩展到其 `page_key` 页面作为展示/引用上下文（retrieval 仍是 chunk 粒度，页面只做阅读层）。
- 如果还要继续研究页面检索：① 去掉文档标题前缀或对标题单独加权；② 对无 clause id 的 hub/root 页降权或排除；③ part 去重后再排序；④ 补 keyword/hybrid 与拒答守卫；⑤ 在更大的 QA 集上同时报告 loose/strict，并单独做"标题前缀"和"max-tokens"消融。
- 当前 `/api/v1/pages/search` 保持只读 A/B 用途；`/api/v1/search` 契约与排序未改动。

### 7.7 复现

```powershell
# 1) 启动（ADMIN_TOKEN 用于重建与页面统计）
$env:ADMIN_TOKEN='e2e-admin-token'
mvn -o spring-boot:run

# 2) 对已有文档重建页面（幂等：先删后插）
curl.exe -s -X POST http://127.0.0.1:8080/api/v1/admin/pages/rebuild `
  -H "X-Admin-Token: e2e-admin-token" -H "Content-Type: application/json" -d "{}"

# 3) A/B（chunk vs page，loose+strict 并列；数据写入 docs/acceptance/phase10-pages.{md,json}）
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\eval-pages.ps1 -AsOf 2026-11-09

# 4) 全量测试
mvn -o test
```

数据文件：`docs/acceptance/phase10-pages.json`（逐题 rank、页面统计、四指标）；`eval/qa.jsonl` 未改动（sha256 见报告头）。
chunk 侧数字与 Phase 9 基线（0.8286/0.9429/0.9714/0.8819）逐位一致，说明 harness 与 gold 匹配规则没有被本次改动影响。

### 7.8 实现与测试记录

- 迁移是 **`V3__add_kb_page.sql`**：`V2` 已被 Phase 6 的 `add_chunk_page_end` 占用，不能重号。`kb_page` 在给定 DDL 基础上增加了 `leaf_clause_id`（strict 口径直接读它，避免在 evaluator 里重复解析路径）。
- 实现：`PageAggregator`（纯函数，无 LLM/无数据库）、`PageIngestionService`（上传后 best effort + rebuild 幂等）、`PageRepository`（向量路 + 统计）、`PageSearchService`、`PageController`、`PageAdminController`；`DocumentFilterSql` 从 `SearchRepository` 抽出，保证 chunk/page 两条路使用同一套 SQL 过滤（版本模式/生效期/version/docIds）。
- 全量 `mvn -o test`：**Tests run: 293, Failures: 0, Errors: 0, Skipped: 1**（基线 274 + 新增 19：`PageAggregatorTest` 7、`PageIngestionServiceTest` 3、`PageSearchIntegrationTest` 5、`PageRebuildIntegrationTest` 4）。
- 覆盖点：聚合（clause 分组/排序/标题去编号/超长按 chunk 切分/单块硬切/无 `clause_path` 的 `__root__`）、页面检索版本三种模式与 docIds/version 过滤（SQL 层）、rebuild 幂等（含清理 stale 行）、admin token fail-closed、上传钩子与失败只记日志、`SchemaIntegrationTest` 的 `kb_page` 表/列/索引/唯一约束/级联。
- 未改：`eval/qa.jsonl`、`eval.ps1` 的匹配函数、`POST /api/v1/search` 的响应契约与排序、Phase 7 拒答阈值。
