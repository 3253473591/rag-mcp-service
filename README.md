# rag-mcp-service

企业知识库 RAG 检索服务（Java/Spring Boot + PostgreSQL/pgvector + MCP + 治理层）。

- 当前状态：**Phase 6（R1-R4 收尾）在 `phase6` 分支实现，待验收，未合并 `main`**；Phase 1/2/3/4/5 已验收。
- Phase 6 交付：中文“第X条”条款 heading 与 `clause_id`；PDF 双栏阅读顺序 + heading 候选清洗；`page_end` 页面跨度 + fixed chunk 主导 section 元数据 + eval pageRange 相交匹配；10 并发残余风险如实复测。详见 [phase6-report](docs/acceptance/phase6-report.md)。
- Phase 5 交付：生产 `spring-ai-starter-mcp-server-webmvc`、Streamable HTTP `/mcp`、Bearer 401、两个低层 tool（`search_knowledge` / `get_document`）、mandate（存在/过期/工具/topK/docIds/文档范围）、`mcp_audit_log`（query 只存哈希）、未知 tool `-32601`、坏参数 `-32602`、内部错误 `-32603`。真实 MCP Java client 集成测试覆盖 initialize/tools/list/tools/call；默认协商协议版本实测为 **2025-11-25**，2025-06-18 客户端请求也按 2025-06-18 回显。
- Phase 4 交付（已验收）：`POST/GET /api/v1/work-items`、带 `X-Admin-Token` 的 approve/deny；审批只能缩小工具/文档/topK 范围；显式 `allowedDocIds` 语义；重复审批 409；管理员 token 常量时间比较、未配置时 401；approve 事务写 `work_item` + `mandate`。
- Phase 3 交付（已验收）：`eval/qa.jsonl` 40 条（35 可回答 + 5 不可回答，覆盖 v2025/v2024/enterprise）、fixed/heading 正式评测、gold 结构校验脚本、T-INT-EVAL-01、10k/策略 3 轮性能、10 并发 60s 脚本。heading hit@5=0.8857、MRR=0.6905；fixed hit@5=0.3429（如实报告，不改 gold）。
- Phase 2 交付保持不变：`POST /api/v1/search`（vector/keyword/hybrid + RRF）、版本三模式过滤、SQL 层 docIds/version/chunkStrategy 过滤、citation、Windows `scripts/eval.ps1`。
- 验收证据：[docs/acceptance/phase5-report.md](docs/acceptance/phase5-report.md)、[phase4-report](docs/acceptance/phase4-report.md)、[phase3-report](docs/acceptance/phase3-report.md)、[phase2-report](docs/acceptance/phase2-report.md)、[检索 smoke](docs/acceptance/phase2-smoke.md)、[MCP spike](docs/acceptance/phase2-mcp-spike.md)、[eval report](eval/report.md)、[perf](docs/acceptance/phase3-perf.json)、[concurrency](docs/acceptance/phase3-concurrency.json)。
- Phase 1 交付保持不变：Flyway V1 六张表、PDF/DOCX/Markdown/TXT 解析 + Block 模型、FixedWindow/HeadingAware 两套切分、Ollama bge-m3 1024 维 embedding（批量、重试）、文档上传/列表/详情/删除 API、SHA-256 幂等与版本治理字段。

## 技术栈

| 项 | 版本 / 说明 |
|---|---|
| Java | 编译目标 `release=17`；本机用 JDK 21 编译/运行 |
| Spring Boot | 3.5.16 |
| Spring AI | 1.1.8（BOM）+ `spring-ai-starter-model-ollama` |
| 数据库 | PostgreSQL 16 + pgvector 0.8.7 + pg_trgm；Flyway 迁移；JdbcTemplate（无 JPA） |
| 检索 | pgvector cosine（HNSW）+ `pg_trgm` ILIKE/similarity + RRF (k=60) |
| MCP | `spring-ai-starter-mcp-server-webmvc` 1.1.8（生产依赖）；Streamable HTTP `/mcp`；SDK `mcp-core 0.18.3` |
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
ADMIN_TOKEN (Phase 4 管理员 token；为空时 approve/deny 全部 401，服务仍可启动)
MCP_AUTH_TOKEN (Phase 5 /mcp 的 Bearer token；与 ADMIN_TOKEN 分开；为空时 /mcp 全部 401，服务仍可启动)
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
`chunkId/docId/docKey/docVersion/docStatus/effectiveFrom/effectiveTo/title/clauseId/clausePath/pageNo/pageEnd/score/content/citation`。

