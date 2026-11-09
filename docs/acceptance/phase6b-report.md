# Phase 6b 验收报告：R1（10 并发检索 p95）

- 日期：2026-11-09
- 分支：`phase6b`（从 `main` @ `4fdd007` 创建）
- 实现 commit：`92109d1`（代码/配置/测试/脚本）
- 证据 commit：本报告所在 commit
- 基线：Phase 1–6 已验收；Phase 6 的 10 并发 p95=1780.18ms（实现方）/2243.04ms（验收方），errors=0
- 结论：**在本文档记录的本机环境与三次独立 60s 正式运行中，p95=445.29/500.12/468.59ms，全部 <800ms；errors=0/0/0（errorRate=0），达到 R1 门槛。验收方独立 3 次 p95=498.53/368.39/408.52ms，cacheHits=0，6/6 次 p95 均 <800ms。** 查询批处理 + 有界并发（以及随之而来的 Ollama 调用合并）是主要原因；缓存对唯一 query 基准的命中率为 0，**没有用缓存伪造基准**。残余：单次长尾 max 仍可能到 4.4s；数字依赖本机 Ollama/GPU/Postgres 环境；p95 达标不等于 p99/max 全部受控。

---

## 1. 交付物

| 类型 | 位置 |
|---|---|
| Query embedding 接口 | `src/main/java/com/lqx/ragmcp/embedding/QueryEmbeddingService.java` |
| 批处理 + 有界队列 + 限流 + 关闭 | `src/main/java/com/lqx/ragmcp/embedding/QueryEmbeddingBatcher.java` |
| 有界 TTL/LRU 缓存 | `src/main/java/com/lqx/ragmcp/embedding/QueryEmbeddingCache.java` |
| 检索侧接入（仅 query） | `SearchService`；`EmbeddingConfig` 新增 `QueryEmbeddingService` bean |
| 配置 | `EmbeddingProperties`、`application.yml` |
| 指标 | `/actuator/metrics` 暴露 `rag.query.embedding.*`（见第 3.5 节） |
| 并发证据 | [phase6b-concurrency.json](phase6b-concurrency.json)（含全部 3 次完整结果） |
| 缓存实验证据 | [phase6b-cache-experiment.json](phase6b-cache-experiment.json) |
| 测试 | `QueryEmbeddingBatcherTest`（10）、`QueryEmbeddingCacheTest`（5）；更新 `SearchServiceTest`、`AbstractIntegrationTest` |
| 脚本 | `scripts/perf-concurrency.ps1` 追加 embedding 指标；新增 `scripts/perf-query-cache.ps1` |

**没有改**：检索 SQL/排序/融合、chunk/QA/gold、MCP/治理、API 字段与错误码语义、`eval/qa.jsonl`。文档入库仍直接用 `EmbeddingService`，批处理只作用于检索 query。

---

## 2. 实现

### 2.1 Query 批处理

`QueryEmbeddingBatcher` 只被 `SearchService`（HTTP 检索和 MCP `search_knowledge` 都走它）使用：

- 并发 query 先进入 `ArrayBlockingQueue`（默认容量 200）；
- 单 dispatcher 线程取到第一条后，在 `query-batch-window-ms`（默认 **5ms**）窗口内继续收集，最多 `query-batch-size`（默认 **8**）条；
- 每个 batch 交给固定大小的批处理线程池（默认 **3** 个并发 batch），调用一次 `EmbeddingService.embed(List<String>)`；
- 一次 batch 的失败/超时**原样传播给该 batch 的每个调用方**（`ERR-EMBEDDING_UNAVAILABLE` 503 / `ERR-EMBEDDING_TIMEOUT` 504），不会静默降级、不会返回零向量或空结果；
- 队列满/已关闭立即 503，不无限等待；
- 关闭时：`close()` 停 dispatcher、中断 batch 线程、排空队列、对仍未完成的 pending 统一 `completeExceptionally`，最多等待 3s，保证调用方不会挂到 `query-wait-timeout-ms`（默认 130s，仅作为最后保险）。

