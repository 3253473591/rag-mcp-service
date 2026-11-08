# 格式测试 fixtures

这些文件只用于 Gate 2 的**解析 / 切分 / 上传**路径验证，**不是**公开评测语料，
不要加入 `eval/qa.jsonl` 的 3 篇 gold 覆盖。目录随测试提交，文件都很小。

| 文件 | 大小 | 用途 |
|---|---:|---|
| `sample.docx` | 13 KB | `Heading 1~3` 样式、2×3 表格、>900 token 长节 |
| `sample.md` | 4.7 KB | ATX `#/##/###`、嵌套标题、Markdown 表格、长节 |
| `sample.txt` | 0.7 KB | 无标题纯文本，验证 heading 策略回退 fixed |
| `sample-gb18030.txt` | 0.5 KB | 与 `sample.txt` 同内容，GB18030 编码，验证编码回退 |
| `sample-scanned.pdf` | 92 KB | 1 页纯图片，无文本层，验证 422 |

`FormatFixturesTest` 会加载以上文件并断言解析/切分行为；改 fixture 后先跑：

```powershell
mvn -q -Dtest=FormatFixturesTest test
```

## 1. DOCX 结构

- H1：`员工培训管理办法（测试样例）`
- H2：`1 总则`
  - H3：`1.1 目的`
  - H3：`1.2 适用范围`
- H2：`2 培训组织与学时`
  - H3：`2.1 培训类型与最低学时`
  - 表格：2 行 × 3 列（表头 + 1 行数据）
    `培训类型 | 最低学时 | 适用对象` / `入职培训 | 8 学时 | 新入职员工`
- H2：`3 长文本与递归切分`
  - H3：`3.1 培训记录与评估条款汇编`（16 段，>900 tokens）
- H2：`4 附则`

期望：

- DOCX 解析出 3 个标题层级，`clausePath` 为
  `员工培训管理办法（测试样例） > 1 总则 > 1.1 目的`；
- 表格整块保留，`table=true`，单元格用 ` | ` 连接；
- `3.1` 这一节超过 900 tokens，`heading` 策略递归切成多块，且每块仍带
  `clauseId=3.1`；
- 没有页号（DOCX 不是分页解析），`pageNo=null`。

## 2. Markdown / TXT 结构

- `sample.md` 与 DOCX 使用同一套标题和表格，但标题是 ATX `#/##/###`；
- `sample.txt` 是 3 个普通段落（说明段 + 两段政策文本），没有标题行、任何
  `#`、编号章节或表格；
- `sample-gb18030.txt` 与 `sample.txt` 文本完全相同，只是编码为 GB18030；
- `sample-scanned.pdf` 只有 1 页 A4 图片，`pdftotext` 提取长度为 0。

期望：

- MD 表格 `table=true`，嵌套标题路径与 DOCX 一致；
- TXT 全部 `headingLevel=0`、`clausePath=null`，heading 策略回退固定窗口；
- GB18030 解码后段落与 UTF-8 版本逐段相同；
- 扫描件返回 **422 `ERR-PARSE_FAILED`**，消息包含“扫描件”。

## 3. 手动上传

```powershell
# DOCX
curl.exe -s -w "`n%{http_code}`n" -X POST http://localhost:8080/api/v1/documents `
  -F "file=@src/test/resources/fixtures/sample.docx" `
  -F "docKey=fixture-docx" -F "version=2026" -F "effectiveFrom=2026-01-01" `
  -F "chunkStrategies=fixed,heading"

# MD
curl.exe -s -w "`n%{http_code}`n" -X POST http://localhost:8080/api/v1/documents `
  -F "file=@src/test/resources/fixtures/sample.md" `
  -F "docKey=fixture-md" -F "version=2026" -F "effectiveFrom=2026-01-01" `
  -F "chunkStrategies=fixed,heading"

# TXT
curl.exe -s -w "`n%{http_code}`n" -X POST http://localhost:8080/api/v1/documents `
  -F "file=@src/test/resources/fixtures/sample.txt" `
  -F "docKey=fixture-txt" -F "version=2026" -F "effectiveFrom=2026-01-01" `
  -F "chunkStrategies=fixed,heading"

# 扫描件：预期 422 ERR-PARSE_FAILED
curl.exe -s -w "`n%{http_code}`n" -X POST http://localhost:8080/api/v1/documents `
  -F "file=@src/test/resources/fixtures/sample-scanned.pdf" `
  -F "docKey=fixture-scan" -F "version=2026" -F "effectiveFrom=2026-01-01"
```

> `docKey + version` 唯一；重复跑验收时换 `docKey` 或先删文档，否则会命中重复/冲突分支。

## 4. 生成方式

- `sample.docx`：由 `sample.md` 经 `pandoc sample.md -o sample.docx` 生成，
  标题使用 Word 内置 `Heading 1~3` 样式，表格为标准 `w:tbl`；
- `sample.md` / `sample.txt` / `sample-gb18030.txt`：UTF-8（GB18030 除外）纯文本，LF 换行；
- `sample-scanned.pdf`：Pillow 把文字和表格画进一张 A4 图片后保存为 PDF，
  页面无任何文本对象。

不需要 PPTX / XLSX / HTML / 图片 / 音频 / RTF；项目范围只支持 PDF/DOCX/MD/TXT。
