# Phase 11 验收：LLM 编译 wiki 页面 vs chunk / 确定性页面

- 生成时间：2026-11-10 23:04:05
- 分支/提交：`d6b74782f6f7bb05d8a59e91d3ed3d055f00dbc7` (dirty=True)
- 数据集：`eval/qa.jsonl` (sha256=1487e261b728debd22a876d31016f376efeb40932d8888d45d2d15b91a15af4c)，40 条（35 可回答 + 5 不可回答）
- 运行参数：topK=5，默认 asOf=2026-11-09，includeSuperseded=False，chunkStrategy=heading
- 三条路线：chunk = `POST /api/v1/search`（hybrid）；page-aggregate = `POST /api/v1/pages/search` + `kind=aggregate`；page-wiki = 同端点 + `kind=wiki`（均为向量路）
- 数据集、gold、asOf、topK 完全相同；本脚本不修改 `eval/qa.jsonl`、gold 或任何既有匹配规则。

## 1. 编译统计（来自 `docs/acceptance/phase11-compile.json`）

- 模型：`deepseek-flash`；文档数 3；小节数 111；成功 111；失败 0；跳过 0
- token：prompt 63714 / completion 74163 / total 137877；耗时 111609 ms

| docKey | version | 小节 | 成功 | 失败 | 跳过 | 写入页面 | LLM 调用 | prompt tokens | completion tokens | 耗时(ms) |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| hypertension-guideline | 2024 | 6 | 6 | 0 | 0 | 6 | 6 | 2874 | 2262 | 5305 |
| hypertension-guideline | 2025 | 56 | 56 | 0 | 0 | 56 | 56 | 40701 | 54321 | 75000 |
| enterprise-internal-control | 2026 | 49 | 49 | 0 | 0 | 49 | 49 | 20139 | 17580 | 31294 |

## 2. 匹配口径（三条路线 × 两种口径，同一张表）

| 路线 | 自身的 clause 定位 | fallback（= 产品 GoldMatcher） | nofallback |
|---|---|---|---|
| chunk | `result.clauseId` | 结果无 `clauseId` 时允许页码相交 | 结果无 `clauseId` 一律不命中 |
| page（aggregate / wiki 相同） | `result.leafClauseId`（页面自身叶子节，**不是** `clauseIds` 包含关系） | 结果无 `leafClauseId` 时允许页码相交 | 结果无 `leafClauseId` 一律不命中 |

**两条路线使用同一条规则，只替换"结果自己的 clause 定位"字段。** 页面侧的 `clauseIds` 包含关系
（Phase 10 的 loose）被刻意排除：页面天然覆盖更多 clause，包含关系比 chunk 侧宽松，会把页面分数抬高。
本表只比较同一口径下的数字。

## 3. 四指标（同一 asOf、topK=5、同一批可回答题）

| 路线 | 口径 | 可回答题数 | hit@1 | hit@3 | hit@5 | MRR | p50(ms) | p95(ms) | 错误 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| chunk | fallback | 35 | 0.8286 | 0.9429 | 0.9714 | 0.8819 | 25 | 34 | 0 |
| chunk | nofallback | 35 | 0.8286 | 0.9429 | 0.9714 | 0.8819 | 25 | 34 | 0 |
| aggregate | fallback | 35 | 0.8286 | 0.9429 | 0.9714 | 0.8867 | 18 | 25 | 0 |
| aggregate | nofallback | 35 | 0.7429 | 0.9143 | 0.9714 | 0.8367 | 18 | 25 | 0 |
| wiki | fallback | 35 | 0.8286 | 0.9429 | 0.9429 | 0.8857 | 16 | 22 | 0 |
| wiki | nofallback | 35 | 0.8286 | 0.9429 | 0.9429 | 0.8857 | 16 | 22 | 0 |

