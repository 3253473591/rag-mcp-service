# Phase 7 验收：可拒答的检索守卫

- 日期：2026-11-09
- 分支：`feat/abstain-guard`（基线 `25b77e8`，未 push、未合并 main）
- 环境：Windows / Java 21.0.9 / Maven 3.9.9 / Spring Boot 3.5.16 / PostgreSQL 16.15 /
  pgvector / Ollama `bge-m3`（1024d）
- 数据库：`ragdb` 已有 3 篇样例文档（`hypertension-guideline` 2024 SUPERSEDED、2025 ACTIVE，
  `enterprise-internal-control` 2026 ACTIVE），共 **248 chunks**（fixed 102 + heading 146）。
  与需求描述里的“382 chunks”不同；本轮没有重新入库，直接使用现有数据标定与评测。

## 1. 实现摘要

- `AbstainGuard`：服务端确定性判定，输入向量路候选的余弦分，输出
  `NONE / NO_MATCH / TOO_NOISY`。不读模型输出，不读 prompt，不新增请求参数。
- `AbstainProperties`：`app.search.abstain.*` 配置，默认值来自下面的标定，不写死在 Java 常量里。
- `SearchResponse` 追加 `abstained` / `abstainReason` / `message` / `diagnostics`；非拒答路径的
  `results` 语义、字段、排序不变。
- `AnswerFallback`：系统常量文案，输入上游文本（当前是检索消息，将来可接生成层）+ 原因，
  空/空白/null 时返回对应原因的非空文案；非空上游原样透传。MCP `search_knowledge` 组装路径已调用。
- MCP 的 `search_knowledge` 在拒答时同样返回 `results=[]` + 四个新增字段；`allowedDocIds=[]`
  的早返回也带非空 `message` 和 `diagnostics`。
- 未改 schema，未加 Flyway 迁移；未改 `eval/qa.jsonl`、gold 或匹配规则。

## 2. 阈值标定

标定脚本：`scripts/calibrate-abstain.ps1`。应用以 `ABSTAIN_ENABLED=false` 启动（只关闭分数拒答，
空结果仍走文案兜底），逐题读取真实 `diagnostics`，再按下面的纯函数离线扫描阈值。
40 题 × fixed/heading = 80 次检索。

### 2.1 top-1 余弦分布

| chunkStrategy | answerable min / p50 / max | unanswerable min / p50 / max |
|---|---|---|
| fixed | 0.5564 / 0.7239 / 0.7943 | 0.4131 / 0.5120 / 0.6501 |
| heading | 0.5673 / 0.7155 / 0.7802 | 0.4499 / 0.4957 / 0.6746 |

可回答题与不可回答题在 0.55 附近有较清晰的分界；但 N05（0.6746）落在可回答题
（Q25/Q26/Q33/Q34/Q35 的 0.5673~0.6467）之间，单纯抬高绝对阈值一定会伤召回。

### 2.2 阈值扫描（`highConfidenceTopScore=0.63`，`minDenseCandidates=8`，`minScoreGap=0.02`）

可回答题被拒答按 miss（rank 0）计入 hit@5/MRR；不可回答题被拒答即正确。