### 2.2 有界缓存

- key = **model + NFKC 规范化后的 query**（`strip` + 连续空白折叠为单空格；保留大小写，避免把语义不同的 query 合并）；
- 容量 1000、TTL 300s，均可用 `query-cache-size` / `query-cache-ttl-seconds` 配置；任一项为 0 即禁用；
- 只缓存成功、维度正确、非零的向量；写入和读取都做 defensive copy，调用方修改返回值不会污染缓存；
- 命中/未命中计数写入 Micrometer。

**对唯一 query 基准的声明**：`perf-concurrency.ps1` 每条 query 都不同（`concurrency worker N request I`），且三次正式运行每次都重启 JVM（空缓存、指标归零），因此缓存命中率必须是 0。实测 **0/8004**，没有靠缓存让基准变快。缓存的独立证据见第 5 节。

### 2.3 配置默认值

```yaml
app.embedding:
  query-batch-size: 8
  query-batch-window-ms: 5
  query-cache-size: 1000
  query-cache-ttl-seconds: 300
  query-queue-capacity: 200
  query-max-concurrent-batches: 3
  query-wait-timeout-ms: 130000
```

文档入库仍使用 `app.embedding.batch-size=32`、`max-concurrent-calls=10`，未改其语义。

---

## 3. 正式性能结果（全部 3 次）

### 3.1 命令与口径

每次都是独立的 60s 运行，使用**新启动的 JVM**（清空 query 缓存与指标），避免第 2/3 次复用第 1 次的缓存：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\perf-concurrency.ps1 `
  -DurationSeconds 60 -ReportJson .tmp\official-runN.json
```

- 10 并发 PowerShell worker，每个复用 `HttpClient`；60s；
- 10k synthetic chunks / 策略（合计 20k）+ 真实 248 chunks；
- 50 次 warm-up 不计入；`chunkStrategy=heading`、`strategy=hybrid`、`topK=5`；
- `clientMs` 为端到端，`serverMs` 为应用 `tookMs`。

### 3.2 三次结果（client 端）

| run | requests | errors | errorRate | p50 | p95 | p99 | max | cacheHits | cacheHitRate | embedding batches | avg batch |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 2,724 | 0 | 0 | 201.46 | **445.29** | 554.84 | 776.51 | 0 | 0 | 1,577 | 1.727 |
| 2 | 2,565 | 0 | 0 | 194.21 | **500.12** | 692.77 | 1,259.72 | 0 | 0 | 1,564 | 1.640 |
| 3 | 2,715 | 0 | 0 | 175.00 | **468.59** | 588.81 | 4,405.15 | 0 | 0 | 1,500 | 1.810 |

- 三次全部 p95 <800ms；errors=0，errorRate=0；
- 合计 8,004 requests，平均 **2,668 requests/60s**；
- 缓存命中 **0/8,004**，确认没有缓存收益；
- 批处理失败 0、队列拒绝 0、等待超时 0（每 run 的 `embeddingMetrics.failures/rejected/waitTimeouts` 均为 0）；
- 三次的 server 端 p50/p95/p99：196/438/541、189/488/663、169/458/568ms，与 client 端基本一致，说明长尾仍在应用/embedding 侧，不是客户端测量误差。

完整逐 worker 数据、server 端数字和 `embeddingMetrics` 见 [phase6b-concurrency.json](phase6b-concurrency.json) 的 `runs[0..2]`。

### 3.3 与 Phase 6 对照

| 指标 | Phase 6 实现方 | Phase 6 验收方 | Phase 6b run1 | run2 | run3 |
|---|---:|---:|---:|---:|---:|
| requests/60s | 1,057 | 972 | 2,724 | 2,565 | 2,715 |
| p50 (ms) | 262.12 | 259.05 | 201.46 | 194.21 | 175.00 |
| **p95 (ms)** | 1,780.18 | 2,243.04 | **445.29** | **500.12** | **468.59** |
| p99 (ms) | 5,050.61 | 4,545.93 | 554.84 | 692.77 | 588.81 |
| errors | 0 | 0 | 0 | 0 | 0 |