差值（同一口径，相对 chunk）：aggregate fallback hit@1 0.0000 / hit@3 0.0000 / hit@5 0.0000 / MRR 0.0048；aggregate nofallback hit@1 -0.0857 / hit@3 -0.0286 / hit@5 0.0000 / MRR -0.0452。
差值（同一口径，相对 chunk）：wiki fallback hit@1 0.0000 / hit@3 0.0000 / hit@5 -0.0285 / MRR 0.0038；wiki nofallback hit@1 0.0000 / hit@3 0.0000 / hit@5 -0.0285 / MRR 0.0038。

## 4. 逐题差异（只列与 chunk fallback 不同的可回答题）

| 题 | chunk F | chunk NF | agg F | agg NF | wiki F | wiki NF | wiki 命中页（leaf/title） |
|---|---:|---:|---:|---:|---:|---:|---|
| Q04 | 1 | 1 | 3 | 3 | 2 | 2 | [4.3] 初诊评估 |
| Q11 | 3 | 3 | 2 | 2 | 2 | 2 | [1.3] 基层高血压管理保障基本药物要求 |
| Q15 | 2 | 2 | 1 | 1 | 1 | 1 | [8.2] 高血压长期随访管理：随访内容 |
| Q21 | 5 | 5 | 1 | 1 | 1 | 1 | [第二十一条] 第二十一条 风险评估中的风险承受度 |
| Q24 | 3 | 3 | 5 | 5 | 0 | 0 | [第五条] 企业建立与实施有效内部控制的五要素 |
| Q29 | 1 | 1 | 1 | 2 | 1 | 1 | [4.2.1] 4.2.1 高血压诊断标准 |
| Q31 | 1 | 1 | 1 | 2 | 1 | 1 | [4.2.1] 4.2.1 高血压诊断标准 |
| Q32 | 1 | 1 | 1 | 4 | 2 | 2 | [4.2.1] 4.2.1 高血压诊断标准 |
| Q33 | 1 | 1 | 2 | 2 | 1 | 1 | [5.2] 5.2 降压目标 |

汇总（与 chunk fallback 不同的题数）：aggregate fallback 6、aggregate nofallback 9、wiki fallback 6、wiki nofallback 6。

## 5. 不可回答题（不计入 hit@k）

- chunk 路：4/5 题被拒答；其中可回答题被误拒答 0 题。
- page 路（aggregate 或 wiki）：5/5 题返回了非空结果。页面端点没有拒答守卫，这是 A/B 探针的既有边界。

## 6. 自动结论（仅陈述数据事实）

- chunk / fallback：hit@1=0.8286、hit@3=0.9429、hit@5=0.9714、MRR=0.8819。
- chunk / nofallback：hit@1=0.8286、hit@3=0.9429、hit@5=0.9714、MRR=0.8819。
- aggregate / fallback：hit@1=0.8286、hit@3=0.9429、hit@5=0.9714、MRR=0.8867。
- aggregate / nofallback：hit@1=0.7429、hit@3=0.9143、hit@5=0.9714、MRR=0.8367。
- wiki / fallback：hit@1=0.8286、hit@3=0.9429、hit@5=0.9429、MRR=0.8857。
- wiki / nofallback：hit@1=0.8286、hit@3=0.9429、hit@5=0.9429、MRR=0.8857。

自动判定（比较同一口径的 hit@5 与 MRR）：
- aggregate / fallback：hit@5 相同；MRR 差 0.0048，不足 1 题量级，视为噪声
- aggregate / nofallback：hit@5 相同；MRR 低 -0.0452
- wiki / fallback：hit@5 更低、MRR 更高（互有高低，差 1 题量级）
- wiki / nofallback：hit@5 更低、MRR 更高（互有高低，差 1 题量级）

`MANUAL:START` 之后为人工分析。重跑本脚本时，只要该标记仍在旧报告里，旧的人工分析会被原样保留。

<!-- MANUAL:START -->
## 7. 结论、可读性对照与风险

### 7.1 结论（基于数据，不美化）

**LLM 编译的 wiki 页面没有比 chunk 更好；也没有比 Phase 10 的确定性聚合页面更好。**

