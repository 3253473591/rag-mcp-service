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
3. 匹配 `第[一二三四五六七八九十百]+[章节条]` 或 `^\d+(\.\d+)*\s`；
4. 都没有 → 视为无标题，fallback 到 FixedWindowChunker。

页码：PDFBox 按页提取，Block 带页号；跨页段落取起始页。

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
- 不把表格从中间切断；超长表格按行切，重复表头。

`HeadingAwareChunker`：

- 先按标题层级分节；
- 每节 ≤ 900 tokens 不切；
- 超过 900 → 递归切到 600/80；
- 每个 chunk 带 `clausePath`/`clauseId`；
- 标题本身不单独成块；
- 无标题 → fallback fixed。

**Token 口径**：jtokkit cl100k；中文按模型 token 近似，不承诺与 bge-m3 完全一致。README 写口径。

### 1.6 clauseId 提取

- 正则：`^(\d+(?:\.\d+)*)(?:\s|、|\.)`；
- 没有编号 → NULL；
- 不编造条款号。

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

- 命中：结果 `docKey` 相同，且 `clauseId` 相同（若 gold 有 clauseId）；没有 clauseId 时按 `pageNo` 落在 `pageRange`。
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
