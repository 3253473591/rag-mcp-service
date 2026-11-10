# RAG Eval Report

- dataset: `eval/qa.jsonl` (sha256=1487e261b728debd22a876d31016f376efeb40932d8888d45d2d15b91a15af4c)
- git commit: `24e9a97e3871e01843bf8dcf36d1203ccb08ef34` (dirty=False)
- embedding: bge-m3 (1024d); topK=5; default asOf=2026-11-09; includeSuperseded=False
- chunk strategies: fixed, heading
- QA: 40 (35 answerable + 5 unanswerable)
- generated: 2026-11-10 13:13:42

## Answerable metrics

| chunkStrategy | answered | hit@1 | hit@3 | hit@5 | MRR | p50 | p95 | errors |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| fixed | 35 | 0.6857 | 0.7429 | 0.7429 | 0.7095 | 76 | 145 | 0 |
| heading | 35 | 0.7714 | 0.9143 | 0.9714 | 0.851 | 41 | 128 | 0 |

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
| fixed | Q01 | True | 1 | 5 | False |  | 0.753099992686576 | 34 | 92.65 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 4  高血压诊断与评估 > 4.2  高血压诊断标准 > 4.2.1  诊断标准 > 第4页 |
| fixed | Q02 | True | 1 | 5 | False |  | 0.721286924154052 | 119 | 132 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 4  高血压诊断与评估 > 4.2  高血压诊断标准 > 4.2.1  诊断标准 > 第4页 |
| fixed | Q03 | True | 1 | 5 | False |  | 0.743441651931904 | 87 | 96.23 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 4  高血压诊断与评估 > 4.2  高血压诊断标准 > 4.2.1  诊断标准 > 第4页 |
| fixed | Q04 | True | 1 | 5 | False |  | 0.723898774648005 | 60 | 100.55 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 4  高血压诊断与评估 > 4.2  高血压诊断标准 > 4.2.2  诊断流程 > 第4页 |
| fixed | Q05 | True | 0 | 5 | False |  | 0.687302187132319 | 145 | 153.79 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 4  高血压诊断与评估 > 4.2  高血压诊断标准 > 4.2.3  血压分类 > 第4页 |
| fixed | Q06 | True | 1 | 5 | False |  | 0.707169897646321 | 63 | 71.67 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 4  高血压诊断与评估 > 4.2  高血压诊断标准 > 4.2.3  血压分类 > 第4页 |
| fixed | Q07 | True | 0 | 5 | False |  | 0.660289644054905 | 58 | 64.16 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 4  高血压诊断与评估 > 4.3  初诊评估 > 第5页 |
| fixed | Q08 | True | 0 | 5 | False |  | 0.728479216824034 | 75 | 81.95 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 5  高血压治疗 > 5.1  治疗原则 > 第5页 |
| fixed | Q09 | True | 1 | 5 | False |  | 0.732330560684209 | 76 | 82.86 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 5  高血压治疗 > 5.3  生活方式干预 > 第5页 |
| fixed | Q10 | True | 1 | 5 | False |  | 0.743915638185765 | 72 | 79.28 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 5  高血压治疗 > 5.4  降压药物治疗 > 5.4.1  启动药物治疗时机 > 第6页 |
| fixed | Q11 | True | 0 | 5 | False |  | 0.743041798057675 | 64 | 72.48 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 1  基层高血压管理基本要求 > 1.3  保障基本药物 > 第2页 |
| fixed | Q12 | True | 2 | 5 | False |  | 0.7745012564962 | 80 | 87.79 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 5  高血压治疗 > 5.4  降压药物治疗 > 5.4.3  药物治疗方案 > 第8页 |
| fixed | Q13 | True | 1 | 5 | False |  | 0.763658898531003 | 98 | 108.48 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 5  高血压治疗 > 5.4  降压药物治疗 > 5.4.6  血压≥ 180/110 mmHg 的紧急处理 > 5.4.6.1  血压≥ 180/110 mmHg，不伴心、脑、肾急 > 第9页 |
| fixed | Q14 | True | 0 | 5 | False |  | 0.738433227154691 | 100 | 107.79 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 7  转诊 > 第13页 |
| fixed | Q15 | True | 1 | 5 | False |  | 0.718400715590149 | 94 | 101.8 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 8  高血压长期随访管理 > 8.2  随访内容 > 第13页 |
| fixed | Q16 | True | 1 | 5 | False |  | 0.786211946091901 | 78 | 87.16 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 9  筛查与预防 > 9.1  筛查方式 > 第13页 |
| fixed | Q17 | True | 0 | 5 | False |  | 0.647535710058044 | 87 | 96.25 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 1  基层高血压管理基本要求 > 1.1  组建管理团队 > 第2页 |
| fixed | Q18 | True | 3 | 5 | False |  | 0.742978392357443 | 98 | 107.22 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第四章 控制活动 > 第三十五条 绩效考评控制要求企业建立和实施绩效考评制度，科学设置考核指标体系，对企业内部各 > 第6页 |
| fixed | Q19 | True | 1 | 5 | False |  | 0.778096530210564 | 81 | 90.04 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第一章 总 则 > 第四条 企业建立与实施内部控制，应当遵循下列原则： > 第2页 |
| fixed | Q20 | True | 1 | 5 | False |  | 0.794328026736288 | 96 | 106.86 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第一章 总 则 > 第四条 企业建立与实施内部控制，应当遵循下列原则： > 第2页 |
| fixed | Q21 | True | 1 | 5 | False |  | 0.732899407416407 | 98 | 106.13 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第二章 内部环境 > 第十八条 企业应当加强文化建设，培育积极向上的价值观和社会责任感，倡导诚实守信、爱岗敬业、 > 第4页 |
| fixed | Q22 | True | 1 | 5 | False |  | 0.779397507798227 | 75 | 82.5 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第三章 风险评估 > 第二十三条 企业识别外部风险，应当关注下列因素： > 第4页 |
| fixed | Q23 | True | 1 | 5 | False |  | 0.76977647251603 | 62 | 69.24 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第三章 风险评估 > 第二十三条 企业识别外部风险，应当关注下列因素： > 第4页 |
| fixed | Q24 | True | 0 | 5 | False |  | 0.718460113680983 | 79 | 89.22 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第一章 总 则 > 第四条 企业建立与实施内部控制，应当遵循下列原则： > 第2页 |
| fixed | Q25 | True | 1 | 5 | False |  | 0.667816360928697 | 97 | 103.58 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第四章 控制活动 > 第三十条 授权审批控制要求企业根据常规授权和特别授权的规定，明确各岗位办理业务和事项的权限 > 第5页 |
| fixed | Q26 | True | 1 | 5 | False |  | 0.641406225739064 | 72 | 78.28 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第四章 控制活动 > 第三十一条 会计系统控制要求企业严格执行国家统一的会计准则制度，加强会计基础工作，明确会计 > 第6页 |
| fixed | Q27 | True | 1 | 5 | False |  | 0.709370478616782 | 71 | 76.99 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第六章 内部监督 > 第四十四条 企业应当根据本规范及其配套办法，制定内部控制监督制度，明确内部审计机构（或经授 > 第7页 |
| fixed | Q28 | True | 1 | 5 | False |  | 0.631437188725984 | 72 | 77.91 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第六章 内部监督 > 第四十四条 企业应当根据本规范及其配套办法，制定内部控制监督制度，明确内部审计机构（或经授 > 第7页 |
| fixed | Q29 | True | 1 | 4 | False |  | 0.732980640771359 | 54 | 60.38 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 4.2 高血压诊断标准 > 4.2.1 诊断标准 > 第1页 |
| fixed | Q30 | True | 1 | 4 | False |  | 0.706447688803827 | 53 | 59.39 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 4.2 高血压诊断标准 > 4.2.1 诊断标准 > 第1页 |
| fixed | Q31 | True | 1 | 4 | False |  | 0.690892790667751 | 53 | 61.99 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 4.2 高血压诊断标准 > 4.2.1 诊断标准 > 第1页 |
| fixed | Q32 | True | 1 | 4 | False |  | 0.700483813809818 | 51 | 58.04 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 4.2 高血压诊断标准 > 4.2.2 诊断流程 > 第1页 |
| fixed | Q33 | True | 0 | 4 | False |  | 0.59155817434398 | 82 | 88.11 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 第1页 |
| fixed | Q34 | True | 0 | 4 | False |  | 0.556448505771532 | 97 | 105.24 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 第1页 |
| fixed | Q35 | True | 1 | 4 | False |  | 0.667048315195804 | 90 | 96.72 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 4.2 高血压诊断标准 > 4.2.2 诊断流程 > 第1页 |
| fixed | N01 | False | 0 | 0 | True | NO_MATCH | 0.468692695732251 | 74 | 81.6 | False | 2026-11-09 |  |
| fixed | N02 | False | 0 | 0 | True | NO_MATCH | 0.413103949735692 | 51 | 56.39 | False | 2026-11-09 |  |
| fixed | N03 | False | 0 | 0 | True | TOO_NOISY | 0.596957098168355 | 72 | 77.5 | False | 2026-11-09 |  |
| fixed | N04 | False | 0 | 0 | True | NO_MATCH | 0.512025091655588 | 151 | 156 | False | 2026-11-09 |  |
| fixed | N05 | False | 0 | 5 | False |  | 0.650133451811467 | 179 | 183.89 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 第2页 |
| heading | Q01 | True | 1 | 5 | False |  | 0.78016354978582 | 131 | 147.78 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 4  高血压诊断与评估 > 4.2  高血压诊断标准 > 4.2.1  诊断标准 > 第4页 |
| heading | Q02 | True | 1 | 5 | False |  | 0.756166805635025 | 111 | 130.72 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 4  高血压诊断与评估 > 4.2  高血压诊断标准 > 4.2.1  诊断标准 > 第4页 |
| heading | Q03 | True | 1 | 5 | False |  | 0.711942675799891 | 107 | 117.78 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 4  高血压诊断与评估 > 4.2  高血压诊断标准 > 4.2.1  诊断标准 > 第4页 |
| heading | Q04 | True | 1 | 5 | False |  | 0.738140977537097 | 81 | 88 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 4  高血压诊断与评估 > 4.2  高血压诊断标准 > 4.2.2  诊断流程 > 第4页 |
| heading | Q05 | True | 0 | 5 | False |  | 0.651108840174066 | 104 | 111.31 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 5  高血压治疗 > 5.4  降压药物治疗 > 5.4.7  特殊情况的处理 > 第9页 |
| heading | Q06 | True | 1 | 5 | False |  | 0.758858680725184 | 128 | 136.56 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 4  高血压诊断与评估 > 4.2  高血压诊断标准 > 4.2.3  血压分类 > 第4页 |
| heading | Q07 | True | 1 | 5 | False |  | 0.709755799392673 | 96 | 105.52 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 5  高血压治疗 > 5.1  治疗原则 > 第5页 |
| heading | Q08 | True | 1 | 5 | False |  | 0.757576145908703 | 78 | 100.94 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 5  高血压治疗 > 5.2  降压目标 > 第5页 |
| heading | Q09 | True | 1 | 5 | False |  | 0.752252578735357 | 98 | 130.67 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 5  高血压治疗 > 5.3  生活方式干预 > 第5页 |
| heading | Q10 | True | 1 | 5 | False |  | 0.751801558429522 | 127 | 136.79 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 5  高血压治疗 > 5.4  降压药物治疗 > 5.4.1  启动药物治疗时机 > 第6页 |
| heading | Q11 | True | 3 | 5 | False |  | 0.76153536072166 | 108 | 135.34 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 1  基层高血压管理基本要求 > 1.3  保障基本药物 > 第2页 |
| heading | Q12 | True | 2 | 5 | False |  | 0.727773688235539 | 129 | 145.28 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 5  高血压治疗 > 5.4  降压药物治疗 > 5.4.3  药物治疗方案 > 第6页 |
| heading | Q13 | True | 2 | 5 | False |  | 0.700274157426142 | 101 | 121.41 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 7  转诊 > 第13页 |
| heading | Q14 | True | 1 | 5 | False |  | 0.769953470834481 | 115 | 123.36 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 8  高血压长期随访管理 > 8.1  随访频率 > 第13页 |
| heading | Q15 | True | 2 | 5 | False |  | 0.697684562482335 | 92 | 105.52 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 4  高血压诊断与评估 > 4.3  初诊评估 > 第4页 |
| heading | Q16 | True | 1 | 5 | False |  | 0.746794208693171 | 84 | 97.87 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 9  筛查与预防 > 9.1  筛查方式 > 第13页 |
| heading | Q17 | True | 2 | 5 | False |  | 0.694639348344849 | 48 | 57.57 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 第2页 |
| heading | Q18 | True | 1 | 5 | False |  | 0.765823317291039 | 32 | 39 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第一章 总 则 > 第三条 本规范所称内部控制，是由企业董事会、监事会、经理层和全体员工实施的、旨在实现控制目 > 第2页 |
| heading | Q19 | True | 1 | 5 | False |  | 0.737237845679088 | 37 | 50.37 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第一章 总 则 > 第四条 企业建立与实施内部控制，应当遵循下列原则： > 第2页 |
| heading | Q20 | True | 1 | 5 | False |  | 0.732869124578556 | 33 | 39.66 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第一章 总 则 > 第五条 企业建立与实施有效的内部控制，应当包括下列要素： > 第2页 |
| heading | Q21 | True | 5 | 5 | False |  | 0.731391851570295 | 25 | 31.03 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第三章 风险评估 > 第二十四条 企业应当采用定性与定量相结合的方法，按照风险发生的可能性及其影响程度等，对识别 > 第5页 |
| heading | Q22 | True | 1 | 5 | False |  | 0.669401943683629 | 57 | 66.24 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第三章 风险评估 > 第二十二条 企业识别内部风险，应当关注下列因素： > 第4页 |
| heading | Q23 | True | 1 | 5 | False |  | 0.66275982248319 | 41 | 47.53 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第三章 风险评估 > 第二十二条 企业识别内部风险，应当关注下列因素： > 第4页 |
| heading | Q24 | True | 4 | 5 | False |  | 0.715466537610709 | 34 | 41.74 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第一章 总 则 > 第五条 企业建立与实施有效的内部控制，应当包括下列要素： > 第2页 |
| heading | Q25 | True | 1 | 5 | False |  | 0.636519106988766 | 41 | 48.57 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第四章 控制活动 > 第三十条 授权审批控制要求企业根据常规授权和特别授权的规定，明确各岗位办理业务和事项的权限 > 第6页 |
| heading | Q26 | True | 1 | 5 | False |  | 0.646697167152702 | 39 | 48.36 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第四章 控制活动 > 第三十三条 预算控制要求企业实施全面预算管理制度，明确各责任单位在预算管理中的职责权限，规 > 第6页 |
| heading | Q27 | True | 1 | 5 | False |  | 0.733824485496504 | 43 | 49.66 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第六章 内部监督 > 第四十四条 企业应当根据本规范及其配套办法，制定内部控制监督制度，明确内部审计机构（或经授 > 第7页 |
| heading | Q28 | True | 1 | 5 | False |  | 0.700593429768478 | 38 | 44.66 | False | 2026-11-09 | 《enterprise-policy-2026》v2026（2026-01-01 生效） > 第六章 内部监督 > 第四十五条 企业应当制定内部控制缺陷认定标准，对监督过程中发现的内部控制缺陷，应当分析缺陷 > 第8页 |
| heading | Q29 | True | 1 | 5 | False |  | 0.734310313124091 | 18 | 30.15 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 4.2 高血压诊断标准 > 4.2.1 诊断标准 > 第1页 |
| heading | Q30 | True | 1 | 5 | False |  | 0.698560661678069 | 11 | 18.3 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 4.2 高血压诊断标准 > 4.2.1 诊断标准 > 第1页 |
| heading | Q31 | True | 1 | 5 | False |  | 0.68852994774193 | 11 | 17.01 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 4.2 高血压诊断标准 > 4.2.1 诊断标准 > 第1页 |
| heading | Q32 | True | 1 | 5 | False |  | 0.691346665958969 | 14 | 20.76 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 4.2 高血压诊断标准 > 4.2.2 诊断流程 > 第1页 |
| heading | Q33 | True | 1 | 5 | False |  | 0.634427119962249 | 15 | 22.06 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 5.2 降压目标 > 第2页 |
| heading | Q34 | True | 1 | 5 | False |  | 0.583588028932332 | 24 | 30.44 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 8.1 随访频率 > 第2页 |
| heading | Q35 | True | 1 | 5 | False |  | 0.567325634738051 | 16 | 21.69 | False | 2024-06-01 | 《hypertension-2024》v2024（2024-01-01 生效） > 测试用摘编 > 4.2 高血压诊断标准 > 4.2.2 诊断流程 > 第1页 |
| heading | N01 | False | 0 | 0 | True | NO_MATCH | 0.460986655442788 | 30 | 35.01 | False | 2026-11-09 |  |
| heading | N02 | False | 0 | 0 | True | NO_MATCH | 0.449864032953725 | 30 | 34.86 | False | 2026-11-09 |  |
| heading | N03 | False | 0 | 0 | True | TOO_NOISY | 0.609403604624511 | 23 | 27.62 | False | 2026-11-09 |  |
| heading | N04 | False | 0 | 0 | True | NO_MATCH | 0.495663402396871 | 23 | 27.8 | False | 2026-11-09 |  |
| heading | N05 | False | 0 | 5 | False |  | 0.674588739871979 | 26 | 31.54 | False | 2026-11-09 | 《hypertension-2025》v2025（2025-01-01 生效） > 第2页 |

Run command:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\eval.ps1
```
