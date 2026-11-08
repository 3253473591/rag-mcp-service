# 企业知识库 RAG 检索服务（Java + MCP）——主设计

- 日期：2026-11-08
- 状态：**设计已定，待实现**
- 项目代号：`rag-mcp-service`
- 目标岗位：Java + AI 国企/电信线
- 主文档职责：**决策记录、技术栈、项目结构、红线、文档索引**
- 说明：具体需求、接口、数据结构、异常、非功能、测试、验收分别见子文档；本文不重复细节，只做交叉引用。

---

## 0. 文档地图

| 文档 | 内容 | 读者 |
|---|---|---|
| [README](README.md) | 文档索引与阅读顺序 | 所有人 |
| **00-design.md（本文）** | 决策、技术栈、结构、红线 | 所有人 |
| [01-prd.md](01-prd.md) | 产品需求、用户故事、功能清单 | 产品/实现/验收 |
| [02-api-contract.md](02-api-contract.md) | HTTP + MCP 接口契约、错误码 | 实现/联调 |
| [03-data-model.md](03-data-model.md) | 表结构、字段、索引、迁移 | 实现/DBA |
| [04-boundaries-and-errors.md](04-boundaries-and-errors.md) | 边界条件、异常矩阵、恢复策略 | 实现/测试 |
| [05-non-functional.md](05-non-functional.md) | 性能、容量、容错、可观测、安全 | 实现/验收 |
| [06-test-plan.md](06-test-plan.md) | 测试策略、用例、环境、CI | 测试/实现 |
| [07-acceptance.md](07-acceptance.md) | 验收清单、命令、证据、签收 | 本人/评审 |
| [08-governance.md](08-governance.md) | WorkItem/Mandate/Approval/Audit 治理层 | 实现/安全/验收 |
| [09-handoff.md](09-handoff.md) | 本地 coding agent 交接说明与开场提示词 | 实现 agent |
| [10-conflict-resolution.md](10-conflict-resolution.md) | 新旧条款：P0 版本治理，P1 冲突提示 | 产品/实现/验收 |
| [11-retrieval-and-eval-spec.md](11-retrieval-and-eval-spec.md) | 切分、RRF、QA、hit@k/MRR 口径 | 实现/评测 |
| [12-mcp-implementation.md](12-mcp-implementation.md) | Spring AI MCP 配置、tool 注册、错误映射 | 实现/联调 |
| [13-dev-workflow.md](13-dev-workflow.md) | 本地 coding agent 工作流、review gate | 实现/协作 |

阅读顺序建议：**00 → 01 → 08 → 10 → 02/03 → 04/05 → 06 → 07 → 09 → 11/12/13**。

---

## 1. 背景与目标

简历里 agent 编排、旅游系统、体检系统已经足够多，再做一个 agent 或业务 CRUD 会重复。目标岗位是 Java + AI 国企/电信线，需要补的是：

- 一条**自己实现、能讲清楚**的 RAG 检索链路；
- 一个 **Java 侧可交付的 AI 服务**，而不是把能力藏在 AnythingLLM 后面；
- 一个和现有项目不重复的**基础设施型作品**；
- 对 **MCP** 有真实工程经验，而不是只背概念；
- 一层**受控自治**的治理能力（WorkItem/Mandate/Approval/Audit），而不是给 AI 无限权限。

**一句话**：RAG 是主体，MCP 是壳；用 Java/Spring Boot 交付，不做前端、不做对话、不做 agent 编排。

详细用户故事与功能清单见 [PRD](01-prd.md)。

---

## 2. 范围

### 2.1 目标

