# Phase 1 验收清单：骨架 + 入库

- 关联：[09-handoff.md](../09-handoff.md) Phase 1、[07-acceptance.md](../07-acceptance.md) AC-01/02/03/04/05/08/21、[03-data-model.md](../03-data-model.md)、[11-retrieval-and-eval-spec.md](../11-retrieval-and-eval-spec.md)
- 验收人：本人 / 辅助验收 agent
- 实现者：新对话（Phase 1）
- 结论：通过 / 不通过（默认不通过，直到所有 gate 有证据）

> Phase 1 只验“骨架 + 入库”。检索/RRF、评测、MCP、WorkItem/审批、MCP Inspector 属于 Phase 2–5，不在本清单内。

---

## 0. 前置条件

| 项 | 要求 |
|---|---|
| 环境 | Windows + JDK 17 或 21（`pom.xml` 设 `maven.compiler.release=17`）+ Maven 3.9+ + Docker + Ollama |
| 模型 | `ollama pull bge-m3`，`ollama list` 能看到 |
| 数据库 | `docker compose up -d postgres` 后 `rag-postgres` 为 healthy |
| 样例 | `docs/samples/README.md` 的 3 篇；不提交 PDF |
| 环境变量 | `.env` 从 `.env.example` 复制；`.env` 不提交 |
| 代码 | 无未提交的本地魔改；`mvn test` 可跑 |

> 本机当前就绪状态见 [phase1-readiness.md](phase1-readiness.md)。Maven、Docker CLI、bge-m3 未就绪时不要开始 Gate 0。
>
> 自动检查（覆盖 Gate 0/1 + health，结果写 `phase1-last-run.md`）：
>
> ```powershell
> powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\accept-phase1.ps1
> ```
>
> 脚本不替代 API/切分/embedding/幂等/版本的人工检查；那些仍按下面 Gate 逐项跑。

---

## 1. Gate 0：构建与测试

```powershell
mvn -q test
Get-ChildItem target\surefire-reports -Filter '*.txt' | Select-String 'Tests run'
```

通过标准：

- `BUILD SUCCESS`，0 failure / 0 error。
- 至少有这些测试：
  - `T-UNIT-PARSE-01..03`
  - `T-UNIT-CHUNK-01..06`
  - `T-EMBED-*`
  - `T-INT-DOC-01/02/05/06`
  - `T-VERSION-01/04/05/09/10`
- 没有长期 `@Disabled`；临时禁用要有原因和恢复日期。

证据：surefire 汇总、测试类名、失败数。

---

## 2. Gate 1：Docker / Flyway / 表结构

```powershell
docker compose up -d postgres
docker compose ps
docker compose exec -T postgres psql -U rag -d ragdb -c "\dt"
docker compose exec -T postgres psql -U rag -d ragdb -c "SELECT extname FROM pg_extension WHERE extname IN ('vector','pg_trgm') ORDER BY 1;"
docker compose exec -T postgres psql -U rag -d ragdb -c "SELECT indexname, indexdef FROM pg_indexes WHERE tablename IN ('kb_document','kb_chunk') ORDER BY 1;"
```

通过标准：

- 6 张表存在：`kb_document`、`kb_chunk`、`work_item`、`mandate`、`mcp_audit_log`、`eval_run`。
- `vector`、`pg_trgm` 扩展存在。
- `kb_chunk` 有 `chunk_strategy`、`clause_id`、`clause_path`、`embedding`；没有旧的 `section_path`。
- `chunk_strategy` 的 CHECK 是小写 `fixed` / `heading`，默认 `heading`。
- HNSW 索引（`vector_cosine_ops`）和 GIN trigram 索引存在。
- `kb_document.checksum` 唯一；`(doc_key, version)` 唯一；`(document_id, chunk_strategy, chunk_index)` 唯一。
- `supersedes_doc_id` 外键是 `ON DELETE SET NULL`。
- 没有 `ddl-auto=update`。

证据：`\dt`、扩展查询、索引定义、`information_schema.columns`。

---

## 3. Gate 2：解析 / 两套切分 / 上传

上传 4 种格式，每种都带两套策略：

```powershell
curl.exe -s -w "`n%{http_code}`n" -X POST http://localhost:8080/api/v1/documents `
  -F "file=@docs/samples/hypertension-2025.pdf" `
  -F "docKey=hypertension-guideline" `
  -F "version=2025" `
  -F "effectiveFrom=2025-01-01" `
  -F "chunkStrategies=fixed,heading"
```

DOCX / MD / TXT 用同结构；没有样例时用临时小文件，但 PDF 必须是文本型。

通过标准：

- 4 种格式均返回 201（或重复上传 200 + `duplicate=true`）。
- 同一 PDF 一次生成两套 chunk；DB 中 `chunk_strategy` 同时有 `fixed` 和 `heading`。
- `chunk_strategy` 值为小写。
- `heading` chunk 有 `clause_path`（有标题的文档）；`fixed` 可以没有，但 `page_no` 尽量保留。
- `token_count > 0`；`chunk_index` 在 `(document_id, chunk_strategy)` 内唯一。
- `chunkCount` 与 DB 一致；若实现提供 `chunkCounts`，`{fixed,heading}` 都要对。
- 空文件 / 扫描件 / 不支持扩展名分别返回 422 / 422 / 415。
- 解析或 embedding 失败时，不留下半截 `kb_document` / `kb_chunk`。

SQL 抽样：

```sql
SELECT chunk_strategy, count(*)
FROM kb_chunk
WHERE document_id = '<docId>'
GROUP BY 1 ORDER BY 1;