| chunkStrategy | minTopScore | answerable hit@5 | answerable MRR | answerable abstained | unanswerable abstained | unanswerable FP | noAnswerAccuracy |
|---|---:|---:|---:|---:|---:|---:|---:|
| fixed | 0.35 | 0.7429 | 0.7143 | 0 | 2 | 3 | 0.4 |
| fixed | 0.40 | 0.7429 | 0.7143 | 0 | 1 | 4 | 0.2 |
| fixed | 0.45 | 0.7429 | 0.7143 | 0 | 2 | 3 | 0.4 |
| fixed | 0.50 | 0.7429 | 0.7143 | 0 | 3 | 2 | 0.6 |
| **fixed** | **0.55** | **0.7429** | **0.7143** | **0** | **4** | **1** | **0.8** |
| fixed | 0.60 | 0.7429 | 0.7143 | 2 | 4 | 1 | 0.8 |
| fixed | 0.65 | 0.6857 | 0.6571 | 5 | 4 | 1 | 0.8 |
| heading | 0.35 | 0.9714 | 0.8748 | 0 | 4 | 1 | 0.8 |
| heading | 0.40 | 0.9714 | 0.8748 | 0 | 3 | 2 | 0.6 |
| heading | 0.45 | 0.9714 | 0.8748 | 0 | 2 | 3 | 0.4 |
| heading | 0.50 | 0.9714 | 0.8748 | 0 | 4 | 1 | 0.8 |
| **heading** | **0.55** | **0.9714** | **0.8748** | **0** | **4** | **1** | **0.8** |
| heading | 0.60 | 0.9143 | 0.8176 | 2 | 3 | 2 | 0.6 |
| heading | 0.65 | 0.8286 | 0.7319 | 5 | 4 | 1 | 0.8 |

> 扫描在 0.35/0.40/0.45 的非单调来自 `denseCount` 依赖 `minTopScore`：阈值越低，
> 满足“密集”的候选越多，`TOO_NOISY` 可能提前触发。最终取值只看“不误伤 answerable”的区间。

### 2.3 score gap 扫描（`minTopScore=0.55`，`highConfidenceTopScore=0.63`，`minDenseCandidates=8`）

| chunkStrategy | minScoreGap | answerable hit@5 | answerable abstained | unanswerable abstained | noAnswerAccuracy |
|---|---:|---:|---:|---:|---:|
| fixed | 0.005 | 0.7429 | 0 | 4 | 0.8 |
| fixed | 0.010 | 0.7429 | 0 | 4 | 0.8 |
| fixed | 0.020 | 0.7429 | 0 | 4 | 0.8 |
| fixed | 0.030 | 0.7429 | 0 | 4 | 0.8 |
| fixed | 0.050 | 0.7429 | 0 | 4 | 0.8 |
| fixed | 0.100 | 0.7429 | 0 | 4 | 0.8 |
| heading | 0.005 | 0.9714 | 0 | 3 | 0.6 |
| heading | 0.010 | 0.9714 | 0 | 3 | 0.6 |
| **heading** | **0.020** | **0.9714** | **0** | **4** | **0.8** |
| heading | 0.030 | 0.9714 | 0 | 4 | 0.8 |
| heading | 0.050 | 0.9714 | 0 | 4 | 0.8 |
| heading | 0.100 | 0.9714 | 0 | 4 | 0.8 |

### 2.4 最终取值与依据

| 配置 | 取值 | 依据 |
|---|---:|---|
| `min-top-score` | **0.55** | 可回答题最低 top-1 是 0.5564（fixed Q34），0.55 不误伤任何 answerable；同时 N01/N02/N04 低于此值 |
| `high-confidence-top-score` | **0.63** | 把 0.55~0.63 定义为“灰色区”；N03（0.6094）在此区间，N05（0.6746）被当作高置信，避免误伤 Q26 等 |
| `min-dense-candidates` | **8** | 灰色区需要足够多高分候选才算噪声；N03 heading dense=16、fixed dense=19，均命中 |
| `min-score-gap` | **0.02** | heading 下从 0.01 提到 0.02，多拒 N03 且不误伤 answerable；fixed 对 gap 不敏感 |

判定函数：

```text
NO_MATCH  : 候选为空，或 top1 < 0.55
TOO_NOISY : top1 >= 0.55 且 top1 < 0.63
            且 denseCount(>=0.55) >= 8 且 (top1 - top2) < 0.02
NONE      : 其余
```

## 3. before / after 指标

同一分支、同一数据库、同一 QA 集、同一 `topK=5`；before 用 `ABSTAIN_ENABLED=false`（等价于旧行为），
after 用默认开启。命令均为 `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\eval.ps1`。

