# PRD：企业知识库 RAG 检索服务

- 版本：v0.1
- 日期：2026-11-08
- 关联主设计：[00-design.md](00-design.md)
- 关联接口：[02-api-contract.md](02-api-contract.md)
- 关联验收：[07-acceptance.md](07-acceptance.md)

---

## 1. 问题

企业内部知识（制度、指南、产品手册）通常散落在 PDF/Word/Markdown 里。AI 应用（Agent、IDE 插件、客服）需要一种可靠方式：

1. 把文档变成可检索的知识；
2. 按语义找回相关片段；
3. 给出可回溯的引用；
4. 通过标准协议（MCP）被 AI 应用调用；
5. 有可量化的检索质量，而不是“感觉能用”。

现有体检系统的 AnythingLLM 能快速验证，但切分、embedding、召回、评测都是黑盒，无法支撑 Java+AI 岗位面试中的 RAG 深度追问。

## 2. 目标用户

| 角色 | 诉求 | 本项目提供 |
|---|---|---|
| 知识库维护者 | 上传文档、确认是否入库、删除过期文档 | 上传/列表/详情/删除；重复文件幂等 |
| AI 应用开发者 | 用统一接口检索知识，拿引用 | 先申请 WorkItem，获批后用 MCP tools |
| 管理员 | 审批 AI 应用的访问范围 | 批准/拒绝 WorkItem，生成限范围/限时的 Mandate |
| 评测者/面试官 | 验证检索质量、切分策略、异常处理 | QA 评测报告、指标、审计、测试 |
| 本人（实现/验收） | 5 天可交付、可讲清楚 | 时间盒、验收清单、面试话术 |

> 本项目**不面向**终端患者/医生做聊天；不做业务系统。

## 3. 核心场景

### 场景 A：知识维护者入库

上传 3 篇公开指南 → 系统解析、切分、embedding、落库 → 返回文档 ID、chunk 数；重复上传同一文件返回已有文档，不重复消耗算力。

### 场景 B：AI 应用检索

AI 应用通过 HTTP 或 MCP 调用 `search_knowledge("高血压诊断标准", topK=5)` → 返回 5 个片段，每个带文档标题、章节、页码、score、citation。

### 场景 C：评测者对比切分

运行 `scripts/eval.sh` → 用 40 条 QA 分别跑 `fixed` / `heading` 两种切分 → 输出 hit@1/3/5、MRR、p50/p95 → 选择更优策略。

### 场景 D：MCP 集成

在 MCP Inspector 中配置 `/mcp` + Bearer token → 调用 `search_knowledge`、`get_document` → 看到结构化结果；未授权返回 401；审计表有记录。

### 场景 E：受控访问

AI 应用先创建 WorkItem（申请人、目的、申请的工具/文档/topK）→ 管理员批准并生成 Mandate（限工具、限文档、限 topK、限时）→ AI 应用带 `mandateId` 调 MCP → 越权/过期被拒绝；每次调用可审计。

## 4. 功能需求

| ID | 需求 | 优先级 | 验收引用 |
|---|---|---|---|
| FR-01 | 支持 PDF/DOCX/Markdown/TXT 上传与解析 | P0 | AC-01 |
| FR-02 | 同一文件（SHA-256）重复上传幂等，返回已有文档 | P0 | AC-02 |
| FR-03 | 支持固定窗口、标题/章节感知两种切分；同一文档两套 chunk 共存（chunkStrategy） | P0 | AC-03 |
| FR-04 | chunk 保留文档、章节、页码、版本等元数据 | P0 | AC-04 |
| FR-05 | 用 Ollama bge-m3 生成 1024 维 embedding 并入库 | P0 | AC-05 |
| FR-06 | 向量 + 关键词混合检索（RRF），支持 metadata filter | P0 | AC-06 |
| FR-07 | 检索结果带可回溯引用（文档/章节/页码） | P0 | AC-07 |
| FR-08 | 文档列表、详情、删除；删除级联 chunk | P1 | AC-08 |
| FR-09 | 30~50 条 QA 评测，输出 hit@1/3/5、MRR、p50/p95 | P0 | AC-09 |
| FR-10 | MCP 暴露 `search_knowledge`、`get_document` | P0 | AC-10 |
| FR-11 | MCP Bearer 鉴权、工具白名单、审计日志 | P0 | AC-11 |
| FR-12 | 健康检查与明确错误响应 | P1 | AC-12 |
| FR-13 | WorkItem 创建/查看：申请人、目的、申请工具/文档/topK | P0 | AC-17 |
| FR-14 | 管理员批准/拒绝；批准生成限范围/限时的 Mandate | P0 | AC-18 |
| FR-15 | MCP 调用必须带 mandate；越权/过期默认拒绝 | P0 | AC-19 |
| FR-16 | 审计记录 workItemId/mandateId；管理员接口鉴权 | P0 | AC-20 |
| FR-17 | 版本治理：docKey/version/生效区间/status/supersedes；默认只召回 ACTIVE，支持 asOf | P0 | AC-21 |
| FR-18 | 冲突提示：新旧/层级/scope/例外；只提示、人工复核，不自动裁决 | P1（时间有余） | AC-22（可选） |

