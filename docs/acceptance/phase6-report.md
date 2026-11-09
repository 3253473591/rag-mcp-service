# Phase 6 验收报告：R1-R4 收尾

- 日期：2026-11-09
- 分支：`phase6`（从 `main` @ `d490a7a` 创建；验收后合并 `main`）
- 实现 commit：`e14c96b69f4774f0cd19b14bcb1dab51ea7f8f29`
- 证据 commit：本报告所在 commit；`eval/report.*` 在该实现 commit 上以 `dirty=False` 重跑
- 基线：Phase 1–5 已验收；Phase 3 正式指标见 [phase3-report.md](phase3-report.md)
- 结论：**R4 已修；R2 显著改善（fixed hit@5 0.3429 → 0.7429，gold 未改，但仍有 page-only 粗匹配残余）；R3 部分修复（clausePath 噪声已清，content 仍可能交错）；R1 完成真实复测但 p95 仍未达标，保留 residual。**

---

## 1. 交付物

| 类型 | 位置 |
|---|---|
| 中文“第X条”识别 | `ClauseIds`、`HeadingCandidates`、`HeadingContext` |
| PDF 双栏/heading 清洗 | `PdfDocumentParser`、`HeadingCandidates` |
| `page_end` 迁移 | `V2__add_chunk_page_end.sql` |
| fixed 主导 section + 页跨度 | `FixedWindowChunker`、`HeadingAwareChunker`、`ChunkDraft`、`PreparedChunk` |
| API/MCP additive `pageEnd` | `SearchResultItem`、`SearchRepository`、`SearchService`、`docs/02` |
| 评测匹配 | `GoldMatcher`、`scripts/eval.ps1`、`scripts/validate-gold.ps1` |
| 测试 | `ClauseIdsTest`、`HeadingCandidatesTest`、`PdfDocumentParserTest`、`FixedWindowChunkerTest`、`HeadingAwareChunkerTest`、`GoldMatcherTest`、`DocumentUploadIntegrationTest`、`SchemaIntegrationTest` |
| 正式评测 | `eval/report.md`、`eval/report.json` |
| 并发证据 | [phase6-concurrency.json](phase6-concurrency.json) |

---

## 2. R4：企业 heading 的 clause_id

### Before

Phase 3 企业内控 11 条题全部只能按 `pageRange` 匹配；heading chunk 的 `clause_id` 为 NULL。原因：`ClauseIds` 只识别阿拉伯数字编号（`4.2.1`），不识别中文条款标题（`第五条`）；PDF 的 `CHAPTER` 正则也只允许中文数字且没有把条款号写入 clause_id。

### After

- `ClauseIds.extract` 新增 `第X章/节/条`：支持中文数字、阿拉伯数字、全角数字、任意空格；输出稳定可读的 `第五条`、`第12条`、`第一章`。
- `PdfDocumentParser` 的 `第X条` 识别是行首 anchored；正文中间出现的“第X条”不会成为 heading。
- `inferLevel`：章=1、节=2、条=3，企业 `clause_path` 现在带上级章，例如 `第一章 总 则 > 第五条 企业建立与实施有效的内部控制...`。
- 企业 heading 中除封面标题外，48/49 个 chunk 有 `clause_id`；唯一 NULL 是文档标题 chunk（不是条款）。
- DB 验证 SQL：

```sql
SELECT count(*) FILTER (WHERE clause_id IS NULL) AS missing_clause
FROM kb_chunk c JOIN kb_document d ON d.id = c.document_id
WHERE d.doc_key='enterprise-internal-control' AND c.chunk_strategy='heading';
-- 1（封面标题），所有“第X条”均为非空
```

- `TestDocuments.docxWithChineseArticles` + `DocxDocumentParserTest` 锁定 `第五条` → `clause_id=第五条`、`clause_path` 含上级章。

### Gold 决策

**企业 11 条 QA 的 gold 保持 `pageRange` 不变，`eval/qa.jsonl` 内容未改。** 理由：

