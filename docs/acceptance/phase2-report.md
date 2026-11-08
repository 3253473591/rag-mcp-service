# Phase 2 验收报告：检索 + 搜索 API

- 日期：2026-11-09
- 分支：`phase2`（从 `main` @ `080adfb` 创建，**未合并 main**）
- 最终 commit：**见本轮 `git rev-parse phase2` / 报告末尾**
- 验收范围：P0 检索接口、RRF、版本过滤、citation、测试、真实 smoke；P1 性能口径、Windows eval、chunkStrategies 校验、MCP spike
- 结论：**P0 通过，P1 完成**；已知缺口见第 9 节

---

## 1. 交付物

| 类型 | 位置 |
|---|---|
| 检索接口 | `src/main/java/com/lqx/ragmcp/api/SearchController.java` |
| 编排/校验/RRF/citation | `src/main/java/com/lqx/ragmcp/search/`（`SearchService`、`SearchRepository`、`SearchRequestValidator`、`RrfFusion`、`CitationFormatter`、`KeywordEscaper`） |
| DTO | `src/main/java/com/lqx/ragmcp/api/dto/Search*.java` |
| 测试 | `src/test/java/com/lqx/ragmcp/search/`、`api/SearchControllerErrorTest`、`integration/SearchIntegrationTest`、`integration/McpRegistrationSpikeTest`、`chunk/ChunkStrategiesTest` |
| Windows eval | `scripts/eval.ps1` + `eval/qa.smoke.jsonl` + `eval/report.md` + `eval/report.json` |
| 性能 | `scripts/perf.ps1` + `docs/acceptance/phase2-perf.json` |
| 真实 smoke | `scripts/smoke-phase2.ps1` + `docs/acceptance/phase2-smoke.md` + `phase2-smoke-results.json` |
| MCP spike | `docs/acceptance/phase2-mcp-spike.md` + `McpRegistrationSpikeTest` |
| 文档 | `README.md`、`docs/05-non-functional.md`（30s/10k 口径） |

---

## 2. 测试结果

命令：

```powershell
mvn clean test
```

结果：**Tests run: 110, Failures: 0, Errors: 0, Skipped: 1；BUILD SUCCESS**。

- `Skipped: 1` 是 `OllamaEmbeddingSmokeTest`，由 `@EnabledIfEnvironmentVariable(RAG_REAL_OLLAMA=true)` 条件跳过，不是失败测试；真实 Ollama 的检索 smoke 在下面用运行中的 app 单独完成。
- 实际执行 109 条。

与验收点直接相关的测试：