1. PDF / DOCX / Markdown / TXT 入库，保留标题、章节、页码、版本元数据。
2. 固定窗口 + 标题感知两种切分策略，可切换、可评测。
3. PostgreSQL + pgvector 存 1024 维 embedding，支持 metadata filter。
4. 向量 + 关键词混合检索（RRF），返回可回溯引用。
5. 30~50 条 QA 离线评测，输出 hit@1/3/5、MRR、p50/p95，并对比切分策略。
6. MCP 暴露 `search_knowledge` / `get_document`，Bearer 鉴权 + 审计。
7. WorkItem → 管理员审批 → Mandate → MCP 调用按范围校验；默认拒绝越权。
8. **P0 版本治理**：`docKey/version/effectiveFrom/effectiveTo/status/supersedes`；默认只召回 ACTIVE，支持 `asOf` 历史查询。
9. `docker compose up` + `mvn spring-boot:run` 可复现；README 有完整命令。
10. 有单元/集成测试，关键行为不靠手工点页面。

### 2.2 非目标

- 不做聊天 UI、多轮对话、agent 编排、自动执行器。
- 不做多租户、SSO、复杂角色体系、自动审批、审批 UI；治理层只做单租户最小闭环。
- 不做生产级高可用。
- 不做模型训练/微调。
- 不做全格式解析（先 PDF/DOCX/MD/TXT）。
- 不做 `/api/v1/answer` 生成式回答；生成交给上层 agent。
- 不把 AnythingLLM 作为依赖。

### 2.3 反目标

- 只是把 AnythingLLM 包一层，切分/向量/检索仍是黑盒。
- 只做 MCP demo，没有 RAG 评测数据。
- 为了“看起来大”加前端/agent/多工具，5 天做不完。
- 指标是编的，无法用评测脚本复现。
- 代码里出现真实密钥、真实患者数据、公司内部文档。

---

## 3. 决策记录（ADR）

### ADR-001：Java 主线，选 Spring Boot 3.5 + Spring AI 1.1.8

- **背景**：目标岗位是 Java + AI 国企线；需要一个能讲 Java/Spring 工程化的 AI 服务。
- **决策**：JDK 17、Spring Boot 3.5.16、Spring AI 1.1.8（BOM 锁版本）。
- **理由**：
  - Spring AI 1.1.x 支持 Boot 3.4/3.5，稳定；
  - MCP server starter、Ollama embedding、RAG 组件齐全；
  - 比 Boot 4 + Spring AI 2.0 更适合 5 天项目，依赖翻车概率低。
- **代价**：不是最新 MCP 规范；README 记录实际 SDK/协议版本。
- **备选**：Boot 4 + Spring AI 2.0（最新但太新）；Python FastAPI + LangChain（偏离 Java 主线）。

### ADR-002：向量库选 PostgreSQL + pgvector，不用 MySQL-only

- **背景**：现有体检系统、Python 侧 AI 应用都用 MySQL，希望统一。
- **决策**：本项目单独用 PostgreSQL 16 + pgvector。
- **理由**：
  - MySQL 8.4 没有 `VECTOR`；MySQL 9.x 有 `VECTOR`，但 `DISTANCE()` 只在 HeatWave/MySQL AI 提供，Community/Commercial 不含；`VECTOR` 不能做 key，公开手册没有 Community 可用的 ANN 索引。
  - MySQL-only 只能 Java 全表扫 cosine，或另起 Qdrant/Milvus；两者都比 pgvector 更重。
  - pgvector 提供 `vector` + HNSW/IVFFlat + cosine/L2/IP + metadata filter，和文档表同库。
  - 亿迅 AI 应用 JD 明写 PostgreSQL，不是减分项。
- **代价**：多一个 PostgreSQL 容器；不共享体检系统的 MySQL。
- **备选**：
  - B：MySQL + Qdrant（MySQL 一致性 + ANN，但两套存储/同步）；
  - C：MySQL-only + Java 精确 cosine（3 篇文档能跑，但没有 ANN，面试故事弱）。

### ADR-003：自建 RAG，不依赖 AnythingLLM

- **背景**：现有体检系统用 AnythingLLM，切分/向量/检索是黑盒。
- **决策**：本项目自己实现解析、切分、embedding、检索、引用、评测。
- **理由**：面试要讲清楚每个参数与指标；黑盒无法支撑 RAG 深度。
- **代价**：实现量更大；用 5 天时间盒控制范围。

