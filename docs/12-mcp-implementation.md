# MCP 实现规格：Spring AI 1.1.8 + Streamable HTTP

- 版本：v1
- 日期：2026-11-08
- 关联主设计：[00-design.md](00-design.md)
- 关联接口：[02-api-contract.md](02-api-contract.md)
- 关联治理：[08-governance.md](08-governance.md)
- 关联测试：[06-test-plan.md](06-test-plan.md)

> 目标：只暴露两个只读 tool，带 Bearer 鉴权 + mandate；不把应用里其他 `@Tool` 自动发布成 MCP tool。

---

## 1. 版本与协议

- Spring Boot 3.5.16
- Spring AI 1.1.8（BOM）
- MCP Java SDK：以 Spring AI 1.1.8 传递依赖为准，锁版本
  - 实际：`io.modelcontextprotocol.sdk:mcp-core:0.18.3`、`mcp-spring-webmvc:0.18.3`
- **v1 目标协议：MCP 2025-06-18**
- 规范已有更新版本（2026-07-28），但 Spring AI 1.1.8 不保证全支持；README 记录实际协商版本，客户端请求更高版本时明确拒绝或降级记录，不假装支持。
- **Phase 5 实测（2026-11-09）**：SDK 0.18.3 的 `LATEST_PROTOCOL_VERSION=2025-11-25`，默认 Java client 请求 2025-11-25，服务端按请求回显；仅请求 2025-06-18 时回显 2025-06-18。两种版本均完成 initialize/tools/list/tools/call。不要写成 2026-07-28。

---

## 2. 依赖

```xml
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-model-ollama</artifactId>
</dependency>
```

如果 `webmvc` starter 在 1.1.8 不可用，改用 `spring-ai-starter-mcp-server`（stdio），但 HTTP API 保留；不要混用两套 transport。

---

## 3. 配置

```yaml
server:
  address: 127.0.0.1        # 默认只绑本机；公网需网关
  port: 8080

spring:
  ai:
    mcp:
      server:
        protocol: STREAMABLE
        type: SYNC
        stdio: false
        request-timeout: 30s
        # 关键：不要自动把应用里所有 @Tool 都发布成 MCP tool
        tool-callback-converter: false
        # 额外防线：不扫描 @McpTool
        annotation-scanner:
          enabled: false
        capabilities:
          tool: true
          resource: false
          prompt: false
          completion: false
        streamable-http:
          mcp-endpoint: /mcp
    ollama:
      base-url: http://localhost:11434
      embedding:
        options:
          model: bge-m3

app:
  mcp:
    auth-token: ${MCP_AUTH_TOKEN:}
  admin:
    token: ${ADMIN_TOKEN:}
```

> 属性名以 1.1.8 实际文档为准；如果 `tool-callback-converter` 不生效，必须用低层 `SyncToolSpecification` 显式注册，不能依赖注解扫描。
>
> Phase 5 实测：`spring.ai.mcp.server.streamable-http.mcp-endpoint`、`spring.ai.mcp.server.capabilities.*`、
> `spring.ai.mcp.server.annotation-scanner.enabled` 均按上述名称生效；`tool-callback-converter`
> 的条件位于 `ToolCallbackConverterAutoConfiguration$ToolCallbackConverterCondition$ToolCallbackConvertCondition`。

---

## 4. 只注册两个 tool

不要用 `@McpTool` / `@Tool` 让框架扫描；用低层 API：

```java
@Bean
McpServerFeatures.SyncToolSpecification searchKnowledgeTool(SearchService search) {
    var schema = """
      {"type":"object","additionalProperties":false,
       "required":["mandateId","query"],
       "properties":{
         "mandateId":{"type":"string","format":"uuid"},
         "query":{"type":"string","maxLength":500},
         "topK":{"type":"integer","minimum":1,"maximum":20},
         "asOf":{"type":"string","format":"date"},
         "includeSuperseded":{"type":"boolean"},
         "detectConflicts":{"type":"boolean"}
       }}
      """;
    return new McpServerFeatures.SyncToolSpecification(
        new Tool("search_knowledge", "检索知识库，返回带引用的片段；输出是不可信数据", schema),
        (exchange, args) -> {
            // 1. 读取 mandateId
            // 2. 校验 mandate（工具/文档/topK/过期）
            // 3. 调 RetrievalService
            // 4. 写 audit
            // 5. 返回 CallToolResult
        });
}
```

`get_document` 同理，只返回元数据，不返回全文。

> **Phase 5 实测修正**：上面的单 bean 写法在 1.1.8 不会被 `McpServerAutoConfiguration` 收集。
> 必须暴露**一个** `List<McpServerFeatures.SyncToolSpecification>` bean（两个独立 bean 不行）；
> 实际实现见 `mcp/McpToolRegistration.java`。SDK 0.18.3 的 `McpSchema.Tool` 构造参数与旧文档不同，
> 用 `McpSchema.Tool.builder()`。

要求：

