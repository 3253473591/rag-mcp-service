# 验收标准：可复现、可解释、可签收

- 版本：v1
- 日期：2026-11-08
- 关联 PRD：[01-prd.md](01-prd.md)
- 关联接口：[02-api-contract.md](02-api-contract.md)
- 关联非功能：[05-non-functional.md](05-non-functional.md)
- 关联测试：[06-test-plan.md](06-test-plan.md)

> 验收原则：**没有证据 = 没通过。** 不接受“我本地跑过”“应该可以”“大概没问题”。

---

## 1. 验收角色与前置条件

| 项 | 要求 |
|---|---|
| 验收人 | 本人 |
| 环境 | Windows + JDK 17 + Maven 3.9+ + Docker + Ollama |
| 数据 | 3 篇公开文档（含同一制度新旧两版）；40 条 QA；无真实患者/公司数据 |
| 启动 | `docker compose up -d` + `mvn spring-boot:run` |
| 模型 | `ollama pull bge-m3`，已预热 |
| 环境变量 | `MCP_AUTH_TOKEN`、`ADMIN_TOKEN` 已设置（不要提交） |
| 代码 | `mvn test` 可跑；无未提交的本地魔改 |

---

## 2. 功能验收

### AC-01 上传与解析

- [ ] 上传 PDF/DOCX/MD/TXT 各 1 篇，均返回 201。
- [ ] 返回 `docId`、`chunkCount`；DB 中 `kb_document` + `kb_chunk` 有数据。
- [ ] 不支持类型返回 415；空文件返回 422。
- 证据：curl 响应 + `select count(*) from kb_chunk where document_id=...`。

### AC-02 幂等

- [ ] 同一文件重复上传返回 200 + `duplicate=true`。
- [ ] `kb_chunk` 数量不增加。
- 证据：两次 curl + count 对比。

### AC-03 切分

- [ ] `fixed` 与 `heading` 均可配置并生效。
- [ ] 同一文档两套 chunk 共存，`chunkStrategy` 可区分。
- [ ] `heading` 的 chunk 带 `clausePath`；超长节被递归切。
- 证据：SQL 查询 `chunk_strategy`、`clause_path`、`token_count`；单测 T-UNIT-CHUNK-*。

### AC-04 元数据

- [ ] chunk 至少带 `docId`、`title`、`clausePath`、`pageNo`、`chunkIndex`。
- 证据：SQL 抽样 + API 响应。

### AC-05 Embedding

- [ ] `kb_chunk.embedding` 维度为 1024。
- [ ] 重复上传不重新 embedding。
- 证据：`select vector_dims(embedding) ...`；日志/耗时。

### AC-06 混合检索

- [ ] `POST /api/v1/search` 返回 topK，score 降序。
- [ ] `chunkStrategy=fixed/heading` 生效；`docIds` / `version` 过滤生效。
- [ ] 无结果返回 200 + `[]`。
- 证据：curl + SQL 对照。

### AC-07 引用

- [ ] 每条结果有 `citation`，包含标题/章节/页码（无页码则省略）。
- [ ] 人工抽 5 条，核对原文位置正确。
- 证据：验收记录表（query、chunkId、原文页码、结论）。

### AC-08 文档管理

- [ ] 列表分页正确；详情字段完整。
- [ ] 删除返回 204，chunk 级联删除；重复删除 404。
- 证据：curl + SQL count。

### AC-09 评测

- [ ] `scripts/eval.sh` 输出 fixed/heading 两组指标。
- [ ] 报告包含 hit@1/3/5、MRR、p50/p95。
- [ ] hit@5 ≥ 0.85，MRR ≥ 0.65（阈值见 [05-non-functional.md](05-non-functional.md)）。
- [ ] `eval/report.md` 与 JSON 可复现。
- 证据：报告文件 + 运行命令输出。

### AC-10 MCP tools

- [ ] MCP Inspector 连接 `/mcp`，`tools/list` 只返回两个 tool。
- [ ] `search_knowledge` 返回结构化结果 + citation。
- [ ] `get_document` 返回元数据，不返回全文。
- 证据：Inspector 截图/日志 + 审计表。

### AC-11 鉴权与审计

- [ ] 无 token 返回 401；错误 token 返回 401。
- [ ] 调用后 `mcp_audit_log` 有记录，且不含 query 原文。
- 证据：curl/Inspector + SQL 查询。

### AC-12 健康与错误

- [ ] `/actuator/health` 正常时 UP；DB 停止时 DOWN/503。
- [ ] 错误响应包含 `code/message/traceId`，无堆栈/SQL。
- 证据：curl + 日志。

---

## 3. 非功能验收

