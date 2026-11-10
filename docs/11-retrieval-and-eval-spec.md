# 检索与评测规格：切分、RRF、QA、指标

- 版本：v1
- 日期：2026-11-08
- 关联主设计：[00-design.md](00-design.md)
- 关联 PRD：[01-prd.md](01-prd.md)
- 关联数据模型：[03-data-model.md](03-data-model.md)
- 关联 API：[02-api-contract.md](02-api-contract.md)
- 关联测试：[06-test-plan.md](06-test-plan.md)

> 本文补上实现时最容易“靠猜”的部分：切分算法、RRF 公式、score 语义、QA schema、hit@k/MRR 口径、切分策略隔离。

---

## 1. 切分策略

### 1.1 统一 Block 模型

解析后先产出 Block：

```java
record Block(
    String text,
    int headingLevel,      // 0=正文，1~6=标题
    String clauseId,       // 可空，如 "3.2"
    String clausePath,     // "第三章 > 3.2"
    Integer pageNo,        // 1-based，可空
    boolean table
) {}
```

### 1.2 PDF 标题识别

按优先级：

1. PDF outline/bookmark（最可靠）；
2. 字号 > 正文中位数 1.2 倍且加粗；
3. 匹配 `第[空格]?[中文数字/阿拉伯数字/全角数字][空格]?[章节条]`，或经过清洗的 `^\d+(\.\d+)*\s`（数值/单位/表格碎片不成为标题）；
4. 都没有 → 视为无标题，fallback 到 FixedWindowChunker。

页码：PDFBox 按页提取，Block 带页号；跨页段落取起始页。PDF 双栏页面按
“页面 → 左栏完整读完后右栏”的顺序重排；找不到纵向栏间距的页面按普通自上而下处理。
表格仍可能被拆/交错，本版只承诺主正文阅读顺序与 heading 候选清洗。

### 1.3 DOCX 标题

- 使用 `Heading 1`~`Heading 6` 样式；
- 无样式但加粗/字号明显大于正文 → 降级识别；
- 表格整块保留，`table=true`。

### 1.4 Markdown 标题

- ATX `#`~`######`；
- 记录层级路径；
- 表格整块保留。

### 1.5 切分规则

`FixedWindowChunker`：

- 目标 600 tokens，overlap 80 tokens；
- 优先在段落边界切；段落超长再按句子切；
- 不把表格从中间切断；超长表格按行切，重复表头；
- Phase 6 起，每个 chunk 从覆盖的 heading 区间继承**主导 section** 的 `clauseId`/`clausePath`
  （按 token 数取最大贡献者），并记录 `[pageNo, pageEnd]` 页面跨度；窗口本身仍是 600/80。

`HeadingAwareChunker`：

- 先按标题层级分节；
- 每节 ≤ 900 tokens 不切；
- 超过 900 → 递归切到 600/80；
- 每个 chunk 带 `clausePath`/`clauseId`；
- 标题本身不单独成块；
- 无标题 → fallback fixed。

**Token 口径**：jtokkit cl100k；中文按模型 token 近似，不承诺与 bge-m3 完全一致。README 写口径。

### 1.6 clauseId 提取

- 数字编号：`^(\d+(?:\.\d+)*)(?=\s|、|\.|$)`，例如 `4.2.1`；
- 中文条款：`^第\s*([中文数字/阿拉伯数字/全角数字]+)\s*([章节条])`，归一化为 `第五条` / 第12条 / 第一章；
- 没有编号、或编号后紧跟 `mmHg`/`cm`/`min`/`d`/`g` 等计量单位 → NULL；
- 不编造条款号；正文中间出现的“第X条”不提取。

---

## 2. 切分策略存储与对比

同一文档需要 fixed/heading 两套 chunk 共存，否则 AC-03/AC-09 冲突。采用：

```sql
chunk_strategy TEXT NOT NULL,          -- fixed / heading
UNIQUE (document_id, chunk_strategy, chunk_index)
```