- `content` > 2000 字符截断并加 `…`。
- `score` 是**原始 RRF 分** `Σ 1/(60+rank)`（典型 0.016~0.033），不是 0~1 概率；两路各 top 30，按 chunkId 去重，同分按 `docId + chunkStrategy + chunkIndex` 稳定排序。
- `citation`：`《title》v{version}（{effectiveFrom} 生效） > {clausePath} > 第{pageNo}页`；没有版本/章节/页码就省略对应段，不编造。
- `pageNo` 是 chunk 起始页，`pageEnd` 是包含结束页（Phase 6 additive，`>= pageNo`）；citation 仍以起始页为准。
- 无结果：HTTP 200 + `results: []`；embedding 失败 503/504，DB 失败 503，不静默返回空结果。
- 版本三模式：默认只看 `ACTIVE` + 生效区间；过去 `asOf` 允许 `ACTIVE/SUPERSEDED` 且仍看 `effective_to`；`includeSuperseded=true` 允许旧版且忽略 `effective_to`。`DRAFT` 永不返回。
- Phase 6 起 `fixed` chunk 也会继承覆盖区间内主导 section 的 `clauseId`/`clausePath`（窗口仍是 600/80），并记录 `pageNo..pageEnd`；没有可继承 heading 时才为 NULL。
- HNSW + metadata filter：pgvector 0.8.7 下 vector SQL 在事务内执行 `SET LOCAL hnsw.iterative_scan = relaxed_order`，缓解过滤导致 HNSW 候选不足；可用 `app.search.hnsw-iterative-scan=false` 关闭。详见 [docs/05-non-functional.md](docs/05-non-functional.md) 3 与验收报告。

## API（Phase 4）：治理 WorkItem / Approval / Mandate

治理链路：`WorkItem(PENDING)` → 管理员 approve → `APPROVED` + `Mandate(ACTIVE)`；或 deny → `DENIED`。审批范围只能**缩小**，不能加入未申请的工具/文档或放大 topK。Mandate 过期是 Phase 5 的逻辑状态（`now() >= expires_at`），Phase 4 不写回 EXPIRED。

### 创建 / 查看 WorkItem

`POST /api/v1/work-items`（无鉴权，内部接口）：

```json
{
  "requester": "agent-report-writer",
  "purpose": "为体检报告生成检索高血压指南依据",
  "requestedTools": ["search_knowledge", "get_document"],
  "requestedDocIds": ["0d5b1f6e-6c1a-4a3d-9a8a-6d0f0f7a1b2c"],
  "requestedTopK": 5
}
```

| 字段 | 必填 | 规则 |
|---|---|---|
| `requester` | 是 | 非空字符串；v1 不做身份校验 |
| `purpose` | 是 | ≤ 500 字符 |
| `requestedTools` | 是 | 1~5 个；只允许 `search_knowledge` / `get_document` |
| `requestedDocIds` | 否 | `null`/不传 = 全部文档；`[]` = 不申请任何文档；数组最多 50 个 UUID |
| `requestedTopK` | 否 | 1~20，默认 5 |

- 201 返回 `{"workItemId":"...","status":"PENDING","createdAt":"..."}`，落库 `work_item`。
- `GET /api/v1/work-items/{workItemId}` 返回 `requester/purpose/requestedTools/requestedDocIds/requestedTopK/status/decidedBy/decidedAt/decisionReason/createdAt`；`APPROVED` 时附 `mandate`。
- 不存在 404 `ERR-WORKITEM_NOT_FOUND`；非法 UUID 400 `ERR-VALIDATION`。不做 WorkItem 列表/审批 UI。

