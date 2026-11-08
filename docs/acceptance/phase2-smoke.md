# Phase 2 real-app smoke

- generated: 2026-11-09T01:03:25.9813230+08:00
- baseUrl: http://127.0.0.1:8080; asOf: 2026-11-09; QA: eval/qa.smoke.jsonl
- corpus: docs/samples/hypertension-2024.pdf (v2024), hypertension-2025.pdf (v2025, supersedes), enterprise-policy-2026.pdf (v2026); chunkStrategies=fixed,heading
- embedding: Ollama bge-m3 (1024 dim)

| case | query | tookMs | statuses | results |
|---|---|---:|---|---:|
| S01_heading_hybrid_current | 高血压的诊断标准是什么？ | 181 | ACTIVE | 5 |
|  |  |  | hypertension-guideline v2025 ACTIVE score=0.0164 | 《国家基层高血压防治管理指南2025版》v2025（2025-01-01 生效） > 90 mmHg > 第4页 |
|  |  |  | hypertension-guideline v2025 ACTIVE score=0.0161 | 《国家基层高血压防治管理指南2025版》v2025（2025-01-01 生效） > 180 mmHg > 4.2  高血压诊断标准 > 4.2.1  诊断标准 > 第4页 |
|  |  |  | hypertension-guideline v2025 ACTIVE score=0.0159 | 《国家基层高血压防治管理指南2025版》v2025（2025-01-01 生效） > 2  基层高血压诊疗管理流程 > 第2页 |
|  |  |  | hypertension-guideline v2025 ACTIVE score=0.0156 | 《国家基层高血压防治管理指南2025版》v2025（2025-01-01 生效） > 3 d、至少 > 第4页 |
|  |  |  | hypertension-guideline v2025 ACTIVE score=0.0154 | 《国家基层高血压防治管理指南2025版》v2025（2025-01-01 生效） > 20 g、川牛膝 > 6.3.7  中药代茶饮 > 第12页 |
| S02_heading_hybrid_current | 诊室血压的诊断阈值是多少？ | 135 | ACTIVE | 5 |
|  |  |  | hypertension-guideline v2025 ACTIVE score=0.0164 | 《国家基层高血压防治管理指南2025版》v2025（2025-01-01 生效） > 90 mmHg > 第4页 |
|  |  |  | hypertension-guideline v2025 ACTIVE score=0.0161 | 《国家基层高血压防治管理指南2025版》v2025（2025-01-01 生效） > 30 min > 第3页 |
|  |  |  | hypertension-guideline v2025 ACTIVE score=0.0159 | 《国家基层高血压防治管理指南2025版》v2025（2025-01-01 生效） > 180 mmHg > 4.2  高血压诊断标准 > 4.2.1  诊断标准 > 第4页 |
|  |  |  | hypertension-guideline v2025 ACTIVE score=0.0156 | 《国家基层高血压防治管理指南2025版》v2025（2025-01-01 生效） > 90 mmHg。△：“和（或）”的意义同 > 第3页 |
|  |  |  | hypertension-guideline v2025 ACTIVE score=0.0154 | 《国家基层高血压防治管理指南2025版》v2025（2025-01-01 生效） > 80 mmHg > 第4页 |
| S03_heading_hybrid_current | 企业内部控制中的风险评估包括哪些内容？ | 101 | ACTIVE | 5 |
|  |  |  | enterprise-internal-control v2026 ACTIVE score=0.0164 | 《企业内部控制基本规范》v2026（2026-01-01 生效） > 第五条 企业建立与实施有效的内部控制，应当包括下列要素： > 第2页 |
|  |  |  | enterprise-internal-control v2026 ACTIVE score=0.0161 | 《企业内部控制基本规范》v2026（2026-01-01 生效） > 第四十六条 企业应当结合内部监督情况，定期对内部控制的有效性进行自我评价，出具内部控制自我 > 第8页 |
|  |  |  | enterprise-internal-control v2026 ACTIVE score=0.0159 | 《企业内部控制基本规范》v2026（2026-01-01 生效） > 第四条 企业建立与实施内部控制，应当遵循下列原则： > 第2页 |
|  |  |  | enterprise-internal-control v2026 ACTIVE score=0.0156 | 《企业内部控制基本规范》v2026（2026-01-01 生效） > 第四十四条 企业应当根据本规范及其配套办法，制定内部控制监督制度，明确内部审计机构（或经授 > 第7页 |
|  |  |  | enterprise-internal-control v2026 ACTIVE score=0.0154 | 《企业内部控制基本规范》v2026（2026-01-01 生效） > 企业内部控制制度（公开文本样例） > 第1页 |
| S04_heading_hybrid_current | 内部控制要素有哪些？ | 92 | ACTIVE | 5 |
|  |  |  | enterprise-internal-control v2026 ACTIVE score=0.0164 | 《企业内部控制基本规范》v2026（2026-01-01 生效） > 第四条 企业建立与实施内部控制，应当遵循下列原则： > 第2页 |
|  |  |  | enterprise-internal-control v2026 ACTIVE score=0.0161 | 《企业内部控制基本规范》v2026（2026-01-01 生效） > 第四十七条 企业应当以书面或者其他适当的形式，妥善保存内部控制建立与实施过程中的相关记录或 > 第8页 |
|  |  |  | enterprise-internal-control v2026 ACTIVE score=0.0159 | 《企业内部控制基本规范》v2026（2026-01-01 生效） > 第五条 企业建立与实施有效的内部控制，应当包括下列要素： > 第2页 |
|  |  |  | enterprise-internal-control v2026 ACTIVE score=0.0156 | 《企业内部控制基本规范》v2026（2026-01-01 生效） > 第三条 本规范所称内部控制，是由企业董事会、监事会、经理层和全体员工实施的、旨在实现控制目 > 第2页 |
|  |  |  | enterprise-internal-control v2026 ACTIVE score=0.0154 | 《企业内部控制基本规范》v2026（2026-01-01 生效） > 第四十五条 企业应当制定内部控制缺陷认定标准，对监督过程中发现的内部控制缺陷，应当分析缺陷 > 第8页 |
| S05_heading_hybrid_current | 高血压患者随访频率如何安排？ | 65 | ACTIVE | 5 |
|  |  |  | hypertension-guideline v2025 ACTIVE score=0.0164 | 《国家基层高血压防治管理指南2025版》v2025（2025-01-01 生效） > 2  基层高血压诊疗管理流程 > 第2页 |
|  |  |  | hypertension-guideline v2025 ACTIVE score=0.0161 | 《国家基层高血压防治管理指南2025版》v2025（2025-01-01 生效） > 8  高血压长期随访管理 > 8.1  随访频率 > 第13页 |
|  |  |  | hypertension-guideline v2025 ACTIVE score=0.0159 | 《国家基层高血压防治管理指南2025版》v2025（2025-01-01 生效） > 110 mmHg > 第3页 |
|  |  |  | hypertension-guideline v2025 ACTIVE score=0.0156 | 《国家基层高血压防治管理指南2025版》v2025（2025-01-01 生效） > 20 g、川牛膝 > 6.3.7  中药代茶饮 > 第12页 |
|  |  |  | hypertension-guideline v2025 ACTIVE score=0.0154 | 《国家基层高血压防治管理指南2025版》v2025（2025-01-01 生效） > 10  健康教育 > 10.1  基本要求 > 第13页 |
| S01_asOf_2024_06_01 | 高血压的诊断标准是什么？ | 41 | SUPERSEDED | 5 |
|  |  |  | hypertension-guideline v2024 SUPERSEDED score=0.0164 | 《国家基层高血压防治管理指南2024版（测试摘编）》v2024（2024-01-01 生效） > 测试用摘编 > 4.2 高血压诊断标准 > 4.2.1 诊断标准 > 第1页 |
|  |  |  | hypertension-guideline v2024 SUPERSEDED score=0.0161 | 《国家基层高血压防治管理指南2024版（测试摘编）》v2024（2024-01-01 生效） > 测试用摘编 > 第1页 |
|  |  |  | hypertension-guideline v2024 SUPERSEDED score=0.0159 | 《国家基层高血压防治管理指南2024版（测试摘编）》v2024（2024-01-01 生效） > 测试用摘编 > 4.2 高血压诊断标准 > 4.2.2 诊断流程 > 第1页 |
|  |  |  | hypertension-guideline v2024 SUPERSEDED score=0.0156 | 《国家基层高血压防治管理指南2024版（测试摘编）》v2024（2024-01-01 生效） > 测试用摘编 > 4.2 高血压诊断标准 > 第1页 |
|  |  |  | hypertension-guideline v2024 SUPERSEDED score=0.0154 | 《国家基层高血压防治管理指南2024版（测试摘编）》v2024（2024-01-01 生效） > 测试用摘编 > 5.2 降压目标 > 第2页 |
| S01_includeSuperseded | 高血压的诊断标准是什么？ | 49 | ACTIVE,SUPERSEDED | 5 |
|  |  |  | hypertension-guideline v2025 ACTIVE score=0.0164 | 《国家基层高血压防治管理指南2025版》v2025（2025-01-01 生效） > 90 mmHg > 第4页 |
|  |  |  | hypertension-guideline v2024 SUPERSEDED score=0.0161 | 《国家基层高血压防治管理指南2024版（测试摘编）》v2024（2024-01-01 生效） > 测试用摘编 > 4.2 高血压诊断标准 > 4.2.1 诊断标准 > 第1页 |
|  |  |  | hypertension-guideline v2025 ACTIVE score=0.0159 | 《国家基层高血压防治管理指南2025版》v2025（2025-01-01 生效） > 180 mmHg > 4.2  高血压诊断标准 > 4.2.1  诊断标准 > 第4页 |
|  |  |  | hypertension-guideline v2025 ACTIVE score=0.0156 | 《国家基层高血压防治管理指南2025版》v2025（2025-01-01 生效） > 2  基层高血压诊疗管理流程 > 第2页 |
|  |  |  | hypertension-guideline v2024 SUPERSEDED score=0.0154 | 《国家基层高血压防治管理指南2024版（测试摘编）》v2024（2024-01-01 生效） > 测试用摘编 > 第1页 |
| S01_fixed_hybrid | 高血压的诊断标准是什么？ | 42 | ACTIVE | 5 |
|  |  |  | hypertension-guideline v2025 ACTIVE score=0.0164 | 《国家基层高血压防治管理指南2025版》v2025（2025-01-01 生效） > 第3页 |
|  |  |  | hypertension-guideline v2025 ACTIVE score=0.0161 | 《国家基层高血压防治管理指南2025版》v2025（2025-01-01 生效） > 第3页 |
|  |  |  | hypertension-guideline v2025 ACTIVE score=0.0159 | 《国家基层高血压防治管理指南2025版》v2025（2025-01-01 生效） > 第3页 |
|  |  |  | hypertension-guideline v2025 ACTIVE score=0.0156 | 《国家基层高血压防治管理指南2025版》v2025（2025-01-01 生效） > 第13页 |
|  |  |  | hypertension-guideline v2025 ACTIVE score=0.0154 | 《国家基层高血压防治管理指南2025版》v2025（2025-01-01 生效） > 第2页 |
| S03_keyword | 企业内部控制中的风险评估包括哪些内容？ | 3 |  | 0 |

Checks:

```json
{"defaultOnlyActive":true,"historicalReturnsSuperseded":true,"includeSupersededReturnsBoth":true,"noEmptyCurrentResults":true}
```
