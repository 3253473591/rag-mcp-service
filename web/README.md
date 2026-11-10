> 这是 rag-mcp-service 的检索控制台。核心不是聊天框，而是“引用”：每条结果先回答文档、版本、条款、页码，再给出被引用的原文，分数只作为仪器读数。

# rag-mcp web

Vue 3 + TypeScript + Vite 的单页前端，放在 `web/`，通过开发代理访问本机 Spring Boot 后端。

## 环境

- Node.js 20.19+ 或 22.12+（本仓库在 Node 24.22 / npm 11.17 下构建）
- 后端 `rag-mcp-service` 监听 `http://127.0.0.1:8080`
- 可选：Ollama 已启动且拉取 `bge-m3`（检索与上传都依赖它）

## 启动

```powershell
# 终端 1：后端
mvn -o spring-boot:run

# 终端 2：前端
cd web
npm install
npm run dev
```

打开 `http://127.0.0.1:5173`。

`vite.config.ts` 把 `/api` 和 `/actuator` 代理到 `http://127.0.0.1:8080`，浏览器端不会出现跨域问题；`/actuator/health` 可在联调时直接打开。

## 脚本

| 命令 | 作用 |
|---|---|
| `npm run dev` | Vite 开发服务器，固定 `127.0.0.1:5173` |
| `npm run typecheck` | `vue-tsc --noEmit`，严格类型检查 |
| `npm run build` | 先 `vue-tsc --noEmit`，再产出 `dist/` |
| `npm run preview` | 预览构建结果，`127.0.0.1:4173`，同样带 `/api` 代理 |

## 让 Spring Boot 直接托管（可选）

不要改 Maven。构建后把 `web/dist` 复制到 `src/main/resources/static/` 即可：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-web.ps1
mvn -o spring-boot:run
# http://127.0.0.1:8080/
```

脚本会先跑 `npm run build`，校验目标路径确实以 `src\main\resources\static` 结尾，再清空并复制。`src/main/resources/static/` 已加入 `.gitignore`，构建产物不提交。本次未改 `pom.xml`，309 个后端测试不受影响。

说明：前端用 `createWebHistory`，从 `http://127.0.0.1:8080/` 进入后点击导航是客户端路由，正常；直接刷新 `/library` 这类深链接需要服务端 fallback，本次按约束没有改 Java。开发时用 Vite 或 `npm run preview` 不受影响。

## 目录

```text
web/
  src/
    api/          # 请求封装与后端 DTO 类型（不要在这里猜字段，对照 api/dto）
    components/   # 页头、检索表单、结果项、拒答、骨架、错误面板
    views/        # 检索 / 文档库 / 关于
    lib/          # 高亮、格式化、错误归一化、剪贴板
    stores/       # 检索结果的跨路由内存状态
    styles/       # tokens.css + base.css
  README.md
  DESIGN.md
```

## 对接的接口

- `POST /api/v1/search`：`query/topK/strategy/chunkStrategy/asOf/includeSuperseded`
- `GET /api/v1/documents?limit=20&offset=0`
- `POST /api/v1/documents`：multipart；`file` + 可选 `title/docKey/version/effectiveFrom/effectiveTo/chunkStrategies`
- `DELETE /api/v1/documents/{docId}`：二次确认后调用，成功为 `204`

错误统一按 `{code, message, traceId}` 展示；搜索的拒答按 `abstained`、`abstainReason`、`message`、`diagnostics` 展示。`score` 固定标为 `RRF`，`rerankScore` 标为 `重排`，并明确结果按重排分排序。

## 字体与资源

全部字体来自 npm 包 `@fontsource/*`，构建时打进 `dist/assets`；没有 Google Fonts、没有 CDN、没有 UI 组件库。CJK 字体使用系统无衬线/宋体回退（见 `DESIGN.md`）。

## 设计

令牌、字体、布局决策与被砍掉的装饰见 [DESIGN.md](DESIGN.md)。