### AC-13 性能

- [ ] 10k chunks，单次检索 p95 < 800ms（见 05 文档）。
- [ ] `EXPLAIN (ANALYZE, BUFFERS)` 显示 HNSW 索引生效。
- 证据：性能脚本输出 + 执行计划。

### AC-14 Ollama 容错

- [ ] 停止 Ollama：入库返回 503/504，不写脏数据。
- [ ] 恢复后重新入库成功。
- 证据：curl + DB count 对比。

### AC-15 DB 容错

- [ ] 停止 PostgreSQL：健康检查 DOWN，请求 503。
- [ ] 恢复后服务可用。
- 证据：health 响应 + 日志。

### AC-16 并发

- [ ] 10 并发检索 60s，错误率 < 1%，p95 < 800ms。
- 证据：性能脚本报告。

---

### AC-17 WorkItem

- [ ] `POST /api/v1/work-items` 返回 201，状态 `PENDING`。
- [ ] `GET /api/v1/work-items/{id}` 字段完整。
- [ ] WorkItem 不存在返回 404。
- 证据：curl + SQL 查询。

### AC-18 Approval / Mandate

- [ ] 管理员批准返回 200，状态 `APPROVED`，生成 `mandateId`。
- [ ] 拒绝返回 `DENIED`。
- [ ] 重复审批返回 409。
- [ ] 无 `X-Admin-Token` 返回 401。
- [ ] 审批不能扩大申请范围。
- 证据：curl + `work_item`/`mandate` 表。

### AC-19 受控 MCP

- [ ] 无 `mandateId` 调用 MCP → `ERR-MANDATE_REQUIRED`。
- [ ] 过期 mandate → `ERR-MANDATE_EXPIRED`。
- [ ] 工具/文档/topK 越权 → `ERR-MANDATE_SCOPE`，不返回数据。
- [ ] 合法 mandate → 检索成功，结果在范围内。
- 证据：MCP Inspector/curl + 响应。

### AC-20 审计与管理鉴权

- [ ] 正常/失败调用都写 `mcp_audit_log`。
- [ ] 审计含 `work_item_id`、`mandate_id`、`status`、`latency_ms`。
- [ ] 审计不含 query 原文。
- [ ] 管理员接口无 token 401。
- 证据：SQL 查询 + 日志。

### AC-21 版本治理（P0）

- [ ] 同一 `docKey` 新旧两版入库；旧版 `SUPERSEDED`，新版 `ACTIVE`。
- [ ] 默认检索只返回新版；旧版不出现。
- [ ] `asOf=旧版有效期` 返回旧版。
- [ ] `includeSuperseded=true` 可返回旧版，引用带版本/生效时间。
- [ ] `asOf=旧版最后有效日` 返回旧版；`asOf=新版生效日` 返回新版（右闭边界）。
- [ ] 同 `docKey+scope` 活跃版本重叠被拒绝。
- [ ] 旧版未物理删除。
- 证据：curl + SQL + 检索响应。

### AC-22 冲突提示（P1，可选）

- [ ] 只有 P0 完成且时间有余才做；未做不阻塞验收。
- [ ] 20 条冲突集输出 relation/confidence/evidence/requiresHumanReview。
- [ ] 不自动裁决、不自动删旧版、不自动改结论。
- 证据：`eval/conflicts/report.md` + API/MCP 响应。

---

## 4. 工程验收

- [ ] `mvn test` 全绿，0 failure/error。
- [ ] 至少 10 个测试；关键异常路径有覆盖。
- [ ] `docker compose up -d` + `mvn spring-boot:run` 可复现。
- [ ] README 包含：启动、上传、检索、评测、MCP、WorkItem/审批、指标复现。
- [ ] `.env.example` 存在；`.env` 未提交；无密钥/PII。
- [ ] Flyway migration 完整；无 `ddl-auto=update`。
- [ ] 治理层有独立测试；越权/过期/重复审批都有用例。
- [ ] 代码分层：`Chunker` / `EmbeddingModel` / `Retriever` 可替换。
- [ ] 无 `@Disabled` 长期测试；无假指标。

---

## 5. 验收命令

