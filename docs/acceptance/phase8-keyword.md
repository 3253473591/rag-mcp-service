# Phase 8 验收报告：关键词路（词项拆分与融合策略）

- 日期：2026-11-10
- 分支：`feat/keyword-terms`（基于 `main` `37f1406`）
- 范围：`strategy=keyword` 路由修复、hybrid 融合精度、评测与文档

---

## 1. 问题：关键词路对自然语言问句 0 命中

原实现是整句模板匹配：

```sql
AND c.content ILIKE '%' || <问题原文> || '%'
```

`高血压的诊断标准是什么？` 这样的问句不会逐字出现在任何 chunk 里，于是关键词路恒定返回 0 行。

**实测（248 chunks，`eval/qa.jsonl` 40 条，直接对库执行同构 SQL）：**

| 指标 | 修复前 |
|---|---:|
| 关键词路命中 ≥1 条的题数 | **0 / 40** |
| 由此导致 `strategy=hybrid` | 退化为单路向量检索 |

这解释了 `docs/acceptance/phase2-smoke.md` 里 `S03_keyword` 返回 0 条的历史记录：不是数据问题，是设计问题。

---

## 2. 修复：抽词项 + OR 匹配

新增 `KeywordTerms`：

- 去疑问词（是什么/有哪些/如何/多少/请问/的/吗…），按标点与「的/是/和/与/在/为/以及」等虚词切分；
- 只保留 CJK ≥2 字、ASCII ≥3 字且不在英文停用词的片段；最长 12 字；去重保序；
- 末尾追加一条「去掉疑问词后的整句」，用于命中原文短语；
- 上限 `app.search.keyword-max-terms`（默认 8），每个词项单独 `KeywordEscaper.toPattern` 转义。

SQL 改为 OR 多个 pattern，并用 `term_hits`（命中几个不同词项）做第一排序键，`similarity` 次之：

```sql
SELECT * FROM (
  SELECT c.*, (CASE WHEN c.content ILIKE ? ESCAPE '\' THEN 1 ELSE 0 END + ...) AS term_hits,
         similarity(c.content, ?) AS route_score
  FROM kb_chunk c JOIN kb_document d ON d.id = c.document_id
  WHERE c.chunk_strategy = ? AND <原有版本/权限过滤>
    AND (c.content ILIKE ? ESCAPE '\' OR ...)
) t WHERE t.term_hits >= ?          -- hybrid=2, 显式 keyword=1
ORDER BY t.term_hits DESC, t.route_score DESC, ... LIMIT ?
```

**修复后关键词路覆盖率：35 / 40。** 未命中的 5 条正是 N01–N04（不可回答题，本来就该无词法命中）与 Q24。

---

## 3. 融合策略：为什么需要「至少命中 2 个词项」

词项 OR 匹配的副作用是**泛词泛滥**：「标准」「要求」「控制」这类词在公文语料里能命中几十个 chunk。
把关键词候选等权塞进 RRF 后，会出现这种事：

- 关键词 rank-1 与向量 rank-1 的 RRF 分**相等**（都是 `1/61`），同分靠 `docId` 排序 → 排序变成随机；
- RRF 相邻名次只差 `0.00026`，所以哪怕关键词权重 0.2（= 12 个名次）也足以把正确的向量首条挤下去。

### 3.1 权重扫描（heading，35 条可回答，min-term-hits=1）

| 关键词权重 | hit@1 | hit@3 | hit@5 | MRR |
|---|---:|---:|---:|---:|
| 0.0001（≈关闭） | **0.8286** | 0.9143 | **0.9714** | **0.8748** |
| 0.2 | 0.7429 | 0.9143 | 0.9429 | 0.8262 |
| 0.5 | 0.7429 | 0.8571 | 0.9429 | 0.82 |
| 1.0 | 0.7429 | 0.8571 | 0.9429 | 0.82 |

结论：**权重调不出甜点**。只要词项召回是脏的，任何能影响排序的权重都在伤 hit@1。

