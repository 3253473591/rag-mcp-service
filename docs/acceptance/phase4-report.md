# Phase 4 验收报告：治理 API（WorkItem → Approval → Mandate）

- 日期：2026-11-09
- 分支：`phase4`（基于 `main` @ `7781dac`）
- Commit：`phase4` 分支 head（`git log -1 --format=%H`；本报告与实现同一提交）
- 结论：P0 已实现；`mvn test` **167 tests / 0 failures / 0 errors / 1 skipped**（skip 为手动真实 Ollama smoke，非禁用失败测试）。
- 范围：WorkItem create/get、admin approve/deny、Mandate 生成、管理员鉴权、错误码/配置、测试。
- 非目标（留 Phase 5）：生产 `/mcp`、mandate 强制校验/过期拒绝、`mcp_audit_log` 写入、MCP Inspector、审计、前端/审批 UI、多租户/角色体系。未合并 `main`。

---

## 1. 交付物

| 文件 | 说明 |
|---|---|
| `src/main/java/com/lqx/ragmcp/api/WorkItemController.java` | 4 个治理端点；approve/deny 先做 admin 鉴权 |
| `src/main/java/com/lqx/ragmcp/api/WorkItemApiMapper.java` | 领域记录 → 契约响应 |
| `src/main/java/com/lqx/ragmcp/api/dto/*` | `CreateWorkItem* / WorkItemResponse / ApproveWorkItem* / DenyWorkItem* / MandateResponse` |
| `src/main/java/com/lqx/ragmcp/governance/WorkItemRecord.java` | `work_item` 领域记录（含 `requestedDocIds=null/[]` 区分） |
| `src/main/java/com/lqx/ragmcp/governance/MandateRecord.java` | `mandate` 领域记录 |
| `src/main/java/com/lqx/ragmcp/governance/WorkItemRepository.java` | JdbcTemplate；JSONB 读写；条件状态更新；mandate 插入 |
| `src/main/java/com/lqx/ragmcp/governance/WorkItemService.java` | 校验/创建/查询/事务审批 |
| `src/main/java/com/lqx/ragmcp/governance/MandatePolicy.java` | 纯规则：缩小校验 + Phase 5 只读校验（未接 MCP） |
| `src/main/java/com/lqx/ragmcp/governance/AdminAuth.java` | `X-Admin-Token`；`MessageDigest.isEqual`；fail closed |
| `src/main/java/com/lqx/ragmcp/config/AdminProperties.java` + `AdminConfig.java` | `app.admin.token` 绑定 |
| `src/main/java/com/lqx/ragmcp/api/error/ErrorCode.java` / `ApiException.java` | 新增 3 个治理错误码/工厂方法 |
| `src/main/resources/application.yml` / `.env.example` | `ADMIN_TOKEN` 占位；空值不影响启动 |
| `src/test/java/com/lqx/ragmcp/governance/*`、`integration/Governance*` | 新增 33 个治理测试 |

**未新增 Flyway migration**：Phase 1 `V1__init.sql` 已建 `work_item` / `mandate`，本阶段只使用既有表与约束；没有 `ddl-auto` 改动。

---

## 2. API 语义

### 2.1 创建 `POST /api/v1/work-items`

| 字段 | 规则 |
|---|---|
| `requester` | 必填、trim 后非空；v1 不做身份校验 |
| `purpose` | 必填、≤500 字符 |
| `requestedTools` | 1~5 个；只允许 `search_knowledge` / `get_document`；重复项按顺序去重 |
| `requestedDocIds` | 缺失/显式 `null` = 全部；`[]` = 无；数组 ≤50 个 UUID；去重 |
| `requestedTopK` | 1~20，默认 5 |

- 成功 201：`{workItemId,status:"PENDING",createdAt}`；`work_item.status` 落库 `PENDING`。
- 校验失败 400 `ERR-VALIDATION`，`details.field` 指向具体字段。
- 请求体为空 → 400；未知字段忽略（`@JsonIgnoreProperties(ignoreUnknown=true)`）。