- p95 相对 Phase 6 实现方下降约 75%，相对验收方下降约 79%；吞吐约为 Phase 6 的 2.6–2.8 倍。
- 本机改动前基线（同一台机器、同一脚本、两次 60s）：p95=**944.28 / 788.37ms**，p50=202.01/166.88，requests=1,746/2,314。也就是说本机环境本身有波动，其中一次已经 <800ms；**不能把“跨过 800”全部归因于本次改动**。但三次正式运行的 p95 稳定在 445–500ms，且吞吐更高，改动带来的余量是清楚的。

### 3.4 探索性调参（非正式三次，仅用于选型）

每个探针也是独立 60s、重启 JVM；它们不是正式验收数字，但如实列出，避免只展示最好结果：

| 配置 | requests | p50 | p95 | p99 | 结论 |
|---|---:|---:|---:|---:|---|
| 改动前 `main` | 1,746 | 202.01 | 944.28 | 1,548.39 | 基线 |
| 改动前 `main`（第二次） | 2,314 | 166.88 | 788.37 | 1,127.59 | 基线波动 |
| batch=1（只限并发，不合并） | 2,226 | 191.49 | 684.95 | 874.14 | 限流有效，但比合并差 |
| batch=8 / window=5 / maxBatches=2 | 2,427 | 163.89 | 495.22 | 953.45 | 有效，p99 一般 |
| batch=8 / window=5 / maxBatches=3 | 2,581 | 184.57 | 471.34 | 637.20 | **选为默认** |
| batch=8 / window=5 / maxBatches=4 | 2,631 | 203.11 | 484.92 | 706.60 | 与 3 接近，p50 略差 |
| batch=8 / window=5 / maxBatches=1 | 1,792 | 269.46 | 771.24 | 920.60 | 吞吐下降，p95 接近门槛 |
| batch=10 / window=10 / maxBatches=2 | 1,226 | 292.72 | 1,641.78 | 6,605.73 | **拒绝**，明显更差 |

`batch=10/window=10` 的这次探针平均 batch 仍只有 1.79 条，但 p95/吞吐显著变差；没有重复该配置，无法区分是窗口/批次大小还是单次运行环境波动。它没有被持久化到默认配置。最终选择 `8 / 5ms / 3 concurrent batches`：3 与 4 的 p95 接近，3 的 p99 更好；1 的吞吐和 p95 都更差。

### 3.5 指标

`application.yml` 的 actuator 暴露增加 `metrics`。`QueryEmbeddingBatcher` 注册：

- `rag.query.embedding.requests`（含 cache hit）
- `rag.query.embedding.cache.hits` / `.cache.misses`
- `rag.query.embedding.batches`、`rag.query.embedding.batch.size`（count/total）
- `rag.query.embedding.batch.latency`
- `rag.query.embedding.failures` / `.rejected` / `.wait.timeouts`
- `rag.query.embedding.queue.depth`

`perf-concurrency.ps1` 在 warm-up 后、worker 结束后各读一次上述计数，把差量写入报告的 `embeddingMetrics`；指标不可用时该字段为 `null`，不影响原脚本运行。这是 additive 变更，原有测量口径（10 并发、60s、10k/策略、50 warm-up）未变。

---

## 4. 缓存重复 query 实验（不等于并发基准）

用 `scripts/perf-query-cache.ps1` 对同一个 query 连续请求 100 次（独立、新启动的 JVM，query 带时间戳确保首次 miss）：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\perf-query-cache.ps1 `
  -Iterations 100 -ReportJson docs\acceptance\phase6b-cache-experiment.json
```

| 指标 | 结果 |
|---|---:|
| iterations | 100 |
| requests | 100 |
| cacheHits | **99** |
| cacheMisses | 1 |
| cacheHitRate | **0.99** |
| embedding batches | 1 |
| errors | 0 |
| client p50 / p95 | 62.4 / 107.7ms |
| server p50 / p95 | 49 / 86ms |