1. R4 的目标是“heading chunk 的 clause_id 非空 + 可验证”，不要求改 QA；
2. 把 11 条 enterprise gold 改为 clauseId 需要逐条确认“问题答案文本确实落在该条”，否则会改变评测口径；本次只做了 5 条 citation 人工核对（见第 6 节），没有把 11 条全部升级为 clause 锚定；
3. 不为了指标放宽/收紧 gold；dataset hash 保持 Phase 3 的 `1487e261b728debd22a876d31016f376efeb40932d8888d45d2d15b91a15af4c`。

残余：Q18“内部控制目标”按 `pageRange=[2,2]` 命中，本轮 top-1 是 `第三条`（目标，正确）；但 page-only gold 仍可能让同页错误条款命中（例如 `第四条` 原则）。这是 page-only gold 的已知粗粒度，后续若做 dataset revision 应逐条升级。

---

## 3. R3：2025 双栏 clausePath 噪声

### Before

Phase 3 的 citation 章节段出现 `90 mmHg`、`3 d、至少`、`20 g、川牛膝` 等表格碎片（见 phase3-report 第 4 节 Q01）。根因：

1. `PDFTextStripper.setSortByPosition(true)` 按 y/x 做行主序排序，左右栏同 y 的行被交叉；
2. heading 判定只看“行首数字”，没有清洗数值/单位/表格碎片。

### After

- `PdfDocumentParser` 采集带坐标的 line segments，按页检测纵向栏间距（用左栏行尾/右栏行首的 90/10 分位，忽略跨栏表格行），跨栏线按 gutter 切开，整页按“左栏→右栏”输出；没有可靠 gutter 的页面保持单栏自上而下。
- `HeadingCandidates` 对 numeric heading 做清洗：
  - 拒绝 `90 mmHg`、`90 mmHg*`、`2.5 cm`、`3 d、至少`、`20 g、川牛膝`、`1 min`、`4 周内...`、`120 mmHg 和...`；
  - 拒绝 `1 4 mg qd`、`2 25 mg bid` 等剂量/表格行；
  - 拒绝三位数页眉（`842 中国循环杂志...`）；
  - 保留 `4.2.1 诊断标准`、`5.4.3.1 <80 岁...`、`5.4.6.1 血压≥ 180/110 mmHg...` 等真实标题。
- 当页面存在专用标题字体（2025 的 `FZXBSJW`/`XBS`）时，numbered heading 必须使用该字体；表格正文的数字行不再成为 heading。
- 真实 2025 PDF 抽样（5 条）：

| 抽样 | citation 章节段（after） | 核对 |
|---|---|---|
| Q01 | `4 高血压诊断与评估 > 4.2 高血压诊断标准 > 4.2.1 诊断标准 > 第4页` | 4.2.1 正文含 `≥140/90 mmHg` |
| Q08 | `5 高血压治疗 > 5.2 降压目标 > 第5页` | 5.2 含 `<80 岁 130/80`、`≥80 岁 150/90` |
| Q12 | `5.4.3 药物治疗方案 > 5.4.3.1 <80 岁、无合并症的高血压药物治疗方案 > 第8页` | 流程图/方案文字在 5.4.3.1 |
| Q13 | `5.4.6 血压≥ 180/110 mmHg 的紧急处理 > 5.4.6.1 ... > 第9页` | 不伴急性并发症处理 |
| Q18（enterprise） | `第一章 总 则 > 第三条 ... > 第2页` | 第三条为目标；page-only gold 仍可能命中同页错误条款，见 R4 残余 |

- 2025 heading chunk 中 `clause_path LIKE '%90 mmHg%'` / `%3 d%` / `%20 g%` 计数均为 **0**（Phase 6 DB，见第 6 节 SQL）。
- **不声称全文顺序已修**：表格/流程图仍可能跨栏拆散；Q05 等少数量题仍可能取到错误 section 的 chunk。R3 的交付边界是“clausePath 噪声 + 主正文/heading 阅读顺序”，不是“双栏表格 100% 还原”。

---

## 4. R2：fixed hit@5

### Before

