# 测试计划：单元、集成、端到端、性能

- 版本：v1
- 日期：2026-11-08
- 关联主设计：[00-design.md](00-design.md)
- 关联 PRD：[01-prd.md](01-prd.md)
- 关联接口：[02-api-contract.md](02-api-contract.md)
- 关联边界：[04-boundaries-and-errors.md](04-boundaries-and-errors.md)
- 关联非功能：[05-non-functional.md](05-non-functional.md)
- 关联验收：[07-acceptance.md](07-acceptance.md)

---

## 1. 测试策略

| 层级 | 目标 | 工具 | 是否进 CI |
|---|---|---|---|
| 单元 | 切分、RRF、指标、校验、鉴权 | JUnit 5 + Mockito | 是 |
| 集成 | DB、迁移、Repository、API、MCP | Testcontainers + Spring Boot Test | 是 |
| 契约 | HTTP/MCP 请求响应与错误码 | MockMvc + JSON 断言 | 是 |
| 端到端 | 真实上传/检索/MCP | Docker + 真 Ollama + MCP Inspector | 手动/发布前 |
| 性能 | p95、并发、错误率 | 脚本（k6/JMeter/Java） | 发布前 |
| 安全 | 鉴权、SQL 注入、路径穿越 | 单测 + 手工 | 是/手动 |

原则：

- 测试用**确定性 fake embedding**，不把 CI 绑到 Ollama。
- 真实 Ollama 只做发布前 smoke，不要求 CI 每次都跑。
- 每个 bug 修复必须补回归测试。
- 重点覆盖“失败路径”，不只覆盖 happy path。

---

## 2. 测试环境

### 2.1 本地

```bash
docker compose up -d postgres
ollama pull bge-m3
mvn test
mvn spring-boot:run
```

### 2.2 CI

- JDK 17
- Docker（Testcontainers）
- `mvn verify`
- 不启动真实 Ollama；用 `@TestConfiguration` 提供 fake `EmbeddingModel`
- 不跑性能测试（单独 job/手动）
- 测试环境设置 `MCP_AUTH_TOKEN` / `ADMIN_TOKEN` 的测试值，不使用真实 token

### 2.3 测试数据

- 3 篇公开文档（与 `docs/samples/README.md` 一致）：
  - `hypertension-2025.pdf`：国家基层高血压防治管理指南 2025（当前版本）
  - `hypertension-2024.pdf`：同一指南的 2024 版/公开摘编（旧版本，用于版本治理）
  - `enterprise-policy-2026.pdf`：公开企业制度文本，本仓库采用《企业内部控制基本规范》（第三篇，用于跨文档/关键词测试）
  - 来源、页数与 SHA-256 见 `docs/samples/SOURCES.md`；`hypertension-2024.pdf` 为测试用摘编，不是官方 2024 版
- 格式 fixture（Gate 2 解析/切分，不进入 `eval/qa.jsonl`）：`src/test/resources/fixtures/` 下的
  `sample.docx`、`sample.md`、`sample.txt`、`sample-gb18030.txt`、`sample-scanned.pdf`；
  由 `FormatFixturesTest` 校验，手动验收时直接上传。
- `eval/qa.jsonl` 40 条（35 条可回答 + 5 条无答案），覆盖 3 篇文档。
- 性能测试用 10k 条合成 chunk（固定随机种子，不提交大文件）。
- 禁止真实患者/公司数据。

---

## 3. 单元测试