结论：重复 query 会命中缓存，且只有第一次触发 embedding；**这不等于** 10 并发唯一 query 基准达标。正式基准的 cacheHitRate=0 已单独记录。

---

## 5. 检索质量回归（40 QA）

在 Phase 6 的 248 chunks DB 上，使用 `scripts/eval.ps1`（40 条 QA，gold 未改）跑 fixed/heading：

| chunkStrategy | hit@1 | hit@3 | hit@5 | MRR | errors |
|---|---:|---:|---:|---:|---:|
| fixed | 0.6571 | 0.7143 | **0.7429** | **0.6867** | 0 |
| heading | 0.7714 | 0.8857 | **0.9429** | **0.8319** | 0 |

与 Phase 6 完全一致，检索质量未回退。`eval/qa.jsonl` 的 SHA-256 仍为 `1487e261b728debd22a876d31016f376efeb40932d8888d45d2d15b91a15af4c`；`validate-gold.ps1` 为 **35/35**。本次未改检索 SQL、排序、matcher、QA 或 gold。

> eval 的 p50/p95 不是性能验收数字：它是串行 40 条请求，且 heading 轮会命中 fixed 轮刚写过的 query 缓存；不作为 R1 证据。

---

## 6. 测试与回归

- `mvn clean test`：**222 tests, 0 failures, 0 errors, 1 skipped；BUILD SUCCESS**。唯一 skipped 仍是条件执行的 `OllamaEmbeddingSmokeTest`。
- `mvn -Dtest=McpIntegrationTest test`：**14 tests, 0 failures/errors**；Phase 5 `/mcp` 契约未破坏。
- 新增/更新的测试覆盖：
  - `QueryEmbeddingBatcherTest.concurrentQueriesAreCoalescedIntoFewerEmbedCallsAndKeepOrder`：10 个并发 query → 2 次 `embed(List)`（8+2），返回向量与 query 一一对应；
  - `cacheHitDoesNotCallEmbeddingAgainAndReturnsCopy`：命中不调用 embedding；不同 query 不命中；返回值是 copy；
  - `failureCodePropagatesToEveryCallerInTheBatch` / `unavailableFailurePropagatesAs503`：503/504 正确传播；
  - `wrongDimensionOrZeroVectorFailsAs503`：维度/零向量仍拒绝；
  - `queueOverflowFailsFastWith503InsteadOfBlocking`：队列满快速 503；
  - `callerWaitTimeoutReturns504AndCloseDoesNotHang`、`closeFailsQueuedAndWaitingCallersInsteadOfHanging`：超时/关闭不挂死；
  - `metricsRecordRequestsBatchAndCacheStats`、`queryNormalizationCollapsesWhitespaceAndUnicodeWidth`；
  - `QueryEmbeddingCacheTest`：copy、TTL、LRU 容量、禁用；
  - `SearchServiceTest`：keyword-only 不调用 embedding；503/504 映射；维度/零向量；DB 失败不被吞；hybrid 双路。
- 集成测试通过 `app.embedding.query-cache-size=0` 避免跨测试缓存掩盖 `FakeEmbeddingService.FAIL_NEXT`；批处理仍开启，生产 query 路径被覆盖。

---

## 7. 失败、回退与残余