### 2.2 查看 `GET /api/v1/work-items/{workItemId}`

- 200 返回 `workItemId/requester/purpose/requestedTools/requestedDocIds/requestedTopK/status/decidedBy/decidedAt/decisionReason/createdAt`；`APPROVED` 时附 `mandate{mandateId,allowedTools,allowedDocIds,maxTopK,expiresAt}`。
- `requestedDocIds=null` 在响应中保留为 `null`；`[]` 保留为 `[]`，不混淆。
- 不存在 404 `ERR-WORKITEM_NOT_FOUND`；非法 UUID 400 `ERR-VALIDATION`。
- 不做列表/审批 UI。

### 2.3 批准 `POST /api/v1/work-items/{workItemId}/approve`

Header：`X-Admin-Token: <ADMIN_TOKEN>`。

| 字段 | 规则 |
|---|---|
| `ttlSeconds` | 60~86400，默认 3600；`expiresAt = now + ttl` |
| `allowedTools` | 可缺省（默认取申请值）；只能取 `requestedTools` 子集；不能含未申请/未知工具 |
| `allowedDocIds` | 语义见第 3 节；可缺省/显式 null/数组 |
| `maxTopK` | 可缺省（默认 `requestedTopK`）；1~20 且 ≤ `requestedTopK` |
| `reason` | 可选；trim 后空串存 NULL |

- 200：`{workItemId,status:"APPROVED",mandate{...}}`。
- 同一事务：`UPDATE work_item ... WHERE status='PENDING'` + `INSERT mandate(status='ACTIVE')`；任一步失败整体回滚。
- 审批只允许缩小；`allowedTools` 显式 `[]` 被允许（只缩小到 0 个工具、mandate 实际不可用，不扩大权限）。
- 重复 approve、approve 已 DENIED、deny 已 APPROVED、deny 已 DENIED → 409 `ERR-APPROVAL_INVALID`；不会重复创建 mandate。

### 2.4 拒绝 `POST /api/v1/work-items/{workItemId}/deny`

Header：`X-Admin-Token`；body `{"reason":"..."}` 可选。

- PENDING → DENIED，记录 `decidedBy=admin`、`decidedAt`、`decisionReason`；200 返回 `{workItemId,status:"DENIED",decidedAt}`。
- 已 APPROVED/DENIED 再 deny → 409；不存在 404；无/错 token 401。

### 2.5 状态机与错误码

```
PENDING --approve--> APPROVED (+ 1 mandate)
   |
   +---deny-------> DENIED
```

- WorkItem 状态只写 `PENDING/APPROVED/DENIED`；Mandate 只写 `ACTIVE`；`EXPIRED` 由 Phase 5 按 `now() >= expires_at` 判断，不写回。
- 新增：`ERR-ADMIN_UNAUTHORIZED(401)`、`ERR-WORKITEM_NOT_FOUND(404)`、`ERR-APPROVAL_INVALID(409)`；参数/范围错误沿用 `ERR-VALIDATION(400)`。
- 统一错误体仍为 `{code,message,traceId,details}`，不返回堆栈/SQL/token。

---

## 3. 显式 `allowedDocIds` 处理（重点）

### 3.1 问题

契约对 `requestedDocIds=null`（申请全部）的审批要求：**审批时没有显式传 `allowedDocIds` → 400，不能默认全量**；只有显式 `"allowedDocIds": null` 才是“批准全部”。普通 DTO 无法区分“字段缺失”和“显式 null”，因为两者反序列化后都是 `null`。

### 3.2 实现

`ApproveWorkItemRequest` 用自定义 setter 记录字段是否出现：

```java
@JsonSetter("allowedDocIds")
public void setAllowedDocIds(List<String> allowedDocIds) {
    this.allowedDocIds = allowedDocIds;
    this.allowedDocIdsProvided = true;   // 显式 null 也会进入这里
}

@JsonIgnore
public boolean isAllowedDocIdsProvided() { ... }
```

Jackson 对字段缺失不会调用 setter，因此：