### 批准 WorkItem

`POST /api/v1/work-items/{workItemId}/approve`，Header `X-Admin-Token`：

```json
{
  "ttlSeconds": 3600,
  "allowedTools": ["search_knowledge"],
  "allowedDocIds": ["0d5b1f6e-6c1a-4a3d-9a8a-6d0f0f7a1b2c"],
  "maxTopK": 3,
  "reason": "报告生成场景，限高血压指南"
}
```

- 200 返回 `workItemId`、`status=APPROVED`、`mandate{mandateId,allowedTools,allowedDocIds,maxTopK,expiresAt}`。
- 事务内更新 `work_item`（APPROVED + decidedBy/at/reason）并插入 `mandate`（ACTIVE）；任一步失败整体回滚，不留半截。
- `ttlSeconds` 60~86400，默认 3600；`expiresAt = now + ttl`。
- `allowedTools` 必须是 `requestedTools` 子集；`maxTopK` 必须 1~20 且 ≤ `requestedTopK`；只能缩小。
- 重复 approve/deny、approve 已 DENIED、deny 已 APPROVED → 409 `ERR-APPROVAL_INVALID`，且不会重复创建 mandate。

**`allowedDocIds` 显式语义**（关键）：

| 创建时 `requestedDocIds` | approve 时 `allowedDocIds` | 结果 |
|---|---|---|
| `null`/不传（全部） | 字段缺失 | 400 `ERR-VALIDATION`（不默认全量） |
| `null`/不传（全部） | 显式 `null` | 允许，mandate 为全部文档 |
| `null`/不传（全部） | 数组 | 允许，作为全量的子集 |
| `[]`（无） | 字段缺失或 `[]` | mandate 为 `[]`（不允许任何文档） |
| `[]`（无） | 显式 `null` 或非空数组 | 400 `ERR-VALIDATION` |
| `[A,B]` | 字段缺失 | mandate 为 `[A,B]` |
| `[A,B]` | `[A]` 或 `[]` | 允许 |
| `[A,B]` | 显式 `null` 或含 `C` | 400 `ERR-VALIDATION` |

实现方式：`ApproveWorkItemRequest` 用自定义 `@JsonSetter` 记录 `allowedDocIds` 字段是否出现，区分“缺失”与“显式 null”；JSONB 中 SQL NULL = 全部、`[]` = 无。

### 拒绝 WorkItem

`POST /api/v1/work-items/{workItemId}/deny`，Header `X-Admin-Token`，body `{"reason":"申请范围过大"}`（可选）。

- PENDING → DENIED，记录 `decidedBy/decidedAt/decisionReason`；200 返回 `workItemId/status=DENIED/decidedAt`。
- 已 APPROVED/DENIED 再 deny → 409；不存在 404；无/错 token 401。

### 管理员鉴权与配置

- `application.yml`：`app.admin.token: ${ADMIN_TOKEN:}`；`.env.example` 只放 `ADMIN_TOKEN=` 占位，不提交真实值。
- token 缺失/错误/服务端未配置 → 401 `ERR-ADMIN_UNAUTHORIZED`；未配置时服务可正常启动（fail closed）。
- 比较使用 `MessageDigest.isEqual` 常量时间比较，不使用 `String.equals`；不记录 token 日志。

```powershell
$env:ADMIN_TOKEN = "local-dev-token-not-committed"
mvn spring-boot:run

$create = @{
  requester = "agent-report-writer"
  purpose = "报告生成"
  requestedTools = @("search_knowledge")
  requestedDocIds = @()
  requestedTopK = 5
} | ConvertTo-Json
$created = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/work-items `
  -ContentType "application/json" -Body $create
$created.workItemId

