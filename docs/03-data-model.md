# 数据结构：表、字段、索引、迁移

- 版本：v1
- 日期：2026-11-08
- 关联主设计：[00-design.md](00-design.md)
- 关联接口：[02-api-contract.md](02-api-contract.md)
- 关联非功能：[05-non-functional.md](05-non-functional.md)
- 关联测试：[06-test-plan.md](06-test-plan.md)

数据库：**PostgreSQL 16 + pgvector**。  
选择理由与 MySQL 对比见主设计 ADR-002；本文只定义结构。

---

## 1. ER 关系

```
kb_document 1 ─── N kb_chunk
     │
     └── 删除时 ON DELETE CASCADE

work_item 1 ─── 0..1 mandate     （v1 一个 WorkItem 最多一个 Mandate）
mcp_audit_log                    独立审计表，不设外键
eval_run                         独立评测记录表
```

---

## 2. DDL

> 所有 DDL 必须进 Flyway migration：`src/main/resources/db/migration/V1__init.sql`。  
> 禁止手工在数据库里加字段后不写 migration。

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE kb_document (
  id                UUID PRIMARY KEY,
  doc_key           TEXT NOT NULL,               -- 同一制度的稳定标识
  title             TEXT NOT NULL,
  source_type       TEXT NOT NULL,               -- PDF / DOCX / MD / TXT
  source_uri        TEXT,                        -- upload://filename 或原始 URL
  checksum          CHAR(64) NOT NULL,           -- 文件内容 SHA-256
  version           TEXT,
  embedding_model   TEXT NOT NULL DEFAULT 'bge-m3',
  effective_from    DATE NOT NULL DEFAULT CURRENT_DATE,
  effective_to      DATE,                        -- 最后有效日（含）；NULL=长期有效
  status            TEXT NOT NULL DEFAULT 'ACTIVE', -- DRAFT/ACTIVE/SUPERSEDED
  supersedes_doc_id UUID REFERENCES kb_document(id) ON DELETE SET NULL,
  authority_level   TEXT,                        -- GROUP/REGION/SUBSIDIARY/DEPARTMENT
  scope             TEXT,                        -- group / region:上海
  topic_key         TEXT,                        -- P1 冲突候选
  metadata          JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ,
  CONSTRAINT ck_kb_document_status
    CHECK (status IN ('DRAFT','ACTIVE','SUPERSEDED')),
  CONSTRAINT ck_kb_document_authority
    CHECK (authority_level IS NULL OR authority_level IN ('GROUP','REGION','SUBSIDIARY','DEPARTMENT')),
  CONSTRAINT ck_kb_document_effective
    CHECK (effective_to IS NULL OR effective_from <= effective_to),
  CONSTRAINT uk_kb_document_key_version UNIQUE (doc_key, version)
);

CREATE UNIQUE INDEX uk_kb_document_checksum
  ON kb_document(checksum);

CREATE INDEX idx_kb_document_doc_key
  ON kb_document(doc_key, status, effective_from, effective_to);

CREATE INDEX idx_kb_document_created_at
  ON kb_document(created_at DESC);

CREATE TABLE kb_chunk (
  id            UUID PRIMARY KEY,
  document_id   UUID NOT NULL REFERENCES kb_document(id) ON DELETE CASCADE,
  chunk_index   INT NOT NULL,
  chunk_strategy TEXT NOT NULL DEFAULT 'heading', -- fixed / heading
  content       TEXT NOT NULL,
  token_count   INT NOT NULL,
  clause_id     TEXT,                            -- "3.2"
  clause_path   TEXT,                            -- "第三章 > 3.2 诊断标准"
  page_no       INT,                             -- 1-based；无页码为 NULL
  metadata      JSONB NOT NULL DEFAULT '{}'::jsonb,
  embedding     VECTOR(1024) NOT NULL,           -- bge-m3
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_kb_chunk_strategy CHECK (chunk_strategy IN ('fixed','heading')),
  CONSTRAINT uk_kb_chunk_strategy UNIQUE (document_id, chunk_strategy, chunk_index)
);

CREATE INDEX idx_kb_chunk_document_id
  ON kb_chunk(document_id);

CREATE INDEX idx_kb_chunk_embedding
  ON kb_chunk USING hnsw (embedding vector_cosine_ops);

CREATE INDEX idx_kb_chunk_content_trgm
  ON kb_chunk USING gin (content gin_trgm_ops);