- `{}` → `provided=false`；
- `{"allowedDocIds": null}` → `provided=true, value=null`；
- `{"allowedDocIds": [...]}` → `provided=true, value=[...]`。

`MandatePolicy.resolveAllowedDocIds` 据此执行：

1. 缺失 + 申请为 `null` → 400（不默认全量）；
2. 显式 `null` + 申请为 `null` → 全量（mandate `allowed_doc_ids IS NULL`）；
3. 显式数组 + 申请为 `null` → 任意子集；
4. 申请为 `[]`：缺失 → `[]`；显式 `null`/非空数组 → 400；
5. 申请为 `[A,B]`：缺失 → `[A,B]`；显式 `null` → 400；数组必须是 `[A,B]` 子集。

### 3.3 语义矩阵

| 创建时 `requestedDocIds` | approve 时 `allowedDocIds` | 结果 |
|---|---|---|
| `null`/不传（全部） | 字段缺失 | 400 `ERR-VALIDATION`，`details.field=allowedDocIds` |
| `null`/不传（全部） | 显式 `null` | 200，mandate 全部文档（SQL NULL） |
| `null`/不传（全部） | 数组 | 200，作为全量子集 |
| `[]`（无） | 字段缺失或 `[]` | 200，mandate `[]` |
| `[]`（无） | 显式 `null` 或非空数组 | 400 |
| `[A,B]` | 字段缺失 | 200，mandate `[A,B]` |
| `[A,B]` | `[A]` 或 `[]` | 200 |
| `[A,B]` | 显式 `null` 或含 `C` | 400 |

### 3.4 JSONB 区分

- 写入：`requested_doc_ids` / `allowed_doc_ids` 为 JSONB；Java `null` 写 SQL NULL，空列表写 `[]`（由 `ObjectMapper` 序列化为 JSON 字符串后 `?::jsonb`）。
- 读取：`rs.getString(...) == null` → `null`（全部）；`"[]"` → 空列表（无）。
- 已用 SQL 直接验证（见第 8 节）：`approved explicit null` 的 `allowed_docs` 为空列值；`requestedDocIds=[]` 的 mandate 为 `[]`。

### 3.5 覆盖测试

- `MandatePolicyTest` 覆盖全部 8 行语义 + 50 上限 + 非法 UUID。
- `GovernanceIntegrationTest.explicitDocs_*` 覆盖 HTTP/Jackson + 真实 JSONB NULL/`[]` + GET 响应。
- 特别地：`requestedDocIds=null` + approve 不带字段 → 400，且 WorkItem 仍 PENDING、无 mandate。

---

## 4. 只能缩小：规则与校验顺序

| 管理员输入 | 规则 | 失败 |
|---|---|---|
| `allowedTools` | 必须是 `requestedTools` 子集；每项在白名单；≤5 | 400 `ERR-VALIDATION` |
| `allowedDocIds` | 见第 3 节；数组 ≤50 UUID | 400 `ERR-VALIDATION` |
| `maxTopK` | 1~20 且 ≤ `requestedTopK` | 400 `ERR-VALIDATION` |
| `ttlSeconds` | 60~86400 | 400 `ERR-VALIDATION` |

- 先检查 WorkItem 存在（404）和状态（409），再校验审批参数；重复审批即使传了非法 body 也返回 409，不产生副作用。
- 所有校验在写事务前完成；失败不会把 WorkItem 改成 APPROVED。
- `allowedTools=[]` 是允许的单调缩小（见第 9 节取舍）。

---

## 5. 管理员鉴权

`AdminAuth.requireAuthorized`：

- 读取 `AdminProperties.token`（`app.admin.token: ${ADMIN_TOKEN:}`）。
- 配置为空/空白 → 直接 401（fail closed），服务仍正常启动。
- 请求头缺失/空 → 401。
- 否则用 `MessageDigest.isEqual(expected.getBytes(UTF_8), supplied.getBytes(UTF_8))`；**不使用** `String.equals`。
- 401 错误码 `ERR-ADMIN_UNAUTHORIZED`；不记录 token、不在错误 message 中回显 token。
- 鉴权在 controller 中先于 UUID 解析/业务逻辑执行；approve 和 deny 都覆盖。
- 使用独立 Spring context（`app.admin.token=` 空）验证：服务启动成功，带任意 `X-Admin-Token` 仍然 401。