### FR-01 文档入库

- 支持扩展名：`.pdf`、`.docx`、`.md`、`.markdown`、`.txt`。
- 单文件上限 20MB；空文件/纯图片 PDF 明确报错。
- 解析结果必须包含可读文本；扫描件不做 OCR。

### FR-02 幂等

- 以文件内容 SHA-256 为唯一键。
- 重复上传返回 200 + `duplicate=true` + 已有 `docId`；不重复写 chunk。

### FR-03 切分

- `fixed`：目标 600 tokens，overlap 80。
- `heading`：按标题层级分节；超长节递归切；保留 `clausePath`。
- 同一文档可同时生成 `fixed`/`heading` 两套 chunk，用 `chunkStrategy` 区分。
- 表格不从中截断；超长表格按行切并重复表头（能做到就做，做不到在 README 说明）。

### FR-04 元数据

每个 chunk 至少带：`docId`、`title`、`clausePath`、`pageNo`、`chunkIndex`、`version`。

### FR-05 Embedding

- 模型 `bge-m3`，维度 1024。
- 批量 16~32 条/批；失败重试 2 次；仍失败则整次入库失败，不写半截数据。

### FR-06 检索

- 向量 top 30 + 关键词 top 30。
- RRF `k=60`；返回 top 10（可配置 topK ≤ 20）。
- 支持 `docIds`、`version` 过滤。

### FR-07 引用

- 每条结果带 `citation`：`《标题》 > 章节 > 第 N 页`。
- 没有页码时省略页码，不编造。

### FR-08 文档管理

- `GET /documents` 分页；`GET /documents/{id}` 元数据；`DELETE` 级联删除 chunk。
- 删除不存在的文档返回 404。

### FR-09 评测

- `eval/qa.jsonl` 30~50 条，覆盖至少 3 篇文档，含 5 条“知识库没有”的问题。
- 输出 `eval/report.md` + JSON；对比 `fixed` / `heading`。
- 指标定义见 [05-non-functional.md](05-non-functional.md)。

### FR-10 MCP

- `search_knowledge`：query/topK/docIds/version。
- `get_document`：docId，返回元数据，不返回全文。
- 结果结构化，含 citation；tool 输出标记为不可信数据。

### FR-11 安全

- Bearer token；未授权 401；工具白名单；审计。
- 不做公网暴露；不记录 query 明文到审计表。

### FR-12 健康与错误

- `/actuator/health` 返回 DB/服务状态。
- 统一错误码，见 [02-api-contract.md](02-api-contract.md)。

### FR-13 WorkItem

- `POST /api/v1/work-items`：申请人、目的、申请工具、申请文档、申请 topK。
- 初始状态 `PENDING`；`GET` 可查看状态。
- 不做多租户/角色体系；v1 信任本地调用方，审批是权限闸门。

### FR-14 Approval / Mandate

- 管理员用 `X-Admin-Token` 批准/拒绝。
- 批准生成 Mandate：allowedTools、allowedDocIds、maxTopK、expiresAt。
- 管理员只能缩小申请范围，不能放大到未申请的工具/文档。
- 重复审批 409；拒绝后不可再批准。

### FR-15 受控 MCP

- `search_knowledge` / `get_document` 必须带 `mandateId`。
- 无 mandate、过期、工具/文档/topK 越权 → 拒绝；默认拒绝。
- 范围过滤在 SQL 层完成，不能查完再过滤。

### FR-16 审计

- 每次 MCP 调用记录 tool、workItemId、mandateId、caller 指纹、args hash、status、latency。
- 不记录 query 原文。

详细规则见 [08-governance.md](08-governance.md)。

### FR-17 版本治理（P0）

- 文档带 `docKey`、`version`、`effectiveFrom`、`effectiveTo`、`status`、`supersedesDocId`。
- 默认检索只返回当前 `ACTIVE` 且 `asOf` 落在生效区间内的版本。
- `asOf` 支持历史查询；`includeSuperseded=true` 可看旧版。
- 新版本入库时旧版置 `SUPERSEDED`，不物理删除。
- 同一 `docKey + scope` 不允许活跃版本重叠。