- 对 chunk（同 fallback 口径）：hit@1、hit@3 完全相同，hit@5 **低 1 题**（34/35 → 33/35，−0.0286），MRR +0.0038（35 题下约等于 0.13 个名次，属于噪声）。
- 对确定性聚合页面（同 fallback 口径，**同一端点、同一向量管线、同一页面粒度，唯一变量就是“是否用 LLM 重写文本”**）：hit@1、hit@3 相同，hit@5 同样低 1 题，MRR −0.0010。这是最干净的对照：**LLM 这个变量本身没有带来检索收益**。
- 两种口径下都没有“优于 chunk”的结果，因此按验收要求如实写：**没有收益**。
- `nofallback` 下 wiki（0.8286）明显高于 aggregate（0.7429），但这不是 LLM 的功劳：Phase 10 的 4 个 root/hub 聚合页没有 `leafClauseId`，无回退时必 miss；wiki 的 3 个 root 页这次没有进 top5。该差值只说明“wiki 页大多带节定位”，不说明“内容更好”。

四条关键数字（完整表见第 3 节）：

| 路线 | 口径 | hit@1 | hit@3 | hit@5 | MRR |
|---|---|---:|---:|---:|---:|
| chunk | fallback | 0.8286 | 0.9429 | **0.9714** | 0.8819 |
| chunk | nofallback | 0.8286 | 0.9429 | **0.9714** | 0.8819 |
| aggregate | fallback | 0.8286 | 0.9429 | **0.9714** | 0.8867 |
| aggregate | nofallback | 0.7429 | 0.9143 | 0.9714 | 0.8367 |
| wiki | fallback | 0.8286 | 0.9429 | **0.9429** | 0.8857 |
| wiki | nofallback | 0.8286 | 0.9429 | **0.9429** | 0.8857 |

### 7.2 编译统计与成本

| 项 | 数值 |
|---|---:|
| 文档 | 3 |
| 叶子小节 | 111 |
| 成功 | 111（100%） |
| 失败 | 0 |
| 跳过（JSON 解析重试后仍失败） | 0 |
| LLM 调用 | 111（无解析重试、无传输重试） |
| prompt tokens | 63,714 |
| completion tokens | 74,163 |
| total tokens | **137,877** |
| 墙钟耗时 | **111,609 ms**（并发 4） |
| 平均 token/小节 | 1,242 |
| 平均墙钟/小节 | 1,006 ms |

分文档：2024 版 6 节 / 5,305 ms；2025 版 56 节 / 75,000 ms；2026 版 49 节 / 31,294 ms。成功率 **100% ≥ 95%**，失败列表为空（`failures: []`）；若复跑出现失败，`POST /api/v1/admin/pages/compile-wiki` 的 `failures[]` 会逐项给出 `docId / clausePath / stage / reason`（`stage` 为 `parse` = 跳过，`llm` / `embedding` / `document` = 失败）。

成本口径：DeepSeek 响应只返回 token 用量，不返回金额；本次实际消耗 **137,877 tokens**，金额取决于账号单价，本报告不编造单价。按每百万 token 单价换算时直接用该总量。墙钟成本约 1.9 分钟/3 篇；按 111 节线性外推，1,000 节约 17 分钟、124 万 tokens，这一层不能忽略。

### 7.3 逐题差异

wiki 与 chunk 名次不同的可回答题共 6 道（3 好 3 坏）：

| 题 | chunk | wiki | 变化 | 说明 |
|---|---:|---:|---|---|
| Q11 | 3 | 2 | +1 | 5.4.2 药物选择，wiki 页覆盖整节后名次前移 |
| Q15 | 2 | 1 | +1 | 8.2 随访内容，wiki 页第 1 |
| Q21 | 5 | 1 | +4 | 企业风险评估；chunk 第 5 是一条从中句开始的片段，wiki 页完整 |
| Q04 | 1 | 2 | −1 | 4.2.2 诊断流程，仍在前 3 |
| Q32 | 1 | 2 | −1 | 2024 版 4.2.2，仍在前 3 |
| **Q24** | **3** | **0** | **−3，跌出 top5** | 内部控制措施清单；唯一造成 hit@5 下降的题 |