| chunkStrategy | 时段 | hit@1 | hit@3 | hit@5 | MRR | falsePositiveRate | noAnswerAccuracy | answerableAbstained | NO_MATCH / TOO_NOISY |
|---|---|---:|---:|---:|---:|---:|---:|---:|---|
| fixed | before | 0.6857 | 0.7429 | 0.7429 | 0.7143 | 1.0 | 0.0 | 0 | 0 / 0 |
| fixed | after | 0.6857 | 0.7429 | 0.7429 | 0.7143 | **0.2** | **0.8** | **0** | 3 / 1 |
| heading | before | 0.8286 | 0.9143 | 0.9714 | 0.8748 | 1.0 | 0.0 | 0 | 0 / 0 |
| heading | after | 0.8286 | 0.9143 | 0.9714 | 0.8748 | **0.2** | **0.8** | **0** | 3 / 1 |

结论：

- answerable 的 hit@5/MRR **完全不变**：fixed 0.7429/0.7143，heading 0.9714/0.8748；
  `answerableAbstained=0`。
- 需求里说的“现状 0.8857（heading）”是 Phase 3 的数字；Phase 6c 的 heading boost 已把基线提高到
  0.9714。本分支 after 仍是 0.9714，高于 0.8857，MRR 下降 0.0000。
- 不可回答题 5 条中 4 条被拒答（3 条 `NO_MATCH` + 1 条 `TOO_NOISY`），
  `falsePositiveRate` 1.0 → 0.2，`noAnswerAccuracy` 0 → 0.8。
- 剩余误报是 N05（2026 新增药物）。原因与取舍见 4.4。

完整 before/after 运行时的 JSON 在 `.tmp/eval-before.json`、`.tmp/eval-after.json`（未提交）；
`eval/report.md` / `eval/report.json` 为 after 运行结果。

## 4. 实测样例（真实响应片段）

### 4.1 正常命中：不拒答

查询：`诊室血压的诊断标准是多少？`（heading，hybrid，topK=5）

```json
{
  "query": "诊室血压的诊断标准是多少？",
  "abstained": false,
  "abstainReason": null,
  "message": null,
  "results": [
    {
      "docKey": "hypertension-guideline",
      "docVersion": "2025",
      "clauseId": "4.2.1",
      "citation": "《hypertension-2025》v2025（2025-01-01 生效） > 4  高血压诊断与评估 > 4.2  高血压诊断标准 > 4.2.1  诊断标准 > 第4页"
    }
  ],
  "diagnostics": {
    "topScore": 0.7801635497858199,
    "secondScore": 0.7599190712457524,
    "scoreGap": 0.0202444785400675,
    "candidateCount": 30,
    "denseCount": 30,
    "minTopScore": 0.55,
    "highConfidenceTopScore": 0.63,
    "minDenseCandidates": 8,
    "minScoreGap": 0.02,
    "enabled": true,
    "reason": "NONE"
  }
}
```

`results` 实际 5 条，这里只保留首条关键字段；`abstained=false`，答案正常返回。

### 4.2 NO_MATCH：未检索到

查询：`公司年假有多少天？`（heading，hybrid，topK=5）

```json
{
  "query": "公司年假有多少天？",
  "results": [],
  "abstained": true,
  "abstainReason": "NO_MATCH",
  "message": "未检索到足以回答该问题的资料。请补充更具体的信息，或联系管理员确认知识库是否已收录相关内容。",
  "diagnostics": {
    "topScore": 0.4609866554427876,
    "secondScore": 0.4410002231598402,
    "scoreGap": 0.019986432282947386,
    "candidateCount": 30,
    "denseCount": 0,
    "minTopScore": 0.55,
    "highConfidenceTopScore": 0.63,
    "minDenseCandidates": 8,
    "minScoreGap": 0.02,
    "enabled": true,
    "reason": "NO_MATCH"
  }
}
```

### 4.3 TOO_NOISY：噪声过多

查询：`患者张三最近的血压记录是多少？`（heading，hybrid，topK=5）

