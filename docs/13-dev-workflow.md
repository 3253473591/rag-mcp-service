# 开发流程：本地 coding agent，不接 GitHub/Feishu 自动化

- 版本：v1
- 日期：2026-11-08
- 关联主设计：[00-design.md](00-design.md)
- 关联交接：[09-handoff.md](09-handoff.md)
- 关联验收：[07-acceptance.md](07-acceptance.md)

> 结论：**本地 agent 只做实现加速，不做 GitHub/Feishu 自动接单、不自动开 PR、不自动合并。**  
> 这与 ADR-009 一致；ADR-008 只借鉴 `dsh-superagent` 的“受控工单/授权/派工”思路，不安装它。

---

## 1. 为什么不用 GitHub/Feishu 自动化

- 5 天项目，配置 webhook/机器人/独立账号的时间会反噬开发时间。
- superagent 的 worker 以高权限运行；本机有密钥/凭据，隔离成本高。
- 面试作品需要你亲自 review/验收；自动合并会掩盖问题。
- 本项目已经有 C+ 治理层，不需要再引入一套外部工单系统。

---

## 2. 工作方式

### 2.1 任务粒度

每个任务必须：

- 1~2 小时内可完成；
- 有明确输入/输出；
- 有验收标准（引用 07 的 AC 编号）；
- 只改一个模块/一组文件；
- 不跨越 P0 边界（P1 冲突提示只在时间有余时做）。

示例：

```text
任务：实现 kb_document/kb_chunk 的 Flyway V1
输入：03-data-model.md 的 DDL
输出：V1__init.sql + 集成测试
验收：T-INT-DB-01；mvn test 绿
禁止：改 API/检索逻辑
```

### 2.2 Review gate

每个任务完成后：

1. agent 自测 `mvn test` / 目标接口；
2. 人 review diff（不看“总结”，看代码）；
3. 跑对应验收项；
4. 通过后才进入下一个任务。

不通过就打回，不要“先合并再修”。

### 2.3 分支/提交

- 本地 git 仓库可选；建议 `main` + 小步提交。
- 提交信息写“做了什么 + 验收项”，不要写空话。
- 不强制接 GitHub；如果要推公开/私有仓库，按之前的双仓库流程，人工确认后推。
- 不自动合并、不自动 force push。

### 2.4 密钥

- agent 只拿测试环境密钥；不拿生产 token。
- `.env` 不提交；示例放 `.env.example`。
- 不在 prompt/日志/截图里放真实 token。
- 如果 agent 需要读密钥，用本地环境变量，不写进仓库。

---

## 3. 每日节奏

| 时间 | 动作 |
|---|---|
| 开始 | 读当天任务 + 验收标准；确认依赖 |
| 上午 | 实现 + 单测 |
| 中午 | 跑 `mvn test`；人 review |
| 下午 | 集成/接口/评测；更新文档 |
| 结束 | 记录完成/阻塞；更新 README/验收勾选 |

行测优先：每天留固定时间复习；项目任务按时间盒砍，不熬夜堆功能。

---

## 4. 停止条件

- 依赖版本不兼容（Spring AI/MCP）→ 降级 stdio 或锁版本，记录。
- 任务超过 2 小时无进展 → 停下来报告，不要硬写。
- 发现文档冲突 → 以 02/03/04 为准，记录到 README，不静默选一个。
- 时间不够 → 砍 P1/语义切分/rerank/HTTP 评测，不砍 P0 版本治理、治理层、评测。

---

## 5. 与 superagent 的关系

只借鉴概念，不安装：

| superagent 概念 | 本项目 |
|---|---|
| Trigger / Thread | 无（不做自动接单） |
| Ticket / 请求 | `WorkItem` |
| Mandate | `Mandate` |
| Policy | `Policy` 校验规则 |
| Assignment / Executor | 无（不做 agent 执行器） |
| Audit | `mcp_audit_log`（本项目新增） |

面试时讲：“我借鉴了受控工单/授权的思路，但没有引入它的 GitHub/Feishu 运行时；我用 Java 实现了最小治理闭环。”
