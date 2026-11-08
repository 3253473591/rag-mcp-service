# Phase 1 验收报告

- 日期：2026-11-08
- 实现者：本地 coding agent（Phase 1 会话）
- 验收人：待验收方
- 分支 / commit：`phase1`（commit 见本报告末行；未合并 main）
- 环境：Windows 11；JDK 21.0.9（`maven.compiler.release=17`）；Maven 3.9.9；Spring Boot 3.5.16；Spring AI 1.1.8；Docker `pgvector/pgvector:pg16`；Ollama `bge-m3:latest`（1024 维）
- 结论：**待验收（实现方未自行宣布通过）**

> 本阶段只覆盖 Phase 1（骨架 + 入库）。检索 / RRF / 评测 / MCP / WorkItem / 审批 / 前端 / 聊天 / agent 编排未实现，也不在本报告范围内。

---

## 1. 构建与测试

| 项 | 结果 |
|---|---|
| `mvn test` | BUILD SUCCESS |
| 测试数 / 失败 / 错误 / 跳过 | **65 / 0 / 0 / 1** |
| 关键测试 | T-UNIT-PARSE-01~03、T-UNIT-CHUNK-01~07、T-EMBED-01~06、T-INT-DB-01、T-INT-DOC-01~06、T-VERSION-01/04/05/09/10/11 |

唯一 Skipped 是 `OllamaEmbeddingSmokeTest`：真实 Ollama smoke，用 `@EnabledIfEnvironmentVariable(RAG_REAL_OLLAMA)` 条件执行，默认不进入 CI；不是 `@Disabled`，失败测试没有被隐藏。

类级结果（surefire）：

```text
FixedWindowChunkerTest            5 / 0 / 0 / 0
HeadingAwareChunkerTest           4 / 0 / 0 / 0
PdfDocumentParserTest             3 / 0 / 0 / 0
DocxDocumentParserTest            1 / 0 / 0 / 0
MarkdownDocumentParserTest        4 / 0 / 0 / 0
FormatFixturesTest                5 / 0 / 0 / 0
FileNamesTest                     4 / 0 / 0 / 0
DocumentServiceValidationTest     9 / 0 / 0 / 0
SpringAiEmbeddingServiceTest      8 / 0 / 0 / 0
EmbeddingHealthIndicatorTest      1 / 0 / 0 / 0
SchemaIntegrationTest             1 / 0 / 0 / 0
DocumentUploadIntegrationTest    12 / 0 / 0 / 0
VersionIntegrationTest            7 / 0 / 0 / 0
OllamaEmbeddingSmokeTest          1 skipped（条件执行）
汇总：Tests run: 65, Failures: 0, Errors: 0, Skipped: 1
```

真实 Ollama smoke（单独执行）：

```powershell
$env:RAG_REAL_OLLAMA='true'
mvn test -Dtest=OllamaEmbeddingSmokeTest
# Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

证据：`target/surefire-reports/`。

---

## 2. 自动验收脚本结果

命令：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\accept-phase1.ps1
```

实际输出（脚本退出码 1）：

```text
[PASS] Gate 0 - pom.xml exists
[PASS] Gate 0 - Flyway V1 exists
[PASS] Gate 0 - mvn test                     BUILD SUCCESS
[PASS] Gate 1 - postgres healthy             healthy
[PASS] Gate 1 - extensions                   pg_trgm,vector
[FAIL] Gate 1 - six tables                   expected eval_run,kb_chunk,kb_document,mcp_audit_log,mandate,work_item;
                                             got:      eval_run,kb_chunk,kb_document,mandate,mcp_audit_log,work_item
[PASS] Gate 1 - chunk_strategy column        1
[PASS] Gate 1 - chunk_strategy check is lowercase
[PASS] Gate 1 - HNSW index                   1
[PASS] Gate 1 - trigram index                1
[PASS] Gate 1 - supersedes FK is SET NULL    n
[PASS] Gate 2 - app health                   status=UP
```

