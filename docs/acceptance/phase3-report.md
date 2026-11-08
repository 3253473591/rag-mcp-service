# Phase 3 验收报告：正式评测 + 性能补测

- 日期：2026-11-09
- 分支：`phase3`（从 `main` @ `554ca11` 创建，未合并 main）
- 实现 commit：`c1668c2890022e97c3593c3cb2061e5b53f040f5`
- 证据 commit：本报告所在 commit（追加报告/README/docs；`perf-concurrency.ps1` 仅把 warm-up 从 10 提到 50，不改变实现语义）
- 结论：**P0 评测通过**（heading hit@5=0.8857、MRR=0.6905；fixed 如实报告为 0.3429/0.3214，不改 gold）；**P1 性能部分通过**：10k/策略 p50/p95/p99 全面达标，10 并发错误率 0%，但 10 并发 p95 在三次 60s 测量中为 552 / 808 / 1129ms，**未稳定低于 800ms**，作为残余风险报告。

---

## 1. 交付物

| 类型 | 位置 |
|---|---|
| 正式 QA 集（40 条 = 35 可回答 + 5 不可回答） | `eval/qa.jsonl` |
| gold 结构校验脚本 | `scripts/validate-gold.ps1` |
| 评测脚本（Windows PowerShell 5.1，默认读 `eval/qa.jsonl`） | `scripts/eval.ps1` |
| 评测报告（机器可读/人读） | `eval/report.json`、`eval/report.md` |
| Java 评测核心（dataset/gold matcher/scorer/service） | `src/main/java/com/lqx/ragmcp/eval/` |
| 单元/集成测试 | `src/test/java/com/lqx/ragmcp/eval/`、`integration/EvalIntegrationTest.java` |
| 10k/策略性能脚本（3 轮、server/client 延迟） | `scripts/perf.ps1` |
| 10 并发 60s 脚本（Start-Job + HttpClient） | `scripts/perf-concurrency.ps1` |
| 性能证据 | `docs/acceptance/phase3-perf.json`、`docs/acceptance/phase3-concurrency.json` |
| 检索路径修复 | `SearchRepository`（HNSW 可用路径）、`SpringAiEmbeddingService`（并发 embedding） |

---

## 2. QA 覆盖

`eval/qa.jsonl`：40 条，id 唯一；35 条可回答 + 5 条不可回答。

| 覆盖 | 条数 | gold 定位方式 |
|---|---:|---|
| `hypertension-guideline` v2025（ACTIVE） | 17 | 16 条 `clauseId` + 1 条 `pageRange`（Q03 动态血压，clause 提取不稳定） |
| `enterprise-internal-control` v2026（ACTIVE） | 11 | 全部 `pageRange`（该文档 heading chunk 的 `clause_id` 为 NULL） |
| `hypertension-guideline` v2024（SUPERSEDED，历史） | 7 | 7 条 `clauseId` + 每题 `asOf=2024-06-01` |
| 不可回答（年假、Kubernetes、个人患者数据、差旅住宿、2026 高血压新药） | 5 | 无 gold，单独统计 |

- 2024 题在 JSONL 中用逐题 `"asOf":"2024-06-01"` 覆盖全局 `-AsOf`；脚本也支持逐题 `includeSuperseded`（本题集未使用，避免新旧版本混检导致误命中）。
- 5 条不可回答题都是知识库确实没有的内容，不是“答案其实在库里”的题。

### 2.1 gold 校验方法（逐题，不照抄示例）

1. 先按实际入库结果查 DB：`docKey/version` 对应的 heading chunk 是否存在 `clause_id`，以及该 chunk 的 `page_no` 是否落在 `pageRange`。
2. 用提交的 `scripts/validate-gold.ps1` 对 35 条可回答题逐条执行同一查询；任何一条找不到就退出 1。
3. 人工核对答案文本落在对应 PDF 页（见第 4 节 citation 抽查）。

校验输出（35/35 全部存在；`chunks` 是该 gold 条件在 heading 策略下匹配到的 chunk 数）：

