# 非功能性：性能、容量、容错、可观测、安全

- 版本：v1
- 日期：2026-11-08
- 关联主设计：[00-design.md](00-design.md)
- 关联数据模型：[03-data-model.md](03-data-model.md)
- 关联异常：[04-boundaries-and-errors.md](04-boundaries-and-errors.md)
- 关联测试：[06-test-plan.md](06-test-plan.md)
- 关联验收：[07-acceptance.md](07-acceptance.md)

> 本文是指标阈值的**唯一定义处**；验收文档引用本文，不重复写第二套数字。

---

## 1. 容量假设

| 项 | 基线 | 上限（v1 不优化） |
|---|---|---|
| 文档数 | 3~50 | 200 |
| chunk 数 | 1k~10k | 100k（超出建议换 Milvus/Qdrant） |
| 向量维度 | 1024 | 1024 |
| 单 chunk tokens | ~600 | 900 |
| 每文档版本数 | 2~5 | 20 |
| 并发检索 | 10 | 32（压测观察） |
| 并发入库 | 1 | 2（同步入库，不追求高并发） |
| 存储 | < 200MB | 2GB |

---

## 2. 性能目标

### 2.1 检索

| 指标 | 目标 | 测量条件 |
|---|---|---|
| p50 | < 250ms | **每套 chunk 策略 10k chunks**（fixed 10k + heading 10k，合计 20k），topK=5，hybrid |
| p95 | **< 800ms** | 同上，10 并发 |
| p99 | < 1500ms | 同上 |
| 错误率 | < 1% | 10 并发，持续 60s |
| 无结果率 | 记录，不设硬目标 | 40 条 QA |

> **10k 口径（v1.1）**：这里的 10k 指**每套策略 10k chunks**，不是两套合计 10k。
> 两套策略合计基准为 20k chunks；验收报告必须写明实际测试的每策略数量与总数。
> Phase 2 实测：fixed 10k + heading 10k（合计 20k），50 次/策略 API 端到端
> （含真实 bge-m3 query embedding），p50/p95 见 `docs/acceptance/phase2-report.md`。
> **注意**：Phase 2 是串行 50 次/策略的基线；p95 稳定达标，p99 有环境方差，10 并发/60s（T-PERF-02）留 Phase 3。
>
> **Phase 3 更新（2026-11-09）**：10k/策略 3 轮实测 p95=42.72/64.62/120.88ms（fixed）、33.28/121.71/88.21ms（heading），
> p99=97.19/91.87/136.88ms、39.51/131.54/93.50ms，全部 <800ms/<1500ms，0 errors。
> 关键修复：原 JOIN 查询被规划成 nested loop + 全量 top-N sort，HNSW 未生效；现改为 SQL 层解析 eligible docIds 后
> 在 `kb_chunk` 上走 HNSW，详见 `docs/acceptance/phase3-report.md` 5.3。
> 10 并发 60s 三次：错误率 0%，p50≈130~157ms，p95=808.59/552.07/1129.14ms —— **错误率达标，p95 未稳定 <800ms**；
> 长尾与 Ollama embedding 直接 10 并发的 p99≈3.0s 同量级，属残余风险。

延迟预算（p95）：

| 阶段 | 预算 |
|---|---|
| 参数校验 + 鉴权 | < 10ms |
| query embedding（Ollama） | < 300ms |
| 向量检索（HNSW） | < 100ms |
| 关键词检索（pg_trgm） | < 100ms |
| RRF + 组装引用 | < 20ms |
| JSON 序列化 | < 20ms |
| 合计 | < 800ms（留 buffer） |

### 2.2 入库

| 指标 | 目标 | 条件 |
|---|---|---|
| 1MB 文本型 PDF 解析+切分 | < 10s | 不含 embedding |
| 全流程（含 embedding） | **< 30s / 每套策略；< 60s / 两套合计** | 3 篇文档、Ollama 已加载模型 |
| 重复上传 | < 100ms | 命中 checksum |
| 批量 embedding | 16~32 条/批 | 减少往返 |

> **30s 口径（v1.1）**：30s 是**每套 chunk 策略**的目标；一次上传同时生成 fixed + heading
> 时，3 篇文档的合计预算为 < 60s。Phase 2 的真实 smoke 中 3 篇文档两套策略合计约 14s
> （2024 摘编 4.4s + 2025 版 7.8s + 企业内控 1.8s），不含首次模型冷启动。

> 模型冷启动（首次加载 bge-m3）不计入 30s；README 说明先 `ollama pull` 并预热。

### 2.3 MCP

| 指标 | 目标 |
|---|---|
| `search_knowledge` p95 | 与 HTTP 检索一致（< 800ms） |
| `get_document` p95 | < 50ms |
| 鉴权失败响应 | < 10ms |
| 审计写入 | 异步/同事务均可，不阻塞结果 > 20ms |

### 2.4 治理