**唯一 FAIL 是脚本自身的期望字符串排序错误**：脚本写的是 `...mcp_audit_log,mandate...`，而 PostgreSQL `ORDER BY tablename` 的正确顺序是 `...mandate,mcp_audit_log...`（`mandate` < `mcp_audit_log`）。数据库实际 6 张表齐全（见第 3 节 `\dt` 等价输出）。实现方**没有修改验收脚本**，请验收方决定是否把脚本期望值改为：

```text
eval_run,kb_chunk,kb_document,mandate,mcp_audit_log,work_item
```

除该条外，Gate 0 / Gate 1 / health 全部 PASS。

---

## 3. Docker / Flyway / 表结构

| 检查 | 结果 |
|---|---|
| `rag-postgres` healthy | PASS |
| 6 张表 | PASS（`eval_run, kb_chunk, kb_document, mandate, mcp_audit_log, work_item`） |
| `vector` + `pg_trgm` | PASS（vector 0.8.7 / pg_trgm 1.6） |
| HNSW + GIN | PASS（`USING hnsw (embedding vector_cosine_ops)`、`USING gin (content gin_trgm_ops)`） |
| `chunk_strategy` 小写 + CHECK + 默认 heading | PASS（`CHECK (chunk_strategy = ANY (ARRAY['fixed','heading']))`，默认 `'heading'`） |
| 唯一约束 | PASS（`uk_kb_document_checksum`、`uk_kb_document_key_version ... NULLS NOT DISTINCT`、`uk_kb_chunk_strategy (document_id, chunk_strategy, chunk_index)`） |
| `supersedes_doc_id ON DELETE SET NULL` | PASS（`confdeltype = n`） |
| 无 `section_path`、统一 `clause_path` | PASS |
| 无 `ddl-auto=update` | PASS（无 JPA；仅 Flyway） |

实际 SQL 证据：

```text
   tablename
---------------
 eval_run
 kb_chunk
 kb_document
 mandate
 mcp_audit_log
 work_item
(6 rows)

 extname | extversion
---------+------------
 pg_trgm | 1.6
 vector  | 0.8.7

 idx_kb_chunk_embedding     | CREATE INDEX ... USING hnsw (embedding vector_cosine_ops)
 idx_kb_chunk_content_trgm  | CREATE INDEX ... USING gin (content gin_trgm_ops)
 idx_kb_chunk_document_id   | CREATE INDEX ... USING btree (document_id)
 uk_kb_document_checksum    | CREATE UNIQUE INDEX ... (checksum)
 uk_kb_document_key_version | CREATE UNIQUE INDEX ... (doc_key, version) NULLS NOT DISTINCT
 uk_kb_chunk_strategy       | CREATE UNIQUE INDEX ... (document_id, chunk_strategy, chunk_index)
```

证据文件：`target/smoke/sql-evidence.txt`（本地生成，未入库）。

---

## 4. 上传 / 解析 / 切分

真实运行 `mvn spring-boot:run`（本地 PostgreSQL + 真实 Ollama bge-m3）后的 curl 结果：

| 文件 | 格式 | HTTP | docId | fixed | heading | 备注 |
|---|---|---|---:|---:|---:|---|
| `guide-2024.md` | MD | 201 | `d168a73e-b6cf-4eea-a18b-a180b15bd40d` | 1 | 3 | 旧版 |
| `guide-2025.md` | MD | 201 | `a9098da8-2b57-49fe-b6c7-312f09882230` | 1 | 5 | 新版，supersedes 旧版 |
| `travel-policy.txt` | TXT | 201 | `f7ebcc47-9127-40b1-a19f-3f96d8fd6fa9` | 1 | 1 | 后续删除用于 204/404 证据 |
| `guide-2025-renamed.md` | MD（同内容） | 200 | 同上 `a909...` | 1 | 5 | `duplicate=true` |
| 空 `empty.txt` | TXT | 422 | — | — | — | `ERR-PARSE_FAILED` |
| `bad.exe` | 不支持 | 415 | — | — | — | `ERR-UNSUPPORTED_TYPE` |