$approve = @{ allowedDocIds = @(); maxTopK = 3 } | ConvertTo-Json
Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/v1/work-items/$($created.workItemId)/approve" `
  -Headers @{ "X-Admin-Token" = $env:ADMIN_TOKEN } `
  -ContentType "application/json" -Body $approve
```

> 用 PowerShell 的 `Invoke-RestMethod` 避免 `curl.exe -d '<json>'` 在 Windows 原生参数传递下的引号问题；CI/curl 场景可用 `--data-binary "@body.json"`。

### Phase 4 错误码

`ERR-ADMIN_UNAUTHORIZED(401)`、`ERR-WORKITEM_NOT_FOUND(404)`、`ERR-APPROVAL_INVALID(409)`；参数/缩小范围失败沿用 `ERR-VALIDATION(400)`。错误体仍为 `{code,message,traceId,details}`。

Phase 4 **不写** `mcp_audit_log`、不做 MCP 端点/mandate 强制校验/过期拒绝；这些属于 Phase 5。

## 切分口径

- **FixedWindowChunker**：目标 600 tokens、overlap 80 tokens；优先段落边界，段落超长按句切，单句超长按 token 硬切；表格不中间截断，超长表格按行切并重复表头。Phase 6 起每个 chunk 继承主导 section 的 `clause_id`/`clause_path`，并记录 `page_no..page_end`。
- **HeadingAwareChunker**：按标题层级分节；每节 ≤ 900 tokens 不切；超长递归到 600/80；chunk 带 `clause_id`/`clause_path`；标题本身不单独成块；无标题文档 fallback 到 fixed。
- **Token 口径**：jtokkit cl100k；中文为近似值，不承诺与 bge-m3 完全一致。
- **标题识别**：PDF 优先 outline/bookmark，其次字号 > 正文中位数 1.2 倍且加粗，最后匹配 `第X章/节/条`（中文/阿拉伯数字、可带空格）或清洗后的 `^\d+(\.\d+)*\s`；数值/单位/表格碎片（`90 mmHg`、`3 d`、`mg qd`）不再成为 heading。双栏 PDF 按左栏→右栏重排。DOCX 用 Heading 1~6 样式（无样式时加粗+大字号降级）；Markdown 用 ATX 标题；TXT 不识别标题（`clause_path` 为 NULL）。
- 页码仅 PDF 有（1-based）；DOCX/Markdown/TXT 为 NULL。PDF 段落按页切分（跨页段落取起始页），chunk 记录 `[page_no,page_end]`。
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
- Phase 4 治理测试 33 个：`MandatePolicyTest` 13（缩小规则/显式文档语义/Phase 5 只读校验）、`AdminAuthTest` 3（常量时间比较/fail closed）、`GovernanceIntegrationTest` 16（T-GOV-01~07 + 边界 + 事务回滚）、`GovernanceAdminUnconfiguredIntegrationTest` 1（未配置 token 仍启动且 401）。
- Phase 5 MCP 测试 22 个：`McpAuthTest` 4（Bearer 解析/常量时间/fingerprint）、`McpToolServiceTest` 3（业务错误/-32602/-32603 不泄露）、`McpIntegrationTest` 14（真实 Streamable HTTP initialize/tools/list/tools/call + mandate 矩阵 + audit + 未知 tool/坏参数）、`McpUnconfiguredTokenIntegrationTest` 1（token 未配置仍启动但 401）。
- 全量 `mvn test`：**188 tests，0 failures/errors，1 skipped**（手动真实 Ollama smoke）。
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

## 评测（Phase 3/6：40 条正式 QA）

`eval/qa.jsonl` 共 40 条：35 条可回答 + 5 条不可回答，覆盖 `hypertension-guideline` v2025（17）、`enterprise-internal-control` v2026（11）、`hypertension-guideline` v2024 历史（7，逐题 `asOf=2024-06-01`）。`eval/qa.smoke.jsonl` 保留为 8 条 smoke。

前置：3 篇 `docs/samples/*.pdf` 已用 `chunkStrategies=fixed,heading` 入库，应用与 Ollama 已启动。