| id | clause | page | chunks | id | clause | page | chunks |
|---|---|---|---:|---|---|---|---:|
| Q01 | 4.2.1 | 4 | 1 | Q19 | - | 2 | 5 |
| Q02 | 4.2.1 | 4 | 1 | Q20 | - | 2 | 5 |
| Q03 | - | 4 | 32 | Q21 | - | 4 | 7 |
| Q04 | 4.2.2 | 4 | 1 | Q22 | - | 4 | 7 |
| Q05 | 4.2 | 4 | 1 | Q23 | - | 4 | 7 |
| Q06 | 4.2.3 | 4 | 1 | Q24 | - | 5 | 5 |
| Q07 | 5.1 | 5 | 1 | Q25 | - | 6 | 9 |
| Q08 | 5.2 | 5 | 1 | Q26 | - | 6 | 9 |
| Q09 | 5.3 | 5 | 1 | Q27 | - | 7 | 7 |
| Q10 | 5.4.1 | 6 | 1 | Q28 | - | 8 | 4 |
| Q11 | 5.4.2 | 6 | 1 | Q29 | 4.2.1 | 1 | 1 |
| Q12 | 5.4.3.1 | 8 | 1 | Q30 | 4.2.1 | 1 | 1 |
| Q13 | 5.4.6.1 | 9 | 1 | Q31 | 4.2.1 | 1 | 1 |
| Q14 | 8.1 | 13 | 1 | Q32 | 4.2.2 | 1 | 1 |
| Q15 | 8.2 | 13 | 1 | Q33 | 5.2 | 2 | 1 |
| Q16 | 9.1 | 13 | 1 | Q34 | 8.1 | 2 | 1 |
| Q17 | 10.1 | 13 | 1 | Q35 | 4.2.2 | 1 | 1 |
| Q18 | - | 2 | 5 | | | | |

- 23 条 clause 锚定题：clause 与 page 都匹配到 1 个 heading chunk。
- 12 条 page-only 题（Q03 + 企业内控 11 条）：因为 fixed chunk 和该企业文档 heading chunk 没有 `clause_id`，按 docs/11 的回退口径用 `pageRange`；这是**匹配口径的粗粒度**，不是 gold 放宽。

---

## 3. 正式评测结果（40 条，真实入库 382 chunks）

环境：Windows、Docker `pgvector/pgvector:pg16`、Ollama `bge-m3`、`mvn spring-boot:run`。重置 DB 后按 2024 → 2025（带 `supersedesDocId`）→ enterprise 顺序入库，`chunkStrategies=fixed,heading`：
2024 10 chunks（4.9s）、2025 309 chunks（8.6s）、enterprise 63 chunks（2.2s），合计 382；v2024 `SUPERSEDED`，v2025/v2026 `ACTIVE`。

