# rag-mcp-service

企业知识库 RAG 检索服务（Java/Spring Boot + PostgreSQL/pgvector + MCP + 治理层）。

- 当前状态：**Phase 2（检索 + 搜索 API）已验收通过（P0；p99/并发 residual 见报告）**，已合并 `main`；Phase 1 已验收。
- Phase 2 交付：`POST /api/v1/search`（vector/keyword/hybrid + RRF）、版本三模式过滤、SQL 层 docIds/version/chunkStrategy 过滤、citation、8 条 smoke QA、Windows `scripts/eval.ps1`、10k/策略性能脚本、MCP 低层注册 spike。Phase 3 的完整 40 条 QA 尚未开始。
- 验收证据：[docs/acceptance/phase2-report.md](docs/acceptance/phase2-report.md)、[检索 smoke](docs/acceptance/phase2-smoke.md)、[MCP spike](docs/acceptance/phase2-mcp-spike.md)、[eval report](eval/report.md)。
- Phase 1 交付保持不变：Flyway V1 六张表、PDF/DOCX/Markdown/TXT 解析 + Block 模型、FixedWindow/HeadingAware 两套切分、Ollama bge-m3 1024 维 embedding（批量、重试）、文档上传/列表/详情/删除 API、SHA-256 幂等与版本治理字段。

## 技术栈

| 项 | 版本 / 说明 |
|---|---|
| Java | 编译目标 `release=17`；本机用 JDK 21 编译/运行 |
| Spring Boot | 3.5.16 |
| Spring AI | 1.1.8（BOM）+ `spring-ai-starter-model-ollama` |
| 数据库 | PostgreSQL 16 + pgvector 0.8.7 + pg_trgm；Flyway 迁移；JdbcTemplate（无 JPA） |
| 检索 | pgvector cosine（HNSW）+ `pg_trgm` ILIKE/similarity + RRF (k=60) |
| MCP | Phase 2 仅 test-scope spike（`spring-ai-starter-mcp-server-webmvc`）；生产 `/mcp` 留 Phase 5 |
| 解析 | PDFBox 3.0.5、POI 5.4.1 |
| Token | jtokkit 1.1.0，cl100k_base |
| 测试 | JUnit 5 / Mockito / Testcontainers `pgvector/pgvector:pg16` |

## 本地依赖

```powershell
docker compose up -d postgres      # 启动 PostgreSQL（首次自动建 vector / pg_trgm 扩展）
docker compose ps                  # 应为 healthy
ollama pull bge-m3                 # embedding 模型；确认 ollama list 有 bge-m3
mvn -v                             # Maven 3.9+
```

连接默认值：`jdbc:postgresql://localhost:5432/ragdb`，用户 `rag`，密码 `rag_local_pw`。可用环境变量覆盖：

```text
RAG_DB_URL / RAG_DB_USER / RAG_DB_PASSWORD
OLLAMA_BASE_URL (默认 http://localhost:11434)
OLLAMA_EMBEDDING_MODEL (默认 bge-m3)
SERVER_ADDRESS (默认 127.0.0.1，仅监听本机回环)
SERVER_PORT (默认 8080)
```

`.env` 不提交；示例见 `.env.example`。

## 启动

```powershell
mvn spring-boot:run
curl.exe -s http://localhost:8080/actuator/health
```

`/actuator/health` 的 `components.embedding` 会检查 Ollama 是否可达且已拉取 `bge-m3`；服务不会因为 Ollama 未就绪而启动失败，但上传会返回 503/504。

HTTP 默认只监听本机回环（`server.address=${SERVER_ADDRESS:127.0.0.1}`），与 `docs/02-api-contract.md` / `docs/12-mcp-implementation.md` 一致；如确需暴露到其他网卡，显式设置 `SERVER_ADDRESS=0.0.0.0` 并自行加访问控制。启动后可这样确认：

```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen | Select-Object LocalAddress,LocalPort
# 预期 LocalAddress = 127.0.0.1
```

## API（Phase 1）

### 上传文档

`POST /api/v1/documents`，`multipart/form-data`：