```powershell
# gold 结构校验：逐题确认 clauseId/pageRange 在真实 DB 中存在
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate-gold.ps1

# 正式评测：默认读 eval/qa.jsonl；-QaPath eval/qa.smoke.jsonl 可跑 smoke
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\eval.ps1
```

- 分别跑 `fixed` / `heading`，输出 hit@1/3/5、MRR、p50/p95、errors，写 `eval/report.md` + `eval/report.json`（含 dataset sha256、git commit、embedding、chunk strategies、topK、逐题 rank/citation）。
- 不可回答题单独统计 `falsePositiveRate` / `noAnswerAccuracy`，不混入 hit@k；errors 单独计数且不进 hit@k/MRR 分母。
- 匹配口径：`docKey` 相同；gold 有 `clauseId` 且结果也有 `clauseId` 时按 clause 匹配；结果没有 `clauseId` 时用 chunk `[pageNo,pageEnd]` 与 `pageRange` 相交判断（`pageEnd` 缺省等于 `pageNo`）。不同 clause 仍是 miss，不能靠同页放宽。
- 逐题可选 `asOf` / `includeSuperseded` 覆盖全局 `-AsOf`；2024 历史题即用此能力。
- 脚本兼容 Windows PowerShell 5.1；请求用 `HttpClient` + 原始字节 UTF-8 解码（`Invoke-WebRequest` 曾在实测中出现 19.8s 客户端假延迟）。p50/p95 采用应用 `tookMs`，客户端 p95 在 JSON 的 `clientP95Ms`。
- 2026-11-09 Phase 6 实测（40 条，248 chunks；gold 未改）：heading hit@1=0.7714、hit@3=0.8857、hit@5=0.9429、MRR=0.8319、p50=128ms、p95=389ms；fixed hit@1=0.6571、hit@3=0.7143、hit@5=0.7429、MRR=0.6867、p50=75ms、p95=214ms；errors=0。
- Phase 3 历史对照（382 chunks）：heading hit@5=0.8857、MRR=0.6905；fixed hit@5=0.3429、MRR=0.3214。两轮都未修改 gold 来抬指标。
- `eval/*.jsonl` 通过 `.gitattributes` 固定 LF，保证 Windows/Unix 下 dataset SHA-256 一致；当前 hash 仍为 `1487e261...`。
- 退出码：有 HTTP/检索错误返回 1；指标阈值只报告，不因 fixed 未达标而伪造通过。

## 性能

```powershell
# 10k/策略，3 轮；写 docs/acceptance/phase3-perf.json
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\perf.ps1 -Rounds 3

# 10 并发 60s；写 docs/acceptance/phase3-concurrency.json
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\perf-concurrency.ps1 -DurationSeconds 60
```