| 测试 | 覆盖 |
|---|---|
| `RrfFusionTest`（4） | 原始 RRF 分 1/61、1/62；两路贡献；chunkId 去重；docId/chunkStrategy/chunkIndex tie-break；topK |
| `SearchRequestValidatorTest`（11） | query/topK/docIds/version/asOf/chunkStrategy/strategy/默认值；`ERR-INVALID_AS_OF` 专用错误码 |
| `KeywordEscaperTest`（3） | `\`/`%`/`_` 转义、`%...%` pattern、200 字符截断 |
| `CitationFormatterTest`（3） | 版本/生效日/章节/页码；缺失段省略、不编造 |
| `SearchVersionModeTest`（3） | 默认 / HISTORICAL / INCLUDE_SUPERSEDED |
| `SearchServiceTest`（6） | keyword-only 不调 embedding；embedding 503/504；维度错误；DB 异常不被吞；hybrid 两路 |
| `SearchIntegrationTest`（8） | 真实 pgvector：结构字段、RRF 分范围、citation、keyword 转义、docIds/version/chunkStrategy 过滤、无结果、内容截断、参数错误、embedding 503、版本三模式、右闭边界、DRAFT |
| `SearchControllerErrorTest`（1） | DB 失败 → 503 `ERR-DB_UNAVAILABLE`，不返回空结果 |
| `ChunkStrategiesTest`（3） | 默认 heading、逗号/重复/大小写、去重、非法值 |
| Phase 1 回归（`DocumentUploadIntegrationTest` 12、`VersionIntegrationTest` 8、`SchemaIntegrationTest` 1 等） | 全部保持绿色 |
| `McpRegistrationSpikeTest`（1） | 低层 List bean 两个 tool 出现在 `listTools()` |

T-VERSION 映射：T-VERSION-02/03/06/07/11 在 `SearchIntegrationTest.tVersion02_03_06_11_versionModesAndRightClosedBoundary` + `tIntSearch01...`（citation）中覆盖；T-VERSION-01 的检索侧断言也由该测试覆盖。

---

## 3. 真实 app smoke

环境：Windows、Docker `pgvector/pgvector:pg16`、Ollama `bge-m3`、`mvn spring-boot:run`。先 `docker compose down -v` 重置本地库。

### 3.1 入库 3 篇公开样例（两套策略）

| 文档 | docKey/version | fixed | heading | 总 chunk | tookMs | 版本状态 |
|---|---|---:|---:|---:|---:|---|
| `hypertension-2024.pdf`（测试摘编） | hypertension-guideline / 2024 | 4 | 6 | 10 | 4437 | 被 2025 版替代后 `SUPERSEDED`，effectiveTo=2024-12-31 |
| `hypertension-2025.pdf` | hypertension-guideline / 2025 | 78 | 231 | 309 | 7820 | `ACTIVE` |
| `enterprise-policy-2026.pdf` | enterprise-internal-control / 2026 | 18 | 45 | 63 | 1835 | `ACTIVE` |

- 合计 382 chunks；两套策略总入库时间约 14.1s（每篇 <30s，3 篇合计 <60s），符合 docs/05 的保守口径。
- 2024 版先入库，2025 版带 `supersedesDocId` 入库；旧版仍保留 chunk，未物理删除。

### 3.2 5 条真实 query（heading + hybrid，asOf=2026-11-09）

完整结果、citation 与每个 case 的 checks 见 [phase2-smoke.md](phase2-smoke.md) / [phase2-smoke-results.json](phase2-smoke-results.json)。

| # | query | 结果 | 首条 citation（摘要） |
|---|---|---|---|
| 1 | 高血压的诊断标准是什么？ | 5 条 ACTIVE v2025 | `《国家基层高血压防治管理指南2025版》v2025（2025-01-01 生效） > 90 mmHg > 第4页` |
| 2 | 诊室血压的诊断阈值是多少？ | 5 条 ACTIVE v2025 | 同上；第 3 条含 `4.2 > 4.2.1 诊断标准` |
| 3 | 企业内部控制中的风险评估包括哪些内容？ | 5 条 ACTIVE v2026 | `《企业内部控制基本规范》v2026（2026-01-01 生效） > 第五条 ... > 第2页` |
| 4 | 内部控制要素有哪些？ | 5 条 ACTIVE v2026 | `... > 第四条 ... > 第2页`；第 3 条为第五条要素 |
| 5 | 高血压患者随访频率如何安排？ | 5 条 ACTIVE v2025 | 第 2 条 `8.1 随访频率 > 第13页` |

人工核对结论：

- 默认查询只返回 `ACTIVE`；旧版 2024 不出现在默认结果中。
- citation 的文档名、版本、生效日、页码来自真实 DB 字段；章节段直接用 Phase 1 落库的 `clausePath`，不做二次编造。
- **已知噪声**：2025 版 PDF 双栏排版，Phase 1 标题识别把个别数值/页眉识别成 clausePath（如 `90 mmHg`、`3 d、至少`）。这是解析层问题，不是 citation 拼接伪造；Phase 2 未回退/改动 Phase 1 解析器。
- fixed 策略不携带 `clauseId`/`clausePath`，citation 只到页码；这是固定窗口的既定限制。

### 3.3 版本与策略 smoke

| case | 结果 |
|---|---|
| `asOf=2024-06-01` | 5 条全部 `SUPERSEDED` v2024，effectiveTo=2024-12-31 |
| `includeSuperseded=true`, asOf=2026-11-09 | 结果同时含 v2025 `ACTIVE` 与 v2024 `SUPERSEDED` |
| `chunkStrategy=fixed` | 固定窗口结果，citation 到页码 |
| `strategy=keyword`（“企业内部控制...风险...”整句） | 0 条：关键词路是 `ILIKE '%整句%'`，不保证整句连续命中；hybrid 才走两路融合 |
| 右闭边界（集成测试） | asOf=2024-12-31 → 旧版；asOf=2025-01-01 → 新版 |
| DRAFT（集成测试） | 永不返回 |

`scripts/smoke-phase2.ps1` 的自动 checks：`defaultOnlyActive=true`、`historicalReturnsSuperseded=true`、`includeSupersededReturnsBoth=true`、`noEmptyCurrentResults=true`。

---

## 4. 评测 smoke（Windows `scripts/eval.ps1`）

- 数据集：[eval/qa.smoke.jsonl](../eval/qa.smoke.jsonl)，8 条（6 可回答 + 2 不可回答）。
- 完整 40 条 QA 属 Phase 3；本阶段没有编造 gold。
- 命令：`powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\eval.ps1`
- 报告：[eval/report.md](../eval/report.md)、[eval/report.json](../eval/report.json)

| chunkStrategy | hit@1 | hit@3 | hit@5 | MRR | p50 | p95 | errors |
|---|---:|---:|---:|---:|---:|---:|---:|
| fixed | 0.1667 | 0.1667 | 0.1667 | 0.1667 | 71.69ms | 94.13ms | 0 |
| heading | 0.5000 | 1.0000 | 1.0000 | 0.7222 | 86.85ms | 159.20ms | 0 |

不可回答题（单独统计，不混入 hit@k）：

| chunkStrategy | falsePositiveRate | noAnswerAccuracy |
|---|---:|---:|
| fixed | 1.0 | 0.0 |
| heading | 1.0 | 0.0 |

说明：

- 8 条 smoke 的指标只用于验证脚本与真实链路，不作为 Phase 3 质量结论；heading hit@5=1.0、MRR=0.7222，fixed 仍差，是真实结果。
- fixed chunk 无 `clauseId`，脚本匹配时在结果无 clause 的情况下回退 `pageRange`；否则 fixed 会被错误地全部判 miss。
- 两路都会返回 topK，所以不可回答题 `falsePositiveRate=1.0` 是预期现象；Phase 3 再评估阈值/相关性过滤。
- 指标阈值（hit@5≥0.85、MRR≥0.65）只报告，不用来伪造数字；脚本只在检索错误时返回非 0。

---

## 5. 性能（10k/策略，合计 20k）

命令：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\perf.ps1
```