| 字段 | 必填 | 说明 |
|---|---|---|
| `file` | 是 | `.pdf/.docx/.md/.markdown/.txt`，单文件 ≤ 20MB，PDF ≤ 500 页 |
| `title` | 否 | 默认文件名去扩展名；≤ 200 字符 |
| `docKey` | 否 | 同一制度的稳定标识；不传用 UUID |
| `version` | 否 | 如 `2025`；≤ 50 字符 |
| `effectiveFrom` | 否 | `YYYY-MM-DD`，默认今天 |
| `effectiveTo` | 否 | `YYYY-MM-DD`；NULL = 长期有效；右闭（最后有效日，含） |
| `authorityLevel` | 否 | `GROUP/REGION/SUBSIDIARY/DEPARTMENT` |
| `scope` | 否 | 如 `group`、`region:上海` |
| `supersedesDocId` | 否 | 替代的旧文档 UUID；必须同 `docKey` |
| `chunkStrategies` | 否 | 默认 `heading`；可传 `fixed,heading`（或重复参数），小写、去重、最多 2 个 |

```powershell
curl.exe -s -X POST http://localhost:8080/api/v1/documents `
  -F "file=@docs/samples/hypertension-2025.pdf" `
  -F "docKey=hypertension-guideline" `
  -F "version=2025" `
  -F "effectiveFrom=2025-01-01" `
  -F "chunkStrategies=fixed,heading"