PDF / DOCX 的 4 格式覆盖由两部分完成：`FormatFixturesTest` 对已提交的格式 fixture（`src/test/resources/fixtures/sample.docx`、`sample.md`、`sample.txt`、`sample-gb18030.txt`、`sample-scanned.pdf`）做解析/切分断言；`T-INT-DOC-01/01b` 用 Testcontainers 上传测试运行时生成的文本型 PDF 与 DOCX，断言 201 与落库。仓库不提交 `docs/samples/*.pdf`（真实语料由验收方准备）；格式 fixture 仅用于 Gate 2，不进入评测语料。扫描件 PDF 返回 422 由 `scannedPdfFixtureFailsWithParseFailed` 与 `scannedPdfReturns422AndWritesNothing` 覆盖。

chunk 抽样 SQL：

```text
document_id                          | chunk_strategy | chunks | min_tokens | max_tokens | null_clause_path | null_embedding
-------------------------------------+----------------+--------+------------+------------+------------------+---------------
a9098da8-... (2025 版)               | fixed          |      1 |        241 |        241 |                1 |              0
a9098da8-... (2025 版)               | heading        |      5 |         28 |         53 |                0 |              0
d168a73e-... (2024 版)               | fixed          |      1 |        103 |        103 |                1 |              0
d168a73e-... (2024 版)               | heading        |      3 |         17 |         33 |                0 |              0
```

结论：

- 同一份文档一次生成 `fixed` + `heading` 两套 chunk；`chunk_strategy` 全部小写。
- `heading` chunk 全部有 `clause_path`（null 数 0）；`fixed` 按设计允许 `clause_path` 为 NULL。
- `token_count > 0`；`chunk_index` 由 `uk_kb_chunk_strategy` 保证在 `(document_id, chunk_strategy)` 内唯一。
- `chunkCount` 与 DB 一致：2025 版 `chunkCount=6`（fixed 1 + heading 5），详情接口与 SQL 一致。

证据：`target/smoke/smoke.log`、`target/smoke/sql-evidence.txt`。

---

## 5. Embedding

| 检查 | 结果 |
|---|---|
| 真实 bge-m3 是否测过 | **是**（本机 Ollama `bge-m3:latest`，1024 维；`OllamaEmbeddingSmokeTest` + 真实 app 上传） |
| `embedding IS NULL` 数量 | **0**（14 个 chunk） |
| 维度不是 1024 的 chunk | **0** |
| `vector_dims(embedding)` | 1024 |
| `embedding_model` | `bge-m3` |
| 停止/不可用 Ollama 时上传 | **503 `ERR-EMBEDDING_UNAVAILABLE`，且 0 行落库** |

```text
 total_chunks | null_embedding | wrong_dim | zero_tokens
--------------+----------------+-----------+-------------
           14 |              0 |         0 |           0

 embedding_model
-----------------
 bge-m3
```

失败注入方式（不干扰共用 Ollama）：以 `OLLAMA_BASE_URL=http://127.0.0.1:1`、`SERVER_PORT=8081` 启动第二个实例，上传 `embedding-failure.md`：

```text
{"code":"ERR-EMBEDDING_UNAVAILABLE","message":"embedding 服务不可用，请确认 Ollama 与 bge-m3 已就绪","traceId":"73219f717f7e4604"}
HTTP=503
SELECT count(*) FROM kb_document WHERE doc_key='embedding-failure-demo';  -> 0
SELECT count(*) FROM kb_chunk ...                                        -> 0
```

集成测试另用 fake `EmbeddingService` 覆盖“失败不写半截数据”（`embeddingFailureReturns503AndWritesNothing`）与重试/维度/零向量/超时语义（`SpringAiEmbeddingServiceTest`）。未直接停止本机 Ollama 服务，避免影响其他会话；此处用不可达 base URL 走同一条真实 HTTP 失败路径。

---

## 6. 幂等

| 检查 | 结果 |
|---|---|
| 重复上传 HTTP | 200 |
| `duplicate` | true |
| `docId` 不变 | 是（`a9098da8-...`） |
| chunk 数不变 | 是（`chunkCount=6`，DB 仍 6） |
| 同内容换文件名 | 返回已有文档（`guide-2025-renamed.md`） |
| 同 docKey+version 不同内容 | 400 `ERR-VALIDATION`，未创建第二份 |
| `CHECKSUM` 计数 | 每个 checksum 仅 1 行 |