---

## 6. 事务与并发

`WorkItemService.approve` 标注 `@Transactional`：

1. `findById` → 不存在 404；状态非 PENDING → 409。
2. `MandatePolicy.decideApproval` 校验并计算缩小后的范围。
3. `UPDATE work_item SET status='APPROVED' ... WHERE id=? AND status='PENDING'`；更新行数必须为 1，否则 409。
4. `INSERT mandate(... ACTIVE, expires_at)`。
5. 捕获 `DuplicateKeyException`（`uk_mandate_work_item`）→ 409；由于抛出运行时异常，第 3 步的状态更新一并回滚。

并发兜底：

- 条件 UPDATE 在 READ COMMITTED 下只有一个事务能把 PENDING 改成 APPROVED；
- `mandate.work_item_id UNIQUE` 是第二道防线，重复 mandate 插入失败会回滚整个审批；
- 未引入额外锁/重试，避免死锁和重复副作用。

事务回滚测试（真实 PostgreSQL）：先为 PENDING WorkItem 手工插入一条 mandate，再调用 approve；接口返回 409 `ERR-APPROVAL_INVALID`，随后查询 `work_item.status` 仍为 `PENDING`、mandate 数量仍为 1，证明没有留下“APPROVED 但无新 mandate”/半截状态。

---

## 7. 测试

命令：

```powershell
mvn test
```

结果（`target/surefire-reports` 汇总 + Maven 输出）：

```text
Tests run: 167, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS
```

新增 Phase 4 测试 33 个：

| 测试类 | 数量 | 覆盖 |
|---|---|---|
| `governance/MandatePolicyTest` | 13 | 显式文档语义矩阵、工具/topK/ttl 缩小、50 上限、Phase 5 只读校验 |
| `governance/AdminAuthTest` | 3 | 正确/错误/缺失 token、未配置 fail closed、message 不含 token |
| `integration/GovernanceIntegrationTest` | 16 | T-GOV-01~07、创建/查看/404/非法 UUID、approve/deny、重复/交叉 409、范围放大 400、ttl 边界、事务回滚 |
| `integration/GovernanceAdminUnconfiguredIntegrationTest` | 1 | token 未配置时服务启动、approve/deny 均 401 |

关键测试点：

- T-GOV-01：创建 201/PENDING；`requested_tools`/`requested_doc_ids` 以 JSONB 落库（测试解析 JSONB 断言）。
- T-GOV-02：GET 字段完整、默认 topK=5、`requestedDocIds=null`、404/非法 UUID。
- T-GOV-03：approve 生成 ACTIVE mandate；`expiresAt ≈ now+ttl`；`work_item`/`mandate` 同事务落库；GET 附 mandate。
- T-GOV-04：deny 记录 reason/decidedBy/decidedAt；无 mandate；deny 后再 approve 409。
- T-GOV-05：approve 后重复 approve、再 deny → 均 409，mandate 数=1。
- T-GOV-06：加未申请工具/文档、maxTopK 超申请、ttl 越界 → 400；失败后仍 PENDING、无 mandate。
- T-GOV-07：无/错 token → 401；正确 token 可审批；token 未配置独立 context 401。
- 显式 docs：`null + 缺失` 400；`null + 显式 null` 200/DB NULL；`null + 数组`；`[] + 缺失` 200/DB `[]`；`[] + 显式 null` 400；非空申请 + 显式 null/非子集 400。
- 事务：mandate 插入冲突时 WorkItem 仍 PENDING（见第 6 节）。
- 全量回归：Phase 1/2/3 原有测试全部保持通过；1 skipped 是 `OllamaEmbeddingSmokeTest` 的条件跳过，不是 `@Disabled`。