```

- 201：新文档；200 + `duplicate=true`：同一内容（SHA-256）已存在，直接返回已有文档，**不重新解析、不重新 embedding、不补缺失策略**。
- 响应含 `docId/docKey/version/title/sourceType/status/effectiveFrom/effectiveTo/chunkStrategies/chunkCount/chunkCounts/duplicate/tookMs`；`chunkCounts` 是 fixed/heading 分项计数（`chunkCount` 为总数）。
- 同 `docKey + version` 不同内容：400 `ERR-VALIDATION`（不创建第二份同版本）。
- 同一内容即使换文件名也按 checksum 幂等。

### 列表 / 详情 / 删除

```powershell
curl.exe -s "http://localhost:8080/api/v1/documents?limit=20&offset=0"
curl.exe -s http://localhost:8080/api/v1/documents/<docId>
curl.exe -s -X DELETE http://localhost:8080/api/v1/documents/<docId>   # 204；chunk 级联删除
```

- 列表：`items/total/limit/offset`，`limit` 1~100，`offset` ≥ 0。
- 详情：额外含 `embeddingModel/chunkCounts/supersedesDocId/metadata`。
- 删除不幂等：重复删除或不存在返回 404 `ERR-DOC_NOT_FOUND`；被新版引用的旧版删除后，新版的 `supersedes_doc_id` 由外键 `ON DELETE SET NULL` 置空。

### 错误体

```json
{"code":"ERR-VALIDATION","message":"...","traceId":"...","details":{}}
```

Phase 1 实现的错误码：`ERR-VALIDATION(400)`、`ERR-FILE_TOO_LARGE(413)`、`ERR-UNSUPPORTED_TYPE(415)`、`ERR-PARSE_FAILED(422)`、`ERR-DOC_NOT_FOUND(404)`、`ERR-EMBEDDING_UNAVAILABLE(503)`、`ERR-EMBEDDING_TIMEOUT(504)`、`ERR-DB_UNAVAILABLE(503)`、`ERR-INTERNAL(500)`。错误信息不含绝对路径/堆栈/SQL。

## API（Phase 2）：检索

`POST /api/v1/search`，`application/json`：

```json
{
  "query": "高血压的诊断标准是什么？",
  "topK": 5,
  "docIds": [],
  "version": null,
  "asOf": "2026-11-09",
  "includeSuperseded": false,
  "chunkStrategy": "heading",
  "strategy": "hybrid",
  "detectConflicts": false
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `query` | 是 | ≤ 500 字符；空/全空白 400 |
| `topK` | 否 | 1~20，默认 5 |
| `docIds` | 否 | ≤ 50 个 UUID；空数组不过滤；在 SQL 层过滤 |
| `version` | 否 | ≤ 50 字符；null 不过滤；在 SQL 层过滤 |
| `asOf` | 否 | `YYYY-MM-DD`，默认今天，1900-01-01~2100-12-31；格式/范围错误 `ERR-INVALID_AS_OF` |
| `includeSuperseded` | 否 | 默认 false；true 时允许 `SUPERSEDED`，只要求 `effective_from <= asOf`，忽略 `effective_to` |
| `chunkStrategy` | 否 | `heading`（默认）/ `fixed`；只查对应策略的 chunk |
| `strategy` | 否 | `hybrid`（默认）/ `vector` / `keyword` |
| `detectConflicts` | 否 | P1 占位；当前接受但 `conflicts` 固定返回 `[]` |

```powershell
curl.exe -s -X POST http://localhost:8080/api/v1/search `
  -H "Content-Type: application/json" `
  -d '{\"query\":\"高血压的诊断标准是什么？\",\"topK\":5,\"chunkStrategy\":\"heading\",\"strategy\":\"hybrid\"}'
```

返回字段按 [docs/02-api-contract.md](docs/02-api-contract.md) 2.9：
`query/asOf/chunkStrategy/strategy/tookMs/results/conflicts`；每条结果含
`chunkId/docId/docKey/docVersion/docStatus/effectiveFrom/effectiveTo/title/clauseId/clausePath/pageNo/score/content/citation`。

- `content` > 2000 字符截断并加 `…`。
- `score` 是**原始 RRF 分** `Σ 1/(60+rank)`（典型 0.016~0.033），不是 0~1 概率；两路各 top 30，按 chunkId 去重，同分按 `docId + chunkStrategy + chunkIndex` 稳定排序。
- `citation`：`《title》v{version}（{effectiveFrom} 生效） > {clausePath} > 第{pageNo}页`；没有版本/章节/页码就省略对应段，不编造。
- 无结果：HTTP 200 + `results: []`；embedding 失败 503/504，DB 失败 503，不静默返回空结果。
- 版本三模式：默认只看 `ACTIVE` + 生效区间；过去 `asOf` 允许 `ACTIVE/SUPERSEDED` 且仍看 `effective_to`；`includeSuperseded=true` 允许旧版且忽略 `effective_to`。`DRAFT` 永不返回。
- `fixed` chunk 不携带 `clauseId`（Phase 1 设计），citation 会省略章节段；这是固定窗口的已知限制。
- HNSW + metadata filter：pgvector 0.8.7 下 vector SQL 在事务内执行 `SET LOCAL hnsw.iterative_scan = relaxed_order`，缓解过滤导致 HNSW 候选不足；可用 `app.search.hnsw-iterative-scan=false` 关闭。详见 [docs/05-non-functional.md](docs/05-non-functional.md) 3 与验收报告。

## 切分口径

- **FixedWindowChunker**：目标 600 tokens、overlap 80 tokens；优先段落边界，段落超长按句切，单句超长按 token 硬切；表格不中间截断，超长表格按行切并重复表头。
- **HeadingAwareChunker**：按标题层级分节；每节 ≤ 900 tokens 不切；超长递归到 600/80；chunk 带 `clause_id`/`clause_path`；标题本身不单独成块；无标题文档 fallback 到 fixed。
- **Token 口径**：jtokkit cl100k；中文为近似值，不承诺与 bge-m3 完全一致。
- **标题识别**：PDF 优先 outline/bookmark，其次字号 > 正文中位数 1.2 倍且加粗，最后匹配 `第X章/节/条` 或 `^\d+(\.\d+)*\s`；DOCX 用 Heading 1~6 样式（无样式时加粗+大字号降级）；Markdown 用 ATX 标题；TXT 不识别标题（`clause_path` 为 NULL）。
- 页码仅 PDF 有（1-based）；DOCX/Markdown/TXT 为 NULL。PDF 段落按页切分（跨页段落取起始页）。
- 全空/扫描件（无可提取文本）返回 422；v1 不做 OCR。

## Embedding

- 抽象 `EmbeddingService`：生产实现是 `SpringAiEmbeddingService`，委托 Spring AI `EmbeddingModel`（Ollama `bge-m3`）；测试使用确定性 fake（1024 维），CI 不依赖 Ollama。
- 批量 32（配置 `app.embedding.batch-size`，上限 32）；失败重试 2 次（500ms/1000ms）；单批超时 120s → 504；不可用/维度错误/零向量 → 503。
- 校验维度 1024、拒绝零向量；`kb_document.embedding_model='bge-m3'`；两套 chunk 都写向量。

## 版本语义（Phase 1 部分）

- 幂等键是文件内容 SHA-256；同内容始终返回已有文档。
- 带 `supersedesDocId` 时：旧版置 `SUPERSEDED`；**仅当旧版 `effective_to` 为空或晚于“新版 `effective_from` - 1 天”时，才收敛到该日期**；旧值更早则保留原值（右闭，最后有效日含当天）。旧 chunk 不删除；新版 `ACTIVE`。该边界由 `VersionIntegrationTest.supersedesKeepsEarlierOldEffectiveTo` 回归覆盖。
- 同一 `docKey + scope` 的 ACTIVE 版本生效区间（`COALESCE(scope,'')` 比较）不允许重叠；无 `supersedesDocId` 的重叠返回 400 `ERR-VALIDATION`。
- `UNIQUE NULLS NOT DISTINCT (doc_key, version)`：同一 docKey 下 NULL version 也不能重复。
- 删除是硬删除；版本替代不是删除。

## 测试

```powershell
mvn test          # 单元 + Testcontainers 集成；默认不跑真实 Ollama
```

- 集成测试用 Testcontainers `pgvector/pgvector:pg16` + `@TestConfiguration` fake `EmbeddingService`，不依赖本机 Ollama。
- 格式 fixture 位于 `src/test/resources/fixtures/`（验收 kit 提供，`FormatFixturesTest` 守护，可直接用于 Gate 2 上传）；集成测试的 PDF/DOCX 仍在运行时用 PDFBox/POI 生成，`docs/samples/*.pdf` 不提交。
- 真实 Ollama smoke（手动，条件执行，默认跳过）：

```powershell
$env:RAG_REAL_OLLAMA='true'
mvn test -Dtest=OllamaEmbeddingSmokeTest
```

- 验收自动检查（Gate 0/1 + health）：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\accept-phase1.ps1
```

> `scripts/accept-phase1.ps1` 的 “six tables” 期望顺序已在验收方提交 `ffa9368` 中修复；修复后 Gate 0/1/2 全部 PASS。最新证据见 `docs/acceptance/phase1-report.md` 第 2 节。

## 评测（Phase 2 smoke）

Phase 2 只做 8 条 smoke QA（`eval/qa.smoke.jsonl`），完整 40 条 QA 属 Phase 3，未编造 gold。

前置：3 篇 `docs/samples/*.pdf` 已用 `chunkStrategies=fixed,heading` 入库，应用与 Ollama 已启动。

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\eval.ps1
```

- 分别跑 `fixed` / `heading`，输出 hit@1/3/5、MRR、p50/p95、errors，写 `eval/report.md` + `eval/report.json`。
- 不可回答题单独统计 `falsePositiveRate` / `noAnswerAccuracy`，不混入 hit@k。
- 匹配口径：`docKey` 相同；gold 有 `clauseId` 且结果也有 `clauseId` 时按 clause 匹配；结果没有 `clauseId` 时回退到 `pageRange`（fixed chunk 不携带 clause_id）。
- 脚本用 `powershell.exe`（Windows PowerShell 5.1）可执行；执行策略用 `-ExecutionPolicy Bypass`。请求体显式 UTF-8，响应按原始字节 UTF-8 解码，避免中文乱码。
- 当前 smoke：heading hit@5=1.0、MRR=0.7222；fixed hit@5=0.1667。fixed 明显差于 heading，是真实结果，不调 gold 改数字；Phase 3 再扩到 40 条并分析切分。
- 2026-11-09 最后一次 smoke 延迟：fixed p50=139.07ms/p95=207.31ms；heading p50=152.81ms/p95=191.21ms；errors=0。
- 退出码：有 HTTP/检索错误返回 1；指标阈值只报告，不因 smoke 指标未达标而伪造通过。

## 性能

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\perf.ps1
```

- 默认每套策略 10,000 chunks（fixed 10k + heading 10k = 20k），每策略 50 次 API 端到端请求（含真实 bge-m3 query embedding），topK=5，hybrid。
- 结果写 `docs/acceptance/phase2-perf.json`；脚本结束删除 synthetic 数据，不污染演示库。
- 2026-11-09 实测（重复两次，均达标；取最后一次）：fixed p50=106.93ms / p95=210.84ms；heading p50=232.04ms / p95=300.50ms；0 errors。p50/p95 均低于 docs/05 的 250ms/800ms 目标。
- 口径：10k 是**每套策略**，两套合计 20k；入库 30s 是**每套策略**，两套合计 <60s。详见 [docs/05-non-functional.md](docs/05-non-functional.md) 2.1/2.2。
- 注意：synthetic chunks 从现有真实 chunk 的向量池循环取样，性能数字衡量 SQL/HTTP/RRF 延迟，不代表检索质量；重复测量有波动，报告记录两次结果。

## MCP 低层 Spike

Phase 2 只在 test scope 验证 Spring AI 1.1.8 的低层 `SyncToolSpecification` 注册，**没有生产 `/mcp`**：

- 结论：`tool-callback-converter=false` 有效；但必须暴露**一个 `List<SyncToolSpecification>` bean**，两个独立 bean 不会被 auto-config 收集。
- 证据：`McpRegistrationSpikeTest` 用 `McpSyncServer.listTools()` 断言两个 tool；详见 [docs/acceptance/phase2-mcp-spike.md](docs/acceptance/phase2-mcp-spike.md)。
- Phase 5 仍需补 Bearer、mandate、审计、HTTP `tools/list` 与 Inspector 验收。

## 已知限制与文档冲突

1. **`UNIQUE (doc_key, version)` vs `UNIQUE NULLS NOT DISTINCT`**：`docs/03-data-model.md` 的 DDL 是普通 `UNIQUE`，本实现按 Phase 1 交付口径使用 `UNIQUE NULLS NOT DISTINCT (doc_key, version)`（PostgreSQL 15+），避免同一 docKey 写入多条 NULL version。已记录在验收报告。
2. **T-VERSION-01 属检索断言**：`docs/06-test-plan.md` 把它描述为“默认检索只返回新版”；Phase 2 已用 `SearchIntegrationTest` 覆盖默认 ACTIVE/历史 asOf/includeSuperseded。
3. **表格跨页**：v1 不合并跨页/多栏表格；超长表格按行切并重复表头；这是已知降级，不对指标做承诺。
4. **`@Disabled`**：没有长期禁用测试；`OllamaEmbeddingSmokeTest` 用 `@EnabledIfEnvironmentVariable` 条件跳过，属于手动真实模型 smoke，不是被禁用的失败测试。
5. **fixed chunk 无 clause_id**：Phase 1 的 FixedWindowChunker 不写 `clause_id`/`clause_path`，所以 fixed 检索结果 citation 省略章节段；heading 有章节。评测的 fixed/heading 对比要按这个口径看。
6. **PDF 双栏标题噪声**：2025 版 PDF 是双栏排版，Phase 1 的标题识别会把一些数值/页眉误识别成 `clausePath`（例如 “90 mmHg”）。检索忠实使用落库的 `clausePath`，Phase 2 不改 Phase 1 解析器；Phase 3 可再评估标题解析。
7. **Phase 2 范围**：没有 WorkItem/Approval/Mandate、生产 MCP、前端、聊天、agent 编排、评测 UI；完整 40 条 QA 与 `@Tag("perf")` CI job 留 Phase 3/后续。
8. **性能口径**：10k/策略是本地 Docker + Ollama 实测；不承诺在其它机器复现同一数字，但脚本可复现测量方法。

## 目录

```text
src/main/java/com/lqx/ragmcp/
  api/          # 文档/搜索接口、统一错误体、traceId
  search/       # SearchService / SearchRepository / RRF / citation / 参数校验
  chunk/        # FixedWindowChunker / HeadingAwareChunker / TokenCounter
  document/     # 上传编排、事务、版本治理、JdbcTemplate 仓储
  embedding/    # EmbeddingService + Spring AI Ollama 实现 + health indicator
  parse/        # Block 模型、PDF/DOCX/MD/TXT 解析
  config/       # 配置属性
src/main/resources/db/migration/V1__init.sql
src/test/java/  # 单元 + Testcontainers 集成 + 条件真实 Ollama smoke + MCP spike
scripts/        # accept-phase1.ps1 / eval.ps1 / perf.ps1 / smoke-phase2.ps1
eval/           # qa.smoke.jsonl + report.md/json
docs/acceptance/phase2-report.md
```

