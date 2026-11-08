# Phase 2 MCP 低层注册 Spike

- 日期：2026-11-09
- 结论：**成功**（在测试 scope 内验证，不产出生产 `/mcp`）
- 时间盒：45 分钟以内完成

## 1. 目标

只验证一个 Phase 5 的前置问题：Spring AI 1.1.8 在
`spring.ai.mcp.server.tool-callback-converter=false` 时，低层
`McpServerFeatures.SyncToolSpecification` 注册的两个 tool 是否会被 auto-config 收集，
并出现在 `tools/list`（服务端 `McpSyncServer.listTools()`）中。

不实现生产 MCP 端点、Bearer、mandate、审计。

## 2. 尝试与结果

| 尝试 | 做法 | 结果 |
|---|---|---|
| 1 | 只加两个 `@Bean SyncToolSpecification`，import 到 `@SpringBootTest` | 失败：两个 bean 都在容器里（`getBeanNamesForType` 能查到），但 `McpSyncServer.listTools()` 为空 |
| 2 | 改为一个 `@Bean List<SyncToolSpecification>` | **成功**：`listTools()` 返回 `search_knowledge`、`get_document` 两个 tool |

## 3. 关键发现

Spring AI 1.1.8 的 `McpServerAutoConfiguration#mcpSyncServer` 注入的是：

```java
ObjectProvider<List<SyncToolSpecification>>
```

实际字节码对它做 `stream().flatMap(Collection::stream)`，即它期待容器里有一个
`List<SyncToolSpecification>` **bean**，而不是两个独立的 `SyncToolSpecification` bean。
只注册独立 bean 时，`ObjectProvider<List<...>>` 取不到 List bean，tools 不会被挂到
`McpServer`，所以 `listTools()` 为空。

`spring.ai.mcp.server.tool-callback-converter=false` 本身是有效的：1.1.8 的
`ToolCallbackConverterAutoConfiguration` 条件注解确实读取该属性；把它设为 false 后，
`@Tool`/`ToolCallback` 的自动转换不会注册，低层 List bean 仍能被 MCP server 收集。

依赖实际版本（由 MCP starter 传递）：

- `org.springframework.ai:spring-ai-mcp:1.1.8`
- `io.modelcontextprotocol.sdk:mcp-core:0.18.3`
- `io.modelcontextprotocol.sdk:mcp-spring-webmvc:0.18.3`

## 4. 证据

测试：`McpRegistrationSpikeTest`
（`src/test/java/com/lqx/ragmcp/integration/McpRegistrationSpikeTest.java`）

```java
List<McpSchema.Tool> tools = mcpSyncServer.listTools();
assertThat(tools).extracting(McpSchema.Tool::name)
        .containsExactlyInAnyOrder("search_knowledge", "get_document");
```

测试使用 `spring-ai-starter-mcp-server-webmvc` **test scope**，生产 classpath 不受影响；
其他集成测试通过 `src/test/resources/application.properties` 把
`spring.ai.mcp.server.enabled` 设为 false，只有本 spike 用 `@DynamicPropertySource` 打开。

## 5. Phase 5 建议

1. 生产配置保持 `tool-callback-converter: false`，并用一个
   `List<SyncToolSpecification>` bean 显式注册两个 tool；不要用两个独立 bean。
2. 不要依赖 `@McpTool`/`@Tool` 自动扫描；`annotation-scanner.enabled=false` 可作为额外防线。
3. HTTP 层 `tools/list` 仍需在 Phase 5 用 MVC 端点/Inspector 验证；本次 spike 只验证
   server 侧注册，不验证鉴权、session、`/mcp` 传输。
4. MCP SDK 0.18.3 的 `McpSchema.Tool` 构造参数与 docs 示例不同，建议用
   `Tool.builder()`，不要照抄旧版构造函数。
5. `mandateId`、Bearer 401、tool error 和审计仍按 docs/02-api-contract.md 3.x 实现。

## 6. 回退

如果后续发现 test-scope MCP starter 影响其他测试，可删除
`pom.xml` 中 test scope 的 `spring-ai-starter-mcp-server-webmvc` 与两个
`Mcp*Spike*` 文件；生产代码没有引入任何 MCP 依赖。
