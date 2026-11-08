# Phase 1 环境就绪检查

- 日期：2026-11-08
- 检查人：辅助验收 agent
- 结论：**接近就绪**；Maven、Docker 已解决，bge-m3 拉取中，样例 PDF 待准备。

| 项 | 状态 | 证据 | 处理 |
|---|---|---|---|
| JDK | PASS（21.0.9） | `java -version`；`JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot` | `pom.xml` 设 `maven.compiler.release=17` 即可；不强制装 JDK 17 |
| Maven | PASS（3.9.9） | `mvn -v` 正常；安装目录 `D:\AAA_Code_Project\tools\apache-maven-3.9.9`；PATH shim `C:\Users\Administrator\.local\bin\mvn.cmd` | 已可用；新开的 shell 也能找到 `mvn` |
| Docker CLI | PASS | `docker version` server 29.8.2；`docker run --rm hello-world` 成功；`docker compose` 可用 | 当前会话已切完整权限；验收时保持完整权限 |
| Ollama | **PULLING** | `ollama pull bge-m3` 后台任务 `pwsh-304312343cd2489c` 正在下载（约 1.2GB） | 完成后 `ollama list` 确认；未完成前 Gate 3 不能开始 |
| 样例 PDF | **FAIL** | `docs/samples/hypertension-2025.pdf` 不存在 | 按 [samples/README.md](../samples/README.md) 准备 3 篇公开文本型 PDF；不提交 |
| 代码骨架 | N/A | 无 `pom.xml`、无 `src/` | Phase 1 实现者创建 |
| Git 仓库 | N/A | 当前目录没有 `.git` | 可选；没有 git 就用文件清单回报 |

---

## 1. 阻断项状态

### Maven：已解决

已安装 Maven 3.9.9 到 `D:\AAA_Code_Project\tools\apache-maven-3.9.9`，并在 `C:\Users\Administrator\.local\bin\mvn.cmd` 放了 shim；新 shell 里 `mvn -v` 正常。

> Chocolatey 方案因当前 shell 未提权失败（`C:\ProgramData\chocolatey\lib` 拒绝访问），改用免管理员解压 + shim。

### Docker CLI：已解决

`docker version` 返回 server 29.8.2，`docker run --rm hello-world` 成功，`docker compose` 可用。之前的权限拒绝是沙箱只允许写 workspace、Docker CLI 写不了自己的日志目录；当前会话已切完整权限。

### bge-m3：拉取中

`ollama pull bge-m3` 后台任务 `pwsh-304312343cd2489c` 正在下载（约 1.2GB）。完成后 `ollama list` 应出现 `bge-m3`；未完成前 Gate 3 不能开始。

```powershell
ollama list
```

模型冷启动不计入 30s 预算，但验收前必须先拉取并预热一次。

---

## 2. 不阻断但要注意

- **JDK 21 vs 文档 JDK 17**：Spring Boot 3.5 支持 21；`pom.xml` 用 `maven.compiler.release=17` 即可。验收不强制装 JDK 17，但报告里要写实际 JDK 版本。
- **样例 PDF**：没有 PDF 时不要用扫描件；用公开文本型 PDF。第三篇可以用公开企业制度模板/产品手册。
- **没有 git**：验收报告里的“分支/commit”可改成“文件清单 + 时间”；如果实现者初始化 git，再补 commit。
- **Phase 1 会话需要完整权限**：Maven 写 `~/.m2`、Docker 写自己的日志目录、Ollama 写模型目录，都在 workspace 外；`workspace-write` 会重演权限拒绝。当前会话已切完整权限；新对话也要用完整权限，或把 Maven 本地仓库指到 workspace 内并单独处理 Docker/Ollama。
- **Docker 需要完整权限**：当前会话已切完整权限；如果换会话，先确认 `docker run --rm hello-world` 可用，不要反复重装 Docker。

---

## 3. 就绪后的验收顺序

1. `mvn test` 绿；
2. `docker compose up -d postgres` + `docker run --rm hello-world`；
3. `ollama list` 有 `bge-m3`；
4. 3 篇样例 PDF 就位；
5. 按 [phase1-checklist.md](phase1-checklist.md) 跑 Gate 0–7。