| 指标 | 目标 |
|---|---|
| WorkItem 创建 | p95 < 100ms |
| 批准 + 生成 Mandate | p95 < 200ms |
| MCP mandate 校验 | p95 < 20ms |
| 审计写入 | 不阻塞工具结果 > 20ms |
| 越权拒绝 | 100% 返回明确错误码，不返回数据 |

### 2.5 检索质量

| 指标 | 目标 | 说明 |
|---|---|---|
| hit@5 | ≥ 0.85 | 40 条 QA，35 条可回答 |
| MRR | ≥ 0.65 | 第一条命中的倒数排名均值 |
| 无答案误报率 | 记录，不设硬门槛 | 5 条不可回答题单独统计 |
| 失败/超时 | 单独计数 | 不能当 miss/丢弃 |

Phase 3 实测（`eval/qa.jsonl` 40 条，382 chunks，2026-11-09）：

| chunkStrategy | hit@1 | hit@3 | hit@5 | MRR | errors |
|---|---:|---:|---:|---:|---:|
| fixed | 0.3143 | 0.3143 | 0.3429 | 0.3214 | 0 |
| heading | 0.5429 | 0.8286 | **0.8857** | **0.6905** | 0 |

heading 达到 hit@5≥0.85、MRR≥0.65；fixed 低是固定窗口无 clause 元数据、page_no 为窗口起始页的真实结果，
未修改 gold。不可回答题两策略均 `falsePositiveRate=1.0`（hybrid 总返回 topK），按约定只记录、不设硬门槛。
详见 [docs/acceptance/phase3-report.md](acceptance/phase3-report.md) 第 3 节。

公式、QA schema、gold 匹配口径见 [11-retrieval-and-eval-spec.md](11-retrieval-and-eval-spec.md)。

---

## 3. 索引与查询

- `kb_chunk.embedding` 建 HNSW：`vector_cosine_ops`。
- `kb_chunk.content` 建 GIN trigram：`gin_trgm_ops`。
- `kb_chunk(document_id)` 普通 B-tree。
- `kb_document.checksum` 唯一索引。
- 检索 SQL 必须 `EXPLAIN (ANALYZE, BUFFERS)` 检查；不得全表顺序扫描（除非数据量很小且明确记录）。
- HNSW 参数先用默认；recall 不达标再调 `m` / `ef_construction`，调参记录进 `eval/report.md`。
- 不在 5 天内做多向量库对比；先保证 pgvector 跑通。

---

## 4. 并发与资源

### 4.1 线程/连接

- Tomcat 默认线程池即可；不额外造线程池。
- HikariCP：`maximumPoolSize=10`，`connectionTimeout=5s`。
- Ollama HTTP 客户端：连接超时 5s，读超时 120s。
- Embedding 执行池：`app.embedding.max-concurrent-calls`（默认 10；Phase 3 从固定 2 调整）。10 并发检索不再被 2 个线程串行化。
- 同步入库：同一文档串行；不同文档可并行，但 v1 不追求。
- 检索：无状态，可并行。向量路先解析 eligible docIds 再走 HNSW（避免 JOIN 导致全量排序），详见 Phase 3 报告 5.3。

### 4.2 内存

- 应用堆：建议 `-Xmx1g`，目标常驻 < 1GB（不含 Ollama）。
- 上传文件不整文件读入内存超过 20MB；流式解析优先。
- 批量 embedding 控制在 32 条，避免大 JSON。

### 4.3 限流

- MCP：可选内存令牌桶 5 req/s/调用方；超出 429。
- HTTP：MVP 不限流；本地使用。
- 限流不是验收硬指标，但代码要留扩展点。

---

## 5. 超时、重试、熔断

### 5.1 超时

| 调用 | 连接超时 | 读超时 |
|---|---|---|
| Ollama embedding（单条） | 5s | 120s |
| Ollama embedding（批量） | 5s | 120s |
| PostgreSQL | 5s | 30s |
| MCP tool 总预算 | — | 30s（超时返回 tool error） |

### 5.2 重试

- embedding：最多 2 次重试，退避 500ms / 1000ms。
- DB：连接失败重试 1 次；事务失败不回滚重试（避免重复写）。
- 不重试 4xx（参数/解析/鉴权）。
- 重试必须保证幂等：上传按 checksum；检索只读。

### 5.3 熔断

- v1 不引入 Resilience4j；重试 + 快速失败足够。
- 连续 5 次 embedding 失败，后续请求直接 503，持续 30s（可选简单熔断）。
- 若实现熔断，必须可配置、可关闭，并有测试。

---

## 6. 容错与降级