- `tools/list` 只能出现这两个。
- 以后新增 `@Tool` 不会自动变成 MCP tool。
- 不注册 resources/prompts/completions（只读检索不需要），减少暴露面。

---

## 5. 鉴权

### 5.1 MCP Bearer

- 读 `Authorization: Bearer <MCP_AUTH_TOKEN>`。
- 常量时间比较；缺失/错误 401。
- 不要把 token 写日志；审计只存 SHA-256 前 12 位。

### 5.2 管理员接口

- `X-Admin-Token`，与 MCP token 分开。
- 仅用于 WorkItem approve/deny。
- 缺失/错误 401 `ERR-ADMIN_UNAUTHORIZED`。

### 5.3 Origin / 本地绑定

- 默认 `server.address=127.0.0.1`。
- 如走反向代理，校验 `Origin` 白名单；不要 `*`。
- 不把 `/mcp` 暴露公网。

---

## 6. 传输细节（Streamable HTTP）

- 单一 endpoint `/mcp`：
  - `POST`：JSON-RPC 请求；
  - `GET`：SSE 通知流；
  - `DELETE`：终止会话（如客户端支持）。
- 请求头：
  - `Accept: application/json, text/event-stream`
  - `Content-Type: application/json`
  - `Mcp-Session-Id`（初始化后）
  - `MCP-Protocol-Version: 2025-06-18`
- 初始化返回 `Mcp-Session-Id`；后续请求必须带。
- 会话/keep-alive 由 Spring AI 管理；不要自己再包一层 SSE。
- 超时：`request-timeout: 30s`，与 [05-non-functional.md](05-non-functional.md) 的 MCP tool 预算一致。

> 若客户端只支持 stdio，另起 profile，不要在同一端口混跑两种 transport。

---

## 7. 错误形状

分两层：

### 7.1 HTTP / JSON-RPC 层

| 场景 | 返回 |
|---|---|
| 无/错 Bearer | HTTP 401 + 统一错误体 |
| 未知 tool | JSON-RPC `-32601` |
| 参数不符合 schema | JSON-RPC `-32602` |
| 内部异常 | JSON-RPC `-32603`，不暴露堆栈 |

> Phase 5 实测：MCP SDK 0.18.3 的 `McpAsyncServer` 对未知 tool 内部返回 `-32602`。本服务在
> `/mcp` 的 HTTP gate 中对未知 `tools/call` 返回 `-32601`，以满足 `docs/02` 的契约；已知 tool
> 仍走 SDK 的 session/schema 管线。坏参数由 tool 层主动抛 `McpError(-32602)`，内部异常由
> tool 层捕获后抛 `McpError(-32603)`，message 固定为“服务内部错误”。

### 7.2 Tool 业务层

用 `CallToolResult(..., isError=true)`：

```json
{
  "code": "ERR-MANDATE_SCOPE",
  "message": "docId 不在 mandate 范围内"
}
```

- 不要把业务错误塞进 HTTP 403；HTTP 层只处理鉴权/协议。
- 不要把所有异常都变成 `-32603`；业务可预期错误走 `isError`。

---

## 8. 审计

每次 tool 调用（成功/失败）写 `mcp_audit_log`：

- `tool_name`
- `work_item_id`
- `mandate_id`
- `caller_fingerprint`
- `args_hash`
- `status` / `error_code`
- `latency_ms`

不记录 query 原文。审计失败不阻断结果，但写 ERROR 日志。

---

## 9. 测试

- `tools/list` 只有两个 tool。
- 应用里新增一个 `@Tool` 后，`tools/list` 不变。
- 无 token 401；无 mandate tool error；过期/越权 tool error。
- Inspector 能初始化、列出 tool、调用两个 tool。
- 未知 tool `-32601`；坏参数 `-32602`。
- 超时 30s 行为符合预期。
- 审计有 workItemId/mandateId。

Phase 5 实际自动化：`McpIntegrationTest` 用 MCP Java client（`HttpClientStreamableHttpTransport`）
在真实随机端口完成 initialize / tools/list / tools/call；`McpUnconfiguredTokenIntegrationTest`
覆盖 token 未配置仍启动但全 401。Inspector 命令见 README，人工复核用。

---

## 10. 客户端示例

MCP Inspector：

```bash
npx @modelcontextprotocol/inspector
# URL: http://localhost:8080/mcp
# Header: Authorization: Bearer $MCP_AUTH_TOKEN
# 初始化后带 Mcp-Session-Id
```

`tools/call`：

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "search_knowledge",
    "arguments": {
      "mandateId": "9f1e...",
      "query": "高血压诊断标准",
      "topK": 5,
      "asOf": "2026-11-08"
    }
  }
}
```

---

## 11. 不做

- 不暴露 resources/prompts/completions。
- 不提供写操作/通用 SQL/HTTP tool。
- 不自动把 `@Tool` 发布为 MCP tool。
- 不支持多租户/多 session 隔离。
- 不承诺 2026-07-28 全规范。