Phase 3：fixed hit@5=0.3429、MRR=0.3214；fixed 无 clause 元数据，`page_no` 是窗口起始页，企业 fixed 18 个 chunk 几乎都从第 1 页起，page-only 题大量 miss。heading：hit@5=0.8857、MRR=0.6905。

### After

- Flyway `V2__add_chunk_page_end.sql`：`kb_chunk.page_end`（1-based，含），约束 `page_end >= page_no`；旧数据 NULL。
- `FixedWindowChunker` 保持 600/80 窗口语义，但：
  - 每 chunk 从覆盖区间继承**主导 section**（按 token 数最大）的 `clause_id`/`clause_path`；
  - 记录 `[page_no, page_end]`，overlap 窗口也带页跨度。
- `HeadingAwareChunker` section chunk 记录 `page_end`（跨页 section 不再是单页）。
- `SearchResultItem` additive `pageEnd`；SQL/HTTP/MCP/README/docs 同步。
- `GoldMatcher` / `eval.ps1`：无 clause 时用 `[pageNo,pageEnd]` 与 `pageRange` **相交**；不同 clauseId 仍是 miss，不能靠同页放宽。
- 正式 eval（40 条，gold 未改）：

| chunkStrategy | hit@1 | hit@3 | hit@5 | MRR | p50 | p95 | errors |
|---|---:|---:|---:|---:|---:|---:|---:|
| fixed Phase 3 | 0.3143 | 0.3143 | 0.3429 | 0.3214 | 51 | 106 | 0 |
| **fixed Phase 6** | **0.6571** | **0.7143** | **0.7429** | **0.6867** | 75 | 214 | 0 |
| heading Phase 3 | 0.5429 | 0.8286 | 0.8857 | 0.6905 | 58 | 141 | 0 |
| **heading Phase 6** | **0.7714** | **0.8857** | **0.9429** | **0.8319** | 128 | 389 | 0 |

- `fixed` 仍低于 heading 的 0.9429；它不再是 Phase 3 的 0.3429，但也不宣称超过 heading。未改 gold、未删 QA、未放宽 matcher。
- 2025 最终 chunk 数：fixed 80 + heading 91；2024：fixed 4 + heading 6；enterprise：fixed 18 + heading 49；合计 248。

---

## 5. R1：10 并发 p95

### Before（Phase 3）

A/B/C/D 四次 60s：p95 = 808.59 / 552.07 / 1129.14 / 826.31ms，errors=0；p50≈119–157ms。根因判断为 Ollama bge-m3 排队/长尾，不是 GC/Hikari/SQL。

### After（Phase 6，一次真实测量）

- 命令：`powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\perf-concurrency.ps1 -DurationSeconds 60 -ReportJson docs/acceptance/phase6-concurrency.json`
- 数据：10k/策略 synthetic + 真实 248 chunks；10 客户端；60s；heading/hybrid；50 次 warm-up 不计入。
- 结果：见 [phase6-concurrency.json](phase6-concurrency.json) 与下表（由脚本写回）。

| run | requests | errors | errorRate | p50 | p95 | p99 | max |
|---|---:|---:|---:|---:|---:|---:|---:|
| Phase 6 | 1,057 | 0 | 0 | 262.12 | **1,780.18** | 5,050.61 | 7,935.39 |

- 是否达标：**否**。p95=1780.18ms，p99=5050.61ms，`errors=0`；与 Phase 3 A/B/C/D（p95 808.59/552.07/1129.14/826.31ms）相比本轮更差。保留 residual，**不修改报告数字**，也不把本轮说成“稳定达标”。
- 说明：本轮 p50=262ms 也高于 Phase 3 的 ~130–157ms；服务端 `tookMs` p95=1761ms 与客户端几乎一致，延迟仍在应用/embedding 侧。最近一次真实测量只代表本轮，不用于挑选有利数字；Phase 3 的 A/B/C/D 仍作为历史对照保留。
- 取舍：本轮**没有**加入 query embedding 缓存。`perf-concurrency.ps1` 每条请求 query 都不同（`concurrency worker N request I`），缓存对唯一 query 基准无帮助；加缓存只会制造“看起来更快”的假象，不能解决 Ollama 长尾。若要硬保证 p95<800ms，应做专用推理服务/限流隔离/更大规模预热，属于基础设施变更。