命令：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\eval.ps1
```

数据：`eval/qa.jsonl` sha256=`1487e261b728debd22a876d31016f376efeb40932d8888d45d2d15b91a15af4c`；embedding `bge-m3` 1024d；topK=5；hybrid；默认 asOf=2026-11-09。

| chunkStrategy | answered | hit@1 | hit@3 | hit@5 | MRR | p50 | p95 | errors |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| fixed | 35 | 0.3143 | 0.3143 | 0.3429 | 0.3214 | 51ms | 106ms | 0 |
| heading | 35 | 0.5429 | 0.8286 | **0.8857** | **0.6905** | 58ms | 141ms | 0 |

- p50/p95 是应用 `tookMs`（query embedding + SQL + RRF）；客户端整体 p95 为 fixed 120.31ms / heading 154.20ms，见 `eval/report.json`。
- **heading/default 达标**：hit@5 0.8857 ≥ 0.85，MRR 0.6905 ≥ 0.65。
- **fixed 如实报告，不达标**：hit@5 0.3429、MRR 0.3214。没有修改 gold、没有把 fixed 的 miss 改成命中。
- 不可回答题（单独统计，不混入 hit@k）：fixed/heading 都是 `falsePositiveRate=1.0`、`noAnswerAccuracy=0.0`；因为 hybrid 总会返回 topK，5 条题都返回了结果。这是已知现象，docs/05 不设硬门槛。

### 3.1 fixed 为什么低（不调 gold）

1. fixed chunk 按 Phase 1 设计不写 `clause_id`/`clause_path`，只能回退 `pageRange`。
2. fixed 是 600-token 窗口，`page_no` 是窗口起始页；答案在第 4 页时，包含答案的窗口可能从第 3 页开始，`[4,4]` 不命中。企业内控的 18 个 fixed chunk 甚至全部从第 1 页起始（窗口重叠导致页号停留在起始页），page-only 的 11 条企业题几乎全 miss。
3. 2025 版是双栏 PDF，heading 的 `clausePath` 有噪声（如 `90 mmHg`），但 heading 的 `clauseId` 在 4.2.1/5.2/8.1 等关键节仍稳定，所以 heading 明显更好。
4. 结论：fixed 的低分是**切分/元数据口径的真实结果**，不是 gold 写错；要提升 fixed 需要改 FixedWindowChunker 的页号/条款元数据，属于 Phase 1 行为变更，本轮不做。

### 3.2 失败与错误

- 两个策略各 35 条可回答、5 条不可回答，`errors=0`，没有超时/HTTP 失败。
- heading 的 4 条 hit@5 miss：Q04、Q10、Q15、Q17（top-5 没有 gold clause）。fixed 的 miss 更多，原因见上。
- 每条题的 rank、results、server/client 延迟、top citation 在 `eval/report.md` 的 per-question 表和 `eval/report.json`。

---

## 4. citation 人工抽查（5 条）

抽查方法：用同一查询真实调用 `/api/v1/search`，取 top-5 的 `citation`、`pageNo`、`clauseId` 与 PDF 页文本对照。完整输出见验收记录；结论：

| # | 题 | 命中结果 | citation 摘要 | PDF 页核对 | 结论 |
|---|---|---|---|---|---|
| 1 | Q01 诊室血压诊断标准 | heading rank 2（clause 4.2.1） | `《hypertension-2025》v2025（2025-01-01 生效） > ... 4.2.1 诊断标准 > 第4页` | 第 4 页 4.2.1 有 `≥140/90 mmHg` | 页码/条款正确；top-1 是第 4 页表格碎片，clausePath 噪声为 `90 mmHg`，答案在 rank 2 |
| 2 | Q14 随访频率 | heading rank 1（8.1） | `... > 8 高血压长期随访管理 > 8.1 随访频率 > 第13页` | 第 13 页 8.1 有“每 3 个月/每月” | 正确 |
| 3 | Q18 企业内控目标 | heading rank 1（page 2） | `《enterprise-policy-2026》v2026（2026-01-01 生效） > 第三条 ... > 第2页` | 第 2 页第三条有“合理保证…” | 正确 |
| 4 | Q25 授权审批控制 | heading rank 1（page 6） | `... > 第三十条 授权审批控制... > 第6页` | 第 6 页第三十条有授权审批要求 | 正确 |
| 5 | Q29 2024 诊室阈值 | heading rank 2（4.2.1） | `《hypertension-2024》v2024（2024-01-01 生效） > ... 4.2.1 诊断标准 > 第1页` | 第 1 页 4.2.1 有 `≥150/95 mmHg` | 正确；证明历史 `asOf` 生效 |

- 5/5 的页码与文档/版本正确；4/5 的 top-1 直接是目标条款，Q01 的 top-1 是同一页的表格碎片（clausePath 噪声），答案在 rank 2。
- 没有发现 citation 编造：citation 的文档名、版本、生效日、页码来自真实 DB 字段；章节段来自落库的 `clausePath`。

---

## 5. 性能补测（10k/策略）

### 5.1 3 轮串行 10k

命令：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\perf.ps1 -Rounds 3
```

- 每策略 10,000 synthetic chunks（fixed 10k + heading 10k，合计 20k，另有 382 真实 chunks）；每轮每策略 50 次，10 次 warm-up 不计入；topK=5、hybrid、真实 bge-m3 query embedding。
- 原始数据：[phase3-perf.json](phase3-perf.json)。p50/p95/p99 为客户端端到端；`serverP50/P95/P99` 为应用 `tookMs`，两者接近。
- 说明：`phase3-perf.json` 的 `gitCommit` 字段是运行时的 base `554ca11`；该次运行已包含 commit `c1668c2` 的同一实现（当时尚未提交），跑完后才提交。eval 报告是干净树在 `c1668c2` 上重跑的（dirty=False）。

| round | strategy | p50 | p95 | p99 | server p95 | server p99 | errors | avg results |
|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | fixed | 26.41 | 42.72 | 97.19 | 40 | 93 | 0 | 5 |
| 1 | heading | 26.89 | 33.28 | 39.51 | 30 | 36 | 0 | 5 |
| 2 | fixed | 26.08 | 64.62 | 91.87 | 62 | 87 | 0 | 5 |
| 2 | heading | 44.43 | 121.71 | 131.54 | 112 | 120 | 0 | 5 |
| 3 | fixed | 37.53 | 120.88 | 136.88 | 116 | 134 | 0 | 5 |
| 3 | heading | 31.72 | 88.21 | 93.50 | 84 | 89 | 0 | 5 |

- 所有轮次 p50 < 250ms、p95 < 800ms、p99 < 1500ms，errors=0。
- 与 Phase 2 的 p99 4.8s/1.54s 方差相比，本轮 3 轮 p99 为 39.5~136.9ms；但见 5.3 的根因和 5.2 的并发残余。

### 5.2 10 并发 60s（T-PERF-02）