---

## 8. curl / SQL 证据

> 本地应用用 `ADMIN_TOKEN=phase4-evidence-token` 启动，连接 compose 的 `rag-postgres`；以下响应为实际输出（时间为服务端时区/UTC ISO-8601，均为合法 ISO-8601）。PowerShell 下用 `--data-binary "@body.json"` 或 `Invoke-RestMethod`，避免 `curl.exe -d '<json>'` 的 Windows 引号问题。

### 8.1 创建（申请全部文档，未传 `requestedDocIds`）

```text
POST /api/v1/work-items
{"requester":"phase4-evidence","purpose":"Phase4 curl evidence: all docs",
 "requestedTools":["search_knowledge","get_document"],"requestedTopK":5}

HTTP 201
{"workItemId":"ff33fe21-89e9-46d0-a716-c74f7dabe487","status":"PENDING",
 "createdAt":"2026-11-08T22:12:28.600265Z"}
```

### 8.2 申请全部 + approve 不带 `allowedDocIds` → 400（不默认全量）

```text
POST /api/v1/work-items/ff33fe21-.../approve
X-Admin-Token: phase4-evidence-token
{}

HTTP 400
{"code":"ERR-VALIDATION",
 "message":"申请范围为全部文档时必须显式提供 allowedDocIds（null=全部，[]=无）",
 "traceId":"f41f3150425741e5","details":{"field":"allowedDocIds"}}
```

### 8.3 申请全部 + 显式 `"allowedDocIds": null` → 200 / mandate 全部

```text
POST /api/v1/work-items/ff33fe21-.../approve
{"allowedDocIds":null,"maxTopK":3,"ttlSeconds":120,"reason":"explicit all"}

HTTP 200
{"workItemId":"ff33fe21-89e9-46d0-a716-c74f7dabe487","status":"APPROVED",
 "mandate":{"mandateId":"f4712426-3a4b-4235-8a8f-49faf1bf1f19",
            "allowedTools":["search_knowledge","get_document"],
            "allowedDocIds":null,"maxTopK":3,
            "expiresAt":"2026-11-09T06:14:29.3223895+08:00"}}
```

### 8.4 重复 approve → 409

```text
POST /api/v1/work-items/ff33fe21-.../approve
{"allowedDocIds":null}

HTTP 409
{"code":"ERR-APPROVAL_INVALID","message":"WorkItem 当前状态为 APPROVED，不能重复审批",
 "traceId":"a0b8a0748a5c43d7"}
```

### 8.5 `requestedDocIds=[]` + approve 缺省 `allowedDocIds` → 200 / mandate `[]`

```text
POST /api/v1/work-items
{"requester":"phase4-evidence","purpose":"Phase4 curl evidence: empty docs",
 "requestedTools":["search_knowledge"],"requestedDocIds":[],"requestedTopK":5}

HTTP 201
{"workItemId":"820f741c-0b0d-49ae-9b13-a2cd53baa3b6","status":"PENDING", ...}

POST /api/v1/work-items/820f741c-.../approve
X-Admin-Token: phase4-evidence-token
{}

HTTP 200
{"workItemId":"820f741c-0b0d-49ae-9b13-a2cd53baa3b6","status":"APPROVED",
 "mandate":{"mandateId":"9f98dd81-f0d5-461c-8db1-f5ef2b4ca989",
            "allowedTools":["search_knowledge"],"allowedDocIds":[],
            "maxTopK":5,"expiresAt":"2026-11-09T07:12:30.3380227+08:00"}}
```

### 8.6 无 token / 范围放大 / deny