---

## 6. 证据与人工核对

### 6.1 数据库

- 重置后按 2024 → 2025（`supersedesDocId`）→ enterprise 入库，`chunkStrategies=fixed,heading`。
- `2025` 取代 `2024`：2024 `SUPERSEDED`、`effective_to=2024-12-31`；2025/2026 `ACTIVE`。
- 企业 heading clause_id：48/49 非空（唯一 NULL 为文档标题）。
- 2025 heading 的 R3 噪声计数：

```sql
SELECT count(*) FILTER (WHERE clause_path LIKE '%90 mmHg%') AS p90,
       count(*) FILTER (WHERE clause_path LIKE '%3 d%') AS p3d,
       count(*) FILTER (WHERE clause_path LIKE '%20 g%') AS p20g
FROM kb_chunk c JOIN kb_document d ON d.id=c.document_id
WHERE d.doc_key='hypertension-guideline' AND d.version='2025' AND c.chunk_strategy='heading';
-- 0 / 0 / 0
```

- 企业 heading `clause_id` SQL：

```sql
SELECT clause_id, page_no, page_end, left(clause_path,80)
FROM kb_chunk c JOIN kb_document d ON d.id=c.document_id
WHERE d.doc_key='enterprise-internal-control' AND c.chunk_strategy='heading'
ORDER BY chunk_index LIMIT 12;
-- 第一条..第四十六条，page_end 有值
```

### 6.2 citation 人工核对（5 条）

| # | 题 | 结果 | 核对 |
|---|---|---|---|
| 1 | Q01 诊室血压标准 | heading rank1，4.2.1 第4页 | 4.2.1 原文含 ≥140/90 mmHg |
| 2 | Q08 降压目标 | heading rank1，5.2 第5页 | 5.2 原文含 <80 岁 130/80、≥80 岁 150/90 |
| 3 | Q12 无合并症方案 | heading rank1，5.4.3.1 第8页 | 5.4.3.1 流程图/方案文字 |
| 4 | Q18 企业内控目标 | heading rank1，page2，top-1 第三条 | 第三条“内部控制的目标”正确；page-only gold 仍可能命中同页错误条款 |
| 5 | Q25 授权审批控制 | heading rank1，第三十条 第6页 | 第三十条含授权审批要求 |

- 未发现 citation 编造文档名/版本/生效日/页码；章节段来自落库 clause_path。

---

## 7. 测试与回归

- `mvn clean test`：**207 tests, 0 failures, 0 errors, 1 skipped；BUILD SUCCESS**。唯一 skipped 是 `OllamaEmbeddingSmokeTest`（`@EnabledIfEnvironmentVariable(RAG_REAL_OLLAMA=true)` 的条件真实模型 smoke）。
- `mvn -Dtest=McpIntegrationTest test`：**14 tests, 0 failures/errors**；`/mcp` 仍只发布两个 tool，Phase 5 契约未破坏。
- `scripts/validate-gold.ps1`：35/35 通过（gold 未改，hash 1487...）。
- 新增/更新测试：
  - `ClauseIdsTest`（中文/阿拉伯/全角/空格、正文中间不提取）
  - `HeadingCandidatesTest`（数值/单位/剂量/页眉清洗；真实 5.4.3.1/5.4.6.1 保留）
  - `PdfDocumentParserTest`（双栏左→右；measurement 不成为 heading）
  - `FixedWindowChunkerTest`（主导 clause + pageEnd）
  - `HeadingAwareChunkerTest`（跨页 section pageEnd）
  - `GoldMatcherTest`（pageEnd 相交命中/不相交 miss）
  - `DocumentUploadIntegrationTest`（fixed clause/pageEnd DB 断言）
  - `SchemaIntegrationTest`（page_end 列）
  - `DocxDocumentParserTest`（第五条 clause_id）
  - `SearchIntegrationTest`（API pageEnd additive）

---

## 8. 复现命令