### ADR-004：MCP 只做“受控壳”，不做 agent

- **背景**：MCP 是当前热点，但容易做成概念 demo；给 AI 无限权限也不适合国企场景。
- **决策**：MCP 暴露两个只读 tool：`search_knowledge`、`get_document`；每次调用必须带 `mandateId`；不做工具编排/多轮/agent 执行器。
- **理由**：项目定位是“受控检索基础设施”；MCP 是交付接口，治理层是权限边界。
- **代价**：调用方要先申请 WorkItem/审批；演示多一步，但这是特意的取舍。

### ADR-005：不做生成式回答

- **背景**：RAG 常被理解为“检索 + 生成”。
- **决策**：只做检索 + 引用 + 评测；生成交给上层 agent。
- **理由**：5 天时间盒；检索质量才是本项目要证明的；避免把项目做成聊天 demo。
- **代价**：不能演示端到端回答；用 MCP tool 返回引用片段来弥补。

### ADR-006：MCP 传输优先 Streamable HTTP，stdio 兜底

- **决策**：HTTP `/mcp` 为主，目标协议 **2025-06-18**；MCP Inspector 验收；若 Spring AI 1.1.8 配置不通，降级 stdio。实现细节见 [12-mcp-implementation.md](12-mcp-implementation.md)。
- **理由**：HTTP 更接近企业集成；stdio 更容易本地联调。

### ADR-007：单租户、单库、无多环境

- **决策**：不做多租户、不做租户隔离、不做多环境配置中心。
- **理由**：5 天项目；这些是生产化议题，不是本次面试重点。

### ADR-008：C+ 治理层，单租户最小闭环

- **背景**：只做 RAG+MCP 是“能查”，国企更关心“谁能查、能查什么、谁批的、可追溯”。
- **决策**：加 WorkItem → Approval → Mandate → Policy → Audit 五件事；只读工具；单租户；无审批 UI。
- **理由**：用 2~3 天换一个更强的工程故事；比再做一个 agent 更贴合 Java+AI 国企线。
- **代价**：MCP 调用前要先申请/审批；演示多一步；v1 的 mandate 是能力令牌，不绑定调用方身份。
- **参考**：`dsh-superagent-prototype` 的**受控工单/授权/派工**思路（不是代码，也不是它的 WorkItem/Audit 实体）。  
  映射：我们的 `WorkItem` ≈ 其 Ticket/请求，`Mandate` ≈ 其 Mandate，`Audit` 是我们新增的 `mcp_audit_log`；不引入其 Trigger/Executor/Feishu 运行时。

### ADR-009：本地 coding agent 加速，不接 GitHub/Feishu 自动化

- **背景**：superagent 等本地 coding agent 能批量写代码/测试/文档，缩短实现时间。
- **决策**：本地 coding agent 只作为实现加速器；不走 GitHub issue/PR 自动集成、不接 Feishu、不让 agent 自动合并。
- **理由**：5 天时间盒；人工 review/验收不能省；避免工具配置反噬。
- **纪律**：小任务、每任务带验收标准、每次改动必须过测试、人工 review 后才进入下一步。详见 [13-dev-workflow.md](13-dev-workflow.md)。

### ADR-010：新旧条款 P0 版本治理，P1 冲突提示

- **背景**：企业真实痛点是新旧条款/多版本制度；向量检索会同时召回旧版。
- **决策**：
  - **P0 必做**：`docKey/version/effectiveFrom/effectiveTo/status/supersedes`；默认只召回 ACTIVE；支持 `asOf`；旧版不物理删除。
  - **P1 时间有余再做**：冲突候选 + 结构化提示 + `requiresHumanReview`；不做自动裁决。
- **理由**：P0 成本约 1 天，解决 80% “旧版被召回”问题；P1 涉及 LLM/NLI 判断，5 天内不应冒险。
- **代价**：上传接口多几个元数据字段；历史查询需要显式 `asOf`。
- **红线**：不自动“以新为准”，不自动删除旧版，不把相似度当冲突。

---

## 4. 技术栈

