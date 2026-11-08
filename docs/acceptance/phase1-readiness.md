# Phase 1 环境就绪检查

- 日期：2026-11-09
- 检查人：辅助验收 agent
- 结论：**已就绪；Phase 1 验收通过**。

| 项 | 状态 | 证据 | 处理 |
|---|---|---|---|
| JDK | PASS（21.0.9） | `java -version`；`maven.compiler.release=17` | 用 JDK 21 编译/运行，编译目标是 17 |
| Maven | PASS（3.9.9） | `mvn -v` 正常；解压到本地工具目录并加 PATH shim | 已可用；新开的 shell 也能找到 `mvn` |
| Docker CLI | PASS | `docker version` server 29.8.2；`docker run --rm hello-world` 成功；`docker compose` 可用 | 当前会话已切完整权限；验收时保持完整权限 |
| Ollama | PASS | `ollama list` 有 `bge-m3:latest`（1.2GB）；`ollama show` 的 embedding length=1024；`POST /api/embed` 实测 dims=1024 | 已完成，Gate 3 可跑 |
| 样例 PDF | PASS | `docs/samples/` 下 4 个文本型 PDF：2025 版 15 页、2024 摘编 2 页、企业内控 8 页，另有 2020 版参考 35 页；SHA-256 见 [samples/SOURCES.md](../samples/SOURCES.md) | 按 [samples/README.md](../samples/README.md) 准备；PDF 不提交 |
| 格式 fixture | PASS | `src/test/resources/fixtures/` 下 DOCX/MD/TXT/GB18030/扫描件 5 个文件；`FormatFixturesTest` 5/5 通过 | Gate 2 直接上传；不进入评测语料 |
| 代码骨架 | PASS | `pom.xml`、`src/main`、`src/test` 已存在；`mvn test` 67/0/0/1 | Phase 1 已交付 |
| Git 仓库 | PASS | `phase1` @ `97d4f4d`，工作区干净，`HEAD == origin/phase1` | 验收后合并 `main`；PDF/`.env` 不提交 |

---

## 1. 阻断项状态

### Maven：已解决

Maven 3.9.9 解压到本地工具目录，并在用户 PATH 目录放了 `mvn.cmd` shim；新 shell 里 `mvn -v` 正常。

> Chocolatey 方案因当前 shell 未提权失败（写 `C:\ProgramData\chocolatey\lib` 被拒绝），改用免管理员解压 + shim。

### Docker CLI：已解决

`docker version` 返回 server 29.8.2，`docker run --rm hello-world` 成功，`docker compose` 可用。之前的权限拒绝是沙箱只允许写 workspace、Docker CLI 写不了自己的日志目录；当前会话已切完整权限。

### bge-m3：已完成

`ollama list` 显示 `bge-m3:latest`（1.2GB），`ollama show` 显示 `embedding length 1024`；`POST /api/embed` 实测返回 1024 维非零向量。

```powershell
ollama list
```

模型冷启动不计入 30s 预算，验收前已预热。

---

## 2. 不阻断但要注意

- **JDK 21 vs 文档 JDK 17**：Spring Boot 3.5 支持 21；`pom.xml` 用 `maven.compiler.release=17` 即可。验收不强制装 JDK 17，但报告里要写实际 JDK 版本。
- **样例 PDF**：已准备 3 篇验收用 + 1 篇参考（2020 版），全部为公开文本型 PDF；不要用扫描件替换。来源与校验见 [samples/SOURCES.md](../samples/SOURCES.md)。
- **Git 仓库**：已初始化并推送到私有仓库；`main` 保留设计/验收文档，`phase1` 是待合并实现分支。
- **Phase 1 会话需要完整权限**：Maven 写 `~/.m2`、Docker 写自己的日志目录、Ollama 写模型目录，都在 workspace 外；`workspace-write` 会重演权限拒绝。当前会话已切完整权限。
- **Docker 需要完整权限**：当前会话已切完整权限；如果换会话，先确认 `docker run --rm hello-world` 可用，不要反复重装 Docker。

---

## 3. 验收顺序

1. `mvn test` 绿；
2. `docker compose up -d postgres` + `docker run --rm hello-world`；
3. `ollama list` 有 `bge-m3`；
4. 样例 PDF 与格式 fixture 就位；
5. 按 [phase1-checklist.md](phase1-checklist.md) 跑 Gate 0–7。