CREATE TABLE work_item (
  id                UUID PRIMARY KEY,
  requester         TEXT NOT NULL,               -- 调用方标识
  purpose           TEXT NOT NULL,               -- 申请目的
  requested_tools   JSONB NOT NULL,              -- ["search_knowledge","get_document"]
  requested_doc_ids JSONB,                       -- NULL=全部；[]=无
  requested_top_k   INT NOT NULL DEFAULT 5,
  status            TEXT NOT NULL DEFAULT 'PENDING', -- PENDING/APPROVED/DENIED
  decided_by        TEXT,
  decided_at        TIMESTAMPTZ,
  decision_reason   TEXT,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_work_item_status CHECK (status IN ('PENDING','APPROVED','DENIED')),
  CONSTRAINT ck_work_item_top_k CHECK (requested_top_k BETWEEN 1 AND 20)
);

CREATE INDEX idx_work_item_status_created
  ON work_item(status, created_at DESC);

CREATE TABLE mandate (
  id                UUID PRIMARY KEY,
  work_item_id      UUID NOT NULL REFERENCES work_item(id) ON DELETE CASCADE,
  requester         TEXT NOT NULL,
  allowed_tools     JSONB NOT NULL,
  allowed_doc_ids   JSONB,                       -- NULL=全部；[]=无
  max_top_k         INT NOT NULL,
  status            TEXT NOT NULL DEFAULT 'ACTIVE', -- ACTIVE/REVOKED；EXPIRED 由 expires_at 判断
  expires_at        TIMESTAMPTZ NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  revoked_at        TIMESTAMPTZ,
  CONSTRAINT ck_mandate_status CHECK (status IN ('ACTIVE','REVOKED')),
  CONSTRAINT ck_mandate_top_k CHECK (max_top_k BETWEEN 1 AND 20),
  CONSTRAINT uk_mandate_work_item UNIQUE (work_item_id)
);

CREATE INDEX idx_mandate_status_expires
  ON mandate(status, expires_at);

