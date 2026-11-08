# rag-mcp-service

企业知识库 RAG 检索服务（Java/Spring Boot + PostgreSQL/pgvector + MCP + 治理层）。

- 当前状态：**C+ 设计文档已完成，尚未开始实现**
- 文档入口：[docs/README.md](docs/README.md)
- 主设计：[docs/00-design.md](docs/00-design.md)
- 目标：5 个工作日内做出可复现的检索 + 引用 + 评测 + MCP 两个 tool + WorkItem/Mandate/Approval/Audit
- 不做：前端、聊天、agent 编排、多租户、模型训练、自动审批
- 实现方式：本地 coding agent 加速；不接 GitHub/Feishu/PR 自动化
- 实现前先读：`docs/00-design.md` → `docs/01-prd.md` → `docs/08-governance.md` → `docs/10-conflict-resolution.md` → `docs/02-api-contract.md` / `docs/03-data-model.md` → `docs/04-boundaries-and-errors.md` / `docs/05-non-functional.md` → `docs/11-retrieval-and-eval-spec.md` / `docs/12-mcp-implementation.md` / `docs/13-dev-workflow.md` → `docs/06-test-plan.md` → `docs/07-acceptance.md` → `docs/09-handoff.md`

验收口径：上传 3 篇文档 → 检索带引用 → 评测有真实 hit@5/MRR → WorkItem/审批/Mandate 生效 → MCP Inspector 调通两个 tool → `mvn test` 全绿。

## 本地依赖：PostgreSQL 16 + pgvector

```bash
docker compose up -d postgres      # 启动（首次自动建 vector / pg_trgm 扩展）
docker compose ps                  # 健康状态
docker compose logs -f postgres    # 跟随日志
docker compose stop postgres       # 停止（数据卷保留）
```

- 镜像 `pgvector/pgvector:pg16`：PostgreSQL 16.15 + pgvector 0.8.7 + pg_trgm 1.6
- 连接串 `jdbc:postgresql://localhost:5432/ragdb`，用户 `rag`，默认密码 `rag_local_pw`
- 需要改端口/账号：复制 `.env.example` 为 `.env` 再改（`.env` 不提交）
- 数据在 Docker 卷 `rag-mcp-service_pgdata`；`docker compose down -v` 会连数据一起清空
