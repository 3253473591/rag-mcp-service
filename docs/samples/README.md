# 样例语料说明

验收命令引用 `docs/samples/*.pdf`，但仓库不提交 PDF（体积/版权/公开库整洁）。请按下面准备 3 篇文本型 PDF，放到本目录。

## 1. 必须的 3 篇

| 文件 | 用途 | 来源 |
|---|---|---|
| `hypertension-2025.pdf` | 当前版本、主评测语料 | 公开的《国家基层高血压防治管理指南2025版》 |
| `hypertension-2024.pdf` | 旧版本、版本治理测试 | 同一指南的 2024 版/摘编；没有就做一份 2~3 页的公开摘编 |
| `enterprise-policy-2026.pdf` | 跨文档/冲突/关键词测试 | 公开的企业制度文本；本仓库采用财政部《企业内部控制基本规范》 |

要求：

- 文本型 PDF（能选中文字），不要扫描件；
- 单文件 ≤ 20MB、≤ 500 页；
- 不含真实患者/公司内部信息；
- 文件内容可公开引用。

## 2. 旧版本怎么做

如果没有 2024 版，创建一份**公开摘编**：

- 从 2025 版摘 2~3 页；
- 把其中一个数值改掉（例如把某个阈值从 140/90 改成 150/95），并在页脚标注“测试用摘编，非正式版本”；
- 保存为 `hypertension-2024.pdf`；
- 仅用于版本/冲突测试，不冒充正式文件。

本仓库的 `hypertension-2024.pdf` 即按此方式生成：2 页，诊室血压阈值改为 150/95 mmHg，
每页页脚带“测试用摘编，非正式版本”。具体改动与校验见 [SOURCES.md](SOURCES.md)。

## 3. 入库参数

```bash
# 旧版
curl -X POST http://localhost:8080/api/v1/documents \
  -F "file=@docs/samples/hypertension-2024.pdf" \
  -F "docKey=hypertension-guideline" \
  -F "version=2024" \
  -F "effectiveFrom=2024-01-01" \
  -F "chunkStrategies=fixed,heading"

# 新版
curl -X POST http://localhost:8080/api/v1/documents \
  -F "file=@docs/samples/hypertension-2025.pdf" \
  -F "docKey=hypertension-guideline" \
  -F "version=2025" \
  -F "effectiveFrom=2025-01-01" \
  -F "supersedesDocId=<旧版docId>" \
  -F "chunkStrategies=fixed,heading"
```

> 重复上传同一文件不会补生成缺失的 `chunkStrategies`；评测请首次就传两套。

## 4. 校验

```bash
sha256sum docs/samples/*.pdf
```

把 checksum 写进 `eval/report.md` 或验收记录，保证语料可复现。

## 5. 当前已准备（2026-11-08）

| 文件 | 页数 | 说明 |
|---|---:|---|
| `hypertension-2025.pdf` | 15 | 官方 2025 版；中国循环杂志 2025;40(9):836-850 |
| `hypertension-2024.pdf` | 2 | 按第 2 节生成的测试用摘编，诊室阈值改为 150/95 |
| `enterprise-policy-2026.pdf` | 8 | 《企业内部控制基本规范》公开文本排版；2026 为样例槽位 |
| `hypertension-2020.pdf` | 35 | 附加真实旧版，供版本链对照；默认 3 篇评测不引用 |

来源、SHA-256、PDF 页锚点和冲突点见 [SOURCES.md](SOURCES.md)。
四份文件都已被 `.gitignore` 忽略，不会随仓库提交。

格式 fixture（DOCX/MD/TXT/扫描件）不放在这里，避免和公开评测语料混在一起；
它们提交在 `src/test/resources/fixtures/`，说明见该目录的 `README.md`。
