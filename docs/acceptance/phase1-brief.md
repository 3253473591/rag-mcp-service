# Phase 1 交接说明（给新对话）

> 目的：把“骨架 + 入库”交给新对话实现；本对话只做验收，不替实现者改代码。
> 实现者完成后按 [phase1-checklist.md](phase1-checklist.md) 自检，并填 [phase1-report-template.md](phase1-report-template.md)。
>
> **开工前先看 [phase1-readiness.md](phase1-readiness.md)**：Maven、Docker 已解决；bge-m3 拉取中；样例 PDF 待准备。未补齐前 Gate 3 不能完成。

---

## 1. 开场提示词（复制到新对话）

```text
你在 D:\AAA_Code_Project\rag-mcp-service 实现 Phase 1（骨架 + 入库），只做 Phase 1，不要做检索/RRF/评测/MCP/WorkItem/审批。

开工前先跑 docs/acceptance/phase1-readiness.md 的检查：
- 本会话需要完整权限（Maven 写 ~/.m2、Docker 写日志、Ollama 写模型目录都在 workspace 外；workspace-write 会权限失败）；
- Maven 3.9+ 可用（或项目有 mvnw）；
- Docker CLI 能连上 Linux engine；
- Ollama 有 bge-m3；
- 3 篇公开文本 PDF 已准备。
未就绪先补环境，不要直接写代码然后无法测试。

先读：
- docs/09-handoff.md 的 Phase 1、Review gate
- docs/acceptance/phase1-checklist.md（验收标准）
- docs/03-data-model.md、docs/11-retrieval-and-eval-spec.md、docs/02-api-contract.md 的文档接口
- docs/13-dev-workflow.md（工作方式）

交付：
- pom.xml、application.yml、docker-compose（已有 postgres）、Flyway V1
- PDF/DOCX/MD/TXT 解析 + Block 模型
- FixedWindowChunker + HeadingAwareChunker；一次上传生成 fixed/heading 两套 chunk
- Ollama bge-m3 embedding（批量 16~32；失败重试 2 次）；两套 chunk 都写 1024 维向量
- POST/GET/GET/DELETE /api/v1/documents；幂等、版本治理字段、chunk_strategy 小写
- 测试：T-UNIT-PARSE/CHUNK、T-EMBED-*、T-INT-DOC-01/02/05/06、T-VERSION-01/04/05/09/10
- 填 docs/acceptance/phase1-report.md

硬约束：
- 不提交 .env、真实 token、PII、样例 PDF。
- 所有 DDL 走 Flyway；禁止 ddl-auto=update。
- 解析/embedding 失败不写半截数据。
- 不靠重复上传补缺失的 chunkStrategies；首次上传就传 ["fixed","heading"]。
- 不编造测试/指标；mvn test 必须绿。
- 只做 Phase 1；检索/MCP 留到后续。

完成回报：
- 分支/commit（如无 git，给文件清单）
- 运行过的命令与结果
- 测试数/失败数
- 证据路径（SQL 输出、curl、surefire）
- 已知缺口/未做项

自检命令（PowerShell 执行策略可能拦截 .ps1，用 Bypass）：
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\accept-phase1.ps1

验收：按 docs/acceptance/phase1-checklist.md 的 Gate 0–7 逐项给证据；验收人不会替你改代码。
```

---

## 2. 实现者必须回报的内容

| 项 | 要求 |
|---|---|
| 代码位置 | 新增/修改的文件清单 |
| 构建 | `mvn test` 输出，测试数/失败/错误 |
| DB | `\dt`、扩展、索引、约束截图或 SQL |
| 上传 | 4 种格式的 HTTP 状态 + docId |
| 切分 | fixed/heading 两套 chunk 数 |
| Embedding | 维度 1024、无 NULL |
| 幂等 | 重复上传 200 + duplicate=true + count 不变 |
| 版本 | 旧版 SUPERSEDED、effective_to=新版生效日-1、重叠 400 |
| 删除 | 204 + 级联；重复删除 404 |
| 工程 | 无 .env/PII、无 ddl-auto=update、README 更新 |
| 报告 | `docs/acceptance/phase1-report.md` 已填 |

---

## 3. 验收方的动作

1. 收到回报后，先看报告是否完整，再看代码/测试。
2. 按 [phase1-checklist.md](phase1-checklist.md) 逐 gate 跑命令；不只看实现者的总结。
3. 任何 gate FAIL：记录“Gate + 命令 + 实际结果 + 期望结果”，退回实现者。
4. 全部 PASS：在报告上签收；Phase 1 结束，进入 Phase 2（检索）。
5. 不在 Phase 1 验收里接受“大概可以”“本地跑过”“后面再补”。

---

## 4. 通过后的下一步

- 更新 README 状态为“Phase 1 完成”。
- 把 Phase 1 的证据路径写入 `docs/acceptance/phase1-report.md`。
- 再开启 Phase 2（检索）的新对话；Phase 2 的验收清单另建。
