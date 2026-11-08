# 本地 coding agent 交接说明

- 日期：2026-11-08
- 适用：新对话中的本地 coding agent（Codex/Claude Code/DSH 等）
- 目标：按文档实现 `rag-mcp-service`，不接 GitHub/Feishu 自动化

---

## 1. 先读什么

按顺序读：

1. [README](README.md)
2. [00-design.md](00-design.md)
3. [01-prd.md](01-prd.md)
4. [08-governance.md](08-governance.md)
5. [10-conflict-resolution.md](10-conflict-resolution.md)
6. [02-api-contract.md](02-api-contract.md)
7. [03-data-model.md](03-data-model.md)
8. [04-boundaries-and-errors.md](04-boundaries-and-errors.md)
9. [05-non-functional.md](05-non-functional.md)
10. [11-retrieval-and-eval-spec.md](11-retrieval-and-eval-spec.md)
11. [12-mcp-implementation.md](12-mcp-implementation.md)
12. [13-dev-workflow.md](13-dev-workflow.md)
13. [06-test-plan.md](06-test-plan.md)
14. [07-acceptance.md](07-acceptance.md)

不要跳过 08/10/11/12/02/03：治理层、版本治理、评测口径、MCP 实现和接口是这次 C+ 的核心，不是附加项。

---

## 2. 硬性约束

- JDK 17；Spring Boot 3.5.16；Spring AI 1.1.8（BOM 锁版本）。
- PostgreSQL 16 + pgvector；Ollama `bge-m3`（1024 维）。
- MCP 优先 Streamable HTTP，目标协议 2025-06-18；配置不通再降级 stdio，并在 README 记录。
- 不做前端、聊天、agent 编排、多租户、模型训练、自动审批。
- 不接 GitHub/Feishu/PR 自动化；不自动合并。
- 密钥走环境变量；不提交 `.env`、真实 token、真实患者/公司数据。
- 所有表结构走 Flyway；禁止 `ddl-auto=update`。
- 不编造评测数字；指标必须由 `scripts/eval.sh` 复现。
- MCP 必须带 `mandateId`；无 WorkItem/Approval/Mandate 默认拒绝。
- 文档范围过滤必须在 SQL 层，不能查完再过滤。
- **P0 版本治理必做**：默认只召回 ACTIVE；`asOf` 历史查询；旧版不物理删除；重叠活跃版本拒绝。
- **P1 冲突提示只在 5 天时间有余时做**；不做自动裁决。

---

## 3. 实现顺序（每阶段人工 review 后再继续）

### Phase 1：骨架 + 入库

- Maven 项目、application.yml、docker-compose（postgres）。
- Flyway V1：`kb_document`、`kb_chunk`、`work_item`、`mandate`、`mcp_audit_log`、`eval_run`。
- PDFBox/POI/Markdown/TXT 解析；`Block` 模型。
- `FixedWindowChunker` + `HeadingAwareChunker`；一次入库生成两套 chunk；入库事务；SHA-256 幂等。
- Ollama embedding（批量 16~32；失败重试 2 次）；两套 chunk 都写向量。
- 文档元数据：`docKey/version/effectiveFrom/effectiveTo/status/supersedes`。
- `chunkStrategies=["fixed","heading"]` 两套 chunk 共存。
- `POST /documents`、`GET /documents`、`GET /documents/{id}`、`DELETE`。
- 测试：T-UNIT-PARSE/CHUNK、T-EMBED-*、T-INT-DOC-01/02/05/06、T-VERSION-01/04/05。

**Review gate**：3 篇文档入库成功（含新旧两版，`fixed`/`heading` 两套 chunk 都有）；重复上传幂等；旧版 `SUPERSEDED`；`mvn test` 绿。

### Phase 2：检索

- embedding 已在 Phase 1 接入；本阶段只做检索。
- 向量 SQL + pgvector HNSW；`pg_trgm` 关键词；RRF。
- `citation` 组装；metadata filter；`POST /search`。
- 默认 `status=ACTIVE` + `asOf` 过滤；`includeSuperseded`；`chunkStrategy` 过滤；引用带版本。
- 测试：T-INT-SEARCH-*、T-UNIT-RRF-*、T-VERSION-02/03/06/07/11。