### FR-18 冲突提示（P1，时间有余）

- 对候选条款对做 `CONTRADICTION/OVERRIDE/EXCEPTION/DUPLICATE/NO_CONFLICT` 判断。
- 输出两份原文、版本、生效时间、scope/authority、`requiresHumanReview`。
- 不自动裁决、不自动删除、不自动改结论。

详细规则见 [10-conflict-resolution.md](10-conflict-resolution.md)。

## 5. 非目标

- 不做聊天 UI、多轮对话、agent 编排、自动执行器。
- 不做多租户、SSO、复杂角色体系、自动审批、审批 UI；治理层只做单租户最小闭环。
- 不做生产级高可用。
- 不做模型训练/微调。
- 不做 `/api/v1/answer` 生成式回答。
- 不做自动条款裁决（P1 只提示 + 人工复核）。
- 不做条款级增量修订/法律级推理。
- 不做全格式解析（先 PDF/DOCX/MD/TXT）。
- 不把 AnythingLLM 作为依赖。

## 6. 成功指标

| 指标 | 目标 | 证据 |
|---|---|---|
| 入库成功率 | 3 篇公开文档全部成功 | 上传响应/DB |
| 检索质量 | hit@5 ≥ 0.85；MRR ≥ 0.65 | `eval/report.md` |
| 检索延迟 | p95 < 800ms（10k chunks，本机） | 评测输出 |
| 引用正确 | 抽 5 条人工核对通过 | 验收记录 |
| MCP 可用 | 两个 tool 在 Inspector 调通 | 截图/日志 |
| 治理 | 无/过期/越权 mandate 100% 拒绝；正常调用 100% 有审计 | 测试 + audit 表 |
| 版本 | 默认只返回 ACTIVE；`asOf` 历史查询正确；旧版不物理删除 | 测试 + SQL |
| 测试 | `mvn test` 全绿 | surefire 报告 |

> 指标阈值只在 [05-non-functional.md](05-non-functional.md) 定义；本表只做摘要，冲突时以 05 为准。

## 7. MVP 与 stretch

**MVP（必须）**

- 3 篇文档入库；重复上传幂等。
- fixed + heading 两种切分。
- pgvector 向量检索 + 关键词 + RRF + 引用。
- 40 条 QA 评测，报告可复现。
- MCP 两个 tool + token + 审计。
- WorkItem → Approval → Mandate → 越权校验 → 审计。
- P0 版本治理：默认只召回 ACTIVE、`asOf` 历史查询、旧版不物理删除。
- Docker Compose + README + 测试。

**Stretch（时间够再做）**

- P1 冲突提示：候选对 + 结构化输出 + 人工复核；不做自动裁决。
- 语义切分对比。
- rerank（bge-reranker 或 LLM judge）。
- `/api/v1/eval/run` HTTP 入口（CLI 已够）。
- Python 脚本做 faithfulness 评测。

## 8. 假设与依赖

- 本机有 Docker、JDK 17、Maven、Ollama；`bge-m3` 已拉取。
- 语料是公开文档，不含真实患者/公司数据。
- 5 个工作日；行测优先，项目不能无限扩张。
- MCP 规范/库版本可能变化；以 README 记录的实际版本为准。

## 9. 已定默认与剩余风险

已定：

- 本地 coding agent 只做实现加速，不接 GitHub/Feishu/PR 自动化（[13-dev-workflow.md](13-dev-workflow.md)）。
- 切分策略：同一文档生成 `fixed`/`heading` 两套 chunk，用 `chunkStrategy` 区分（[11-retrieval-and-eval-spec.md](11-retrieval-and-eval-spec.md)）。
- QA schema / hit@k / MRR / 无答案题口径见 11。
- `effective_to` 采用**右闭**（最后有效日）；`includeSuperseded=true` 只要求 `effectiveFrom <= asOf`（[10-conflict-resolution.md](10-conflict-resolution.md)）。
- P1 冲突提示不阻塞 5 天验收；P0 版本治理必做。

剩余风险：

- 样例 PDF 不随仓库提交，需按 `docs/samples/README.md` 自备；否则 AC-01 无法执行。
- MCP 协议版本/Spring AI 1.1.8 行为以 [12-mcp-implementation.md](12-mcp-implementation.md) 为准，可能需要按实际版本微调。
- 评测指标依赖 40 条 QA 的标注质量；标注口径见 11。