```text
{"docId":"a9098da8-...","duplicate":true,"chunkCount":6,...}

checksum                                                         | count
-----------------------------------------------------------------+-------
 6da1be335ec476bf8534c279dda8bc9fe0b956012cf21ead6bec323639f2d1dc |     1
 76ef54929cdc8d2e98287a3ae71e1805f92ab5d04f5ce68eef208e2aafdc8bc6 |     1
```

同 docKey+version 不同内容（overlap-b.txt 以 version=v1 上传）：

```text
HTTP 400
{"code":"ERR-VALIDATION","message":"同一 docKey + version 已存在；如需替代旧版请使用新的 version 和 supersedesDocId","traceId":"c40834efa49f492a"}
```

---

## 7. 版本治理（Phase 1 部分）

```sql
SELECT version, status, effective_from, effective_to, supersedes_doc_id
FROM kb_document WHERE doc_key = 'hypertension-guideline' ORDER BY effective_from;
```

```text
 version |   status   | effective_from | effective_to |          supersedes_doc_id
---------+------------+----------------+--------------+--------------------------------------
 2024    | SUPERSEDED | 2024-01-01     | 2024-12-31   | 
 2025    | ACTIVE     | 2025-01-01     |              | d168a73e-b6cf-4eea-a18b-a180b15bd40d
```

| 检查 | 结果 |
|---|---|
| 旧版 `SUPERSEDED` | PASS |
| 旧版 `effective_to=2024-12-31`（新版生效日 - 1，右闭） | PASS |
| 新版 `ACTIVE`，`effective_from=2025-01-01` | PASS |
| 旧 chunk 未删除 | PASS（旧版仍 4 个 chunk） |
| 重叠上传 → 400 `ERR-VALIDATION` | PASS（见下） |
| 删除被引用旧版 → 204，新版 `supersedes_doc_id=NULL` | PASS |
| 同 docKey+scope 非重叠区间允许并存 | PASS（集成测试 `nonOverlappingActiveIntervalsAreAllowed`） |
| 删除旧版后旧 chunk 级联删除、新 chunk 保留 | PASS（3 / 0） |

重叠响应（真实 app）：

```text
HTTP 400
{"code":"ERR-VALIDATION","message":"同一 docKey + scope 存在生效区间重叠的活跃版本","traceId":"f64c1b1b4b67489e",
 "details":{"conflictDocId":"7de04659-8219-4895-acac-f4fa292a89aa","conflictVersion":"v1",
            "conflictEffectiveFrom":"2024-01-01","conflictEffectiveTo":null}}
```

删除被引用旧版（合成文档 `cascade-demo`）：

```text
DELETE old  -> HTTP 204
DELETE old  -> HTTP 404 ERR-DOC_NOT_FOUND
GET new     -> "supersedesDocId": null
SELECT count(*) FROM kb_chunk WHERE document_id = '<old>';  -> 0
SELECT count(*) FROM kb_chunk WHERE document_id = '<new>';  -> 3
```

---

## 8. 文档管理

| 检查 | 结果 |
|---|---|
| 列表分页 | PASS：`total=4, limit=20, offset=0`，`items` 4 条 |
| 详情字段完整 | PASS：`docKey/version/status/effectiveFrom/effectiveTo/supersedesDocId/embeddingModel/chunkCount/chunkCounts/metadata` |
| 删除 204 + chunk 级联 | PASS |
| 重复删除 404 | PASS |
| 删除不存在 404 | PASS |
| `limit`/`offset` 非法 400 | PASS（集成测试 + 真实 app 校验） |