净效应：hit@1 0（+2/−2），hit@3 0（+2/−2），hit@5 −1，MRR +0.0038。

**Q24 是本次唯一“丢命中”的题，值得单独看：**

- gold 没有 `clauseId`，只有 `pageRange=[5,5]`；匹配只能靠页码相交。
- chunk 第 3 名命中 `第二十八条`（第 5 页），正文从“的方 法，运用相应的控制措施……”开始（标题切分把句子截断了），但包含完整清单。
- aggregate 的 `第二十八条` 页在第 5 名（score 0.6596）。
- **wiki 的 `第二十八条` 页掉到第 8 名（score 0.6485）**；wiki top5 是第 2 页的第五条、第四条、第三条、第八条（都是第 2 页）和第 8 页的第四十五条，页 5 被挤出。
- 该页的 JSON 本身并没有丢内容：清单“不相容职务分离控制、授权审批控制、会计系统控制、财产保护控制、预算控制、运营分析控制和绩效考评控制等”逐字保留。**丢的是检索名次，不是信息**。重写后的 embedding 文本（`title + summary + key_points`）与原始 chunk 的向量相似度下降了约 0.011，刚好跨过 top5 边界。

#### 7.3.1 真实 JSON 样例（一个检索命中、一个未命中）

样例 A 是**命中**：Q01 / Q29 / Q31 的 gold 都是 2025 版 `4.2.1`，wiki 页排名第 1。以下为该页 `kb_page.body`（`kind='wiki'`、`llm_model='deepseek-flash'`、`page_key='hypertension-guideline@2025#4  高血压诊断与评估 > 4.2  高血压诊断标准 > 4.2.1  诊断标准'`、`page_no/page_end=4/4`）的原始内容：

```json
{
  "title" : "4.2.1 高血压诊断标准",
  "summary" : "本节给出诊室、家庭和动态血压的高血压诊断标准，并列出表1中诊室及诊室外血压对应的收缩压/舒张压阈值。",
  "key_points" : [ "诊室血压诊断标准：4 周内非同日 3 次规范测量的诊室血压均≥ 140/90 mmHg。", "家庭血压诊断标准：经准确度验证的电子血压计规范测量的家庭血压平均值≥ 135/85 mmHg。", "建议测量 5~7 d，早晚各测量 1 次，每次测量 2~3 遍，取所有测量值的平均值用于高血压的诊断。", "若无法进行 5~7 d 的测量，可取不少于 3 d、至少 12 个测量值的平均值用于高血压的诊断。", "动态血压诊断标准：24 小时动态血压平均值 ≥ 130/80 mmHg，或白天血压平均值 ≥ 135/85 mmHg，或夜间血压平均值≥ 120/70 mmHg。", "表1：诊室血压收缩压≥ 140 mmHg 和 ( 或 ) 舒张压≥ 90 mmHg *；动态血压白天收缩压≥ 135 mmHg 和 ( 或 ) 舒张压≥ 85 mmHg，夜间收缩压≥ 120 mmHg 和 ( 或 ) 舒张压≥ 70 mmHg，24 小时收缩压≥ 130 mmHg 和 ( 或 ) 舒张压≥ 80 mmHg *；家庭血压收缩压≥ 135 mmHg 和 ( 或 ) 舒张压≥ 85 mmHg；注：*：指平均血压。1 mmHg=0.133 kPa。" ],
  "cross_refs" : [ ],
  "sources" : [ "c8a5fcb2-dabe-4696-ae23-d0ba436c8065" ]
}
```

样例 B 是**未命中**：Q24 的 gold 只有 `pageRange=[5,5]`；wiki 的正确页（第 5 页，`第二十八条`）排第 8，未进 top5。以下为该页 `kb_page.body` 的原始内容：