```powershell
docker compose up -d postgres
ollama pull bge-m3
mvn clean test
mvn spring-boot:run

# reset（保留 schema/Flyway，清数据）
docker exec rag-postgres psql -U rag -d ragdb -c "TRUNCATE kb_document, work_item, mandate, mcp_audit_log, eval_run RESTART IDENTITY CASCADE;"

# 入库 2024 → 2025（带 supersedesDocId）→ enterprise，两次策略
# 见 docs/samples/README.md 的 curl 示例；2025 需要 -F supersedesDocId=<2024 docId>

powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate-gold.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\eval.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\perf-concurrency.ps1 -DurationSeconds 60 -ReportJson docs/acceptance/phase6-concurrency.json
```

---

## 9. 残余与取舍

1. **R1 未稳定达标**：p95 仍可能 >800ms；本轮一次测量如实记录，未用缓存伪造。
2. **R3 不是全文双栏还原**：表格/流程图仍可能交错；clausePath 噪声已清，但 content 仍可能跨栏。
3. **enterprise gold 仍是 pageRange**：Q18 的 heading top-1 是第三条（正确）；但 fixed top-1 是第四条（错误条款），仍因 pageRange=[2,2] 被算作 hit。page-only 匹配会高估 fixed 的语义正确率；改 gold 需要逐条校验并记录 hash，本次未改。
4. **fixed 仍低于 heading**：0.7429 < 0.9429；主导 section 是启发式，窗口跨多个标题时可能选错 section。
5. **pageEnd 的语义**：fixed 的 pageEnd 是覆盖页范围，citation 仍只展示起始页；没有改 citation 格式，避免破坏 Phase 3/5 契约。
6. **不能用本轮数字宣称 R1 达标**，也不能把 R3 的“clausePath 已清”说成“全文顺序已修”。

---

## 10. 验收方独立复核（2026-11-09）

| 项 | 结果 |
|---|---|
| `mvn clean test` | **207 / 0 / 0 / 1**，BUILD SUCCESS；skip 为条件 Ollama smoke |
| `McpIntegrationTest` | **14 / 0 / 0**；Phase 5 `/mcp` 未破坏 |
| gold | `validate-gold.ps1` **35/35**；`eval/qa.jsonl` sha256 仍为 `1487e261...`，未改 |
| 正式 eval | fixed hit@5 **0.7429** / MRR **0.6867**；heading hit@5 **0.9429** / MRR **0.8319**；0 errors；与实现方一致 |
| chunk 数 | 248（2025 171、enterprise 67、2024 10）；2025 heading 从 231 降到 91 是 section 合并，heading token 36,328、fixed token 40,701，无空 chunk；未观察到丢内容 |
| R4 | 企业 heading 49 个，48 个有 `clause_id`；唯一 NULL 是封面标题；`clause_path` 带章 |
| R3 | 2025 `clause_path` 中 `90 mmHg` / `3 d` / `20 g` 计数均 0；heading Q18 top-1 第三条正确 |
| R2 语义残余 | fixed Q18 top-1 是第四条（错误），但因 page-only gold `[2,2]` 被算 hit；fixed 指标含 pageRange 粗匹配，不能等同 clause 级正确 |
| 10 并发独立复跑 | requests=972，errors=0，p50=259.05ms，p95=**2243.04ms**，p99=4545.93ms；仍不达标，R1 residual |
| 结论 | **R4 已修；R2 显著改善但未完全闭环；R3 部分修复；R1 未达标**；Phase 6 可合并 `main`，residual 保留 |

### 10.1 验收方判断

- R2 的 0.3429→0.7429 是真实代码/元数据变化带来的提升，但其中包含 page-only 粗匹配；不能宣称 fixed 已达到 clause 级语义正确。
- R4 的 clause_id 是真实 metadata 修复；QA gold 仍 page-only，是刻意的 dataset 边界，不是本次为了指标而改。
- R3 只承诺 clausePath 噪声与主阅读顺序，不承诺表格/流程图 100% 还原。
- R1 没有靠缓存/挑选数字掩盖；p95 2243 与实现方 1780 都如实保留。