- 10k/策略（fixed 10k + heading 10k = 20k，另 248 真实 chunks），每轮每策略 50 次，10 次 warm-up，topK=5，hybrid，真实 bge-m3 embedding；插入前后 `VACUUM (ANALYZE)` 避免 autovacuum 干扰。
- 2026-11-09 最终 3 轮 p95/p99：fixed 42.72/97.19、64.62/91.87、120.88/136.88ms；heading 33.28/39.51、121.71/131.54、88.21/93.50ms；全部 <250ms/<800ms/<1500ms，0 errors。详见 [phase3-perf.json](docs/acceptance/phase3-perf.json)。
- **关键修复**：原先 `kb_chunk JOIN kb_document ORDER BY embedding <=> ?` 被 PostgreSQL 规划成 nested loop + 全量 top-N sort，HNSW 实际没被使用；10k 时串行尚可、10 并发会打满 DB。现在先在 SQL 层解析 eligible docIds，再在 `kb_chunk` 上走 HNSW（`document_id IN (...) AND chunk_strategy=?`），最后只为候选取文档元数据；`EXPLAIN` 走 `idx_kb_chunk_embedding`。embedding 并发池也从固定 2 改为可配置 `app.embedding.max-concurrent-calls=10`。
- 10 并发 60s Phase 3 实测四次：requests 2076/2851/1803/2166，errors 0（错误率 0%），p50 157/136/130/119ms，p95 **808.59 / 552.07 / 1129.14 / 826.31ms**，p99 1233.82/991.34/4679.77/2118.88ms。**错误率达标，p95 未稳定 <800ms**；长尾与直接对 Ollama `/api/embed` 做 10 并发的 p99≈3.0s 同量级，GC 最大 pause 45ms、Hikari 无失败，判定为 embedding 排队/长尾而非 SQL/GC。详见 [phase3-concurrency.json](docs/acceptance/phase3-concurrency.json) 与 [phase3-report.md](docs/acceptance/phase3-report.md) 第 5 节。
- 10 并发 60s Phase 6 复测一次：requests=1057，errors=0，p50=262.12ms，p95=**1780.18ms**，p99=5050.61ms，max=7935.39ms。**仍未达标，且比 Phase 3 更差**；本轮没有加 cache 伪造结果。证据：[phase6-concurrency.json](docs/acceptance/phase6-concurrency.json)。
- `hnsw-iterative-scan=false` 实测没有收益（heading p95=473ms、p99=1032ms，默认 true 为 33~122/39~137ms），且存在 under-return 风险，保持默认 true。
- 口径：10k 是**每套策略**，两套合计 20k；入库 30s 是**每套策略**，两套合计 <60s。详见 [docs/05-non-functional.md](docs/05-non-functional.md) 2.1/2.2。
- synthetic chunks 从真实 chunk 向量池循环取样，只衡量 SQL/HTTP/RRF/embedding 延迟，不代表检索质量；本机数字不承诺跨机复现。

## MCP（Phase 5，生产）

### 连接方式

- 端点：`http://127.0.0.1:8080/mcp`（Streamable HTTP；POST JSON-RPC + GET SSE + DELETE）。
- 所有请求必须带：

```http
Authorization: Bearer <MCP_AUTH_TOKEN>
```

- 无/错 token、服务端未配置 token → HTTP **401**：

```json
{"code":"ERR-MCP_UNAUTHORIZED","message":"MCP token 缺失或错误","traceId":"..."}
```

- `MCP_AUTH_TOKEN` 与 `ADMIN_TOKEN` 完全分开；未配置时服务可启动，但 `/mcp` 全 401（fail closed）。
- 默认只监听 `127.0.0.1`；不要直接把 `/mcp` 暴露公网。

### 协议版本

- MCP SDK 0.18.3 默认客户端请求 **2025-11-25**，服务端按请求回显；**实测默认协商为 2025-11-25**。
- 客户端仅声明 `2025-06-18` 时，服务端也回显 2025-06-18（`docs/12` 的 v1 目标版本可用）。
- **不承诺 2026-07-28**。

### tools

`tools/list` 恰好两个，均为只读、且每次调用必须带 `mandateId`：

| tool | 必填 | 说明 |
|---|---|---|
| `search_knowledge` | `mandateId`, `query` | 检索；可选 `topK`（1~20，默认 5）、`docIds`（≤50）、`version`、`asOf`、`includeSuperseded`、`chunkStrategy`、`detectConflicts`；最多返回 10 条 |
| `get_document` | `mandateId`, `docId` | 只返回文档元数据，不返回全文 |

- 不注册 resources/prompts/completions；`tool-callback-converter=false`，应用里的 `@Tool` 不会被自动发布。
- 业务错误是 tool error（`CallToolResult.isError=true` + `{"code":"ERR-...","message":"..."}`），**不是 HTTP 403**。
- 未知 tool → JSON-RPC `-32601`；坏参数 → `-32602`；内部异常 → `-32603`（不暴露堆栈）。

### 最小调用流程