```json
{
  "query": "患者张三最近的血压记录是多少？",
  "results": [],
  "abstained": true,
  "abstainReason": "TOO_NOISY",
  "message": "检索到的内容噪声过多，无法判断。请缩小问题范围或提供更明确的条款、文档名称后重试。",
  "diagnostics": {
    "topScore": 0.6094036046245114,
    "secondScore": 0.593435516712153,
    "scoreGap": 0.015968087912358375,
    "candidateCount": 30,
    "denseCount": 16,
    "minTopScore": 0.55,
    "highConfidenceTopScore": 0.63,
    "minDenseCandidates": 8,
    "minScoreGap": 0.02,
    "enabled": true,
    "reason": "TOO_NOISY"
  }
}
```

这是“top-1 超过 0.55，但 0.55~0.63 灰色区内有 16 个候选且 top1-top2 只差 0.016”的典型噪声场景。
fixed 下同一问题 topScore=0.5970、dense=19，也判 `TOO_NOISY`，与上表一致。

### 4.4 已记录的剩余误报：N05

查询：`2026 年高血压指南新增了哪些降压药物？`

```json
{
  "query": "2026 年高血压指南新增了哪些降压药物？",
  "results": [ "…5 条…" ],
  "abstained": false,
  "abstainReason": null,
  "message": null,
  "diagnostics": {
    "topScore": 0.6745887398719788,
    "secondScore": 0.6327985902308588,
    "scoreGap": 0.041790149641119934,
    "candidateCount": 30,
    "denseCount": 30,
    "minTopScore": 0.55,
    "highConfidenceTopScore": 0.63,
    "minDenseCandidates": 8,
    "minScoreGap": 0.02,
    "enabled": true,
    "reason": "NONE"
  }
}
```

N05 的 top1 0.6746 高于 `highConfidenceTopScore=0.63`，所以不被判噪声。要把 N05 也拒掉，
`minTopScore` 需提到约 0.675，这会同时拒掉 answerable 的 Q25（0.6365）、Q26（0.6467）、
Q33（0.6344）、Q34（0.5836）、Q35（0.5673），heading hit@5 会从 0.9714 跌到约 0.8286，
低于 0.8857。因此按“优先保可回答题召回”的约定保留为 1/5 误报，并在 `eval/report.md` 中可见。

## 5. 测试与命令结果

### 5.1 全量 `mvn -o -q test`（真实结果：失败，原因不是代码）

```text
[ERROR] Tests run: 250, Failures: 0, Errors: 63, Skipped: 1
[ERROR] Failed to execute goal ... maven-surefire-plugin ...
EXIT=1
```

63 个 error 全部是 Testcontainers 集成测试，根因：

```text
DOCKER_HOST npipe:////./pipe/docker_engine is not listening
java.nio.file.AccessDeniedException: \\.\pipe\docker_engine
Could not find a valid Docker environment.
```

受影响类（均为 `AbstractIntegrationTest` / `McpTestDatabase` 的静态初始化）：
`DocumentUploadIntegrationTest`、`EvalIntegrationTest`、`GovernanceIntegrationTest`、
`GovernanceAdminUnconfiguredIntegrationTest`、`McpIntegrationTest`、
`McpUnconfiguredTokenIntegrationTest`、`SchemaIntegrationTest`、`SearchIntegrationTest`、
`VersionIntegrationTest`。本轮没有执行 `docker compose down -v`，也没有尝试绕过 Docker 权限。
这是环境限制，不能算“测试通过”；按需求如实记录，而不是跳过不解释。

### 5.2 非集成测试全绿

```text
mvn -o test -Dtest='!**/*IntegrationTest'
Tests run: 187, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS
```

唯一 skipped 是 `OllamaEmbeddingSmokeTest`（显式 smoke/环境测试，需真实 Ollama；本次未启用）。
新增/修改的测试包括：

