# 风险台账（跨 Phase 追踪）

- 更新：2026-11-09
- 当前 `main`：Phase 6c 签收后
- Phase 6：`phase6` 已验收（P0，带 residual）并合并 `main`；R1–R4 状态见下
- Phase 6b：仅 R1；`phase6b` 已验收并合并 `main`；实现方 3 次 + 验收方 3 次 60s，p95 全部 <800ms
- Phase 6c：仅 R3/Q04；`phase6c` 已验收并合并 `main`；Q04 heading/fixed top-1 为 4.2.2，R3 按信息正确性闭环
- 用途：把“已解决 / 部分改善 / 仍待修”说清楚，避免把测量、定位或文档口径当成已修复。

## 状态总表

| # | 风险 | 状态 | 证据 / 位置 | 下一步 |
|---|---|---|---|---|
| R1 | 10 并发 p95 不稳定 | **本机达标（6/6），残余长尾** | Phase 6b：query 批处理（8 / 5ms）+ 最多 3 个并发 batch + 有界队列/缓存；实现方三次 p95=**445.29 / 500.12 / 468.59ms**、errors=0、cacheHits=0/8004；验收方独立三次 p95=**498.53 / 368.39 / 408.52ms**、errors=0/1/0（0.0412%<1%）、cacheHits=0/8863；40 QA 指标与 Phase 6 一致（fixed 0.7429/0.6867、heading 0.9429/0.8319） | p99=444~749ms；max 可到 11.2s，单次长尾仍在；数字依赖本机 Ollama/GPU，见 [phase6b-report.md](phase6b-report.md) |
| R2 | fixed hit@5=0.3429 偏低 | **显著改善，未完全闭环** | Phase 6：`page_end` + fixed 主导 section clause 元数据 + pageRange 相交匹配；fixed hit@5 **0.7429**、MRR **0.6867**；heading hit@5 0.9429、MRR 0.8319；gold 未改。但 fixed Q18 top-1 是第四条（错误），因 page-only gold 被算 hit | fixed 指标仍含 pageRange 粗匹配；要声明 clause 级正确需逐条升级 enterprise gold 或加严格匹配；fixed 仍低于 heading |
| R3 | 2025 双栏 clausePath 噪声 | **已闭环（Q04 信息错误已修）** | Phase 6c：`SectionTitleMatcher` 叶子标题 boost；Q04 heading/fixed 均 **rank1 = 4.2.2 诊断流程**，content 含“诊断流程/明确诊断/白大衣/隐蔽性”；40 QA heading hit@5 **0.9714**、MRR **0.8748**，fixed hit@5 **0.7429**、MRR **0.7143**；Q05 heading top-1 仍 `5.4.7` 内容正确；gold/QA hash 未变。详见 [phase6c-report.md](phase6c-report.md) | 全文双栏顺序仍未重建（验收不要求）；Q12 top-1 由 5.4.3.1→5.4.3（rank2，仍 hit@5）；fixed Q05 top-1 仍是 4.2.3（Phase 6 既有） |
| R4 | 企业 heading 无 clause_id | **已修（gold 未改）** | 中文 `第X条` 识别；企业 heading 48/49 有 clause_id（唯一 NULL 为封面标题），clause_path 带章；DB/SQL 见 phase6-report 第 2/6 节 | enterprise 11 条 QA 仍用 pageRange（dataset hash 未改）；Q18 本轮 top-1 为第三条，但 page-only 仍可能命中同页错误条款，后续 dataset revision 再逐条升级 |
| R5 | MCP 低层注册与生产端点 | **已闭环（Phase 5）** | Phase 2：`tool-callback-converter=false` 可用，必须一个 `List<SyncToolSpecification>` bean。Phase 5：生产 `/mcp` + Bearer 401 + mandate 强制校验 + `mcp_audit_log` + 真实 Streamable HTTP client 测试（`McpIntegrationTest`）；`tools/list` 恰好两个 | 残余：SDK 0.18.3 默认协商 2025-11-25（2025-06-18 也支持）；未知 tool 由 HTTP gate 返回 `-32601`；mandateId 仍是 bearer secret |
| R6 | Windows 评测脚本 | **已闭环** | Phase 3：`scripts/eval.ps1` 跑 40 条；Phase 6 增加 pageEnd 相交匹配与 dataset LF hash 稳定化 | 保持 |
| R7 | 两套 chunk 的 30s / 10k 口径 | **已闭环（口径+实测）** | Phase 2/3：入库 30s 是每套策略、两套合计 <60s；10k 是每套策略、合计 20k | 10 并发 p95 仍见 R1 |
| R8 | chunkCount | **已闭环** | `DocumentUploadIntegrationTest` 断言 `chunkCount == sum(chunkCounts)`，并与 DB 计数一致 | 保持 |
| R9 | chunkStrategies 校验 | **已闭环** | `FileNamesTest`/上传测试：默认 heading、逗号、大小写、去重、最多 2 个、非法值 400 | 保持 |

## 统计

- 已闭环：R3、R5、R6、R7、R8、R9（6 项）
- 已修（metadata；仍有 page-only/语义残余）：R4（1 项）
- 显著改善、未完全闭环：R2（1 项）
- 本机达标（6/6，残余长尾）：R1（1 项）

## 原则

- “已测量”不等于“已修复”；“改了口径”也不等于“性能达标”。
- R2/R3/R4 不得通过改 gold、放宽匹配或删除 QA 来“解决”；Phase 6 未改 `eval/qa.jsonl`，dataset hash 仍为 `1487e...`。
- R1 的 Phase 6b 达标指实现方 3 次 + 验收方 3 次独立 60s 的 p95<800ms（445.29/500.12/468.59 + 498.53/368.39/408.52ms），errors 均 <1%；不是对所有机器/所有负载的硬保证。正式基准 cacheHits=0，缓存未被用来伪造唯一 query 数字；重复 query 缓存实验（99/100 命中）单独记录，不能当作并发基准。
- R1 仍有残余：p99 554.84/692.77/588.81ms，run3 单次 max=4405ms；不得把 p95 达标说成“所有长尾都已消除”。
- R2 的 0.7429 不是 clause 级语义正确率：enterprise 仍是 pageRange gold，fixed Q18 top-1 为错误条款却被计 hit；报告已标注。
- R3 的 Q04 闭环指 heading/fixed top-1 为 `4.2.2 诊断流程` 且内容正确，不等于全文双栏顺序已还原；`eval/qa.jsonl` hash 仍为 `1487e...`。
- Phase 6c 的 boost 不按 Q04 的 question id / docKey / clauseId 特判：它只检查规范化 query 是否完整包含候选 `clause_path` 叶子标题（3–20 字），4 字标题加 0.002、上限 0.003；父标题和长条款不参与。