```json
{
  "title" : "第二十八条 控制措施的类型与风险控制要求",
  "summary" : "企业应结合风险评估结果，采用手工控制与自动控制、预防性控制与发现性控制相结合的方法，运用相应控制措施，将风险控制在可承受度之内。",
  "key_points" : [ "企业应当结合风险评估结果，通过手工控制与自动控制、预防性控制与发现性控制相结合的方法，运用相应的控制措施，将风险控制在可承受度之内。", "控制措施一般包括：不相容职务分离控制、授权审批控制、会计系统控制、财产保护控制、预算控制、运营分析控制和绩效考评控制等。" ],
  "cross_refs" : [ ],
  "sources" : [ "3b4694d0-77cb-497d-80a9-cca3fadfbc29" ]
}
```

这两例说明：**页面内容正确、可读，并不等于它会被检索到。** 样例 B 的信息完整度甚至高于 chunk（chunk 从“的方 法”开始），但它的 embedding 文本排名更低。

### 7.4 是摘要丢了数字/条款定位，还是页面粒度本身？

三件事要分开：

1. **条款定位没有丢。** wiki 页的 `leaf_clause_id`、`clause_ids`、`page_no/page_end` 都直接继承自该小节的 heading chunk，不经过 LLM。111 页中 108 页有 `leaf_clause_id`，另外 3 页是 root/hub（2024「测试用摘编」、2025 `__root__`、2026 首页），与 Phase 10 聚合页的结构性 root 相同。Q24 的失败与 `leafClauseId` 无关（gold 本来就没有 clauseId）。
2. **数字会丢，但不是系统性丢失。** 对 111 个 wiki 页做朴素数字比对（把该小节所有 heading chunk 的正文与 `title+summary+key_points` 都抽出 `\d+(\.\d+)?`，按 token 集合比较）：**28/111 页至少漏掉一个数字 token，合计 302 个 token**。其中很多是页眉/页脚/参考文献数字（如 2025、327、40、9、DOI 10.3969），属于应当去掉的噪声；但也存在真实丢失：5.3「生活方式干预」页丢了 **BMI 24~<28 kg/m²、4.0 kg/m²** 这类表格数字。相反，Q01 的 140/90、135/85、130/80、120/70、5~7 d、3 次、12 个测量值、0.133 kPa **全部逐字保留**。结论是：**摘要会按“重要性”取舍，主问题相关的数字通常保留，表格/边栏数字可能消失**。本次 35 道可回答题没有一题因为数字丢失而失败（Q09 的 5.3 节仍排第 1），但这对“数值型问答”是真实风险。
3. **页面粒度本身是主因，但不全是“稀释”。** 同一小节变成一个页面后，embedding 文本从“原始正文”变成“标题+摘要+要点”，这会改变向量方向（Q04/Q32 的 1→2、Q24 的 5→8），方向不稳定。LLM 并没有让页面“更小”或“更聚焦”；它让页面更可读，但检索单元仍然是整节，且文本被重写。**所以不是“摘要丢了条款定位”，而是“页面粒度 + 重写后的 embedding 文本”共同造成小幅重排，收益不足以抵消损失。**

### 7.5 人工可读性对照（3 组，同一问题）

#### 对照 1：Q01「诊室血压的诊断标准是多少？」（chunk 第 1，wiki 第 1）

chunk 原文（命中第 1）：

> （1）诊室血压诊断标准：4 周内非同日 3 次规范测量的诊室血压均≥ 140/90 mmHg。……（3）动态血压诊断标准：24 小时动态血压平均值 ≥ 130/80 mmHg，或白天血压平均值 ≥ 135/85 mmHg，或夜间血压平均值≥ 120/70 mmHg。…… 839中国循环杂志 2025 on Journal，September，2025，Vol. 40 No.9（Serial No.327）

wiki 页面（命中第 1，节选）：

> **4.2.1 高血压诊断标准**
> 本节给出诊室、家庭和动态血压的高血压诊断标准，并列出表1中诊室及诊室外血压对应的收缩压/舒张压阈值。
> - 诊室血压诊断标准：4 周内非同日 3 次规范测量的诊室血压均≥ 140/90 mmHg。
> - 家庭血压诊断标准：……家庭血压平均值≥ 135/85 mmHg。
> - 动态血压诊断标准：24 小时动态血压平均值 ≥ 130/80 mmHg，或白天血压平均值 ≥ 135/85 mmHg，或夜间血压平均值≥ 120/70 mmHg。
> - 表1：诊室血压收缩压≥ 140 mmHg 和 ( 或 ) 舒张压≥ 90 mmHg……