```text
# deny 不带 token
POST /api/v1/work-items/820f741c-.../deny {"reason":"x"}
HTTP 401
{"code":"ERR-ADMIN_UNAUTHORIZED","message":"管理员 token 缺失或错误",
 "traceId":"0a29d85f96ea487a"}

# 申请工具只有 search_knowledge，approve 加入 get_document
POST /api/v1/work-items/8c5aca28-.../approve
{"allowedTools":["search_knowledge","get_document"],"allowedDocIds":[]}
HTTP 400
{"code":"ERR-VALIDATION",
 "message":"allowedTools 必须是 requestedTools 的子集，不能扩大申请范围",
 "traceId":"51299d4fadfb4cb4","details":{"field":"allowedTools"}}

# 申请 topK=5，approve maxTopK=6
POST /api/v1/work-items/8c5aca28-.../approve {"allowedDocIds":[],"maxTopK":6}
HTTP 400
{"code":"ERR-VALIDATION",
 "message":"maxTopK 不能大于 requestedTopK，不能扩大申请范围",
 "traceId":"3014292372924ac4","details":{"field":"maxTopK"}}

# 合法 deny
POST /api/v1/work-items/8c5aca28-.../deny {"reason":"apply scope too broad"}
HTTP 200
{"workItemId":"8c5aca28-b491-4ead-ac60-4178c471f5c0","status":"DENIED",
 "decidedAt":"2026-11-09T06:12:41.6197225+08:00"}
```

### 8.7 GET（APPROVED 附 mandate）

```text
GET /api/v1/work-items/ff33fe21-89e9-46d0-a716-c74f7dabe487
HTTP 200
{"workItemId":"ff33fe21-89e9-46d0-a716-c74f7dabe487",
 "requester":"phase4-evidence","purpose":"Phase4 curl evidence: all docs",
 "requestedTools":["search_knowledge","get_document"],
 "requestedDocIds":null,"requestedTopK":5,"status":"APPROVED",
 "decidedBy":"admin","decidedAt":"2026-11-08T22:12:29.32239Z",
 "decisionReason":"explicit all","createdAt":"2026-11-08T22:12:28.600265Z",
 "mandate":{"mandateId":"f4712426-3a4b-4235-8a8f-49faf1bf1f19",
            "allowedTools":["search_knowledge","get_document"],
            "allowedDocIds":null,"maxTopK":3,
            "expiresAt":"2026-11-08T22:14:29.32239Z"}}
```

### 8.8 SQL 证据

```text
$ docker exec rag-postgres psql -U rag -d ragdb -c \
  "SELECT id, requester, status, requested_doc_ids::text AS requested_docs, \
          decided_by, decision_reason FROM work_item \
   WHERE requester = 'phase4-evidence' ORDER BY created_at;"

                  id                  |    requester    |  status  | requested_docs | decided_by | decision_reason
--------------------------------------+-----------------+----------+----------------+------------+-----------------
 1b06c8dd-a0ce-4e02-9157-a179a0f6ff09 | phase4-evidence | PENDING  |                |            |
 ff33fe21-89e9-46d0-a716-c74f7dabe487 | phase4-evidence | APPROVED |                | admin      | explicit all
 820f741c-0b0d-49ae-9b13-a2cd53baa3b6 | phase4-evidence | APPROVED | []             | admin      |

$ docker exec rag-postgres psql -U rag -d ragdb -c \
  "SELECT work_item_id, allowed_doc_ids::text AS allowed_docs, max_top_k, status \
   FROM mandate ORDER BY created_at;"

             work_item_id             | allowed_docs | max_top_k | status
--------------------------------------+--------------+-----------+--------
 ff33fe21-89e9-46d0-a716-c74f7dabe487 |              |         3 | ACTIVE   -- SQL NULL = 全部
 820f741c-0b0d-49ae-9b13-a2cd53baa3b6 | []           |         5 | ACTIVE   -- [] = 无

$ docker exec rag-postgres psql -U rag -d ragdb -c \
  "SELECT id, status, decided_by, decision_reason, decided_at IS NOT NULL AS has_decided_at \
   FROM work_item WHERE id = '8c5aca28-b491-4ead-ac60-4178c471f5c0'::uuid;"

                  id                  | status | decided_by |    decision_reason    | has_decided_at
--------------------------------------+--------+------------+-----------------------+----------------
 8c5aca28-b491-4ead-ac60-4178c471f5c0 | DENIED | admin      | apply scope too broad | t
```

