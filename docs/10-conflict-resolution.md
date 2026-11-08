# 新旧条款与冲突处理：P0 版本治理，P1 冲突提示

- 版本：v1
- 日期：2026-11-08
- 关联主设计：[00-design.md](00-design.md)
- 关联 PRD：[01-prd.md](01-prd.md)
- 关联接口：[02-api-contract.md](02-api-contract.md)
- 关联数据模型：[03-data-model.md](03-data-model.md)
- 关联验收：[07-acceptance.md](07-acceptance.md)

> 结论：**P0 必做，P1 只在 5 天时间有余时做。**  
> P0 解决“默认不召回旧条款、能查历史、引用带版本”；P1 才做“发现跨文档冲突并提示人工复核”。  
> 任何情况下都不做自动裁决。

---

## 1. 问题

企业知识库里同一制度经常有多个版本、多个适用范围：

- 旧版《差旅制度》住宿 500/晚，新版 600/晚；
- 集团制度 500，子公司制度 600；
- 通用报销规则与上海特例；
- 同一制度新旧版本同时留在库里。

向量检索会把新旧条款都召回，甚至旧条款因为表述更完整而排在前面。**这不是 embedding 能解决的问题**，必须靠元数据、生效时间和版本链。

---

## 2. P0：版本治理（必做）

### 2.1 元数据

`kb_document` 增加：

| 字段 | 说明 |
|---|---|
| `doc_key` | 同一制度的稳定标识，不随版本变 |
| `version` | 版本号，如 `2025`、`v2` |
| `effective_from` | 生效日期（DATE） |
| `effective_to` | 失效日期（DATE，NULL=长期有效） |
| `status` | `DRAFT` / `ACTIVE` / `SUPERSEDED` |
| `supersedes_doc_id` | 本版本替代哪一份文档 |
| `authority_level` | 集团/区域/子公司/部门（文本，P0 只存不裁决） |
| `scope` | 适用范围（文本，如 `group`、`region:上海`） |
| `topic_key` | 主题标识（P1 冲突候选用，P0 可空） |

`kb_chunk` 增加：

| 字段 | 说明 |
|---|---|
| `clause_id` | 条款号，如 `3.2` |
| `clause_path` | 条款路径，如 `第三章 > 3.2` |

> `effective_from/to`、`status` 不冗余到 chunk；检索时 join `kb_document`，避免状态不同步。

### 2.2 状态生命周期

```
DRAFT ──发布──> ACTIVE ──被新版本替代──> SUPERSEDED
```

- 默认只召回 `ACTIVE`。
- `DRAFT` 永不召回。
- `SUPERSEDED` 只在 `includeSuperseded=true` 或历史 `asOf` 查询时返回。
- 不物理删除旧版本；删除是独立管理动作。

### 2.3 新版本入库规则

上传新版本时：

1. 必须提供 `docKey`、`version`、`effectiveFrom`。
2. 可选 `supersedesDocId`；如果提供，校验它属于同一 `docKey`。
3. 事务内：
   - 插入新文档；
   - 旧文档 `status = SUPERSEDED`；
   - 旧文档 `effective_to = 新文档.effective_from - 1 天`（右闭区间，`effective_to` 是最后有效日；如果旧值为空或更晚）。
4. 同一 `doc_key + scope` 不允许两个 `ACTIVE` 版本在时间上重叠；重叠且没有 `supersedesDocId` → 400。
5. 旧版本的 chunk 不删除，历史查询可用。

### 2.4 检索语义

请求增加：

```json
{
  "query": "出差住宿标准",
  "asOf": "2026-11-08",
  "includeSuperseded": false
}
```

规则：