| 层 | 选型 | 版本/说明 |
|---|---|---|
| 语言 | Java | 17 |
| 框架 | Spring Boot | 3.5.16（或当前 3.5.x 稳定版） |
| AI | Spring AI | 1.1.8（BOM） |
| MCP | Spring AI MCP server starter | Streamable HTTP，v1 目标 2025-06-18，stdio fallback |
| 治理 | 自研（WorkItem/Mandate/Policy/Audit） | 单租户；只读工具；无审批 UI |
| 数据库 | PostgreSQL + pgvector | `pgvector/pgvector:pg16` |
| Embedding | Ollama `bge-m3` | 1024 维，本地 |
| 文档解析 | PDFBox 3.x / POI 5.x | PDF / DOCX |
| Token 计数 | jtokkit | cl100k 口径 |
| 迁移 | Flyway | `V1__init.sql` |
| 测试 | JUnit 5 / Mockito / Testcontainers | pgvector 容器 |
| 构建 | Maven | 3.9+ |
| 运行 | Docker Compose | 只起 PostgreSQL；应用用 `mvn spring-boot:run` 本地启动 |

> 具体依赖坐标见 [03-data-model.md](03-data-model.md) 与项目 `pom.xml` 实现时的实际版本；以 README 记录为准。

---

## 5. 总体架构

```
┌────────────────────────────────────────────────────────────┐
│                    rag-mcp-service (Java)                  │
│                                                            │
│  HTTP API                 MCP Server (Streamable HTTP)     │
│  /api/v1/documents        /mcp                             │
│  /api/v1/search           tools:                           │
│  /api/v1/eval             - search_knowledge               │
│  /api/v1/work-items       - get_document                   │
│         │                         │                        │
│         └──────────┬──────────────┘                        │
│                    ▼                                       │
│  Governance: WorkItem → Approval → Mandate → Policy        │
│                    │                                       │
│                    ▼                                       │
│  Ingestion → Chunker → Embedding → Retrieval → Citation    │
│                    │            │                          │
│                    ▼            ▼                          │
│              PostgreSQL      Ollama bge-m3                 │
│              + pgvector      (1024 dims)                   │
└────────────────────────────────────────────────────────────┘
```

模块：

- `document`：上传、解析、登记、删除。
- `chunk`：固定窗口/标题感知切分、token 计数、元数据。
- `embedding`：Ollama bge-m3 批量/单条。
- `retrieval`：向量、关键词、RRF、filter、引用。
- `eval`：QA 集、指标、切分策略对比。
- `governance`：WorkItem、Approval、Mandate、Policy、Audit。
- `mcp`：tool、鉴权、mandate 校验、参数校验、审计。
- `api`：HTTP 接口、错误处理。

---

## 6. 项目结构

```
rag-mcp-service/
  pom.xml
  docker-compose.yml
  .env.example
  README.md
  docs/
    00-design.md
    01-prd.md
    02-api-contract.md
    03-data-model.md
    04-boundaries-and-errors.md
    05-non-functional.md
    06-test-plan.md
    07-acceptance.md
    08-governance.md
    09-handoff.md
    10-conflict-resolution.md
    11-retrieval-and-eval-spec.md
    12-mcp-implementation.md
    13-dev-workflow.md
    samples/README.md
    acceptance/
    README.md
  src/main/java/com/lqx/ragmcp/
    api/
    document/
    chunk/
    embedding/
    retrieval/
    eval/
    governance/
    mcp/
    audit/
    config/
  src/main/resources/
    application.yml
    db/migration/V1__init.sql
  src/test/java/...
  eval/qa.jsonl
  eval/conflicts/
  scripts/eval.sh
  scripts/eval.ps1
  Dockerfile
```

---

## 7. 红线