- 上传参数 `chunkStrategies`：默认 `["heading"]`；评测时传 `["fixed","heading"]`。
- 一次入库生成两套 chunk 和 embedding；3 篇文档成本可接受。
- 检索参数 `chunkStrategy`：默认 `heading`；评测分别传 `fixed`/`heading`。
- 不要把 `chunkStrategy` 和 `strategy`（hybrid/vector/keyword）混用。

---

## 3. 检索

### 3.1 向量

```sql
SELECT c.id, c.document_id, c.content, c.clause_path, c.page_no,
       d.version, d.effective_from, d.effective_to, d.status,
       1 - (c.embedding <=> ?::vector) AS vector_score
FROM kb_chunk c
JOIN kb_document d ON d.id = c.document_id
WHERE c.chunk_strategy = ?
  AND d.status = 'ACTIVE'
  AND d.effective_from <= ?::date
  AND (d.effective_to IS NULL OR ?::date <= d.effective_to)
ORDER BY c.embedding <=> ?::vector
LIMIT ?;
```

### 3.2 关键词

不要直接 `ILIKE '%' || ? || '%'`，用户输入里的 `%`/`_` 会被当通配符；也不要改用 `position()/strpos()`，那会让 `gin_trgm_ops` 索引失效、退化成全表扫描。

正确做法：Java 侧先转义，再拼 pattern：

```java
String escaped = q.replace("\\", "\\\\")
                   .replace("%", "\\%")
                   .replace("_", "\\_");
String pattern = "%" + escaped + "%";
```

```sql
WHERE c.content ILIKE ? ESCAPE '\'
```

`ILIKE` 能走 `gin_trgm_ops`；`similarity(c.content, ?)` 仍可用于排序。query > 200 字符时，关键词分支只取前 200 字符或抽关键词，避免 trigram 退化。

#### 3.2.1 词项拆分（Phase 8）

**原实现有 bug**：匹配用的是**整句** `ILIKE '%问题原文%'`。中文自然语言问句（例如 `高血压的诊断标准是什么？`）不会逐字出现在任何 chunk 里，实测 `eval/qa.jsonl` 40 条 **全部 0 命中**，`strategy=hybrid` 实际退化成单路向量检索。

现在先抽词项再 OR 匹配（`KeywordTerms`）：

- 去掉疑问词（是什么/有哪些/如何/多少/的/吗 …）、按标点与「的/是/和/与/在/为」等虚词切分；
- 只留 CJK ≥2 字、ASCII ≥3 字且不在英文停用词的片段，最长 12 字，去重后最多 `app.search.keyword-max-terms`（默认 8）个；
- 额外保留一条「去掉疑问词后的整句」，用于命中原文短语；
- 每个词项单独转义后拼成 `(content ILIKE ? ESCAPE '\' OR ...)`，排序按 `term_hits DESC, similarity DESC`。

覆盖率：35/40（未命中的 5 条正好是 4 条不可回答题 + Q24）。

**融合策略**（`app.search.keyword-min-term-hits`、`keyword-weight`）：单个泛词（「标准」「要求」）会命中几十个 chunk，等权进 RRF 会把正确的向量首条挤下去。因此 hybrid 要求 chunk **至少命中 2 个不同词项**（显式 `strategy=keyword` 仍为 1）。实测取舍见 [docs/acceptance/phase8-keyword.md](acceptance/phase8-keyword.md)：`hit@5`/`hit@3` 不变，`hit@1` 0.8286 → 0.7714（2 道题降为第 2 名），`KEYWORD_WEIGHT=0.01` 可完全回到向量排序。

### 3.3 RRF

```text
score(d) = Σ_i 1 / (k + rank_i(d)),  k = 60
rank_i 从 1 开始
```