| ID | 对象 | 用例 | 期望 |
|---|---|---|---|
| T-UNIT-CHUNK-01 | FixedWindowChunker | 1000 tokens 文本 | 切出多块，overlap 80 |
| T-UNIT-CHUNK-02 | FixedWindowChunker | 小于窗口 | 1 块，不切 |
| T-UNIT-CHUNK-03 | HeadingAwareChunker | 多级标题 | `clausePath` 正确 |
| T-UNIT-CHUNK-04 | HeadingAwareChunker | 单节超长 | 递归切分 |
| T-UNIT-CHUNK-05 | 表格 | 超长表格 | 不截断行/重复表头（按实现） |
| T-UNIT-CHUNK-06 | 空文本 | 空白 | 抛业务异常/空列表 |
| T-UNIT-PARSE-01 | PDF 解析 | 文本型 PDF | 页号/文本正确 |
| T-UNIT-PARSE-01b | PDF 双栏 | 左右栏同 y | 左栏完整先于右栏 |
| T-UNIT-PARSE-01c | PDF heading 清洗 | `90 mmHg` / `3 d` / `mg qd` | 不成为 heading/clauseId |
| T-UNIT-PARSE-02 | DOCX 解析 | 标题+段落 | 文本正确 |
| T-UNIT-PARSE-03 | Markdown | 嵌套标题 | 层级正确 |
| T-UNIT-CLAUSE-01 | ClauseIds | `第五条` / `第 12 条` / `第１２条` | 稳定可读 clauseId |
| T-UNIT-CHUNK-08 | FixedWindowChunker | 跨 heading/页 | 主导 clause + pageEnd |
| T-UNIT-RRF-01 | RRF | 两路候选 | 分数/排序正确 |
| T-UNIT-RRF-02 | RRF | 重复 chunk | 去重 |
| T-UNIT-METRIC-01 | hit@k | gold 在第 3 位 | hit@1=0, hit@3=1, hit@5=1 |
| T-UNIT-METRIC-02 | MRR | gold 第 2 位 | MRR=0.5 |
| T-UNIT-VALID-01 | 参数校验 | topK=0/21 | 拒绝 |
| T-UNIT-VALID-02 | 文件名 | `../../etc/passwd` | 只取 basename |
| T-UNIT-AUTH-01 | MCP token | 缺失/错误 | 401 |
| T-UNIT-AUTH-02 | MCP token | 正确 | 放行 |

---

## 4. 集成测试

使用 Testcontainers `pgvector/pgvector:pg16`，Flyway 建表，fake embedding（固定向量）。

| ID | 场景 | 期望 |
|---|---|---|
| T-INT-DB-01 | Flyway V1 迁移 | 表/索引/扩展存在 |
| T-INT-DOC-01 | 上传 PDF | 201，doc + chunks 落库 |
| T-INT-DOC-02 | 重复上传同文件 | 200，`duplicate=true`，chunk 数不变 |
| T-INT-DOC-03 | 不支持扩展名 | 415 |
| T-INT-DOC-04 | 空文件 | 422 |
| T-INT-DOC-05 | 删除文档 | 204，chunks 级联删除 |
| T-INT-DOC-06 | 删除不存在 | 404 |
| T-INT-SEARCH-01 | 向量检索 | 返回 topK，score 降序 |
| T-INT-SEARCH-02 | 关键词检索 | 命中中文关键词 |
| T-INT-SEARCH-03 | hybrid | 返回融合结果 |
| T-INT-SEARCH-04 | docIds 过滤 | 只返回指定文档 |
| T-INT-SEARCH-05 | 无结果 | 200 + `[]` |
| T-INT-MCP-01 | tools/list | 只返回两个 tool |
| T-INT-MCP-02 | search_knowledge | 结构化结果 + citation |
| T-INT-MCP-03 | get_document | 元数据，无全文 |
| T-INT-MCP-04 | 无 token | 401 |
| T-INT-MCP-05 | 审计 | 调用后 audit 表有记录 |
| T-INT-EVAL-01 | 40 条 QA | 返回指标，hit@5 在 0~1 |

---

## 5. 契约测试

| ID | 场景 | 期望 |
|---|---|---|
| T-CONTRACT-01 | 上传响应字段 | 符合 `02-api-contract` |
| T-CONTRACT-02 | 检索请求缺 query | 400 `ERR-VALIDATION` |
| T-CONTRACT-03 | topK=21 | 400 `ERR-VALIDATION` |
| T-CONTRACT-04 | 错误响应结构 | code/message/traceId |
| T-CONTRACT-05 | MCP tool schema | 必填/上限/默认值正确 |
| T-CONTRACT-06 | MCP 业务错误 | `isError:true` + code |

---

## 6. 端到端测试（发布前手动）

