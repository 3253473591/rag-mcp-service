# 治理层：WorkItem、Mandate、Approval、Audit

- 版本：v1
- 日期：2026-11-08
- 关联主设计：[00-design.md](00-design.md)
- 关联 PRD：[01-prd.md](01-prd.md)
- 关联接口：[02-api-contract.md](02-api-contract.md)
- 关联数据模型：[03-data-model.md](03-data-model.md)
- 关联边界：[04-boundaries-and-errors.md](04-boundaries-and-errors.md)
- 关联验收：[07-acceptance.md](07-acceptance.md)

> C+ 的“激进”不在更多功能，而在**受控自治**：AI 应用不能无限权限地查知识库；每次外部访问都要有工作项、授权范围、审批和审计。  
> 本层是单租户的治理壳，不是完整 agent，不做多轮规划/工具编排。

---

## 1. 为什么需要这层

RAG + MCP 解决了“能查”，但没有解决：

- 谁可以查？
- 能查哪些文档？
- 能查多少条？
- 谁批准了这次访问？
- 出问题能不能追溯？

对国企/电信场景，“能查”不是最难，“可控、可审计、最小权限”才是。  
本层借鉴 `dsh-superagent-prototype` 的 control plane 思路（WorkItem / Mandate / Policy / Executor / Audit），但只实现单租户、只读工具的最小闭环。

---

## 2. 概念

| 概念 | 定义 | v1 是否落库 |
|---|---|---|
| WorkItem | 一次“申请访问知识库”的工作项，含申请人、目的、申请的工具/文档/ topK | 是 |
| Mandate | 管理员批准后生成的授权凭证，限定工具、文档范围、topK、有效期 | 是 |
| Policy | 校验规则：mandate 是否有效、工具/文档/topK 是否在范围内 | 代码内规则，不落库 |
| Approval | 管理员对 WorkItem 的批准/拒绝动作 | 是（WorkItem 状态 + Mandate） |
| Audit | 每次 MCP 工具调用的记录 | 是 |

**不做**：多租户、角色体系、SSO、自动审批、完整 agent 执行器、审批 UI。

---

## 3. 状态机

### 3.1 WorkItem

```
PENDING ──approve──> APPROVED
   │
   └──deny───────> DENIED
```

- `PENDING`：已创建，等待管理员。
- `APPROVED`：已批准，生成 Mandate。
- `DENIED`：已拒绝，记录原因。
- 不做 `EXPIRED`：过期是 Mandate 的属性，WorkItem 保留历史。

### 3.2 Mandate

```
ACTIVE ──expiresAt 到期──> EXPIRED
   │
   └──revoke（可选）────> REVOKED
```

- `ACTIVE`：可用。
- `EXPIRED`：**逻辑状态**，`now() > expires_at` 即视为过期；不要求定时任务写回。
- `REVOKED`：v1 可不实现撤销接口；预留字段。

---

## 4. 流程

```mermaid
sequenceDiagram
    participant A as AI应用/调用方
    participant API as WorkItem API
    participant Admin as 管理员
    participant MCP as MCP Server
    participant DB as PostgreSQL

    A->>API: POST /work-items（requester, purpose, tools, docs, topK）
    API->>DB: INSERT work_item(PENDING)
    API-->>A: workItemId
    Admin->>API: POST /work-items/{id}/approve（X-Admin-Token, ttl）
    API->>DB: UPDATE work_item(APPROVED); INSERT mandate(ACTIVE)
    API-->>Admin: mandateId + expiresAt
    A->>MCP: tools/call(search_knowledge, {mandateId, query})
    MCP->>DB: 查 mandate；校验工具/文档/topK/过期
    alt 校验通过
        MCP->>DB: 检索 + 写 audit(work_item_id, mandate_id)
        MCP-->>A: results + citations
    else 校验失败
        MCP->>DB: 写 audit(status=ERROR)
        MCP-->>A: tool error（ERR-MANDATE_*）
    end
```

---

## 5. 校验规则

MCP `search_knowledge` / `get_document` 必须带 `mandateId`，并按顺序校验：