命令：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\perf-concurrency.ps1 -DurationSeconds 60
```

- 10 个 PowerShell `Start-Job` 进程，每个复用 `HttpClient`；60s；heading + hybrid；10k/策略；50 次 warm-up 不计入；错误率按非 2xx/异常计。
- `phase3-concurrency.json` 保存的是最近一次（run C）。三次连续测量的实际值：

| run | requests | errors | errorRate | p50 | p95 | p99 | max | 备注 |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| A | 2,076 | 0 | 0 | 157.35 | **808.59** | 1233.82 | 8056.88 | 冷启动后第一轮，p95 略超 800 |
| B | 2,851 | 0 | 0 | 135.97 | **552.07** | 991.34 | 1217.65 | 同一代码/数据，warm 后 |
| C | 1,803 | 0 | 0 | 129.95 | **1129.14** | 4679.77 | 5848.26 | 50 次 warm-up 后仍有长尾 |

- **错误率 0%**，满足 <1%；**p95 未稳定 <800ms**：B 通过，A 超 1.1%，C 明显超。不能把 B 当作“稳定达标”；本报告把 A/B/C 都列出。
- 服务端 `tookMs` 与客户端几乎一致（A 801 / B 547 / C 1097），说明延迟在应用内部，不是 PowerShell 客户端。
- 长尾定位：
  - keyword-only 10 并发 60s：31,952 请求，p95 29.6ms、错误 0 → pg_trgm/SQL 不是瓶颈。
  - 直接对 Ollama `/api/embed` 做 10 并发 30s：1,336 请求，p50 97ms、p95 686.6ms、p99 3,003ms、max 4,083ms → **Ollama embedding 自身有秒级长尾**，与 app 10 并发 p99 4.3s 同量级。
  - JVM GC（-Xlog:gc）最大 pause 45ms；Hikari 无获取失败；因此 **不是 GC/Hikari 主导**。
- 结论：10 并发 p50 很好（~130-157ms），但 p95/p99 受 Ollama embedding 排队/长尾影响，当前配置无法稳定保证 p95<800ms。若要硬保证，需要限制/隔离 Ollama、预热更多、或对查询 embedding 做缓存（本轮不做，避免改变语义）。

### 5.3 Phase 2 p99 方差与 HNSW 根因

Phase 3 第一次 3 轮 perf 出现：round 1 fixed p50=102ms，之后 heading p95=1.6s、p99=6.1s，round 2/3 p50≈1.3s。排查发现：

1. **PostgreSQL checkpoint/autovacuum**：日志显示 `checkpoint starting` 后 `checkpoint complete` 写了 14,592 buffers、耗时 269s，覆盖测量窗口；之前删除/重插 20k synthetic 行会产生 20k dead tuples。`perf.ps1` 现在在插入前后执行 `VACUUM (ANALYZE)`，避免 autovacuum 在测量中触发。
2. **向量查询没有走 HNSW**：`EXPLAIN` 显示 `kb_chunk JOIN kb_document ... ORDER BY embedding <=> ? LIMIT 30` 被规划成 `Nested Loop` + `Sort`（全量 10k 距离计算），HNSW 只在不带 join 的简单查询里生效。10 并发时这个 full sort 把 DB 打满，是 p95/p99 多秒的主要原因。
3. 修复：`SearchRepository.vectorSearch` 先在 SQL 里解析 eligible `document_id`（status/asOf/version/docIds 过滤仍在 SQL），再对 `kb_chunk` 用 `document_id IN (...) AND chunk_strategy=? ORDER BY embedding <=> ? LIMIT 30`；最后只为候选 chunk 取文档元数据。`EXPLAIN` 现在走 `Index Scan using idx_kb_chunk_embedding`，1ms 级。
4. 同时把 embedding 执行池从固定 2 线程改为可配置 `app.embedding.max-concurrent-calls=10`；10 并发检索不再被 2 个 embedding 线程串行化。

### 5.4 `hnsw-iterative-scan=false` 取舍

在 10k data 上用 `APP_SEARCH_HNSW_ITERATIVE_SCAN=false` 跑 1 轮（每策略 50 次）：

| strategy | p50 | p95 | p99 | avg results |
|---|---:|---:|---:|---:|
| fixed | 63.59 | 101.07 | 135.14 | 5 |
| heading | 122.95 | 473.44 | 1032.06 | 5 |

对比默认 `true` 的 3 轮 heading：p95 33~122ms、p99 39~137ms。关闭 iterative scan **没有带来收益，反而 p99 更差**；合成数据过滤较宽，仍返回 5 条，但更窄的 docIds/版本过滤下有 under-return 风险。因此保持默认 `true`，不为了 p99 关闭过滤扫描。

---

## 6. 测试

命令：`mvn test`

- **Tests run: 134, Failures: 0, Errors: 0, Skipped: 1；BUILD SUCCESS**。
- 唯一 skipped 是 `OllamaEmbeddingSmokeTest`（`@EnabledIfEnvironmentVariable(RAG_REAL_OLLAMA=true)` 的条件真实模型 smoke），不是被禁用/删除的失败测试；实际执行 133 条。

新增/扩展：

| 测试 | 覆盖 |
|---|---|
| `EvalScorerTest`（6） | hit@1/3/5、MRR、miss、error 单独计数且不进分母、不可回答 falsePositive/noAnswer、nearest-rank 百分位 |
| `GoldMatcherTest`（9） | clause 优先、docKey 不同、结果无 clause 回退 pageRange、不同 clause 同页不误命中、page-only、多 chunk 只算一次 |
| `EvalDatasetTest`（6） | 40=35+5、id 唯一、3 个 doc/version、逐题 asOf、重复 id/非法 pageRange/坏 JSON 行号 |
| `EvalServiceTest`（1） | 逐题 asOf 覆盖全局、includeSuperseded 传递、错误与成功分开计数 |
| `EvalIntegrationTest`（T-INT-EVAL-01，1） | 真实 pgvector + fake embedding 跑 40 条；两种策略 answered=35、errors=0、hit@k/MRR ∈ [0,1]、Q18 真实命中 |
| `SpringAiEmbeddingServiceTest.tEmbed07`（1） | 并发 embedding 池 ≥3 并发（旧固定 2 池会失败） |
| 既有 `SearchIntegrationTest` / `VersionIntegrationTest` / `SearchServiceTest` | 向量/关键词/hybrid、版本三模式、docIds/version/chunkStrategy 过滤、无结果、embedding 503、DB 503 全部保持绿色 |

---

## 7. 残余风险与取舍

1. **10 并发 p95 未稳定达标**：A/B/C 三次为 808.59/552.07/1129.14ms；错误率 0%。根因主要是 Ollama bge-m3 embedding 的排队/长尾（直接 10 并发 p99=3.0s），不是 GC/Hikari/SQL。需要缓存/限流/专用推理服务才能硬保证，本轮不做。
2. **fixed 指标低**（hit@5=0.3429、MRR=0.3214）：fixed 无 clause 元数据、窗口页号是起始页、企业文档 fixed 全部从第 1 页起；这是 Phase 1 切分语义的真实结果。未改 gold、未为达标改切分。
3. **2025 双栏 clausePath 噪声**：heading 的 clausePath 常出现 `90 mmHg` 等碎片；gold 优先使用稳定的 `clauseId`，page-only 题（Q03）显式只用 pageRange。citation 的章节段可能噪声，但页码/文档/版本正确。
4. **企业内控 heading 的 clause_id 为 NULL**：11 条题只能按 pageRange 匹配，粒度较粗；匹配口径见 docs/11，不是 gold 放宽。
5. **HNSW 近似召回**：向量路现在真正走 HNSW；`hnsw.iterative_scan=relaxed_order` 用于过滤 under-return。关闭 iterative scan 未改善延迟且可能 under-return，故保持开启。
6. **不可回答题 falsePositiveRate=1.0**：hybrid 总返回 topK，当前没有相关性阈值；docs/05 只要求记录，不设硬门槛。
7. **PowerShell 5.1 客户端噪声**：`Invoke-WebRequest` 曾在 eval 中把 463ms 的服务端请求放大到 19.8s；eval 已改用 `HttpClient` 并按原始字节 UTF-8 解码。perf 也使用 `HttpClient`，server/client 延迟同时记录。
8. **未做 Phase 4/5**：没有 WorkItem/Approval/Mandate、生产 MCP、审计、前端；未合并 main。

---

## 8. 复现命令

```powershell
# 1) 依赖
docker compose up -d postgres
ollama pull bge-m3
mvn test

# 2) 重置并入库 3 篇（2024 先入，2025 带 supersedesDocId，enterprise）
#    参数见 docs/samples/README.md；固定/标题两套策略

# 3) 启动
mvn spring-boot:run

# 4) gold 结构校验
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate-gold.ps1

# 5) 正式评测（fixed/heading，40 条）
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\eval.ps1

# 6) 10k/策略 3 轮
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\perf.ps1 -Rounds 3

# 7) 10 并发 60s
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\perf-concurrency.ps1 -DurationSeconds 60
```
