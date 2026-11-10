# RAG Eval Report

- dataset: `eval/qa.jsonl` (sha256=1487e261b728debd22a876d31016f376efeb40932d8888d45d2d15b91a15af4c)
- git commit: `8a2228e9ca5fd744246a5f876950da08539abdcb` (dirty=False)
- embedding: bge-m3 (1024d); topK=5; default asOf=2026-11-09; includeSuperseded=False
- chunk strategies: fixed, heading
- QA: 40 (35 answerable + 5 unanswerable)
- generated: 2026-11-10 14:00:56

## Answerable metrics

| chunkStrategy | answered | hit@1 | hit@3 | hit@5 | MRR | p50 | p95 | errors |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| fixed | 35 | 0.7143 | 0.7429 | 0.7714 | 0.7343 | 20 | 26 | 0 |
| heading | 35 | 0.8286 | 0.9429 | 0.9714 | 0.8819 | 25 | 30 | 0 |

Unanswerable (reported separately, not mixed into hit@k):

| chunkStrategy | falsePositiveRate | noAnswerAccuracy | abstained (NO_MATCH / TOO_NOISY) | answerableAbstained | errors |
|---|---:|---:|---|---:|---:|
| fixed | 0.2 | 0.8 | 3 / 1 | 0 | 0 |
| heading | 0.2 | 0.8 | 3 / 1 | 0 | 0 |

Thresholds from docs/05-non-functional.md: hit@5 >= 0.85, MRR >= 0.65 (heading/default;
fixed is reported as measured). Errors are counted separately and excluded from the
hit@k/MRR denominator. Gold was not changed to tune these numbers.

p50/p95 are the app-reported server-side retrieval time (`tookMs`, includes query
embedding + SQL + RRF). Client wall-clock p50/p95 (including PowerShell/HTTP) is in
`report.json` as `clientP50Ms`/`clientP95Ms`.

Gate: this script exits 1 on any retrieval error, and exits 2 when any answerable
question was abstained (`answerableAbstained > 0`). False abstention is a regression;
see docs/acceptance/phase7-abstain.md before changing `app.search.abstain.*`.

## Per-question detail