```powershell
# 1) 管理员创建/批准 WorkItem，拿到 mandateId（Phase 4）
# 2) MCP Inspector 连接
$env:MCP_AUTH_TOKEN = "local-dev-token-not-committed"
mvn spring-boot:run
npx @modelcontextprotocol/inspector
# Transport: Streamable HTTP
# URL: http://localhost:8080/mcp
# Header: Authorization: Bearer $env:MCP_AUTH_TOKEN
# 先 initialize，再：
#   tools/call search_knowledge {"mandateId":"...","query":"高血压诊断标准","topK":5}
#   tools/call get_document     {"mandateId":"...","docId":"..."}
```

- 无 token → 401；有 token 但无 `mandateId` → tool error `ERR-MANDATE_REQUIRED`（不是 HTTP 403）。
- 审计：每次 `tools/call` 写 `mcp_audit_log`；不含 query 原文，只存参数 SHA-256 与 token SHA-256 前 12 位。
- 真实 HTTP 证据：`mvn -Dtest=McpIntegrationTest test`（MCP Java client；替代手工 Inspector）。
- 低层注册结论仍见 [docs/acceptance/phase2-mcp-spike.md](docs/acceptance/phase2-mcp-spike.md)：必须一个 `List<SyncToolSpecification>` bean。

## 已知限制与文档冲突

1. **`UNIQUE (doc_key, version)` vs `UNIQUE NULLS NOT DISTINCT`**：`docs/03-data-model.md` 的 DDL 是普通 `UNIQUE`，本实现按 Phase 1 交付口径使用 `UNIQUE NULLS NOT DISTINCT (doc_key, version)`（PostgreSQL 15+），避免同一 docKey 写入多条 NULL version。已记录在验收报告。
2. **T-VERSION-01 属检索断言**：`docs/06-test-plan.md` 把它描述为“默认检索只返回新版”；Phase 2 已用 `SearchIntegrationTest` 覆盖默认 ACTIVE/历史 asOf/includeSuperseded。
3. **表格跨页**：v1 不合并跨页/多栏表格；超长表格按行切并重复表头；这是已知降级，不对指标做承诺。
4. **`@Disabled`**：没有长期禁用测试；`OllamaEmbeddingSmokeTest` 用 `@EnabledIfEnvironmentVariable` 条件跳过，属于手动真实模型 smoke，不是被禁用的失败测试。
5. **fixed chunk 的 clause/page 元数据是启发式**：Phase 6 起 fixed chunk 会继承覆盖区间内主导 section 的 `clause_id`/`clause_path`，并记录 `page_no..page_end`；但一个 600-token 窗口跨多个 heading 时只有一个 section 能胜出，fixed hit@5=0.7429 仍低于 heading 0.9429。未改 gold。
6. **PDF 双栏只修到 clausePath/主正文顺序**：Phase 6 按 gutter 重排左右栏并清洗 heading 候选，`90 mmHg`/`3 d`/`20 g` 不再成为 clausePath；表格/流程图仍可能跨栏交错，**没有**还原全文顺序。Q05 等少数题仍可能取到错误 section；不宣称 R3 全文已修。
7. **企业内控 heading 有 clause_id，但 QA gold 仍是 pageRange**：企业 heading 48/49 有 `clause_id`（唯一 NULL 是封面标题），但 11 条 enterprise QA 没有逐条升级为 clauseId，dataset hash 保持 `1487e...`；Q18 本轮 top-1 是“第三条（目标）”，但 page-only 匹配仍可能命中同页错误条款。这是 page-only gold 的已知粗粒度，不是 R4 未修。
8. **10 并发 p95 未达标**：Phase 6 一次 60s 复测 requests=1057、errors=0、p50=262.12ms、p95=1780.18ms、p99=5050.61ms，差于 Phase 3 的 A/B/C/D（808/552/1129/826ms）。根因仍是 Ollama embedding 排队/长尾；query 缓存对 `perf-concurrency.ps1` 的唯一 query 基准无效，本轮没有加缓存伪造 p95。要硬保证需专用推理/限流隔离/更多预热。
9. **性能口径**：10k/策略是本地 Docker + Ollama 实测；不承诺在其它机器复现同一数字，但脚本可复现测量方法。p99 受 PostgreSQL checkpoint/autovacuum 与 Ollama 影响，报告已记录。
10. **Phase 3/4 范围（历史）**：Phase 3 未包含 WorkItem/Approval/Mandate、生产 MCP、前端、聊天、agent 编排、评测 UI；Phase 4 已补治理 API； Phase 5 已补生产 MCP、mandate 强制校验与审计（本分支，未合并 main）。
11. **`docs/02-api-contract.md` 2.5 旧措辞**：该节写“`requestedDocIds` 为空/不传表示申请全部文档”，与 Phase 4 任务书及 [docs/03-data-model.md](docs/03-data-model.md) 的 `NULL=全部，[]=无` 冲突。本实现按任务书 + data model，`null`=全部、`[]`=无，并用集成测试覆盖；`docs/02` 未改动，留给验收方决定是否同步契约文字。
12. **`allowedTools=[]`**：允许管理员显式批准 0 个工具（单调缩小，不扩大权限；mandate 实际不可用），未额外定义为 400。
13. **治理数据保留**：v1 不自动清理 `work_item`/`mandate`/`mcp_audit_log`；审计建议保留 30 天，由运维处理。
14. **MCP 协议版本**：SDK 0.18.3 默认客户端请求 2025-11-25，服务端实际回显 2025-11-25；仅请求 2025-06-18 时回显 2025-06-18。不承诺 2026-07-28。
15. **未知 tool 的 code**：SDK 0.18.3 内部返回 `-32602`；本服务按 `docs/02`/任务书在 HTTP gate 返回 `-32601`。已知 tool 仍走 SDK schema/session 管线。
16. **MCP topK/空范围的取舍**：`topK>10` 时检索后截断输出到 10 条，不把输入上限改成 10；`allowedDocIds=[]` 返回成功空结果（不调用检索、不泄露），不是 tool error。
17. **mandateId 是 bearer secret**：v1 不绑定调用方身份，拿到 mandateId 可重放；多租户/多 session 隔离不做。
18. **MCP 参数错误层级**：`docs/02` 3.2 写“参数错误 → tool error `ERR-VALIDATION`”，而 3.4 / `docs/12` 7.1 / Phase 5 任务书要求坏参数 → JSON-RPC `-32602`。本实现按后者；业务错误仍是 tool error，未改 `docs/02` 契约文字。