- 数据：每套策略插入 10,000 条 synthetic chunks（fixed 10k + heading 10k，合计 20k），测试前 DB 另有 382 条真实样例 chunks。
- 请求：每策略 50 次 API 端到端（含真实 bge-m3 query embedding），topK=5，hybrid；5 次 warm-up 不计入。
- 原始数据：[phase2-perf.json](phase2-perf.json)

| chunkStrategy | chunks | requests | p50 | p95 | p99 | errors |
|---|---:|---:|---:|---:|---:|---:|
| fixed | 10,000 | 50 | 146.87ms | 220.47ms | 242.08ms | 0 |
| heading | 10,000 | 50 | 92.97ms | 131.72ms | 154.03ms | 0 |

结论：p50/p95/p99 均低于 docs/05 的 250ms / 800ms / 1500ms 目标；0 errors。**不编造**：这是本机 Docker + Ollama 的实测，synthetic vectors 复用同一向量，只衡量 SQL/HTTP/RRF 延迟，不代表检索质量，也不承诺其它机器复现同一数字。

### 5.1 30s / 10k 口径（已写入 docs/05 + README）

- 入库 30s：**每套 chunk 策略**；一次上传 fixed+heading 时，3 篇文档合计预算 **<60s**。
- 检索 10k：**每套策略 10k**；性能测试 fixed 10k + heading 10k，**合计 20k**。
- 5 并发/10 并发 60s 的错误率压测未在本轮执行完整版；本轮是每策略 50 次串行 API 端到端，0 errors。`T-PERF-02` 留 Phase 3/后续。

---

## 6. MCP 低层注册 spike

时间盒内完成，结论：**成功**。详见 [phase2-mcp-spike.md](phase2-mcp-spike.md)。

- Spring AI 1.1.8 / MCP SDK 0.18.3。
- `spring.ai.mcp.server.tool-callback-converter=false` 有效。
- **关键坑**：`McpServerAutoConfiguration` 注入 `ObjectProvider<List<SyncToolSpecification>>` 并 flatMap，必须暴露一个 `List<SyncToolSpecification>` bean；两个独立 `SyncToolSpecification` bean 不会被收集（`listTools()` 为空）。
- `McpRegistrationSpikeTest` 断言 `listTools()` 恰好包含 `search_knowledge`、`get_document`。
- 未做生产 `/mcp`、Bearer、mandate、审计；这些留 Phase 5。