| strategy | id | answerable | rank | results | abstained | reason | topScore | server(ms) | client(ms) | error | asOf | top citation |
|---|---|---|---:|---:|---|---|---:|---:|---:|---|---|---|
| fixed | Q01 | True | 1 | 5 | False |  | 0.753099992686576 | 19 | 24.87 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 4  高血压诊断与评估 > 4.2  高血压诊断标准 > 4.2.1  诊断标准 > 第4页 |
| fixed | Q02 | True | 1 | 5 | False |  | 0.721286924154052 | 20 | 25.06 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 4  高血压诊断与评估 > 4.2  高血压诊断标准 > 4.2.1  诊断标准 > 第4页 |
| fixed | Q03 | True | 1 | 5 | False |  | 0.743441651931904 | 20 | 24 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 4  高血压诊断与评估 > 4.2  高血压诊断标准 > 4.2.1  诊断标准 > 第4页 |
| fixed | Q04 | True | 1 | 5 | False |  | 0.723898774648005 | 23 | 27.45 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 4  高血压诊断与评估 > 4.2  高血压诊断标准 > 4.2.2  诊断流程 > 第4页 |
| fixed | Q05 | True | 0 | 5 | False |  | 0.687302187132319 | 27 | 30.92 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 4  高血压诊断与评估 > 4.2  高血压诊断标准 > 4.2.3  血压分类 > 第4页 |
| fixed | Q06 | True | 1 | 5 | False |  | 0.707169897646321 | 19 | 23.57 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 4  高血压诊断与评估 > 4.2  高血压诊断标准 > 4.2.3  血压分类 > 第4页 |
| fixed | Q07 | True | 5 | 5 | False |  | 0.660289644054905 | 18 | 21.92 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 4  高血压诊断与评估 > 4.3  初诊评估 > 第5页 |
| fixed | Q08 | True | 0 | 5 | False |  | 0.728479216824034 | 21 | 24.83 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 5  高血压治疗 > 5.1  治疗原则 > 第5页 |
| fixed | Q09 | True | 1 | 5 | False |  | 0.732330560684209 | 26 | 29.93 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 5  高血压治疗 > 5.3  生活方式干预 > 第5页 |
| fixed | Q10 | True | 1 | 5 | False |  | 0.743915638185765 | 20 | 24.49 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 5  高血压治疗 > 5.4  降压药物治疗 > 5.4.1  启动药物治疗时机 > 第6页 |
| fixed | Q11 | True | 0 | 5 | False |  | 0.743041798057675 | 19 | 22.6 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 1  基层高血压管理基本要求 > 1.3  保障基本药物 > 第2页 |
| fixed | Q12 | True | 1 | 5 | False |  | 0.7745012564962 | 20 | 24.04 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 5  高血压治疗 > 5.4  降压药物治疗 > 5.4.3  药物治疗方案 > 5.4.3.1  <80 岁、无合并症的高血压药物治疗方案 > 第8页 |
| fixed | Q13 | True | 2 | 5 | False |  | 0.763658898531003 | 24 | 27.74 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 5  高血压治疗 > 5.4  降压药物治疗 > 5.4.7  特殊情况的处理 > 第9页 |
| fixed | Q14 | True | 0 | 5 | False |  | 0.738433227154691 | 17 | 21.2 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 7  转诊 > 第13页 |
| fixed | Q15 | True | 1 | 5 | False |  | 0.718400715590149 | 24 | 27.51 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 8  高血压长期随访管理 > 8.2  随访内容 > 第13页 |
| fixed | Q16 | True | 1 | 5 | False |  | 0.786211946091901 | 20 | 23.6 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 9  筛查与预防 > 9.1  筛查方式 > 第13页 |
| fixed | Q17 | True | 0 | 5 | False |  | 0.647535710058044 | 18 | 21.56 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 10  健康教育 > 10.2  教育内容 > 第13页 |
| fixed | Q18 | True | 1 | 5 | False |  | 0.742978392357443 | 24 | 27.37 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第一章 总 则 > 第四条 企业建立与实施内部控制，应当遵循下列原则： > 第2页 |
| fixed | Q19 | True | 1 | 5 | False |  | 0.778096530210564 | 23 | 27.48 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第一章 总 则 > 第四条 企业建立与实施内部控制，应当遵循下列原则： > 第2页 |
| fixed | Q20 | True | 1 | 5 | False |  | 0.794328026736288 | 21 | 25.53 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第一章 总 则 > 第五条 企业建立与实施有效的内部控制，应当包括下列要素： > 第2页 |
| fixed | Q21 | True | 1 | 5 | False |  | 0.732899407416407 | 19 | 22.19 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第二章 内部环境 > 第十八条 企业应当加强文化建设，培育积极向上的价值观和社会责任感，倡导诚实守信、爱岗敬业、 > 第4页 |
| fixed | Q22 | True | 1 | 5 | False |  | 0.779397507798227 | 22 | 25.66 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第三章 风险评估 > 第二十三条 企业识别外部风险，应当关注下列因素： > 第4页 |
| fixed | Q23 | True | 1 | 5 | False |  | 0.76977647251603 | 22 | 25.63 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第三章 风险评估 > 第二十三条 企业识别外部风险，应当关注下列因素： > 第4页 |
| fixed | Q24 | True | 0 | 5 | False |  | 0.718460113680983 | 20 | 23.39 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第一章 总 则 > 第四条 企业建立与实施内部控制，应当遵循下列原则： > 第2页 |
| fixed | Q25 | True | 1 | 5 | False |  | 0.667816360928697 | 24 | 27.78 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第四章 控制活动 > 第三十条 授权审批控制要求企业根据常规授权和特别授权的规定，明确各岗位办理业务和事项的权限 > 第5页 |
| fixed | Q26 | True | 1 | 5 | False |  | 0.641406225739064 | 24 | 28.01 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第四章 控制活动 > 第三十一条 会计系统控制要求企业严格执行国家统一的会计准则制度，加强会计基础工作，明确会计 > 第6页 |
| fixed | Q27 | True | 1 | 5 | False |  | 0.709370478616782 | 19 | 23.43 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第六章 内部监督 > 第四十四条 企业应当根据本规范及其配套办法，制定内部控制监督制度，明确内部审计机构（或经授 > 第7页 |
| fixed | Q28 | True | 1 | 5 | False |  | 0.631437188725984 | 36 | 40.52 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第六章 内部监督 > 第四十四条 企业应当根据本规范及其配套办法，制定内部控制监督制度，明确内部审计机构（或经授 > 第7页 |
| fixed | Q29 | True | 1 | 4 | False |  | 0.732980640771359 | 12 | 16.63 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 4.2 高血压诊断标准 > 4.2.1 诊断标准 > 第1页 |
| fixed | Q30 | True | 1 | 4 | False |  | 0.706447688803827 | 8 | 11.94 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 4.2 高血压诊断标准 > 4.2.1 诊断标准 > 第1页 |
| fixed | Q31 | True | 1 | 4 | False |  | 0.690892790667751 | 8 | 12.49 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 4.2 高血压诊断标准 > 4.2.1 诊断标准 > 第1页 |
| fixed | Q32 | True | 1 | 4 | False |  | 0.700483813809818 | 8 | 11.91 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 4.2 高血压诊断标准 > 4.2.2 诊断流程 > 第1页 |
| fixed | Q33 | True | 0 | 4 | False |  | 0.59155817434398 | 9 | 13.61 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 4.2 高血压诊断标准 > 4.2.2 诊断流程 > 第1页 |
| fixed | Q34 | True | 0 | 4 | False |  | 0.556448505771532 | 9 | 40.25 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 4.2 高血压诊断标准 > 4.2.2 诊断流程 > 第1页 |
| fixed | Q35 | True | 1 | 4 | False |  | 0.667048315195804 | 11 | 15.12 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 4.2 高血压诊断标准 > 4.2.2 诊断流程 > 第1页 |
| fixed | N01 | False | 0 | 0 | True | NO_MATCH | 0.468692695732251 | 20 | 24.87 | False | 2026-11-09 |  |
| fixed | N02 | False | 0 | 0 | True | NO_MATCH | 0.413103949735692 | 23 | 26.36 | False | 2026-11-09 |  |
| fixed | N03 | False | 0 | 0 | True | TOO_NOISY | 0.596957098168355 | 18 | 22.1 | False | 2026-11-09 |  |
| fixed | N04 | False | 0 | 0 | True | NO_MATCH | 0.512025091655588 | 19 | 22.25 | False | 2026-11-09 |  |
| fixed | N05 | False | 0 | 5 | False |  | 0.650133451811467 | 19 | 23.93 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 5  高血压治疗 > 5.4  降压药物治疗 > 5.4.1  启动药物治疗时机 > 第6页 |
| heading | Q01 | True | 1 | 5 | False |  | 0.78016354978582 | 30 | 34.08 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 4  高血压诊断与评估 > 4.2  高血压诊断标准 > 4.2.1  诊断标准 > 第4页 |
| heading | Q02 | True | 1 | 5 | False |  | 0.756166805635025 | 31 | 35.13 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 4  高血压诊断与评估 > 4.2  高血压诊断标准 > 4.2.1  诊断标准 > 第4页 |
| heading | Q03 | True | 1 | 5 | False |  | 0.711942675799891 | 28 | 31.8 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 4  高血压诊断与评估 > 4.2  高血压诊断标准 > 4.2.1  诊断标准 > 第4页 |
| heading | Q04 | True | 1 | 5 | False |  | 0.738140977537097 | 26 | 30.41 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 4  高血压诊断与评估 > 4.2  高血压诊断标准 > 4.2.2  诊断流程 > 第4页 |
| heading | Q05 | True | 0 | 5 | False |  | 0.651108840174066 | 30 | 34.34 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 5  高血压治疗 > 5.4  降压药物治疗 > 5.4.7  特殊情况的处理 > 第9页 |
| heading | Q06 | True | 1 | 5 | False |  | 0.758858680725184 | 24 | 28.27 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 4  高血压诊断与评估 > 4.2  高血压诊断标准 > 4.2.3  血压分类 > 第4页 |
| heading | Q07 | True | 1 | 5 | False |  | 0.709755799392673 | 26 | 30.12 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 5  高血压治疗 > 5.1  治疗原则 > 第5页 |
| heading | Q08 | True | 1 | 5 | False |  | 0.757576145908703 | 25 | 29.65 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 5  高血压治疗 > 5.2  降压目标 > 第5页 |
| heading | Q09 | True | 1 | 5 | False |  | 0.752252578735357 | 31 | 35.1 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 5  高血压治疗 > 5.3  生活方式干预 > 第5页 |
| heading | Q10 | True | 1 | 5 | False |  | 0.751801558429522 | 24 | 29.05 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 5  高血压治疗 > 5.4  降压药物治疗 > 5.4.1  启动药物治疗时机 > 第6页 |
| heading | Q11 | True | 3 | 5 | False |  | 0.76153536072166 | 25 | 28.56 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 1  基层高血压管理基本要求 > 1.3  保障基本药物 > 第2页 |
| heading | Q12 | True | 1 | 5 | False |  | 0.727773688235539 | 23 | 26.98 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 5  高血压治疗 > 5.4  降压药物治疗 > 5.4.3  药物治疗方案 > 5.4.3.1  <80 岁、无合并症的高血压药物治疗方案 > 第8页 |
| heading | Q13 | True | 2 | 5 | False |  | 0.700274157426142 | 29 | 32.47 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 7  转诊 > 第13页 |
| heading | Q14 | True | 1 | 5 | False |  | 0.769953470834481 | 22 | 25.58 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 8  高血压长期随访管理 > 8.1  随访频率 > 第13页 |
| heading | Q15 | True | 2 | 5 | False |  | 0.697684562482335 | 29 | 32.49 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 4  高血压诊断与评估 > 4.3  初诊评估 > 第4页 |
| heading | Q16 | True | 1 | 5 | False |  | 0.746794208693171 | 23 | 26.76 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 9  筛查与预防 > 9.1  筛查方式 > 第13页 |
| heading | Q17 | True | 1 | 5 | False |  | 0.694639348344849 | 23 | 26.24 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 10  健康教育 > 10.1  基本要求 > 第13页 |
| heading | Q18 | True | 1 | 5 | False |  | 0.765823317291039 | 27 | 30.77 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第一章 总 则 > 第三条 本规范所称内部控制，是由企业董事会、监事会、经理层和全体员工实施的、旨在实现控制目 > 第2页 |
| heading | Q19 | True | 1 | 5 | False |  | 0.737237845679088 | 28 | 32.23 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第一章 总 则 > 第四条 企业建立与实施内部控制，应当遵循下列原则： > 第2页 |
| heading | Q20 | True | 1 | 5 | False |  | 0.732869124578556 | 26 | 29.63 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第一章 总 则 > 第四条 企业建立与实施内部控制，应当遵循下列原则： > 第2页 |
| heading | Q21 | True | 5 | 5 | False |  | 0.731391851570295 | 23 | 27.21 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第三章 风险评估 > 第二十四条 企业应当采用定性与定量相结合的方法，按照风险发生的可能性及其影响程度等，对识别 > 第5页 |
| heading | Q22 | True | 1 | 5 | False |  | 0.669401943683629 | 25 | 29.5 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第三章 风险评估 > 第二十二条 企业识别内部风险，应当关注下列因素： > 第4页 |
| heading | Q23 | True | 1 | 5 | False |  | 0.66275982248319 | 26 | 30.06 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第三章 风险评估 > 第二十二条 企业识别内部风险，应当关注下列因素： > 第4页 |
| heading | Q24 | True | 3 | 5 | False |  | 0.715466537610709 | 22 | 26.18 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第一章 总 则 > 第五条 企业建立与实施有效的内部控制，应当包括下列要素： > 第2页 |
| heading | Q25 | True | 1 | 5 | False |  | 0.636519106988766 | 27 | 31.02 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第四章 控制活动 > 第三十条 授权审批控制要求企业根据常规授权和特别授权的规定，明确各岗位办理业务和事项的权限 > 第6页 |
| heading | Q26 | True | 1 | 5 | False |  | 0.646697167152702 | 27 | 30.54 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第四章 控制活动 > 第三十三条 预算控制要求企业实施全面预算管理制度，明确各责任单位在预算管理中的职责权限，规 > 第6页 |
| heading | Q27 | True | 1 | 5 | False |  | 0.733824485496504 | 23 | 26.84 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第六章 内部监督 > 第四十四条 企业应当根据本规范及其配套办法，制定内部控制监督制度，明确内部审计机构（或经授 > 第7页 |
| heading | Q28 | True | 1 | 5 | False |  | 0.700593429768478 | 22 | 26.99 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第六章 内部监督 > 第四十五条 企业应当制定内部控制缺陷认定标准，对监督过程中发现的内部控制缺陷，应当分析缺陷 > 第8页 |
| heading | Q29 | True | 1 | 5 | False |  | 0.734310313124091 | 6 | 20.21 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 4.2 高血压诊断标准 > 4.2.1 诊断标准 > 第1页 |
| heading | Q30 | True | 1 | 5 | False |  | 0.698560661678069 | 5 | 9.54 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 4.2 高血压诊断标准 > 4.2.1 诊断标准 > 第1页 |
| heading | Q31 | True | 1 | 5 | False |  | 0.68852994774193 | 5 | 9.26 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 4.2 高血压诊断标准 > 4.2.1 诊断标准 > 第1页 |
| heading | Q32 | True | 1 | 5 | False |  | 0.691346665958969 | 6 | 10.57 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 4.2 高血压诊断标准 > 4.2.2 诊断流程 > 第1页 |
| heading | Q33 | True | 1 | 5 | False |  | 0.634427119962249 | 8 | 12.4 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 5.2 降压目标 > 第2页 |
| heading | Q34 | True | 1 | 5 | False |  | 0.583588028932332 | 8 | 12.17 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 8.1 随访频率 > 第2页 |
| heading | Q35 | True | 1 | 5 | False |  | 0.567325634738051 | 8 | 12.92 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 4.2 高血压诊断标准 > 4.2.2 诊断流程 > 第1页 |
| heading | N01 | False | 0 | 0 | True | NO_MATCH | 0.460986655442788 | 25 | 29.7 | False | 2026-11-09 |  |
| heading | N02 | False | 0 | 0 | True | NO_MATCH | 0.449864032953725 | 29 | 33.37 | False | 2026-11-09 |  |
| heading | N03 | False | 0 | 0 | True | TOO_NOISY | 0.609403604624511 | 24 | 28.53 | False | 2026-11-09 |  |
| heading | N04 | False | 0 | 0 | True | NO_MATCH | 0.495663402396871 | 25 | 29.78 | False | 2026-11-09 |  |
| heading | N05 | False | 0 | 5 | False |  | 0.674588739871979 | 24 | 29.38 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 5  高血压治疗 > 5.4  降压药物治疗 > 5.4.2  降压药物选择 > 第6页 |

Run command:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\eval.ps1
```
