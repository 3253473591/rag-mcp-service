# RAG Eval Report (Phase 2 smoke)

- dataset: `eval/qa.smoke.jsonl` (sha256=bcfda68329001167abebc96a42038a28b34ecd56a59f4332d265577e9e6b2eea)
- git commit: `080adfbd7b185294ac245d4d596b71f38fe3fb0c`
- embedding: bge-m3; topK=5; asOf=2026-11-09; QA=8 (6 answerable + 2 unanswerable)
- NOTE: this is a smoke set for Phase 2. The full 40-question Phase 3 dataset is not fabricated here.

| chunkStrategy | hit@1 | hit@3 | hit@5 | MRR | p50 | p95 | errors |
|---|---:|---:|---:|---:|---:|---:|---:|
| fixed | 0.1667 | 0.1667 | 0.1667 | 0.1667 | 71.69 | 94.13 | 0 |
| heading | 0.5 | 1 | 1 | 0.7222 | 86.85 | 159.2 | 0 |

Unanswerable (reported separately, not mixed into hit@k):

| chunkStrategy | falsePositiveRate | noAnswerAccuracy |
|---|---:|---:|
| fixed | 1 | 0 |
| heading | 1 | 0 |

Thresholds from docs/05-non-functional.md: hit@5 >= 0.85, MRR >= 0.65. The smoke set is small;
treat threshold flags as informational and do not tune them by changing gold labels.

Per-question detail:

| strategy | id | answerable | rank | results | error |
|---|---|---|---:|---:|---|
| fixed | S01 | True | 0 | 5 | False |
| fixed | S02 | True | 0 | 5 | False |
| fixed | S03 | True | 0 | 5 | False |
| fixed | S04 | True | 0 | 5 | False |
| fixed | S05 | True | 1 | 5 | False |
| fixed | S06 | True | 0 | 5 | False |
| fixed | N01 | False | 0 | 5 | False |
| fixed | N02 | False | 0 | 5 | False |
| heading | S01 | True | 2 | 5 | False |
| heading | S02 | True | 3 | 5 | False |
| heading | S03 | True | 1 | 5 | False |
| heading | S04 | True | 1 | 5 | False |
| heading | S05 | True | 2 | 5 | False |
| heading | S06 | True | 1 | 5 | False |
| heading | N01 | False | 0 | 5 | False |
| heading | N02 | False | 0 | 5 | False |