### 3.2 加「至少 2 个词项」过滤后（min-term-hits=2）

| 配置 | hit@1 | hit@3 | hit@5 | MRR |
|---|---:|---:|---:|---:|
| 权重 1.0 | 0.7714 | 0.9143 | **0.9714** | 0.851 |
| 权重 0.2 | 0.7714 | 0.9143 | **0.9714** | 0.851 |

`hit@5` / `hit@3` 回到基线，`hit@1` 仍低 0.0572。逐题差异只有 3 条：

| 题 | 基线 | 修复后 | 说明 |
|---|---:|---:|---|
| Q13 | 3 | **2** | 唯一改善 |
| Q15 | 1 | 2 | 降为第 2 名 |
| Q17 | 1 | 2 | 降为第 2 名 |

权重 0.2 与 1.0 结果完全一致，说明这些位移不是"权重过大"，而是"该 chunk 同时命中两个词项、向量排名也靠前"，属于语义排序与词法排序的真实分歧——只有 rerank 能裁决。

### 3.3 最终默认值

| 配置 | 默认 | 理由 |
|---|---:|---|
| `keyword-max-terms` | 8 | 控制 SQL 分支数与召回噪声 |
| `keyword-min-term-hits` | 2 | hybrid 至少两个概念命中；显式 keyword 仍为 1 |
| `keyword-weight` | 1.0 | 权重不敏感（0.2 与 1.0 同结果），保持等权语义简单 |

**取舍**：`hit@5`/`hit@3` 不变（RAG 实际喂给模型的就是 top-5），代价是 2 道题从第 1 名降到第 2 名。
如果要求零回归，设 `KEYWORD_WEIGHT=0.01` 即可完全回到向量排序（实测与 0.0001 一致）。

---

## 4. 前后对照（完整 40 条，fixed + heading）

| chunkStrategy | hit@1 | hit@3 | hit@5 | MRR | falsePositiveRate | answerableAbstained |
|---|---:|---:|---:|---:|---:|---:|
| fixed（前） | 0.6857 | 0.7429 | 0.7429 | 0.7143 | 0.2 | 0 |
| fixed（后） | 0.6857 | 0.7429 | 0.7429 | 0.7095 | 0.2 | 0 |
| heading（前） | 0.8286 | 0.9143 | 0.9714 | 0.8748 | 0.2 | 0 |
| heading（后） | 0.7714 | 0.9143 | 0.9714 | 0.851 | 0.2 | 0 |

Phase 7 的拒答守卫不受影响：`falsePositiveRate=0.2`、`answerableAbstained=0` 均未变（守卫只看向量余弦）。

---

## 5. 测试

- 新增 `KeywordTermsTest`（8 个用例）：中文切分、疑问词剔除、整句保留、英文停用词、
  `100%_` 原样保留、不可用查询、词项上限、确定性与去重。
- 新增集成测试 `SearchIntegrationTest.tIntSearch10_...`：自然语言问句 `差旅报销的时限是多少？`
  在语料中不存在原文，修复前必 0 条，现在必须命中。
- `mvn -o test`：`Tests run: 250, Failures: 0, Errors: 0, Skipped: 1`（新增 9 个用例后为 259）。

---

## 6. 已知限制

1. **不是分词器**：没有词典/POS，靠疑问词与虚词切分。遇到「有效」「对策」这类含虚词的真词会略微切坏，
   但由于是 OR 召回、且有整句兜底，影响限于个别题。
2. **2 字中文词走不了 trigram 索引**：实测 `EXPLAIN` 为 `Seq Scan`（248 chunks 下 0.39ms）。
   10 万级语料建议迁到 `tsvector`（需自建镜像装 `zhparser`/`pg_jieba`，当前 pgvector 镜像没有）。
3. **仍需 rerank**：`hit@1` 的分歧本质是语义排序 vs 词法排序，只有 cross-encoder 能裁决。
4. **排序键 `term_hits` 不进 API**：仅用于路由内排序，不改变对外 `score` 语义（仍是原始 RRF）。
