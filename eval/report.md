# RAG Eval Report

- dataset: `eval/qa.jsonl` (sha256=1487e261b728debd22a876d31016f376efeb40932d8888d45d2d15b91a15af4c)
- git commit: `c1668c2890022e97c3593c3cb2061e5b53f040f5` (dirty=False)
- embedding: bge-m3 (1024d); topK=5; default asOf=2026-11-09; includeSuperseded=False
- chunk strategies: fixed, heading
- QA: 40 (35 answerable + 5 unanswerable)
- generated: 2026-11-09 02:49:25

## Answerable metrics

| chunkStrategy | answered | hit@1 | hit@3 | hit@5 | MRR | p50 | p95 | errors |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| fixed | 35 | 0.3143 | 0.3143 | 0.3429 | 0.3214 | 51 | 106 | 0 |
| heading | 35 | 0.5429 | 0.8286 | 0.8857 | 0.6905 | 58 | 141 | 0 |

Unanswerable (reported separately, not mixed into hit@k):

| chunkStrategy | falsePositiveRate | noAnswerAccuracy | errors |
|---|---:|---:|---:|
| fixed | 1 | 0 | 0 |
| heading | 1 | 0 | 0 |

Thresholds from docs/05-non-functional.md: hit@5 >= 0.85, MRR >= 0.65 (heading/default;
fixed is reported as measured). Errors are counted separately and excluded from the
hit@k/MRR denominator. Gold was not changed to tune these numbers.

p50/p95 are the app-reported server-side retrieval time (`tookMs`, includes query
embedding + SQL + RRF). Client wall-clock p50/p95 (including PowerShell/HTTP) is in
`report.json` as `clientP50Ms`/`clientP95Ms`.

## Per-question detail

