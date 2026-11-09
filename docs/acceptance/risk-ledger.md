# 风险台账（跨 Phase 追踪）

- 更新：2026-11-09
- 当前 `main`：Phase 5 签收后（`d490a7a`）
- Phase 6：`phase6` 分支已实现（未合并 main）；R1–R4 状态见下
- 用途：把“已解决 / 部分改善 / 仍待修”说清楚，避免把测量、定位或文档口径当成已修复。

## 状态总表

| # | 风险 | 状态 | 证据 / 位置 | 下一步 |
|---|---|---|---|---|
| R1 | 10 并发 p95 不稳定 | **部分改善，未达标** | Phase 3 A/B/C/D p95=808.59/552.07/1129.14/826.31ms；Phase 6 一次 60s 复测 p50=262.12、p95=**1780.18**、p99=5050.61ms、errors=0（[phase6-concurrency.json](phase6-concurrency.json)） | 需要专用推理/限流隔离/更多预热；query 缓存对唯一 query 基准无效，本轮未加 |
| R2 | fixed hit@5=0.3429 偏低 | **已修（仍有残余）** | Phase 6：`page_end` + fixed 主导 section clause 元数据 + pageRange 相交匹配；fixed hit@5 **0.7429**、MRR **0.6867**；heading hit@5 0.9429、MRR 0.8319；gold 未改 | fixed 仍低于 heading；主导 section 是启发式，窗口跨多标题时可能选错 |
| R3 | 2025 双栏 clausePath 噪声 | **部分修复** | 双栏 gutter 重排 + heading 候选清洗；`90 mmHg`/`3 d`/`20 g` clausePath 计数为 0；真实 2025 抽样 5 条见 [phase6-report.md](phase6-report.md) 第 3 节 | 表格/流程图仍可能跨栏交错，**未修全文顺序**；不宣称 content 已完全正确 |
| R4 | 企业 heading 无 clause_id | **已修（gold 未改）** | 中文 `第X条` 识别；企业 heading 48/49 有 clause_id（唯一 NULL 为封面标题），clause_path 带章；DB/SQL 见 phase6-report 第 2/6 节 | enterprise 11 条 QA 仍用 pageRange（dataset hash 未改）；Q18 本轮 top-1 为第三条，但 page-only 仍可能命中同页错误条款，后续 dataset revision 再逐条升级 |
| R5 | MCP 低层注册与生产端点 | **已闭环（Phase 5）** | Phase 2：`tool-callback-converter=false` 可用，必须一个 `List<SyncToolSpecification>` bean。Phase 5：生产 `/mcp` + Bearer 401 + mandate 强制校验 + `mcp_audit_log` + 真实 Streamable HTTP client 测试（`McpIntegrationTest`）；`tools/list` 恰好两个 | 残余：SDK 0.18.3 默认协商 2025-11-25（2025-06-18 也支持）；未知 tool 由 HTTP gate 返回 `-32601`；mandateId 仍是 bearer secret |
| R6 | Windows 评测脚本 | **已闭环** | Phase 3：`scripts/eval.ps1` 跑 40 条；Phase 6 增加 pageEnd 相交匹配与 dataset LF hash 稳定化 | 保持 |
| R7 | 两套 chunk 的 30s / 10k 口径 | **已闭环（口径+实测）** | Phase 2/3：入库 30s 是每套策略、两套合计 <60s；10k 是每套策略、合计 20k | 10 并发 p95 仍见 R1 |
| R8 | chunkCount | **已闭环** | `DocumentUploadIntegrationTest` 断言 `chunkCount == sum(chunkCounts)`，并与 DB 计数一致 | 保持 |
| R9 | chunkStrategies 校验 | **已闭环** | `FileNamesTest`/上传测试：默认 heading、逗号、大小写、去重、最多 2 个、非法值 400 | 保持 |

## 统计

- 已闭环：R5、R6、R7、R8、R9（5 项）
- 已修（仍有残余）：R2、R4（2 项）
- 部分修复：R3（1 项）
- 部分改善、未达标：R1（1 项）

## 原则

- “已测量”不等于“已修复”；“改了口径”也不等于“性能达标”。
- R2/R3/R4 不得通过改 gold、放宽匹配或删除 QA 来“解决”；Phase 6 未改 `eval/qa.jsonl`，dataset hash 仍为 `1487e...`。
- R1 若要硬保证 p95<800ms，需要缓存/限流/隔离 embedding；Phase 6 的 10 并发复测 p95=1780.18ms，不能宣称达标。