**判断：wiki 明显更好读。** 去掉了 PDF 页眉/页脚噪声，把散落在长段落里的三类阈值组织成条目；所有关键数字保留。检索名次相同（都是 1）。

#### 对照 2：Q21「企业应当如何开展风险评估？」（chunk 第 5，wiki 第 1）

chunk 命中的第 5 名是一条**从中句开始**的片段：

> 的风险 承受度。 风险承受度是企业能够承担的风险限度，包括整体风险承受能力和业务层面的可接受风险水平。

wiki 页面（命中第 1）：

> **第二十一条 风险评估中的风险承受度**
> 企业开展风险评估应准确识别与实现控制目标相关的内部风险和外部风险，确定相应风险承受度；风险承受度是企业能够承担的风险限度，包括整体风险承受能力和业务层面的可接受风险水平。
> - 企业开展风险评估，应当准确识别与实现控制目标相关的内部风险和外部风险，确定相应风险承受度。
> - 风险承受度是企业能够承担的风险限度，包括整体风险承受能力和业务层面的可接受风险水平。

**判断：wiki 同时改善可读性和检索名次（5 → 1）。** 原因是 heading chunk 会把小节标题/首句切走，页面把该节重新组织后语义完整。这是 LLM 页面真实的价值场景。

#### 对照 3：Q24「内部控制措施一般包括哪些？」（chunk 第 3，wiki 第 8 = 未命中）

chunk 命中（第 3）：

> 的方 法，运用相应的控制措施，将风险控制在可承受度之内。 控制措施一般包括：不相容职务分离控制、授权审批控制、会计系统控制、财产保护控制、预算控制、运营分 析控制和绩效考评控制等。

wiki 页面（第 8，未进 top5）：

> **第二十八条 控制措施的类型与风险控制要求**
> 企业应结合风险评估结果，采用手工控制与自动控制、预防性控制与发现性控制相结合的方法，运用相应控制措施，将风险控制在可承受度之内。
> - 企业应当结合风险评估结果，通过手工控制与自动控制、预防性控制与发现性控制相结合的方法，运用相应的控制措施，将风险控制在可承受度之内。
> - 控制措施一般包括：不相容职务分离控制、授权审批控制、会计系统控制、财产保护控制、预算控制、运营分析控制和绩效考评控制等。

**判断：wiki 页面更完整、更好读（补全了被切走的句子，清单一行不差），但检索从第 3 掉到第 8。** 这是本轮最核心的 trade-off：**可读性提升是真实的，检索收益不成立**。原因不是内容缺失，而是重写后的 embedding 文本把该页的相似度从 0.6596 拉到 0.6485，top5 边界上被第 2 页的“五要素/原则/定义”类页面挤掉。

### 7.6 最脆弱的地方