1. **不提交真实密钥**：API key、DB 密码、MCP token 一律环境变量；提交 `.env.example`，不提交 `.env`。
2. **不提交真实患者/公司数据**：只用公开文档；示例数据必须脱敏/虚构。
3. **不编造指标**：所有 hit@k/MRR/延迟必须由 `scripts/eval.sh` 可复现。
4. **不用 AnythingLLM 作为依赖**：本项目要自己实现检索链路。
5. **不做前端/agent/多租户**：超出范围，5 天做不完。
6. **不裸拼 SQL**：所有查询参数化；tool 输入必须校验。
7. **不把 MCP 暴露公网**：本地/内网；Bearer token + 白名单。
8. **MCP 不得无 mandate 检索**：没有 WorkItem/Approval/Mandate 的调用默认拒绝；范围过滤必须在 SQL 层，不能查完再过滤。
9. **不跳过测试**：`mvn test` 必须绿；关键异常路径必须有测试。
10. **不改表结构不留迁移**：所有 DDL 进 Flyway。
11. **不把“能跑”当“验收”**：验收看 [07-acceptance.md](07-acceptance.md)。
12. **不自动裁决新旧条款**：默认只召回 ACTIVE；旧版不物理删除；P1 只做冲突提示 + 人工复核，不做“以新为准”的自动结论。

---

## 8. 时间盒（5 天）

| 天 | 交付 |
|---|---|
| Day 1 | 骨架、Docker Compose、Flyway（含 work_item/mandate、版本字段）、PDF/DOCX/MD 解析、固定 + 标题感知切分、Ollama embedding、pgvector 写入、上传 API（一次生成两套 chunk） |
| Day 2 | 向量/关键词/RRF、引用、`asOf` 默认 ACTIVE |
| Day 3 | 40 条 QA 评测、指标报告、WorkItem 创建/审批 API；P0 版本验收 |
| Day 4 | MCP 两个 tool、mandate 校验、审计、MCP Inspector 联调 |
| Day 5 | 测试、README、Docker、故障注入、10 分钟演示；半天 buffer |

如果落后：砍语义切分/rerank/HTTP 评测入口/**P1 冲突提示**，**不砍 P0 版本治理、治理层与评测**。  
本地 coding agent 只做实现加速；每个任务必须带验收标准，人工 review 后才进入下一步。

---

## 9. 已定默认

1. 语料：`国家基层高血压防治管理指南2025版` + 2 篇公开文档，共 3 篇。
2. MCP：Streamable HTTP 为主，stdio 兜底；验收以 HTTP + MCP Inspector 为准。
3. 不做 `/api/v1/answer`；生成交给上层 agent。
4. 治理层是 **C+ 正式范围**：WorkItem → Approval → Mandate → Policy → Audit；不是 stretch。
5. 实现方式：本地 coding agent 加速；不接 GitHub/Feishu/PR 自动化，不自动合并。
6. 新旧条款：**P0 版本治理必做**（AC-21）；**P1 冲突提示只在 5 天时间有余时做**（AC-22，可选）。
7. 5 天时间盒；如果落后，砍语义切分/rerank/HTTP 评测入口/P1，不砍 P0、治理与评测。

---

## 10. 变更控制

- 修改**范围/目标**：先改 [01-prd.md](01-prd.md)，再同步本文 ADR。
- 修改**接口**：先改 [02-api-contract.md](02-api-contract.md)，再改代码/测试。
- 修改**表结构**：先改 [03-data-model.md](03-data-model.md)，再加 Flyway migration。
- 修改**异常行为**：先改 [04-boundaries-and-errors.md](04-boundaries-and-errors.md)，再补测试。
- 修改**指标/性能**：先改 [05-non-functional.md](05-non-functional.md) 与 [07-acceptance.md](07-acceptance.md)。
- 文档与代码冲突时：**以文档为需求，以代码为事实**；冲突必须记录在 PR/README，不能默默绕过。

---

## 11. 一句话验收

**上传 3 篇文档（含新旧版本）→ 默认只召回 ACTIVE、`asOf` 可查历史 → 检索带正确引用 → 评测有真实 hit@5/MRR → WorkItem/审批/Mandate 生效 → MCP Inspector 调通两个 tool → `mvn test` 全绿。**  
详见 [07-acceptance.md](07-acceptance.md)。
