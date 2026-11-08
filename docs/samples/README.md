# 样例语料说明

验收命令引用 `docs/samples/*.pdf`，但仓库不提交 PDF（体积/版权/公开库整洁）。请按下面准备 3 篇文本型 PDF，放到本目录。

## 1. 必须的 3 篇

| 文件 | 用途 | 来源 |
|---|---|---|
| `hypertension-2025.pdf` | 当前版本、主评测语料 | 公开的《国家基层高血压防治管理指南2025版》 |
| `hypertension-2024.pdf` | 旧版本、版本治理测试 | 同一指南的 2024 版/摘编；没有就做一份 2~3 页的公开摘编 |
| `enterprise-policy-2026.pdf` | 跨文档/冲突/关键词测试 | 公开的企业制度模板或公开产品手册 |

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