| strategy | id | answerable | rank | results | server(ms) | client(ms) | error | asOf | top citation |
|---|---|---|---:|---:|---:|---:|---|---|---|
| fixed | Q01 | True | 0 | 5 | 144 | 158.63 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 第3页 |
| fixed | Q02 | True | 0 | 5 | 113 | 120.31 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 第3页 |
| fixed | Q03 | True | 0 | 5 | 45 | 50.84 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 第3页 |
| fixed | Q04 | True | 0 | 5 | 51 | 56.98 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 第2页 |
| fixed | Q05 | True | 0 | 5 | 47 | 53.02 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 第3页 |
| fixed | Q06 | True | 0 | 5 | 44 | 50.52 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 第3页 |
| fixed | Q07 | True | 0 | 5 | 43 | 48.35 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 第2页 |
| fixed | Q08 | True | 0 | 5 | 67 | 72.96 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 第1页 |
| fixed | Q09 | True | 0 | 5 | 38 | 44.56 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 第3页 |
| fixed | Q10 | True | 1 | 5 | 96 | 101.97 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 第6页 |
| fixed | Q11 | True | 4 | 5 | 77 | 82.93 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 第2页 |
| fixed | Q12 | True | 0 | 5 | 76 | 82.1 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 第6页 |
| fixed | Q13 | True | 1 | 5 | 106 | 112.34 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 第9页 |
| fixed | Q14 | True | 1 | 5 | 89 | 95.66 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 第13页 |
| fixed | Q15 | True | 1 | 5 | 48 | 58.38 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 第13页 |
| fixed | Q16 | True | 1 | 5 | 39 | 48.24 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 第13页 |
| fixed | Q17 | True | 1 | 5 | 101 | 120.44 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 第13页 |
| fixed | Q18 | True | 0 | 5 | 60 | 70.63 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第1页 |
| fixed | Q19 | True | 0 | 5 | 44 | 50.21 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第1页 |
| fixed | Q20 | True | 0 | 5 | 35 | 40.21 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第1页 |
| fixed | Q21 | True | 0 | 5 | 35 | 41.38 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第1页 |
| fixed | Q22 | True | 0 | 5 | 38 | 45.51 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第1页 |
| fixed | Q23 | True | 0 | 5 | 75 | 87.01 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第1页 |
| fixed | Q24 | True | 0 | 5 | 100 | 117.38 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第1页 |
| fixed | Q25 | True | 0 | 5 | 95 | 101.17 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第1页 |
| fixed | Q26 | True | 0 | 5 | 84 | 94.7 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第1页 |
| fixed | Q27 | True | 0 | 5 | 51 | 62.47 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第1页 |
| fixed | Q28 | True | 0 | 5 | 37 | 43.46 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第1页 |
| fixed | Q29 | True | 1 | 4 | 43 | 49.02 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 第1页 |
| fixed | Q30 | True | 1 | 4 | 47 | 53.21 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 第1页 |
| fixed | Q31 | True | 1 | 4 | 59 | 69.94 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 第1页 |
| fixed | Q32 | True | 1 | 4 | 34 | 38.93 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 第1页 |
| fixed | Q33 | True | 0 | 4 | 37 | 44.16 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 第1页 |
| fixed | Q34 | True | 0 | 4 | 78 | 85.74 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 第1页 |
| fixed | Q35 | True | 1 | 4 | 59 | 65.04 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 第1页 |
| fixed | N01 | False | 0 | 5 | 32 | 39.16 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第1页 |
| fixed | N02 | False | 0 | 5 | 32 | 39.01 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第1页 |
| fixed | N03 | False | 0 | 5 | 66 | 72.47 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 第13页 |
| fixed | N04 | False | 0 | 5 | 81 | 91.97 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第1页 |
| fixed | N05 | False | 0 | 5 | 99 | 107 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 第2页 |
| heading | Q01 | True | 2 | 5 | 85 | 96.94 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 90 mmHg > 第4页 |
| heading | Q02 | True | 2 | 5 | 40 | 46.35 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 180 mmHg > 4.2  高血压诊断标准 > 4.2.3  血压分类 > 第4页 |
| heading | Q03 | True | 1 | 5 | 39 | 44.47 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 90 mmHg > 第4页 |
| heading | Q04 | True | 0 | 5 | 26 | 31.31 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 2  基层高血压诊疗管理流程 > 第2页 |
| heading | Q05 | True | 1 | 5 | 46 | 52.22 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 180 mmHg > 4.2  高血压诊断标准 > 第4页 |
| heading | Q06 | True | 1 | 5 | 32 | 38.87 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 180 mmHg > 4.2  高血压诊断标准 > 4.2.3  血压分类 > 第4页 |
| heading | Q07 | True | 2 | 5 | 38 | 43.64 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 3  诊疗关键点 > 1.1  组建管理团队 > 第2页 |
| heading | Q08 | True | 3 | 5 | 39 | 47.88 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 90 mmHg。△：“和（或）”的意义同 > 第3页 |
| heading | Q09 | True | 2 | 5 | 37 | 43.48 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 5  高血压治疗 > 5.1  治疗原则 > 第5页 |
| heading | Q10 | True | 0 | 5 | 67 | 72.32 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 20 mg/ > 12.5 mg) > 5.4.3.1  <80 > 第8页 |
| heading | Q11 | True | 3 | 5 | 88 | 98.22 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 5 mmHg，死亡风险降低 > 1.3  保障基本药物 > 第2页 |
| heading | Q12 | True | 1 | 5 | 117 | 132.97 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 20 mg/ > 12.5 mg) > 5.4.3.1  <80 > 第8页 |
| heading | Q13 | True | 2 | 5 | 102 | 113.9 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 25 mg，口服，1 h > 5.4.5  已用药患者的治疗方案调整建议 > 5.4.6.2  血压≥ > 第9页 |
| heading | Q14 | True | 1 | 5 | 139 | 148.53 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 8  高血压长期随访管理 > 8.1  随访频率 > 第13页 |
| heading | Q15 | True | 0 | 5 | 152 | 166.41 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 2  基层高血压诊疗管理流程 > 第2页 |
| heading | Q16 | True | 2 | 5 | 112 | 118.8 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 85 mmHg > 5.3  生活方式干预 > 第5页 |
| heading | Q17 | True | 0 | 5 | 104 | 109.27 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 3  诊疗关键点 > 1.1  组建管理团队 > 第2页 |
| heading | Q18 | True | 1 | 5 | 72 | 77.86 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第三条 本规范所称内部控制，是由企业董事会、监事会、经理层和全体员工实施的、旨在实现控制目 > 第2页 |
| heading | Q19 | True | 1 | 5 | 141 | 154.2 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第四条 企业建立与实施内部控制，应当遵循下列原则： > 第2页 |
| heading | Q20 | True | 1 | 5 | 146 | 197.97 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第五条 企业建立与实施有效的内部控制，应当包括下列要素： > 第2页 |
| heading | Q21 | True | 4 | 5 | 119 | 135.18 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第二十四条 企业应当采用定性与定量相结合的方法，按照风险发生的可能性及其影响程度等，对识别 > 第5页 |
| heading | Q22 | True | 1 | 5 | 101 | 109.22 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第二十二条 企业识别内部风险，应当关注下列因素： > 第4页 |
| heading | Q23 | True | 1 | 5 | 69 | 74.12 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第二十二条 企业识别内部风险，应当关注下列因素： > 第4页 |
| heading | Q24 | True | 4 | 5 | 58 | 62.48 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第五条 企业建立与实施有效的内部控制，应当包括下列要素： > 第2页 |
| heading | Q25 | True | 1 | 5 | 57 | 61.63 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第三十条 授权审批控制要求企业根据常规授权和特别授权的规定，明确各岗位办理业务和事项的权限 > 第6页 |
| heading | Q26 | True | 1 | 5 | 59 | 63.52 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第三十三条 预算控制要求企业实施全面预算管理制度，明确各责任单位在预算管理中的职责权限，规 > 第6页 |
| heading | Q27 | True | 1 | 5 | 57 | 61.27 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第四十四条 企业应当根据本规范及其配套办法，制定内部控制监督制度，明确内部审计机构（或经授 > 第7页 |
| heading | Q28 | True | 1 | 5 | 58 | 62.56 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第四十五条 企业应当制定内部控制缺陷认定标准，对监督过程中发现的内部控制缺陷，应当分析缺陷 > 第8页 |
| heading | Q29 | True | 1 | 5 | 30 | 34.74 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 第1页 |
| heading | Q30 | True | 1 | 5 | 30 | 34.67 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 第1页 |
| heading | Q31 | True | 1 | 5 | 27 | 31.65 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 第1页 |
| heading | Q32 | True | 1 | 5 | 26 | 31.56 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 第1页 |
| heading | Q33 | True | 2 | 5 | 25 | 30.2 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 第1页 |
| heading | Q34 | True | 2 | 5 | 25 | 29.94 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 第1页 |
| heading | Q35 | True | 1 | 5 | 32 | 37.04 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 4.2 高血压诊断标准 > 4.2.2 诊断流程 > 第1页 |
| heading | N01 | False | 0 | 5 | 59 | 64.31 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第十六条 企业应当制定和实施有利于企业可持续发展的人力资源政策。人力资源政策应当包括下列内 > 第3页 |
| heading | N02 | False | 0 | 5 | 60 | 64.5 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第三十六条 企业应当根据内部控制目标，结合风险应对策略，综合运用控制措施，对各种业务和事项 > 第6页 |
| heading | N03 | False | 0 | 5 | 56 | 60.54 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 180 mmHg > 4.1.2  诊室外血压监测 > 第4页 |
| heading | N04 | False | 0 | 5 | 56 | 60.84 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第三十五条 绩效考评控制要求企业建立和实施绩效考评制度，科学设置考核指标体系，对企业内部各 > 第6页 |
| heading | N05 | False | 0 | 5 | 60 | 64.78 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 5 mmHg，死亡风险降低 > 1.3  保障基本药物 > 第2页 |

Run command:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\eval.ps1
```