- `AbstainGuardTest`：9 个用例，覆盖空候选、绝对下限、灰色区、dense 不足、gap 过大、
  高置信、关闭开关、keyword 无余弦、排序。
- `AnswerFallbackTest`：4 个用例，覆盖 null/空串/全空白 → 对应原因文案，非空原样透传。
- `SearchServiceTest`：13 个用例，覆盖正常命中、NO_MATCH、TOO_NOISY、关闭开关仍透传、
  keyword 空结果仍有文案，以及“任何响应都不允许 results 空 + message 空”的断言。
- `McpToolServiceTest`：拒答响应体含四字段；上游空结果仍被兜底为 NO_MATCH 文案。
- `EvalServiceTest`：不可回答题返回空结果（拒答）时，`falsePositiveRate=0`、`noAnswerAccuracy=1`，
  gold/匹配规则未改。

### 5.3 手工验证

- `诊室血压的诊断标准是多少？`：`abstained=false`，5 条结果，首条 `4.2.1`。
- `公司年假有多少天？`：`abstained=true`、`NO_MATCH`、`results=[]`、非空文案、
  `diagnostics.topScore=0.4609866554427876`。
- `患者张三最近的血压记录是多少？`：`abstained=true`、`TOO_NOISY`、
  `diagnostics.topScore=0.6094036046245114`、`denseCount=16`。

## 6. 实际遇到的限制

1. **Docker 权限**：`docker` 命令被拒绝，Testcontainers 连接 `\\.\pipe\docker_engine` 得到
   `AccessDeniedException`，全量测试的 63 个集成用例无法运行。未尝试 `docker compose down -v`。
2. **数据库 chunk 数与需求描述不一致**：需求说“很可能已有 382 个 chunk”，实际 `ragdb` 是
   248 个（fixed 102 + heading 146，3 篇文档）。本轮直接使用现有数据，没有重新上传；
   标定和评测数字都来自这 248 个 chunk。若换库/重新入库，阈值应重新跑标定脚本。
3. **GBK/UTF-8 显示**：Windows PowerShell 5.1 直接 `Get-Content` 中文会乱码；评测/标定脚本
   用 `HttpClient` + `UTF8.GetString` 读取响应，文档与报告按 UTF-8 写入。
4. **docs/ 目录的 shell 写入被 sandbox ACL 拒绝**：本轮用 DSH 的 `write` 工具落盘文档；
   这不影响代码、评测脚本或应用行为。
5. **首查延迟**：应用冷启动后第一次查询约 4.5s（Ollama 首次加载 bge-m3），
   预热后 heading p50/p95 约 24/26ms。延迟数字受本轮机器负载影响，不作为验收门槛。
6. **N05 仍是误报**：这是“优先保召回”的已知取舍，不是漏实现；见 4.4。
7. **keyword-only 无余弦**：`strategy=keyword` 没有向量余弦，守卫不做分数拒答；若结果为空，
   仍会走 `NO_MATCH` 文案兜底。MCP 默认 hybrid，不受影响。
8. **集成测试关闭分数守卫**：测试用 `FakeEmbeddingService` 的随机单位向量没有语义余弦，
   因此在 `AbstractIntegrationTest` / `McpIntegrationTest` 中设置
   `app.search.abstain.enabled=false`；守卫逻辑由单元测试覆盖，真实阈值由本报告的评测覆盖。

## 7. 复现命令

```powershell
# 1) 标定：先关闭分数拒答，采集真实余弦
$env:ABSTAIN_ENABLED = 'false'
mvn -o spring-boot:run
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\calibrate-abstain.ps1
# 输出 .tmp/phase7-calibration.{md,json}

# 2) before：仍在 ABSTAIN_ENABLED=false 下
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\eval.ps1
# 保存 eval/report.md 为 before

# 3) after：重启应用，使用默认（开启）
Remove-Item Env:ABSTAIN_ENABLED
mvn -o spring-boot:run
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\eval.ps1

# 4) 单元测试
mvn -o test -Dtest='!**/*IntegrationTest'
```