---

## 7. P0 行为核对清单

| 要求 | 状态 | 证据 |
|---|---|---|
| `POST /api/v1/search` 字段/默认值 | 通过 | `SearchRequestValidatorTest`、`SearchIntegrationTest` |
| query ≤500、topK 1~20、docIds≤50 UUID、version≤50 | 通过 | 单元 + 集成 400 |
| asOf 格式/范围专用 `ERR-INVALID_AS_OF` | 通过 | 单元 + 集成 |
| `asOf` 默认今天 | 通过 | validator 测试 |
| query 1024 维 bge-m3 | 通过 | 真实 smoke；fake 集成 |
| 向量 SQL `?::vector`、cosine、`ORDER BY <=> LIMIT 30` | 通过 | `SearchRepository`、集成测试 |
| keyword 转义 `\` `%` `_`、`ILIKE ? ESCAPE '\'`、similarity、top30 | 通过 | `KeywordEscaperTest`、`tIntSearch02` |
| query>200 keyword 截断 | 通过 | `KeywordEscaperTest` |
| RRF k=60、rank 从 1、两路 top30、去重、tie-break、原始分 | 通过 | `RrfFusionTest` |
| 版本三模式、DRAFT 永不返回 | 通过 | `SearchVersionModeTest`、`tVersion02_03_06_11...` |
| docIds/version/chunkStrategy 在 SQL 层 | 通过 | `SearchRepository.appendFilters` + 集成过滤测试 |
| 结果字段、content 2000 截断、citation | 通过 | 集成结构/截断测试 |
| 无结果 200 + `[]` | 通过 | `tIntSearch05` |
| embedding 失败 503/504、DB 失败 503，不静默 | 通过 | `SearchServiceTest`、`SearchControllerErrorTest`、集成 embedding 503 |
| HNSW filter under-return 缓解 | 通过 | 事务内 `set_config('hnsw.iterative_scan','relaxed_order',true)`；pgvector 0.8.7 实测支持；所有 vector 集成测试均走该路径 |
| 错误不泄露 SQL/堆栈 | 通过 | `GlobalExceptionHandler` + 错误体测试 |

---

## 8. 风险取舍

1. **fixed chunk 的 clause 元数据缺失**：Phase 1 设计，Phase 2 不改；citation 省略章节，eval 用 pageRange 回退。若验收要求 fixed 也按 clause 匹配，需要 Phase 3 改 FixedWindowChunker 或增加列。
2. **PDF 双栏 clausePath 噪声**：沿用 Phase 1 解析结果；未在 Phase 2 强行修，避免回退/大改解析器。引用仍可定位到页码。
3. **HNSW iterative scan 依赖 pgvector≥0.8**：项目固定 0.8.7；`app.search.hnsw-iterative-scan` 可关闭。未做旧版本降级分支。
4. **性能是串行 50 次/策略**，不是 10 并发 60s；p95 达标，但 `T-PERF-02` 的并发错误率未完整压测。
5. **eval 是 8 条 smoke**，不是 40 条正式评测；fixed 结果差，保留真实值，不调 gold。
6. **MCP spike 只在 test scope**：生产 `/mcp`、鉴权、mandate、审计未做；Phase 5 需重新按 HTTP 层验收。
7. **detectConflicts 仅接受参数**：返回 `[]`，符合 Phase 2 P1 占位；不做冲突检测。

---

## 9. 复现命令

```powershell
# 1) 依赖
docker compose up -d postgres
ollama pull bge-m3
mvn clean test

# 2) 启动
mvn spring-boot:run

# 3) 重置并入库 3 篇（见 docs/samples/README.md 的具体参数）
#    2024 先入，2025 带 supersedesDocId，第三篇 enterprise

# 4) 真实检索 smoke
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\smoke-phase2.ps1

# 5) eval / perf
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\eval.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\perf.ps1
```

环境变量、入库参数与样例来源见 [README.md](../../README.md)、[docs/samples/README.md](../samples/README.md)；未提交 `.env`、真实 token、PII 或样例 PDF。

---

## 10. 最终 commit / 分支

- 分支：`phase2`
- base：`main` @ `080adfb`
- 最终 commit：**待 push 后由 `git rev-parse HEAD` 填入**
- `main` 未被修改/合并；验收方可独立复核 `git diff main..phase2`。
