# 文档索引

`rag-mcp-service` 的设计文档按职责拆分；主设计只保留决策、技术栈、结构和红线，细节分别落在子文档。

| 顺序 | 文档 | 用途 |
|---|---|---|
| 1 | [00-design.md](00-design.md) | 主设计：ADR、技术栈、项目结构、红线、文档地图 |
| 2 | [01-prd.md](01-prd.md) | PRD：用户、场景、功能需求、范围、成功指标 |
| 3 | [02-api-contract.md](02-api-contract.md) | HTTP API + MCP tool 的请求/响应/错误码契约 |
| 4 | [03-data-model.md](03-data-model.md) | 表结构、字段、索引、迁移、数据生命周期 |
| 5 | [04-boundaries-and-errors.md](04-boundaries-and-errors.md) | 边界条件、异常矩阵、恢复策略 |
| 6 | [05-non-functional.md](05-non-functional.md) | 性能、容量、容错、可观测、安全 |
| 7 | [06-test-plan.md](06-test-plan.md) | 测试策略、用例、环境、CI |
| 8 | [07-acceptance.md](07-acceptance.md) | 验收清单、命令、证据、签收 |
| 9 | [08-governance.md](08-governance.md) | WorkItem/Mandate/Approval/Audit 治理层 |
| 10 | [09-handoff.md](09-handoff.md) | 本地 coding agent 交接说明与开场提示词 |
| 11 | [10-conflict-resolution.md](10-conflict-resolution.md) | 新旧条款/冲突：P0 版本治理，P1 冲突提示 |
| 12 | [11-retrieval-and-eval-spec.md](11-retrieval-and-eval-spec.md) | 切分、RRF、QA、hit@k/MRR 口径 |
| 13 | [12-mcp-implementation.md](12-mcp-implementation.md) | Spring AI MCP 配置、tool 注册、错误映射 |
| 14 | [13-dev-workflow.md](13-dev-workflow.md) | 本地 coding agent 工作流、review gate、红线 |
| — | [samples/README.md](samples/README.md) | 样例语料准备说明（不提交 PDF） |
| — | [acceptance/phase1-brief.md](acceptance/phase1-brief.md) | Phase 1 交接说明、开场提示词、回报要求 |
| — | [acceptance/phase1-readiness.md](acceptance/phase1-readiness.md) | Phase 1 环境就绪检查（Maven/Docker/Ollama） |
| — | [acceptance/phase1-checklist.md](acceptance/phase1-checklist.md) | Phase 1 验收 gate、命令、证据 |
| — | [acceptance/phase1-report.md](acceptance/phase1-report.md) | Phase 1 验收报告（已通过，含独立复核证据） |
| — | [acceptance/phase2-report.md](acceptance/phase2-report.md) | Phase 2 验收报告：检索、RRF、版本过滤、真实 smoke、性能、MCP spike |
| — | [acceptance/phase2-smoke.md](acceptance/phase2-smoke.md) | Phase 2 真实 app smoke 结果与 citation |
| — | [acceptance/phase2-mcp-spike.md](acceptance/phase2-mcp-spike.md) | MCP 低层注册 spike 结论（Phase 5 参考） |
| — | [acceptance/phase1-report-template.md](acceptance/phase1-report-template.md) | Phase 1 验收报告模板 |

阅读顺序：**00 → 01 → 08 → 10 → 02/03 → 04/05 → 06 → 07 → 09 → 11/12/13**。

约定：

- 需求编号 `FR-xx` 在 PRD 定义，验收文档引用同一编号。
- 错误码 `ERR-xxx` 在 API 契约定义，边界文档与测试文档引用同一编号。
- 指标阈值只在非功能文档定义一次，验收文档引用，不重复写两套数字。
- 文档与代码冲突时：文档是需求，代码是事实；冲突必须记录，不能静默绕过。