1. **没有回退**：批处理/限流在三次正式运行中均有效，保留。探索性探针中 `batch=10/window=10` 明显更差，未被采用；`maxBatches=1` 接近门槛，也未采用。
2. **缓存不参与基准**：正式 3 次 cacheHits=0；如果未来有人把 `perf-concurrency.ps1` 的 query 改成可重复，必须重新说明口径，不能把缓存命中当成唯一 query 性能。
3. **单次长尾**：run3 的 max=4,405ms（p99 仍为 588.81ms），说明偶发 Ollama/调度停顿仍存在；p95/p99 达标不等于 max 受控。
4. **环境依赖**：本机 RTX 3080 + Ollama bge-m3 + Docker Postgres/pgvector 0.8.7；不承诺其它机器得到同样的绝对值。报告给出脚本、配置和原始 JSON，验收方可在自身环境复跑。
5. **缓存语义**：缓存是 query 级别，不缓存文档入库；TTL 300s 内同一 query 会返回同一向量。query 规范化保留大小写，只折叠空白/做 NFKC，避免过度合并。
6. **指标暴露面**：新增 `/actuator/metrics`，仍只监听本机回环；没有新增公网端点。
7. **p95 口径**：达标指本报告三次 60s 的 client p95（与 server p95 接近）；不是所有负载/所有数据规模下的硬保证。

---

## 8. 是否建议合并

**建议合并到 `main`（最终由验收方决定）。** 理由：

- 三次独立 60s、10 并发、10k/策略全部 p95 <800ms，errors=0；
- 40 QA 检索质量与 Phase 6 完全一致，gold 未改；
- 全量测试 222/0/0/1，`McpIntegrationTest` 14/0/0；
- 缓存对唯一 query 基准的命中率为 0，没有用缓存伪造数字；
- 改动是 query 侧 additive 封装，文档入库/检索语义/API/MCP 未变。

如果验收方更看重“任何一次都不能有 4.4s 的单请求长尾”，则 R1 的 p95 目标已达标但 max 长尾仍应作为残余保留；这不影响本次 p95<800 的结论。

---

## 9. 验收方独立复核（2026-11-09）

同一台机器、同一构建、同一 10 并发 60s 脚本；验收方在**同一 JVM 连续 3 次**运行（实现方为每次新 JVM）：

| run | requests | errors | errorRate | p50 | p95 | p99 | max | cacheHits | batches |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| A | 2,425 | 1 | 0.0412% | 161.18 | **498.53** | 749.30 | 11,220.48 | 0 | 1,345 |
| B | 3,249 | 0 | 0 | 138.40 | **368.39** | 444.34 | 674.91 | 0 | 1,676 |
| C | 3,189 | 0 | 0 | 140.31 | **408.52** | 557.43 | 708.37 | 0 | 1,613 |

- 3/3 p95 <800ms；A 仅 1 个错误，errorRate 0.0412%<1%；cacheHits=0/8863，缓存没有参与唯一 query 基准。
- 平均 batch size 1.803/1.939/1.977，说明确实发生并发合并，不是把请求串行化。
- `mvn clean test`：**222/0/0/1**；`McpIntegrationTest` 14/0/0。
- 40 QA：fixed hit@5=0.7429/MRR=0.6867，heading 0.9429/0.8319，errors=0，与 Phase 6 完全一致；gold/QA 未改。
- 残余：A 的 max=11.2s、p99=749ms；p95 达标不等于 p99/max 全部受控。R1 的结论是**本机 6/6 次 p95<800ms**，不是跨机硬保证。

### 9.1 验收结论

R1 在本机达到门槛，建议合并；p99/max 长尾保留为残余，不阻塞 p95 目标。

## 10. 复现命令

```powershell
docker compose up -d postgres
ollama pull bge-m3
mvn clean test
mvn -DskipTests package

# 每次正式 run 前重启应用（清空 query cache / 指标）
java -Xmx1g -jar target\rag-mcp-service-0.1.0-SNAPSHOT.jar

# 60s、10 并发；重复 3 次，每次重启应用
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\perf-concurrency.ps1 `
  -DurationSeconds 60 -ReportJson .tmp\official-run1.json

# 重复 query 缓存实验（不是并发基准）
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\perf-query-cache.ps1 `
  -Iterations 100 -ReportJson docs\acceptance\phase6b-cache-experiment.json

# 检索质量（gold 未改）
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate-gold.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\eval.ps1 `
  -ReportPath .tmp\phase6b-eval.md -ReportJson .tmp\phase6b-eval.json
```