## 目录

```text
src/main/java/com/lqx/ragmcp/
  api/          # 文档/搜索/治理接口、统一错误体、traceId
  search/       # SearchService / SearchRepository / RRF / citation / 参数校验
  governance/   # WorkItem/Mandate 记录、审批策略、JdbcTemplate 仓储、AdminAuth
  chunk/        # FixedWindowChunker / HeadingAwareChunker / TokenCounter
  document/     # 上传编排、事务、版本治理、JdbcTemplate 仓储
  embedding/    # EmbeddingService + Spring AI Ollama 实现 + health indicator
  parse/        # Block 模型、PDF/DOCX/MD/TXT 解析
  config/       # 配置属性
  eval/         # QA dataset/gold matcher/scorer/eval service
  mcp/          # Phase 5 MCP：Bearer filter、低层 tool 注册、mandate tool 服务、audit
src/main/resources/db/migration/V1__init.sql
src/test/java/  # 单元 + Testcontainers 集成 + 条件真实 Ollama smoke + MCP 真实 HTTP client
scripts/        # accept-phase1.ps1 / eval.ps1 / validate-gold.ps1 / perf.ps1 / perf-concurrency.ps1 / smoke-phase2.ps1
eval/           # qa.jsonl + qa.smoke.jsonl + report.md/json
docs/acceptance/phase2-report.md
docs/acceptance/phase3-report.md
docs/acceptance/phase3-perf.json
docs/acceptance/phase3-concurrency.json
docs/acceptance/phase4-report.md
docs/acceptance/phase5-report.md
```