| 故障 | 行为 | 用户可见 |
|---|---|---|
| Ollama 不可用 | 入库失败；检索 503 | `ERR-EMBEDDING_UNAVAILABLE` |
| Ollama 超时 | 重试 2 次；仍失败 504 | `ERR-EMBEDDING_TIMEOUT` |
| PostgreSQL 不可用 | 健康检查 DOWN；请求 503 | `ERR-DB_UNAVAILABLE` |
| HNSW 索引缺失 | 检索降级为顺序扫描；日志 WARN；性能可能不达标 | 结果可用，延迟变高 |
| 审计写入失败 | 检索仍返回；记录 ERROR 日志 | 结果可用 |
| MCP token 未配置 | 服务启动失败（fail fast） | 启动错误 |
| 管理员 token 未配置 | 审批接口不可用；服务可启动 | 启动 WARN，审批 401 |
| Mandate 查询失败 | 拒绝调用，返回 503/500；不放行 | 明确错误 |
| 审计写入失败 | 工具结果仍返回；记录 ERROR 日志 | 结果可用，审计缺失告警 |
| 单篇文档解析失败 | 该次上传失败；不影响其他文档 | `ERR-PARSE_FAILED` |
| 评测中某条 QA 失败 | 记录失败，继续跑完；报告中列出 | 指标含失败数 |

**不允许的降级**：

- 用空结果冒充“没有相关知识”（必须区分“无结果”和“检索失败”）。
- embedding 失败后写入零向量。
- DB 失败后返回内存里的旧结果（v1 不做缓存）。

---

## 7. 可观测性

### 7.1 指标（Micrometer）

- `rag.search.latency`（timer，tag: strategy）
- `rag.search.results`（histogram）
- `rag.ingest.latency`（timer，tag: sourceType）
- `rag.ingest.chunks`（counter）
- `rag.embedding.latency`（timer）
- `rag.embedding.errors`（counter）
- `mcp.tool.calls`（counter，tag: tool/status）
- `mcp.tool.latency`（timer，tag: tool）
- `rag.eval.hit_at_5`（gauge，最近一次）

暴露：`/actuator/metrics`、`/actuator/prometheus`（如加 Micrometer registry）。

### 7.2 日志

- JSON 或 pattern，包含 `traceId`、`docId`、`tool`、`latencyMs`、`errorCode`。
- 不记录：文件内容、query 原文、token、密钥、身份证/手机号。
- 解析失败记录：文件名、阶段、异常类型；不记录全文。

### 7.3 审计

- `mcp_audit_log` 记录 tool、caller 指纹、args hash、status、latency、error_code。
- 审计表不存 query 原文。

### 7.4 健康检查

- `/actuator/health`：DB、embedding、磁盘。
- DB DOWN → HTTP 503；embedding DOWN → `DEGRADED`，不阻塞启动。

---

## 8. 安全

| 项 | 要求 |
|---|---|
| 密钥 | 环境变量；`.env` gitignore；不写日志 |
| MCP 鉴权 | Bearer；常量时间比较；未授权 401 |
| 管理员鉴权 | `X-Admin-Token`；常量时间比较；与 MCP token 分开 |
| Mandate | 工具/文档/topK/有效期校验；默认拒绝；文档范围下推 SQL |
| HTTP | 默认 localhost；公网需网关 |
| SQL | 全部参数化；禁止拼接 |
| 输入 | 文件/页数/query/topK/purpose/ttl 限制 |
| 输出 | content 截断；不返回绝对路径 |
| Tool 安全 | 只读；无通用 SQL/HTTP 工具；输出标记不可信 |
| 审计 | tool/workItem/mandate/args hash/status/latency；不存 query 原文 |
| 版本 | 默认只召回 ACTIVE；旧版不物理删除；不自动裁决新旧条款 |
| 依赖 | 锁版本；不引入无关大包 |
| 数据 | 只用公开文档；不提交真实患者/公司数据 |

---

## 9. 成本

- 本地 Ollama：无 API 费用；成本是电/内存。
- PostgreSQL：本地 Docker，无云费用。
- 不做按 token 计费；评测记录延迟即可。
- 若未来接云端 embedding，需加成本字段；v1 不做。

---

## 10. 兼容性

- JDK 17；Windows/Linux 均可。
- Docker Compose 使用 `pgvector/pgvector:pg16`。
- Ollama 本机 11434；容器内可用 `host.docker.internal`。
- 不依赖特定 IDE；Maven 可构建。

---

## 11. 验收引用

性能/容错验收项：

- AC-13：p95 检索 < 800ms（10k chunks）。Phase 3 实测 3 轮均 <121.71ms，通过。
- AC-14：Ollama 停止时入库/检索返回 503/504，不写脏数据。
- AC-15：DB 停止时健康检查 DOWN，请求 503。
- AC-16：10 并发检索错误率 < 1%。Phase 3 三次 60s 均 0 errors；p95 目标见 2.1 的 Phase 3 残余说明（未稳定 <800ms）。
- AC-17：WorkItem 创建/查看。
- AC-18：批准生成 Mandate；拒绝/重复审批正确。
- AC-19：无/过期/越权 mandate 100% 拒绝；合法 mandate 可检索。
- AC-20：审计含 workItemId/mandateId；管理员接口 token 校验。
- AC-21（P0）：默认只召回 ACTIVE；`asOf` 历史查询；旧版不物理删除；重叠活跃版本拒绝。
- AC-22（P1，可选）：冲突提示 + 人工复核标记；不做自动裁决。

详见 [07-acceptance.md](07-acceptance.md)。