SELECT count(*) FROM kb_chunk WHERE document_id = '<docId>' AND clause_path IS NULL;
SELECT count(*) FROM kb_chunk WHERE document_id = '<docId>' AND token_count <= 0;
```

证据：curl 响应、上述 SQL 结果。

---

## 4. Gate 3：Embedding

```sql
SELECT count(*) FROM kb_chunk WHERE document_id = '<docId>' AND embedding IS NULL;
SELECT vector_dims(embedding) FROM kb_chunk WHERE document_id = '<docId>' LIMIT 1;
SELECT embedding_model FROM kb_document WHERE id = '<docId>';
```

通过标准：

- 两套 chunk 都有 embedding；`embedding IS NULL` 为 0。
- 维度为 1024。
- `embedding_model` 为 `bge-m3`。
- 不允许零向量；停止 Ollama 时上传返回 503/504，且不写脏数据。

证据：SQL + 停 Ollama 的失败注入记录。

---

## 5. Gate 4：幂等

- 同一文件重复上传：HTTP 200，`duplicate=true`，`docId` 不变。
- 重复上传后 `kb_chunk` 总数不变。
- 同一内容换文件名：仍返回已有文档（按 checksum 幂等）。
- 同一 `docKey + version` 不同内容：不得创建第二个同版本文档；预期 400 `ERR-VALIDATION` 或 409（记录实际行为，不能静默成功）。
- 并发上传同一文件：唯一索引兜底，返回已有文档（可选）。

SQL：

```sql
SELECT count(*) FROM kb_document WHERE checksum = '<sha256>';
SELECT count(*) FROM kb_chunk WHERE document_id = '<docId>';
```

证据：两次 curl + count 对比。

---

## 6. Gate 5：版本治理（Phase 1 部分）

先传旧版，再传新版：

```powershell
# 旧版
curl.exe -s -X POST http://localhost:8080/api/v1/documents `
  -F "file=@docs/samples/hypertension-2024.pdf" `
  -F "docKey=hypertension-guideline" -F "version=2024" -F "effectiveFrom=2024-01-01" `
  -F "chunkStrategies=fixed,heading"

# 新版（用旧版 docId）
curl.exe -s -X POST http://localhost:8080/api/v1/documents `
  -F "file=@docs/samples/hypertension-2025.pdf" `
  -F "docKey=hypertension-guideline" -F "version=2025" -F "effectiveFrom=2025-01-01" `
  -F "supersedesDocId=<旧版docId>" -F "chunkStrategies=fixed,heading"
```

```sql
SELECT version, status, effective_from, effective_to, supersedes_doc_id
FROM kb_document
WHERE doc_key = 'hypertension-guideline'
ORDER BY effective_from;
```

通过标准：

- 旧版 `status=SUPERSEDED`，`effective_to=2024-12-31`（右闭，最后有效日）。
- 新版 `ACTIVE`，`effective_from=2025-01-01`。
- 旧版 chunk 不删除；旧版仍可查。
- 同一 `docKey+scope` 活跃区间重叠、且没有 `supersedesDocId` → 400 `ERR-VALIDATION`。
- 删除被新版引用的旧版：204；新版 `supersedes_doc_id` 变为 NULL；不出现 FK 错误。用合成文档做这个测试，不要删演示语料。

证据：上述 SQL + 重叠上传的 400 响应 + 删除旧版后的 SQL。

---

## 7. Gate 6：文档管理

- `GET /api/v1/documents?limit=20&offset=0`：分页字段 `items/total/limit/offset` 正确。
- `GET /api/v1/documents/{docId}`：`docKey/version/status/effectiveFrom/effectiveTo/supersedesDocId/embeddingModel/chunkCount` 存在。
- `DELETE /api/v1/documents/{docId}`：204；`kb_chunk` 级联删除。
- 重复删除：404 `ERR-DOC_NOT_FOUND`。
- 删除不存在：404。

证据：curl + SQL count。

---

## 8. Gate 7：工程与安全

- `.env` 未提交；`.env.example` 存在；无真实 token/PII。
- 所有 DDL 走 Flyway；`application.yml` 没有 `ddl-auto: update`。
- 日志不记录文件内容、query 原文、token、绝对路径。
- README 有启动、上传、测试命令。
- `docs/samples/*.pdf` 不提交。
- 代码分层：`Chunker` / `EmbeddingModel` 可替换。
- Windows 下命令可执行（不要只写 `./scripts/eval.sh`）。

证据：`Select-String` 检查、README、目录列表。

---

## 9. 通过标准

所有 Gate 0–7 为 PASS，且证据可复现，才判 Phase 1 通过。

任何一条 FAIL 即不通过，按“Gate + 命令 + 实际结果 + 期望结果”退回新对话。

`SKIP` 只允许“样例 PDF 暂缺”，但必须用另一篇公开文本 PDF 替代并记录；Ollama 不可用不算 SKIP，embedding 是 P0。

---

## 10. 证据清单

| Gate | 证据 | 位置 |
|---|---|---|
| 0 | `mvn test` 输出 / surefire | `target/surefire-reports/` |
| 1 | `\dt`、扩展、索引 | 验收记录 / SQL 输出 |
| 2 | 4 格式上传、两套 chunk | curl 输出 + SQL |
| 3 | 维度 1024、无 NULL | SQL |
| 4 | 重复上传、count 不变 | curl + SQL |
| 5 | SUPERSEDED、effective_to、重叠 400 | SQL + curl |
| 6 | 列表/详情/删除/404 | curl + SQL |
| 7 | 无密钥、Flyway、README | 检查记录 |

> 证据可以是文本/截图/SQL 输出；关键文本建议放 `docs/acceptance/`，截图可不入库。

---

## 11. 回报模板

见 [phase1-report-template.md](phase1-report-template.md)。