1. **样本太小，1 题 = 2.86pp。** hit@5 的差距恰好是 1 题（Q24），MRR 的 +0.0038 也只是名次加权的结果。方向与 Phase 10 一致（没有收益），但不足以做统计推断。
2. **只跑了一次。** LLM 温度 0.2，但同一个提示词重跑仍可能改变 Q24 这类边界题的名次；没有做多次重复取均值。结论应理解为“本次运行没有收益”，不是“任何运行都不会有收益”。
3. **chunk 与 page 的管线不同。** chunk 是 hybrid+RRF+rerank，页面是纯向量；所以 chunk vs wiki 混入了“检索管线”变量。真正隔离 LLM 的是 **wiki vs aggregate**（同端点、同向量、同粒度）：结论仍然是 wiki 不更好。这个对照是本报告最重要的设计。
4. **prompt 没有防注入。** 文档正文被直接拼进 user prompt；恶意文档可以尝试覆盖 system prompt。当前只做了 `sources` 白名单过滤（只保留真实 chunkId）和 JSON 结构校验，没有对 summary/key_points 做内容审查。
5. **失败/重试路径的覆盖率仍有限。** 单测覆盖了 429、401、坏 JSON 重试和“两次坏 JSON 跳过”；真实编译 111/111 全部一次成功，没有触发限流/超时。批量大时的并发限流、部分失败、embedding 失败回滚（有单测，未在真实 111 节上演练）仍是风险。
6. **页面正文是 JSON。** 当前 `kb_page.body` 存的是严格 JSON（便于验收和保留 `cross_refs`），`/api/v1/pages/search` 的 `content` 直接返回这段 JSON。可读性收益在字段层面成立，但产品若要展示给最终用户，还需要渲染层；否则用户看到的是 JSON。
7. **root/hub 页与 nofallback 口径。** 3 个 wiki root 页没有 `leaf_clause_id`。本次它们没有进 top5；换语料后如果 root 页进入 top5，`nofallback` 指标会突然变差。这不是 LLM 的问题，是页面层结构问题，Phase 10 已记录，仍未修复。
8. **成本与幂等。** 每次 compile 对目标文档全量重编（先删后插），没有“只编变更小节”的增量模式；111 节 1.9 分钟可以接受，1 万节不可接受。token 用量也没有预算上限/熔断。
9. **`GET /api/v1/admin/pages/stats` 仍只统计 aggregate。** wiki 的页面数只能从 compile 响应或 SQL 看；这是刻意的向后兼容，但观测性不完整。

### 7.7 复现

```powershell
# 0) key 只在环境变量里；仓库内不得出现任何真实 key
$env:DEEPSEEK_API_KEY = (Get-Content C:\Users\Administrator\.dsh-secrets\deepseek.key -Raw).Trim()
$env:ADMIN_TOKEN = 'phase11-admin-token'

# 1) 启动（Flyway 自动应用 V4）
mvn -B -o spring-boot:run

# 2) 编译全部文档（并发 4）；响应保存为验收数据
curl.exe -s -X POST http://127.0.0.1:8080/api/v1/admin/pages/compile-wiki `
  -H "X-Admin-Token: phase11-admin-token" -H "Content-Type: application/json" `
  --data-binary '{\"concurrency\":4}' > docs/acceptance/phase11-compile.json

# 3) 三条路线 × 两种口径的 A/B（会读取上面的 compile 统计）
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\eval-wiki.ps1 -AsOf 2026-11-09

# 4) 全量测试
mvn -o test
```

数据文件：

- `docs/acceptance/phase11-compile.json`：编译响应（逐文档小节/成功/失败/跳过/token/耗时，`failures=[]`）。
- `docs/acceptance/phase11-llm-wiki.json`：逐题名次（6 个 route/mode 字段）、四指标、耗时、口径定义。
- `docs/acceptance/phase11-llm-wiki.md`：本报告。

`eval/qa.jsonl`、gold、`eval.ps1` / `eval-pages.ps1` 的既有匹配逻辑均未改动；`POST /api/v1/search` 的契约、排序、拒答阈值、rerank 权重均未改动。

### 7.8 实现的边界（便于复核）

- `WikiCompiler` 只为 **heading chunk** 生成 wiki 页；只有 fixed 策略的文档没有输入，也就没有 wiki 页。
- wiki 页不做 `part` 切分：一个小节 = 一行（`part=1`），111 节 = 111 页；Phase 10 的 aggregate 因超长切成 125 行。两者页面数不同，但 A/B 按“检索命中的小节”比较，不受行数影响。
- embedding 文本严格按验收要求：`title + "\n" + summary + "\n" + key_points 用 "\n" 拼接`。`body` 是完整 JSON，供人读/复核，不参与 embedding。
- `kind` 过滤在 SQL（`p.kind = ?`）中完成；`kind` 缺省为 `aggregate`，未知值返回 `ERR-VALIDATION`。
- 缺 key 时 `POST /compile-wiki` 返回 `503 ERR-LLM_NOT_CONFIGURED`，不读写 `kb_page`，不回退假数据；测试用假 key + 本地 `HttpServer` 桩，真实 key 只用于本报告这一次手动编译。