1. Mandate 存在；否则 `ERR-MANDATE_REQUIRED`。
2. `status = ACTIVE` 且 `now() < expires_at`；否则 `ERR-MANDATE_EXPIRED`。
3. 工具在 `allowed_tools` 内；否则 `ERR-MANDATE_SCOPE`。
4. `topK`（若有）≤ `max_top_k`；否则 `ERR-MANDATE_SCOPE`。
5. `docIds`（若有）必须是 `allowed_doc_ids` 的子集；否则 `ERR-MANDATE_SCOPE`。
   - `allowed_doc_ids = NULL` 表示全部文档；
   - `allowed_doc_ids = []` 表示不允许任何文档（默认拒绝）。
6. `get_document` 的 `docId` 必须在允许范围内；否则 `ERR-MANDATE_SCOPE`。
7. 通过后执行检索；写 audit。

> 默认拒绝：没有 mandate、mandate 过期、范围为空，都不放行。  
> 不做“先查再过滤”，必须在 SQL 层带 `document_id` 过滤，避免越权数据进入内存。

---

## 6. 审批接口（摘要）

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/api/v1/work-items` | 无（本地/内网） | 创建申请 |
| GET | `/api/v1/work-items/{id}` | 无 | 查看状态 |
| POST | `/api/v1/work-items/{id}/approve` | `X-Admin-Token` | 批准，创建 mandate |
| POST | `/api/v1/work-items/{id}/deny` | `X-Admin-Token` | 拒绝 |

详细字段见 [02-api-contract.md](02-api-contract.md)。

### 审批参数

- `ttlSeconds`：60~86400，默认 3600。
- `allowedTools`：默认取申请的工具；管理员可缩小，不可放大到未申请的工具。
- `allowedDocIds`：默认取申请的文档；管理员可缩小；`null` 表示全部（仅管理员显式选择）。
  - 如果申请时 `requestedDocIds=null` 且审批时没有显式传 `allowedDocIds`，返回 400，**不默认全量**。
- `maxTopK`：默认取申请 topK；不可大于 20。

### 审批幂等

- 对同一个 `PENDING` WorkItem 重复 approve：返回 409 `ERR-APPROVAL_INVALID`，不重复创建 mandate。
- 对已 `DENIED` 的 WorkItem approve：409。
- 对已 `APPROVED` 的 WorkItem deny：409。

---

## 7. 审计

每次 MCP `tools/call` 写 `mcp_audit_log`：

- `tool_name`
- `work_item_id`（可空：如果 mandate 无效，仍记录请求里的值）
- `mandate_id`（可空）
- `caller_fingerprint`（MCP token SHA-256 前 12 位）
- `args_hash`（参数 JSON 的 SHA-256）
- `status`：`OK` / `ERROR`
- `error_code`
- `latency_ms`

不记录：query 原文、文档内容、token 明文。

审计失败不阻断主流程（记录 ERROR 日志），但验收要求正常路径有记录。

---

## 8. 威胁模型

| 威胁 | 对策 |
|---|---|
| 没有授权就查知识库 | 必须带 mandate；默认拒绝 |
| 用别人的 mandate | v1 不做调用方身份绑定；mandateId 视为 bearer secret；README 说明局限 |
| 扩大 topK 拿更多内容 | `max_top_k` 限制 |
| 查未授权文档 | SQL 层 `document_id` 过滤；越权直接 `ERR-MANDATE_SCOPE` |
| 重放/重复调用 | 检索只读，不产生副作用；审计记录 |
| 管理员 token 泄露 | 环境变量；本地/内网；不提交 |
| 审计缺失 | 正常路径必须有记录；验收检查 |
| Prompt injection | tool 输出标记为不可信数据；不执行文档内容 |

> v1 的 Mandate 是“能力令牌”，不是完整 OAuth；调用方身份不绑定。面试时要主动说清这个边界。

---

## 9. 非目标

- 不做多租户隔离。
- 不做用户/角色/SSO。
- 不做自动审批/规则引擎。
- 不做审批 Web UI（用 curl/Postman）。
- 不做写操作（删除/入库）的 MCP tool；v1 只有只读工具。
- 不做完整 agent 执行器/多轮规划。

---

## 10. 验收

- AC-17：创建/查看 WorkItem。
- AC-18：批准生成 Mandate；拒绝生效；重复审批 409。
- AC-19：无/过期/越权 mandate 被拒绝；合法 mandate 可检索。
- AC-20：审计包含 work_item_id/mandate_id；管理员接口无 token 401。

详见 [07-acceptance.md](07-acceptance.md)。