CREATE TABLE mcp_audit_log (
  id                  BIGSERIAL PRIMARY KEY,
  tool_name           TEXT NOT NULL,
  work_item_id        UUID,
  mandate_id          UUID,
  caller_fingerprint  CHAR(12),                  -- token SHA-256 前 12 位
  args_hash           CHAR(64),                  -- 参数 JSON 的 SHA-256
  status              TEXT NOT NULL,             -- OK / ERROR
  error_code          TEXT,
  latency_ms          INT,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_mcp_audit_created_at
  ON mcp_audit_log(created_at DESC);

CREATE TABLE eval_run (
  id             BIGSERIAL PRIMARY KEY,
  dataset_hash   TEXT,                            -- eval/qa.jsonl 的 SHA-256
  chunk_strategy TEXT NOT NULL,                   -- fixed / heading
  embedding_model TEXT,
  git_commit     TEXT,
  top_k          INT,
  qa_count       INT NOT NULL,
  hit_at_1       NUMERIC(5,4),
  hit_at_3       NUMERIC(5,4),
  hit_at_5       NUMERIC(5,4),
  mrr            NUMERIC(6,4),
  p50_ms         INT,
  p95_ms         INT,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_eval_run_strategy CHECK (chunk_strategy IN ('fixed','heading'))
);
```

### 2.1 为什么不用 `content_tsv`

- 中文默认分词不可靠；`to_tsvector('simple', ...)` 不会按中文词切。
- 用 `pg_trgm` 的 `gin_trgm_ops` 支持 `ILIKE '%关键词%'` 和 similarity 排序，适合本项目的中文关键词候选召回。
- 英文语料可在后续 migration 增加 `tsvector` + GIN；v1 不做。

### 2.2 HNSW 参数

- v1 用默认 HNSW 参数，先跑通。
- 如 recall 不够，再调 `m` / `ef_construction`；调参记录写进 `eval/report.md`。
- 不要在 5 天内做 IVFFlat/HNSW 对比，除非评测主线已完成。

---

## 3. 字段说明

### 3.1 `kb_document`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | UUID | 主键 |
| `doc_key` | TEXT | 同一制度的稳定标识；不随版本变 |
| `title` | TEXT | 展示标题；默认文件名去扩展名 |
| `source_type` | TEXT | `PDF`/`DOCX`/`MD`/`TXT` |
| `source_uri` | TEXT | 来源标识；本地上传用 `upload://filename` |
| `checksum` | CHAR(64) | SHA-256，幂等键 |
| `version` | TEXT | 版本号，如 `2025`、`v2` |
| `embedding_model` | TEXT | 默认 `bge-m3`；换模型需重建索引 |
| `effective_from` | DATE | 生效日期；默认今天 |
| `effective_to` | DATE | 最后有效日（含）；NULL=长期有效 |
| `status` | TEXT | `DRAFT`/`ACTIVE`/`SUPERSEDED`；默认只召回 ACTIVE |
| `supersedes_doc_id` | UUID | 本版本替代的旧文档；同 doc_key |
| `authority_level` | TEXT | 集团/区域/子公司/部门；P0 只存不裁决 |
| `scope` | TEXT | 适用范围，如 `group`、`region:上海` |
| `topic_key` | TEXT | 主题标识；P1 冲突候选用 |
| `metadata` | JSONB | `author`、`sourceUrl` 等 |
| `created_at` | TIMESTAMPTZ | 入库时间 |
| `updated_at` | TIMESTAMPTZ | 预留；状态变更时更新 |

### 3.2 `kb_chunk`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | UUID | 主键 |
| `document_id` | UUID | 外键，级联删除 |
| `chunk_index` | INT | 策略内序号，从 0 开始 |
| `chunk_strategy` | TEXT | `fixed`/`heading`；同文档两套 chunk 共存 |
| `content` | TEXT | 原文片段 |
| `token_count` | INT | jtokkit/cl100k 计数 |
| `clause_id` | TEXT | 条款号，如 `3.2`；无则 NULL |
| `clause_path` | TEXT | 条款路径；无标题则 NULL |
| `page_no` | INT | 1-based；未知为 NULL |
| `metadata` | JSONB | 冗余 `docId`/`title`/`version`/`chunkIndex` |
| `embedding` | VECTOR(1024) | bge-m3 |
| `created_at` | TIMESTAMPTZ | 入库时间 |

### 3.3 `work_item`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | UUID | 主键 |
| `requester` | TEXT | 调用方标识；v1 不做身份校验 |
| `purpose` | TEXT | 申请目的 |
| `requested_tools` | JSONB | 申请的工具列表 |
| `requested_doc_ids` | JSONB | 申请的文档；NULL=全部，[]=无 |
| `requested_top_k` | INT | 申请 topK |
| `status` | TEXT | `PENDING`/`APPROVED`/`DENIED` |
| `decided_by` | TEXT | 管理员标识 |
| `decided_at` | TIMESTAMPTZ | 审批时间 |
| `decision_reason` | TEXT | 审批理由 |

### 3.4 `mandate`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | UUID | 主键 |
| `work_item_id` | UUID | 一对一；唯一索引 |
| `requester` | TEXT | 从 WorkItem 复制 |
| `allowed_tools` | JSONB | 允许的 tool |
| `allowed_doc_ids` | JSONB | NULL=全部；[]=无 |
| `max_top_k` | INT | topK 上限 |
| `status` | TEXT | `ACTIVE`/`REVOKED`；`EXPIRED` 是查询时的逻辑状态（`now() > expires_at`），不要求写回 |
| `expires_at` | TIMESTAMPTZ | 过期时间 |
| `revoked_at` | TIMESTAMPTZ | 预留 |

### 3.5 `mcp_audit_log`

| 字段 | 类型 | 说明 |
|---|---|---|
| `tool_name` | TEXT | `search_knowledge` / `get_document` |
| `work_item_id` | UUID | 可空；审批失败时也可能记录 |
| `mandate_id` | UUID | 可空；无效 mandate 也记录请求值 |
| `caller_fingerprint` | CHAR(12) | token 哈希前 12 位；不存明文 |
| `args_hash` | CHAR(64) | 参数 JSON 的 SHA-256；不存 query 原文 |
| `status` | TEXT | `OK` / `ERROR` |
| `error_code` | TEXT | 可空 |
| `latency_ms` | INT | 工具耗时 |

### 3.6 `eval_run`

用于保存评测结果，保证 `eval/report.md` 可复现；v1 可选，CLI 也可以只写文件。字段包含 `dataset_hash`、`chunk_strategy`、`embedding_model`、`git_commit`、`top_k`，用于回答“这个数字是哪版代码/数据跑出来的”。

---

## 4. 数据生命周期

### 4.1 入库

1. 计算文件 SHA-256。
2. 查 `kb_document.checksum`；存在则直接返回，不解析。
3. 解析 → 切分 → embedding（全部在内存完成）。
4. 事务：插入 `kb_document` + 批量插入 `kb_chunk`。
5. 任一步失败：不插入任何行；返回明确错误。

> 不要在解析/embedding 前先插 `PROCESSING` 行；v1 是同步入库，事务足够。若未来做异步，再引入状态机。

### 4.1.1 新版本入库

1. 新文档必须带 `doc_key`、`version`、`effective_from`。
2. 若带 `supersedes_doc_id`，校验旧文档同 `doc_key`。
3. 事务内：
   - 插入新文档（`ACTIVE`）；
   - 旧文档 `status=SUPERSEDED`；
   - 旧文档 `effective_to = 新文档.effective_from - 1 天`（如果旧值为空或更晚）。
4. 同一 `doc_key + scope` 的活跃版本不允许时间重叠；重叠且无 `supersedes_doc_id` → 拒绝。
5. 旧 chunk 不删除，历史查询/审计仍可用。

### 4.2 删除

- `DELETE FROM kb_document WHERE id = ?`；`kb_chunk` 由外键级联删除。
- 不删除 `mcp_audit_log` / `eval_run`（审计与评测记录独立保留）。

### 4.3 保留

- 文档/chunk：保留到用户删除。
- 审计：建议保留 30 天；v1 不做自动清理，README 说明。
- 评测：永久保留（数据量小）。

### 4.4 治理数据

- `work_item`：审批历史，保留 90 天（v1 不自动清理）。
- `mandate`：过期后保留，用于审计；不物理删除。
- 审批动作必须在一个事务里：更新 WorkItem 状态 + 插入 Mandate；要么都成功，要么都失败。
- 删除 WorkItem 不级联删除审计；审计保留。
- `mandate` 过期通过 `now() > expires_at` 判断，不依赖定时任务。

### 4.5 迁移

- 所有变更走 Flyway：`V2__...sql`、`V3__...sql`。
- 禁止 `ddl-auto=update`。
- 迁移必须可重复执行（`IF NOT EXISTS` 谨慎使用；Flyway 版本管理优先）。

---

## 5. 查询模式

### 5.1 向量检索

```sql
SELECT c.id, c.document_id, c.content, c.clause_path, c.page_no,
       d.version, d.effective_from, d.effective_to, d.status,
       1 - (c.embedding <=> ?::vector) AS score
FROM kb_chunk c
JOIN kb_document d ON d.id = c.document_id
WHERE c.chunk_strategy = ?
  AND d.status = 'ACTIVE'
  AND d.effective_from <= ?::date
  AND (d.effective_to IS NULL OR ?::date <= d.effective_to)
  AND (?::jsonb IS NULL OR c.metadata @> ?::jsonb)
ORDER BY c.embedding <=> ?::vector
LIMIT ?;
```

> 上面是默认模式（当前时点、只要 ACTIVE、`asOf` 落在生效区间内）的简化版。三种模式：
>
> | 模式 | status | 生效区间 |
> |---|---|---|
> | 默认 | `ACTIVE` | `effective_from <= asOf AND (effective_to IS NULL OR asOf <= effective_to)` |
> | 历史 `asOf`（过去日期） | `IN ('ACTIVE','SUPERSEDED')` | 同上，仍看 `effective_to` |
> | `includeSuperseded=true` | `IN ('ACTIVE','SUPERSEDED')` | **只要求 `effective_from <= asOf`，忽略 `effective_to`** |
>
> 不要把 `includeSuperseded=true` 只改成 `status IN (...)` 却保留 `effective_to`；那样旧版在当前时点仍然查不到。对应 SQL 里要整段去掉 `effective_to` 条件。

### 5.2 关键词候选

```sql
SELECT c.id, c.document_id, c.content, c.clause_path, c.page_no,
       d.version, d.effective_from, d.effective_to, d.status,
       similarity(c.content, ?) AS score
FROM kb_chunk c
JOIN kb_document d ON d.id = c.document_id
WHERE c.chunk_strategy = ?
  AND d.status = 'ACTIVE'
  AND d.effective_from <= ?::date
  AND (d.effective_to IS NULL OR ?::date <= d.effective_to)
  AND c.content ILIKE ? ESCAPE '\'
ORDER BY score DESC
LIMIT ?;
```

> `similarity` 的 `?` 用原始 query；`ILIKE` 的 `?` 用 Java 侧转义后的 pattern（`%`、`_`、`\` 已转义，形如 `%关键词%`）。不要用 `position()/strpos()`，否则 `gin_trgm_ops` 索引失效。

### 5.3 文档 chunk 数

```sql
SELECT count(*) FROM kb_chunk WHERE document_id = ?;
```

### 5.4 过滤

- `docIds`：`document_id = ANY(?::uuid[])`。
- `version`：`d.version = ?`（走 join 的 `kb_document`，不再依赖 metadata 冗余）。
- `asOf`：默认今天；`d.effective_from <= asOf AND (d.effective_to IS NULL OR asOf <= d.effective_to)`。
- `asOf` 为过去：允许 `ACTIVE`/`SUPERSEDED`（当时有效），排除 `DRAFT`；仍看 `effective_to`。
- `includeSuperseded=false`：当前时点只 `ACTIVE`；true 时允许 `SUPERSEDED`，**只要求 `effective_from <= asOf`，忽略 `effective_to`**（用于看“旧版怎么规定”）。
- 过滤条件必须参数化，不能拼字符串。
- **mandate 范围过滤必须进 SQL**：`document_id = ANY(allowed_doc_ids)`；不能查完再在 Java 过滤。

### 5.5 Mandate 校验

```sql
SELECT m.*, w.status AS work_item_status
FROM mandate m
JOIN work_item w ON w.id = m.work_item_id
WHERE m.id = ?
  AND m.status = 'ACTIVE'
  AND m.expires_at > now()
  AND w.status = 'APPROVED';
```

工具/文档/topK 范围在 Java 校验；文档范围同时下推到 SQL。

---

## 6. 容量假设

| 项 | 假设 |
|---|---|
| 文档数 | 3~50 |
| chunk 数 | 1k~10k |
| 向量维度 | 1024 |
| 单 chunk | 600 tokens 左右 |
| 并发检索 | 10 |
| WorkItem | 1k |
| Mandate | 1k |
| 审计 | 100k 行 |
| 存储 | 10k × (1024×4B + 文本) ≈ 50~100MB |

超过 10 万 chunk 再考虑 Milvus/Qdrant；v1 不为此优化。

---

## 7. 数据一致性

- `kb_document` 与 `kb_chunk` 同事务写入；不会出现“有文档无 chunk”或反之。
- `checksum` 唯一索引兜底并发重复上传；冲突时返回已有文档。
- `chunk_index` 在 `(document_id, chunk_strategy)` 内唯一；同文档两套策略互不覆盖。
- 新版本切换（旧版 `SUPERSEDED` + 新版 `ACTIVE`）必须同事务；不允许同时存在两个活跃重叠版本。
- `UNIQUE(doc_key, version)` 防止同版本重复；`doc_key+scope` 活跃区间重叠先用应用层校验，后续可用 `EXCLUDE USING gist` + `daterange` 兜底（需 `btree_gist`）。
- 幂等键是 `checksum`：同一文件内容视为同一文档，不能以新 `docKey/version` 重复入库；如需此能力，v2 改成 `(checksum, doc_key, version)` 并加 admin override。
- 删除是硬删除；v1 不做软删除/回收站；但“版本替代”不是删除，旧版保留；`supersedes_doc_id` 用 `ON DELETE SET NULL`，删除旧版不会阻塞。
- `embedding_model` 记录在文档上；启动/检索时校验与当前配置一致，换模型必须重建索引，不允许混用不同维度的向量。

---

## 8. 与接口的映射

| API 字段 | 数据来源 |
|---|---|
| `docId` | `kb_document.id` |
| `docKey` | `kb_document.doc_key` |
| `docVersion` / `version` | `kb_document.version` |
| `docStatus` | `kb_document.status` |
| `effectiveFrom` / `effectiveTo` | `kb_document.effective_from/to` |
| `title` | `kb_document.title` |
| `sourceType` | `kb_document.source_type` |
| `chunkCount` | `count(kb_chunk)` |
| `clauseId` | `kb_chunk.clause_id` |
| `clausePath` | `kb_chunk.clause_path` |
| `pageNo` | `kb_chunk.page_no` |
| `citation` | 由 `title + version + effectiveFrom + clausePath + pageNo` 组装 |
| `score` | 向量/关键词/RRF 计算值，不落库 |
| `workItemId` | `work_item.id` |
| `mandateId` | `mandate.id` |
| `audit.status` | 工具执行结果 |

---

## 9. 变更检查

- 加字段：必须给默认值/可空，写新 migration。
- 改字段类型/语义：视为破坏，需评估数据迁移。
- 加索引：写 migration；HNSW 索引构建可能耗时，README 说明。
- 删表/删列：先确认无 API/评测依赖，再写 migration。