### 8.9 未写审计（Phase 5 非目标）

```text
$ docker exec rag-postgres psql -U rag -d ragdb -c "SELECT count(*) FROM mcp_audit_log;"
 count
-------
     0
```

（`mcp_audit_log` 是 Phase 5 才写入；Phase 4 未触碰。）

---

## 9. 取舍、已知缺口与风险

1. **`docs/02-api-contract.md` 2.5 的旧措辞与 Phase 4 任务书冲突**：2.5 写“`requestedDocIds` 为空/不传表示申请全部文档”，而任务书和 `docs/03-data-model.md` 明确 `NULL=全部，[]=无`。本实现按后者，并只把前者记录为文档冲突；未擅自修改契约文件，留给验收方决定是否同步文字。
2. **`allowedTools=[]` 允许**：这是严格单调缩小，不扩大权限；mandate 实际不可用。若验收方希望“至少保留一个工具”，需要契约明确后改为 400。
3. **`reason`/`requester` 没有长度上限**：契约只写了 `purpose ≤500`；为避免擅自改变接口语义，本阶段只做 `purpose` 上限。若担心资源耗尽，可在后续统一加 TEXT 长度约束。
4. **过期不写回**：Phase 4 只写 `ACTIVE` 与 `expiresAt`；`EXPIRED` 由 Phase 5 查询时按 `now() >= expires_at` 判断，避免定时任务。
5. **Mandate 是 bearer capability**：v1 不绑定调用方身份（docs/08 威胁模型明确）；Phase 5 才在 MCP 层强制校验工具/docIds/topK/过期。
6. **未实现**：MCP `/mcp`、`mcp_audit_log` 写入、MCP Inspector、WorkItem 列表/审批 UI、多租户/角色/SSO、撤销接口（`REVOKED` 字段预留）。
7. **并发**：没有专门的 N 并发压力测试；通过条件 UPDATE + 唯一约束 + 事务回滚测试保证正确性。若验收要求并发数值，可补 10 并发 approve 的压测。
8. **本地证据数据**：curl 证据在本地 compose 的 `rag-postgres` 留下了 `phase4-evidence` 的 work_item/mandate 行；Testcontainers 测试使用独立临时库，不受影响。
9. **`allowedDocIds` 显式 null 的 JSON 处理**：选择“可变 DTO + `@JsonSetter` 记录 presence”，而不是 raw `JsonNode`。原因：类型仍由 Jackson 绑定到 `List<String>`，非法元素走统一 400；同时能精确区分 absent/null。单元/集成测试均覆盖。
10. **`MandatePolicy` 的 Phase 5 只读校验已实现但未接线 MCP**：`isActive/allowsTool/allowsTopK/allowsDocIds` 有单测，供 Phase 5 复用；Phase 4 没有任何 MCP 端点或审计写入。

---

## 10. 复现

```powershell
# 1. 启动依赖
docker compose up -d postgres

# 2. 全量测试（Testcontainers，不依赖 Ollama）
mvn test

# 3. 启动服务（设置临时 admin token）
$env:ADMIN_TOKEN = "local-dev-token-not-committed"
mvn spring-boot:run

# 4. 创建/审批/查看（PowerShell 例子见 README Phase 4 节）
# 5. SQL 对照
docker exec rag-postgres psql -U rag -d ragdb -c "SELECT * FROM work_item;"
docker exec rag-postgres psql -U rag -d ragdb -c "SELECT * FROM mandate;"
```

---

## 11. 签收结论

- P0 功能：**通过**（create/get/approve/deny、mandate、缩小、显式 null、409/401/404、事务）。
- P0 工程：**通过**（`mvn test` 167/0/0/1；无真实 token/PII 提交；无 `ddl-auto`；无 `@Disabled`）。
- Phase 5：**未做**（按要求）。
- 待验收方确认：`docs/02` 2.5 文字是否同步为 `null=全部，[]=无`；`allowedTools=[]` 是否需要禁止。