- 两路：vector、keyword；各取 top 30。
- 按 `chunkId` 去重；同一 chunk 在另一路没有排名则不贡献。
- 排序：score 降序；同分按 `docId + chunkStrategy + chunkIndex` 稳定排序。
- 返回 `score` 是**原始 RRF 分**，典型值约 `0.016~0.033`，不是 0-1 概率。
- 如果 UI 需要 0~1，可另返回 `normalizedScore`，但不要混用两个字段。

> 之前 API 示例写的 `score: 0.83` 是错的；已改为 RRF 原始分量级。

### 3.4 可拒答的检索守卫（Phase 7）

`score` 是 RRF 名次分（top-1 恒为 `1/61`），不能用于相关性阈值。守卫改用 `SearchCandidate.routeScore()`：
它在向量路是余弦相似度 `1 - (c.embedding <=> ?)`，在 keyword 路是 trigram 相似度。
**判定只使用向量路的余弦值**；`strategy=keyword` 时没有余弦，守卫保持中性（空结果仍走系统文案兜底）。

判定是服务端的确定性函数，输入候选余弦列表，输出 `NONE / NO_MATCH / TOO_NOISY`，不经过模型或 prompt：

```text
top1   = 最大余弦（无候选时为 0）
top2   = 第二大余弦（不足两个候选时为 0）
dense  = 余弦 >= minTopScore 的候选数

NO_MATCH  : 候选为空，或 top1 < minTopScore
TOO_NOISY : top1 >= minTopScore 且 top1 < highConfidenceTopScore
            且 dense >= minDenseCandidates 且 (top1 - top2) < minScoreGap
NONE      : 其余情况
```

两条原因不是同一个分支：

- `NO_MATCH` 回答“知识库里没有足够相关的资料”（绝对下限）；
- `TOO_NOISY` 回答“高分候选很多且彼此差距很小，排序无法判断”（相对区分度/噪声）。

配置（`app.search.abstain.*`，默认值来自 `eval/qa.jsonl` 标定）：

| 配置 | 默认 | 说明 |
|---|---:|---|
| `enabled` | `true` | 关闭后不按分数拒答；空结果仍必须有系统文案 |
| `min-top-score` | `0.55` | top-1 余弦绝对下限 |
| `high-confidence-top-score` | `0.63` | 灰色区上界；达到即视为 top-1 足够自信 |
| `min-dense-candidates` | `8` | 触发 `TOO_NOISY` 所需的高分候选数 |
| `min-score-gap` | `0.02` | 触发 `TOO_NOISY` 的 top1-top2 最大差距 |

约定：

- 拒答时 `results` 必须为空数组，不把低质量候选当结果返回；
- 响应必须带 `abstained: true`、`abstainReason`、非空 `message` 和可审计 `diagnostics`
  （至少 `topScore`、`candidateCount`、`reason`，另含 `secondScore`、`scoreGap`、`denseCount`、
  阈值快照与降序 `candidateScores`）；
- `message` 是系统常量，不允许模型改写；
- 拒答默认开启，不新增请求参数来触发/关闭；调用方无法通过 prompt 或 tool description 影响判定；
- 任何响应都不允许出现 `results` 为空且 `message` 为空；`enabled=false` 时也同样兜底。

标定与验收：阈值扫描、两条原因的实测 JSON、前后指标和剩余误报见
[docs/acceptance/phase7-abstain.md](acceptance/phase7-abstain.md)。标定脚本：
`scripts/calibrate-abstain.ps1`（需先以 `ABSTAIN_ENABLED=false` 启动应用采集原始余弦）。

---

## 4. 评测集

### 4.1 `eval/qa.jsonl`

```json
{"id":"Q01","question":"高血压的诊断标准是什么？","answerable":true,
 "gold":{"docKey":"hypertension-guideline","version":"2025","clauseId":"3.2","pageRange":[12,13]}}
{"id":"Q41","question":"公司年假有多少天？","answerable":false}
```