1. `docker compose up -d`
2. `ollama pull bge-m3`
3. `mvn spring-boot:run`
4. 上传 3 篇文档；重复上传 1 篇，确认 `duplicate=true`。
5. `curl /api/v1/search`，人工核对 5 条引用（文档/章节/页码）。
6. `scripts/eval.sh`，确认报告有 fixed/heading 对比。
7. 创建 WorkItem；管理员批准拿 mandateId。
8. MCP Inspector 连接 `/mcp`，带 mandateId 调两个 tool；无 token 应 401，无 mandate 应 tool error（`ERR-MANDATE_REQUIRED`），不是 HTTP 403。
9. 停止 Ollama，检索应 503/504；恢复后正常。
10. 停止 Postgres，健康检查 DOWN；恢复后正常。

---

## 7. 性能测试

| ID | 场景 | 通过标准 |
|---|---|---|
| T-PERF-01 | 10k chunks，单次检索 | p95 < 800ms |
| T-PERF-02 | 10 并发，60s | 错误率 < 1%，p95 < 800ms |
| T-PERF-03 | 1MB PDF 入库 | < 30s（Ollama 已预热） |
| T-PERF-04 | 重复上传 | < 100ms |
| T-PERF-05 | MCP search | 与 HTTP 同量级 |

方法：

- 生成 10k 合成 chunk（固定种子），批量写入。
- 用 20 条 query 循环，记录 p50/p95/p99。
- 用 `EXPLAIN (ANALYZE, BUFFERS)` 确认 HNSW 索引生效。
- 性能不达标时，先看索引/查询计划，再看模型延迟；不要直接换库。

---

## 8. 安全测试

| ID | 场景 | 期望 |
|---|---|---|
| T-SEC-01 | 无/错 MCP token | 401，不泄露 token 是否存在 |
| T-SEC-02 | SQL 注入 query | 参数化，查询正常/无注入 |
| T-SEC-03 | 路径穿越文件名 | 只取 basename |
| T-SEC-04 | 文档内容含“忽略之前指令” | 作为数据返回，不执行 |
| T-SEC-05 | 错误响应 | 无堆栈/SQL/绝对路径 |
| T-SEC-06 | 日志 | 不含 query 原文/token |
| T-SEC-07 | 超大输入 | 413/400，拒绝 |

---

## 9. 治理测试

| ID | 场景 | 期望 |
|---|---|---|
| T-GOV-01 | 创建 WorkItem | 201，状态 PENDING |
| T-GOV-02 | 查看 WorkItem | 字段完整，状态正确 |
| T-GOV-03 | 管理员批准 | 200，状态 APPROVED，生成 Mandate |
| T-GOV-04 | 管理员拒绝 | 200，状态 DENIED |
| T-GOV-05 | 重复批准 | 409 `ERR-APPROVAL_INVALID` |
| T-GOV-06 | 审批扩大范围 | 400 `ERR-VALIDATION` |
| T-GOV-07 | 无 Admin Token 审批 | 401 `ERR-ADMIN_UNAUTHORIZED` |
| T-GOV-08 | MCP 无 mandateId | tool error `ERR-MANDATE_REQUIRED` |
| T-GOV-09 | mandate 过期 | tool error `ERR-MANDATE_EXPIRED` |
| T-GOV-10 | topK 超 maxTopK | tool error `ERR-MANDATE_SCOPE` |
| T-GOV-11 | docIds 越权 | tool error `ERR-MANDATE_SCOPE`，不返回数据 |
| T-GOV-12 | 合法 mandate 检索 | 200/结果，audit 含 workItemId/mandateId |
| T-GOV-13 | 审计字段 | 有 tool/workItem/mandate/status/latency；无 query 原文 |

---

## 10. 版本治理测试（P0）