```bash
# 1. 启动依赖
docker compose up -d postgres
ollama pull bge-m3

# 2. 测试
mvn test

# 3. 启动
mvn spring-boot:run

# 4. 上传（旧版；样例准备见 docs/samples/README.md）

curl -X POST http://localhost:8080/api/v1/documents \
  -F "file=@docs/samples/hypertension-2024.pdf" \
  -F "docKey=hypertension-guideline" \
  -F "version=2024" \
  -F "effectiveFrom=2024-01-01" \
  -F "chunkStrategies=fixed,heading"

# 4.1 上传（新版，替代旧版）
curl -X POST http://localhost:8080/api/v1/documents \
  -F "file=@docs/samples/hypertension-2025.pdf" \
  -F "docKey=hypertension-guideline" \
  -F "version=2025" \
  -F "effectiveFrom=2025-01-01" \
  -F "supersedesDocId=<旧版docId>" \
  -F "chunkStrategies=fixed,heading"

# 5. 检索（默认今天，只返回 ACTIVE）
curl -X POST http://localhost:8080/api/v1/search \
  -H "Content-Type: application/json" \
  -d '{"query":"高血压诊断标准","topK":5}'

# 5.1 历史检索（旧版有效期）
curl -X POST http://localhost:8080/api/v1/search \
  -H "Content-Type: application/json" \
  -d '{"query":"高血压诊断标准","topK":5,"asOf":"2024-06-01"}'

# 6. 治理：创建 + 批准
curl -X POST http://localhost:8080/api/v1/work-items \
  -H "Content-Type: application/json" \
  -d '{"requester":"agent-report-writer","purpose":"报告生成","requestedTools":["search_knowledge","get_document"],"requestedTopK":5}'

curl -X POST http://localhost:8080/api/v1/work-items/<workItemId>/approve \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: $ADMIN_TOKEN" \
  -d '{"ttlSeconds":3600,"maxTopK":5}'

# 7. 评测
./scripts/eval.sh

# 8. MCP（tool args 带 mandateId）
npx @modelcontextprotocol/inspector
```

---

## 6. 验收证据清单

| 证据 | 位置 |
|---|---|
| 上传/检索 curl 输出 | `docs/acceptance/` 或验收记录 |
| 评测报告 | `eval/report.md` + JSON |
| 测试报告 | `target/surefire-reports/` |
| MCP Inspector 截图/日志 | `docs/acceptance/` |
| work_item / mandate 查询结果 | SQL 输出 |
| 审计表查询结果 | SQL 输出 |
| 性能报告 | `eval/perf-report.md` |
| 故障注入记录 | 验收记录 |

> 证据文件可以不入库（截图/大日志），但必须在验收时能展示；关键文本报告建议入库。

---

## 7. 10 分钟演示脚本

1. **0:00-0:45** 启动 compose + 服务，展示 `/actuator/health`。
2. **0:45-2:15** 上传 3 篇文档（含同一制度新旧两版）；重复上传 1 篇，展示 `duplicate=true` 与旧版 `SUPERSEDED`。
3. **2:15-4:00** 检索 3 个问题，展示结果、score、citation；再传 `asOf` 查旧版；打开原文核对 1 条。
4. **4:00-5:00** 创建 WorkItem；管理员批准，拿 `mandateId`；展示 `work_item`/`mandate`。
5. **5:00-6:30** 跑 `scripts/eval.sh`，展示 fixed/heading 对比与 hit@5/MRR。
6. **6:30-8:00** MCP Inspector：无 token 401；无 mandate 返回 tool error（不是 403）；带 mandate 调两个 tool；展示 audit 行。
7. **8:00-9:00** 停 Ollama/DB，展示 503/DOWN；恢复。
8. **9:00-10:00** `mvn test` 结果 + 一句话总结取舍。

---

## 8. 拒绝验收的情况

出现任一条，直接不通过：

- 指标是编的，或无法用 `scripts/eval.sh` 复现。
- 仍依赖 AnythingLLM 完成检索。
- 检索结果没有引用，或引用无法回溯。
- 为了“跑通”静默吞错、写零向量、返回假空结果。
- 提交真实密钥/PII/公司内部文档。
- 测试被禁用/删除来“通过”。
- MCP 无鉴权或暴露公网。
- 表结构靠 `ddl-auto=update`，没有 migration。

---

## 9. 签收

| 项 | 内容 |
|---|---|
| 验收结论 | 通过 / 不通过 |
| 验收人 | |
| 日期 | |
| Commit | |
| 指标摘要 | hit@5=___，MRR=___，p95=___ms；治理越权拒绝=___% |
| 备注 | |

---

## 10. 验收后

- 把 `eval/report.md` 的数字写进简历（只写真实数字）。
- 把 10 分钟演示脚本练一遍，确保能脱稿讲。
- 记录 3 个面试追问题：
  1. 为什么 pgvector 不用 MySQL？
  2. 切分策略怎么选、指标怎么变？
  3. MCP 和 RAG 的关系？
- 详细话术见 [10-conflict-resolution.md](10-conflict-resolution.md) 与 [11-retrieval-and-eval-spec.md](11-retrieval-and-eval-spec.md)。