**Review gate**：5 条 query 人工核对引用；默认只返回新版；`asOf` 查旧版正确。

### Phase 3：评测

- `eval/qa.jsonl` 40 条；`fixed`/`heading` 对比。
- hit@1/3/5、MRR、p50/p95；`eval/report.md` + JSON。
- `scripts/eval.sh` 一键跑。
- 测试：T-INT-EVAL-01。

**Review gate**：指标真实、可复现；不达标先调切分/检索，不改数字。

### Phase 4：治理 API

- `POST /work-items`、`GET /work-items/{id}`。
- `POST /work-items/{id}/approve`（`X-Admin-Token`）→ 创建 Mandate。
- `POST /work-items/{id}/deny`；重复审批 409。
- 测试：T-GOV-01~07。

**Review gate**：申请→批准→拿 mandateId；范围只能缩小；重复审批 409。

### Phase 5：MCP + 强制 mandate

- MCP server（Streamable HTTP，`/mcp`）。
- `search_knowledge` / `get_document`；`mandateId` 必填。
- Policy 校验：有效期、工具、docIds、topK；SQL 层过滤。
- 审计：workItemId/mandateId/status/latency；不含 query 原文。
- 测试：T-GOV-08~13、T-INT-MCP-*。

**Review gate**：MCP Inspector 无 token 401；无 mandate 返回 tool error（不是 403）；带 mandate 成功；审计有记录。

### Phase 6：收尾

- 故障注入：停 Ollama / 停 Postgres；503/DOWN。
- README：启动、上传、检索、评测、WorkItem/审批、MCP、指标复现。
- `.env.example`；`.gitignore`。
- 跑 `mvn test` + `scripts/eval.sh`；按 [07-acceptance.md](07-acceptance.md) 逐项验收。

---

## 4. 停止条件

遇到以下情况，停下来报告，不要硬扛：

- Spring AI 1.1.8 MCP starter 与 Boot 3.5 不兼容；降级 stdio 并记录。
- `bge-m3` 维度不是 1024；确认模型配置，不要改 schema 硬塞。
- Testcontainers 无法启动 Docker；先跑单元测试，集成测试标记原因。
- 文档/接口冲突：以 02/03/04 为准；冲突写入 README，不要静默选一个。
- 时间不够：按主设计时间盒砍语义切分/rerank/HTTP 评测，不砍治理与评测。

---

## 5. 交付前自检

- [ ] `mvn test` 全绿，0 failure/error。
- [ ] `scripts/eval.sh` 输出 hit@5/MRR/p95。
- [ ] 3 篇文档入库；重复上传幂等。
- [ ] 默认只返回 ACTIVE；`asOf` 可查旧版；旧版不物理删除。
- [ ] MCP 两个 tool 在 Inspector 调通；无 token 401；无 mandate 返回 tool error（不是 403）。
- [ ] 审计有 workItemId/mandateId。
- [ ] README 能复现。
- [ ] 无密钥/PII；`.env` 未提交。
- [ ] 按 [07-acceptance.md](07-acceptance.md) 逐项勾选。

---

## 6. 开场提示词（复制到新对话）

```text
你在 D:\AAA_Code_Project\rag-mcp-service 实现一个 Java 项目。
先读 docs/09-handoff.md，再按其中顺序读文档；不要跳读 08/10/11/12/02/03。

硬约束：
- JDK 17、Spring Boot 3.5.16、Spring AI 1.1.8、PostgreSQL 16 + pgvector、Ollama bge-m3。
- 不做前端/agent/多租户/自动审批；不接 GitHub/Feishu；不自动合并。
- MCP 必须带 mandateId；文档范围过滤必须在 SQL 层。
- 所有 DDL 走 Flyway；所有指标必须可复现。
- 每阶段先跑测试，人工 review 后再继续；不要一次写完全部。

验收：按 docs/07-acceptance.md，逐项给证据。
```