| ID | 场景 | 期望 |
|---|---|---|
| T-VERSION-01 | 新旧两版入库 | 默认检索只返回新版 |
| T-VERSION-02 | `asOf` 旧版有效期 | 返回旧版 |
| T-VERSION-03 | `includeSuperseded=true` | 可返回旧版；只要求 `effectiveFrom <= asOf`，忽略 `effectiveTo` |
| T-VERSION-04 | 新版本带 `supersedesDocId` | 旧版 `SUPERSEDED`，`effective_to` 正确 |
| T-VERSION-05 | 同 docKey+scope 重叠活跃版本 | 400 `ERR-VALIDATION` |
| T-VERSION-06 | `DRAFT` 文档 | 永不返回 |
| T-VERSION-07 | 引用 | 带版本/生效时间 |
| T-VERSION-08 | 旧版 | 不物理删除，可查历史 |
| T-VERSION-09 | `chunkStrategies=fixed,heading` | 两套 chunk 共存，检索按 `chunkStrategy` 过滤 |
| T-VERSION-10 | 重复上传不同策略 | 不补生成缺失策略（按文档说明） |
| T-VERSION-11 | 右闭边界 | `asOf=旧版 effectiveTo` 返回旧版；`asOf=新版 effectiveFrom` 返回新版 |

### 可选：冲突提示（P1）

| ID | 场景 | 期望 |
|---|---|---|
| T-CONFLICT-01 | 20 条冲突集 | 时间/层级/scope/例外/无冲突 |
| T-CONFLICT-02 | 任一冲突 | 返回双方原文 + `requiresHumanReview` |
| T-CONFLICT-03 | 自动裁决 | 禁止；不自动改文档/结论 |

---

## 11. 回归测试

- 每个已修复 bug 加一条测试，命名 `T-REG-<issue>`。
- 修改切分/检索/评分逻辑必须重跑 `scripts/eval.sh`，对比指标。
- 修改表结构必须重跑 Flyway + 集成测试。
- 修改 MCP schema 必须重跑 Inspector smoke。

---

## 12. 覆盖率与质量门

- 核心模块（chunk/retrieval/eval/mcp）行覆盖 ≥ 70%。
- 关键错误路径（鉴权、解析失败、embedding 失败、DB 失败）必须有测试，不只看总覆盖率。
- 不允许 `@Disabled` 长期存在；临时禁用需写原因和恢复日期。
- 测试必须可重复：不依赖系统时间、随机顺序、真实网络。

---

## 13. 运行命令

```bash
# 单元 + 集成（CI）
mvn verify

# 只跑某类
mvn test -Dtest='*ChunkerTest'
mvn test -Dtest='*IntegrationTest'

# 评测（Windows PowerShell / Linux 二选一）
./scripts/eval.ps1
./scripts/eval.sh

# MCP 手动
npx @modelcontextprotocol/inspector
```

---

## 14. 验收映射

| 验收项 | 测试 |
|---|---|
| AC-01 上传/解析 | T-INT-DOC-01、T-UNIT-PARSE-* |
| AC-02 幂等 | T-INT-DOC-02 |
| AC-03 切分 | T-UNIT-CHUNK-* |
| AC-04 元数据 | T-INT-DOC-01 |
| AC-05 embedding | T-INT-DOC-01、T-EMBED-* |
| AC-06 混合检索 | T-INT-SEARCH-* |
| AC-07 引用 | T-INT-SEARCH-01、E2E 人工核对 |
| AC-08 管理 | T-INT-DOC-05/06 |
| AC-09 评测 | T-INT-EVAL-01、`scripts/eval.sh` |
| AC-10 MCP | T-INT-MCP-01/02/03 |
| AC-11 鉴权/审计 | T-INT-MCP-04/05、T-UNIT-AUTH-* |
| AC-12 健康/错误 | T-CONTRACT-04、E2E 故障注入 |
| AC-13 性能 | T-PERF-01/02 |
| AC-14 容错 | E2E 停 Ollama |
| AC-15 DB 故障 | E2E 停 Postgres |
| AC-16 并发 | T-PERF-02 |
| AC-17 WorkItem | T-GOV-01/02 |
| AC-18 审批/Mandate | T-GOV-03/04/05/06/07 |
| AC-19 受控 MCP | T-GOV-08/09/10/11/12 |
| AC-20 审计/管理员鉴权 | T-GOV-07/12/13 |
| AC-21 版本治理（P0） | T-VERSION-01~11 |
| AC-22 冲突提示（P1，可选） | T-CONFLICT-01~03 |
