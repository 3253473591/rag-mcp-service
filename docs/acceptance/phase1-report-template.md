# Phase 1 验收报告

- 日期：
- 实现者：
- 验收人：
- 分支 / commit：
- 环境：Windows / JDK 17 / Maven / Docker / Ollama
- 结论：**通过 / 不通过**

---

## 1. 构建与测试

| 项 | 结果 |
|---|---|
| `mvn test` | BUILD SUCCESS / FAILURE |
| 测试数 / 失败 / 错误 |  |
| 关键测试 | T-UNIT-PARSE、T-UNIT-CHUNK、T-EMBED、T-INT-DOC、T-VERSION |

证据：

```text
（粘贴 surefire 汇总或命令输出）
```

---

## 2. Docker / Flyway / 表结构

| 检查 | 结果 |
|---|---|
| `rag-postgres` healthy |  |
| 6 张表 |  |
| `vector` + `pg_trgm` |  |
| HNSW + GIN |  |
| `chunk_strategy` 小写 + CHECK |  |
| 唯一约束 |  |
| `supersedes_doc_id ON DELETE SET NULL` |  |

证据：

```sql
（粘贴 \dt / 扩展 / 索引查询）
```

---

## 3. 上传 / 解析 / 切分

| 文件 | 格式 | HTTP | docId | fixed chunks | heading chunks | 备注 |
|---|---|---|---:|---:|---:|---|
| hypertension-2025.pdf | PDF |  |  |  |  |  |
| enterprise-policy-2026.pdf | PDF |  |  |  |  |  |
| sample.docx | DOCX |  |  |  |  |  |
| sample.md | MD |  |  |  |  |  |
| sample.txt | TXT |  |  |  |  |  |

SQL 证据：

```sql
SELECT chunk_strategy, count(*) FROM kb_chunk WHERE document_id = '<docId>' GROUP BY 1;
SELECT count(*) FROM kb_chunk WHERE document_id = '<docId>' AND clause_path IS NULL;
SELECT count(*) FROM kb_chunk WHERE document_id = '<docId>' AND token_count <= 0;
```

---

## 4. Embedding

| 检查 | 结果 |
|---|---|
| `embedding IS NULL` 数量 |  |
| `vector_dims(embedding)` | 1024 / 其他 |
| `embedding_model` | bge-m3 / 其他 |
| 停 Ollama 上传 | 503/504，且无脏数据 |

证据：

```sql
（粘贴 SQL）
```

---

## 5. 幂等

| 检查 | 结果 |
|---|---|
| 重复上传 HTTP | 200 |
| `duplicate` | true |
| `docId` 不变 |  |
| chunk 数不变 |  |
| 同内容换文件名 | 返回已有文档 |
| 同 docKey+version 不同内容 | 400/409，未静默成功 |

证据：

```text
（两次 curl + count 对比）
```

---

## 6. 版本治理（Phase 1 部分）

```sql
SELECT version, status, effective_from, effective_to, supersedes_doc_id
FROM kb_document WHERE doc_key = 'hypertension-guideline' ORDER BY effective_from;
```

| 检查 | 结果 |
|---|---|
| 旧版 `SUPERSEDED` |  |
| 旧版 `effective_to=2024-12-31` |  |
| 新版 `ACTIVE` |  |
| 旧 chunk 未删除 |  |
| 重叠上传 → 400 `ERR-VALIDATION` |  |
| 删除被引用旧版 → 204，`supersedes_doc_id=NULL` |  |

证据：

```text
（粘贴 SQL + curl）
```

---

## 7. 文档管理

| 检查 | 结果 |
|---|---|
| 列表分页 |  |
| 详情字段完整 |  |
| 删除 204 + chunk 级联 |  |
| 重复删除 404 |  |

---

## 8. 工程与安全

| 检查 | 结果 |
|---|---|
| `.env` 未提交 |  |
| 无真实 token/PII |  |
| 无 `ddl-auto: update` |  |
| Flyway 迁移完整 |  |
| README 命令可复现 |  |
| 样例 PDF 未提交 |  |
| Windows 命令可执行 |  |

---

## 9. 证据路径

| 证据 | 路径 |
|---|---|
| surefire |  |
| SQL 输出 |  |
| curl 输出 |  |
| 失败注入记录 |  |

---

## 10. 阻塞 / 遗留

| 项 | 影响 | 处理 |
|---|---|---|
|  |  |  |

---

## 11. 签收

| 项 | 内容 |
|---|---|
| 验收结论 | 通过 / 不通过 |
| 验收人 |  |
| 日期 |  |
| Commit |  |
| 备注 |  |