- `asOf` 缺省 = 今天；只允许 `1900-01-01` ~ `2100-12-31`。
- 默认（`asOf` 为今天）：`status='ACTIVE' AND effective_from <= asOf AND (effective_to IS NULL OR asOf <= effective_to)`。
- `asOf` 为过去日期：返回**当时有效**的版本，允许 `SUPERSEDED`（历史查询），仍受 `effective_from/to` 约束。
- `includeSuperseded=true`：在当前时点也允许返回已被替代的旧版，用于“旧版怎么规定”的对比；**只要求 `effective_from <= asOf`，忽略 `effective_to`**。
- 命中结果带 `docVersion`、`effectiveFrom`、`effectiveTo`、`docStatus`、`clauseId`、`clausePath`。
- `citation` 带版本与生效时间：`《差旅制度》v2026（2026-07-01 生效） > 3.2 > 第5页`。

### 2.5 P0 验收

- 新旧两版同时入库，默认检索只返回新版。
- `asOf=旧版有效期` 返回旧版。
- `includeSuperseded=true` 可看到旧版，且引用带版本。
- 同 `docKey+scope` 重叠活跃版本被拒绝。
- 旧版不被物理删除。

详见 [07-acceptance.md](07-acceptance.md) AC-21。

---

## 3. P1：冲突提示（时间有余再做）

### 3.1 只做“发现 + 提示”，不做“自动裁决”

冲突类型：

| 类型 | 例子 | 处理 |
|---|---|---|
| 时间冲突 | 旧版 500，新版 600 | P0 已通过版本/生效时间解决 |
| 层级冲突 | 集团 500，子公司 600 | 展示 authority/scope；人工判断 |
| 特殊优于一般 | 通用规则 vs 上海特例 | 展示 scope；人工判断 |
| 范围不同 | 华东 500，华南 600 | 不是冲突 |
| 例外 | “除上海外……” | 不是冲突，标 exception |
| 真矛盾 | 同层级/同 scope/无优先级 | 人工复核 |

### 3.2 候选生成

- 先按 `topic_key`、`doc_key`、`scope`、`authority_level` 找候选；
- 再用向量相似度找“同主题但不同文档”的 clause 对；
- 只对候选对调用 LLM/NLI，不把所有 chunk 两两比较。

### 3.3 判定输出

```json
{
  "relation": "CONTRADICTION | OVERRIDE | EXCEPTION | DUPLICATE | NO_CONFLICT",
  "confidence": 0.82,
  "rationale": "两份制度对同一城市住宿标准给出不同上限",
  "clauses": [
    {"docId": "...", "version": "2025", "clauseId": "3.2", "content": "..."},
    {"docId": "...", "version": "2026", "clauseId": "3.2", "content": "..."}
  ],
  "requiresHumanReview": true
}
```

规则：

- 先用确定性规则（生效时间、supersedes、authority、scope）算候选关系；
- LLM 只做“发现/解释”，不做最终裁决；
- 不确定 → `requiresHumanReview=true`；
- 不自动修改文档、不自动删除旧版、不自动生成最终结论。

### 3.4 API / MCP（P1）

- `POST /api/v1/search` 增加 `"detectConflicts": true`（默认 false）。
- 响应增加 `conflicts` 数组；无冲突返回 `[]`。
- MCP `search_knowledge` 增加可选 `detectConflicts`。
- 不新增写操作；冲突复核复用 WorkItem/Approval（可选）。

### 3.5 P1 验收（可选）

- 20 条冲突集：时间/层级/scope/例外/真矛盾/无冲突。
- 指标：冲突识别准确率、错误引用率、`requiresHumanReview` 覆盖率。
- 任何冲突结果都能回溯到两份原文；不自动裁决。

---

## 4. 明确不做

- 不做法律级冲突推理。
- 不做自动“以新为准”：层级/例外/属地放宽都可能让旧条款仍有效。
- 不做自动删除旧版。
- 不做条款级增量修订（P1 也只到文档级版本 + 条款路径）。
- 不把“相似度高”当冲突。

---

## 5. 数据与接口影响

- 数据模型见 [03-data-model.md](03-data-model.md) 的 `kb_document` / `kb_chunk` 新字段。
- API 见 [02-api-contract.md](02-api-contract.md) 的 `asOf` / `includeSuperseded`（P0）与 `detectConflicts`（P1）。
- 验收见 [07-acceptance.md](07-acceptance.md) AC-21（P0）/ AC-22（P1，可选）。
