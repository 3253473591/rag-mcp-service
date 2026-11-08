# 风险台账（跨 Phase 追踪）

- 更新：2026-11-09
- 当前 `main`：Phase 4 签收后（`cffd960`）
- 用途：把“已解决 / 部分改善 / 仍待修”说清楚，避免把测量、定位或文档口径当成已修复。

## 状态总表

| # | 风险 | 状态 | 证据 / 位置 | 下一步 |
|---|---|---|---|---|
| R1 | 10 并发 p95 不稳定 | **部分改善，未闭环** | Phase 3：A/B/C/D = 808.59 / 552.07 / 1129.14 / 826.31ms，errors=0；嵌入池 2→10、HNSW 路径修复后 p50≈119~157ms | Phase 6：查询 embedding 缓存/限流/隔离，或明确放宽目标；不能宣称稳定 <800ms |
| R2 | fixed hit@5=0.3429 偏低 | **已测量/定位，未修** | Phase 3：fixed 无 clause 元数据、600-token 窗口 page_no 是起始页、企业 fixed 全从第 1 页起；heading hit@5=0.8857 | Phase 6：fixed chunk 增加 clause/page span，或改为 token-overlap/页范围匹配；不改 gold |
| R3 | 2025 双栏 clausePath 噪声 | **未修** | Phase 1/2/3 报告；citation 章节段出现 “90 mmHg” 等碎片，页码/文档/版本仍正确 | Phase 6：双栏感知的 heading/段落抽取，或对 clausePath 做清洗 |
| R4 | 企业 heading 无 clause_id | **未修** | Phase 3：11 条企业题只能按 pageRange 匹配，粒度粗 | Phase 6：中文“第X条”条款识别，写入 clause_id/clause_path |
| R5 | MCP 低层注册 | **Spike 已解决，生产待 Phase 5** | Phase 2：`tool-callback-converter=false` 可用；必须暴露一个 `List<SyncToolSpecification>` bean，两个独立 bean 不会被收集 | Phase 5：`/mcp` + Bearer + mandate + audit |
| R6 | Windows 评测脚本 | **已闭环** | Phase 3：`scripts/eval.ps1` 跑 40 条，fixed/heading 指标、JSON/MD 报告 | 保持 |
| R7 | 两套 chunk 的 30s / 10k 口径 | **已闭环（口径+实测）** | Phase 2/3：入库 30s 是每套策略、两套合计 <60s；10k 是每套策略、合计 20k；3 篇入库约 14~19s，10k 串行 p95 29~93ms | 10 并发 p95 仍见 R1 |
| R8 | chunkCount | **已闭环** | `DocumentUploadIntegrationTest` 断言 `chunkCount == sum(chunkCounts)`，并与 DB 计数一致 | 保持 |
| R9 | chunkStrategies 校验 | **已闭环** | `FileNamesTest`/上传测试：默认 heading、逗号、大小写、去重、最多 2 个、非法值 400 | 保持 |

## 统计

- 已闭环：R6、R7、R8、R9（4 项）
- Spike 已解决、生产待做：R5（Phase 5）
- 部分改善、未闭环：R1（Phase 6）
- 已测量/定位、未修复：R2、R3、R4（Phase 6）

## 原则

- “已测量”不等于“已修复”；“改了口径”也不等于“性能达标”。
- R2/R3/R4 不得通过改 gold、放宽匹配或删除 QA 来“解决”。
- R1 若要硬保证 p95<800ms，需要缓存/限流/隔离 embedding，而不是改报告数字。