```text
list -> {"items":[...4 条...],"total":4,"limit":20,"offset":0}
detail -> {"docId":"a9098da8-...","embeddingModel":"bge-m3","status":"ACTIVE",
           "effectiveFrom":"2025-01-01","effectiveTo":null,
           "supersedesDocId":"d168a73e-...","chunkCount":6,
           "chunkCounts":{"fixed":1,"heading":5},"chunkStrategies":["fixed","heading"]}
DELETE travel -> HTTP 204
GET deleted travel -> HTTP 404 {"code":"ERR-DOC_NOT_FOUND",...}
```

---

## 9. 工程与安全

| 检查 | 结果 |
|---|---|
| `.env` 未提交 | PASS（`.gitignore` 忽略；仓库无 `.env`） |
| 无真实 token/PII | PASS（仅公开技术样例与测试 fixture） |
| 无 `ddl-auto: update` | PASS（无 JPA，只有 Flyway） |
| Flyway 迁移完整 | PASS（`V1__init.sql`） |
| README 命令可复现 | PASS |
| 样例 PDF 未提交 | PASS（`docs/samples/*.pdf` 被 ignore；测试内生成） |
| Windows 命令可执行 | PASS（README 用 PowerShell/`curl.exe`） |
| 代码分层可替换 | PASS（`DocumentParser` / `Chunker` / `EmbeddingService` 均为接口） |
| 日志不记录文件内容/绝对路径/token | PASS（仅 docId/docKey/策略/数量/耗时） |
| 错误体 `{code,message,traceId,details?}` | PASS（含 `X-Trace-Id` 响应头） |
| 不写半截数据 | PASS（解析/切分/embedding 完成后才进事务；失败注入 0 行） |

---

## 10. 证据路径

| 证据 | 路径 |
|---|---|
| surefire | `target/surefire-reports/` |
| 自动验收脚本输出 | `docs/acceptance/phase1-last-run.md`（gitignore） |
| 真实 smoke 的 HTTP 输出 | `target/smoke/smoke.log`（本地生成） |
| SQL 证据 | `target/smoke/sql-evidence.txt`（本地生成） |
| 验收脚本运行日志 | `target/acceptance-run.log`（本地生成） |

`target/` 未提交；关键结论已转写在本报告。

---

## 11. 阻塞 / 遗留 / 冲突

| 项 | 影响 | 处理 |
|---|---|---|
| `accept-phase1.ps1` 的 “six tables” 期望字符串排序错误 | 脚本误报 FAIL（Gate 1） | 未改验收脚本；建议把期望值改为 `eval_run,kb_chunk,kb_document,mandate,mcp_audit_log,work_item`；DB 证据见第 3 节 |
| `docs/03-data-model.md` 用 `UNIQUE (doc_key, version)`；Phase 1 口径要求 `UNIQUE NULLS NOT DISTINCT` | 契约细节差异 | 按 Phase 1 口径实现 `NULLS NOT DISTINCT`（PG15+），本报告记录；未改 03 文档 |
| `T-VERSION-01` 在 06 中描述为“默认检索只返回新版” | Phase 1 无检索接口，不能用检索断言 | Phase 1 验证 `SUPERSEDED`/唯一 ACTIVE/旧 chunk 保留；检索侧默认过滤留 Phase 2 |
| 表格跨页/多栏合并 | 解析质量限制 | v1 不合并跨页表格；超长表格按行切并重复表头；README 已说明 |
| 真实 Ollama 停服务注入 | 未直接停共用 Ollama | 用不可达 base URL 走真实 HTTP 失败路径；集成测试用 fake 覆盖“不写半截” |
| 真实 app smoke 的 PDF/DOCX | 仓库无样例 PDF | PDF/DOCX 由 Testcontainers 集成测试覆盖（测试内生成 fixture）；验收时请用验收方样例 PDF 复跑 Gate 2 |
| Phase 2+ 功能 | 不在本阶段 | 检索/RRF/评测/MCP/WorkItem/审批均未实现 |

---

## 12. 签收

| 项 | 内容 |
|---|---|
| 验收结论 | 待验收方填写 |
| 验收人 |  |
| 日期 |  |
| Commit | `phase1` 分支最新提交（见回报消息） |
| 备注 | 自动脚本唯一 FAIL 为脚本期望排序错误，非实现缺陷 |