字段：

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | 是 | 唯一 |
| `question` | 是 | 问题 |
| `answerable` | 是 | 知识库是否能回答 |
| `gold.docKey` | 可回答时必填 | 稳定文档标识 |
| `gold.version` | 否 | 版本 |
| `gold.clauseId` | 否 | 条款号；优先用它匹配 |
| `gold.pageRange` | 否 | `[start,end]`，无 clauseId 时用 |

40 条：35 条可回答 + 5 条不可回答；至少覆盖 3 篇文档。

### 4.2 匹配口径

- 命中：结果 `docKey` 相同，且 `clauseId` 相同（若 gold 有 clauseId）；没有 clauseId 时按
  chunk 的 `[pageNo, pageEnd]` 与 gold `pageRange` **相交**判断（`pageEnd` 缺省等于 `pageNo`）。
- 不同 clauseId 仍是 miss，即使页码重叠；不能靠“同页就算命中”放宽。
- 只看 topK 的文档/条款，不要求 exact chunk id。
- 同一文档多个 chunk 只算一次。

### 4.3 指标

对可回答题：

```text
hit@k = 1[topK 中存在命中] 的均值
MRR   = 第一条命中结果的 1/rank 的均值；未命中计 0
```

对不可回答题（单独统计，不混入 hit@k）：

```text
falsePositiveRate = 返回结果数 > 0 的比例
noAnswerAccuracy  = 结果为空的比例
```

Phase 7 起，拒答响应的 `results` 为空，因此不可回答题被拒答会自然计入“正确”，不需要改
`EvalService`/`EvalScorer` 的判定口径，也不能改 gold 或匹配规则来“制造”这个结果。对可回答题，
被拒答按 miss（rank 0）计入 hit@k/MRR；标定要求 answerable 的 hit@5 不低于 0.8857、MRR 下降不超过
0.01，达不到时优先牺牲拒答率，不能牺牲可回答题召回。当前实测见
[docs/acceptance/phase7-abstain.md](acceptance/phase7-abstain.md)。

失败/超时：

- 单独计数 `errors`，不能当 miss，也不能丢弃；
- 报告中列 `answered`、`unanswered`、`errors`。

### 4.4 对比

- 同一 QA 集、同一 embedding 模型、同一 topK。
- 分别跑 `chunkStrategy=fixed` / `heading`。
- 输出 `eval/report.md`：

```markdown
# RAG Eval Report
- dataset: eval/qa.jsonl (sha256=...)
- embedding: bge-m3
- chunk strategies: fixed, heading
- topK: 5

| chunkStrategy | hit@1 | hit@3 | hit@5 | MRR | p50 | p95 | errors |
|---|---:|---:|---:|---:|---:|---:|---:|
| fixed | | | | | | | |
| heading | | | | | | | |
```

### 4.5 可复现字段

`eval_run` 表包含：

- `dataset_hash`
- `chunk_strategy`
- `embedding_model`
- `git_commit`
- `top_k`

这样才能回答“这个数字是哪版代码/哪版数据跑出来的”。

---

## 5. 评测执行

```bash
# Linux/macOS/Git Bash
./scripts/eval.sh

# Windows PowerShell
./scripts/eval.ps1
```

流程：

1. 清空/使用独立 schema（避免污染演示库）；
2. 用 `chunkStrategies=["fixed","heading"]` 入库 3 篇文档；
3. 对每个 strategy 跑 40 条 QA；
4. 输出 `eval/report.md` + JSON；
5. 退出码非 0 表示有错误/指标未达标。

> v1 不持久化原始 PDF/Block；评测用独立 schema 重新上传。生产化再加 `kb_block`/原始文件存储。

---

## 6. 已知限制

- 中文关键词用 pg_trgm，不是专业分词；如实报告。
- 表格/多栏 PDF 解析可能不完美；样本文档要选文本型 PDF。
- RRF 是启发式融合，不保证优于单路；用评测证明。
- 语义切分不在 P0；P1 也不默认启用。
