# Contract: MCP 接入（第20节）

对外契约：`.yokeos/mcp_servers.yaml` 配置格式（业务方书写面）+ MCP 工具的注册与调用语义（使用者预期）。

## 1. `.yokeos/mcp_servers.yaml` 格式

```yaml
servers:
  - name: github-mcp            # 必填：server 名（日志点名）
    transport: stdio            # 可缺省视为 stdio；第一阶段唯一支持值，其他值跳过并 WARN
    command: npx -y server-github   # 必填：启动命令字符串，按空白拆 argv（含空格参数不可表达，凭证走 env）
    env:
      GITHUB_TOKEN: ${GITHUB_TOKEN}  # ${ENV} 占位从环境变量解析；缺失保留原样 + WARN（不阻断加载）
```

**文件级语义**：
- 文件缺失 → 零 server，启动照常；
- 解析失败（非 YAML / 顶层无 `servers` 列表）→ 零 server + WARN，启动照常；
- `servers: []` 空列表合法；
- `yokeos init` 幂等补建注释模板（已存在不覆盖）。

## 2. 注册语义（启动时一次性）

1. 逐 server 读取配置，`transport` 非 stdio → WARN 跳过；
2. 连接（stdio 子进程）+ `initialize()` + `listTools()`——任一步失败 → **WARN 跳过该 server 全部工具**，启动继续（外部依赖的可用性不是自己的可用性）；
3. `listTools()` 结果逐个经 `McpToolAdapter` 包装 `register` 进注册表——与内置工具同名：**单工具 WARN 跳过，该 server 其余工具照常注册**（逐工具容错，clarify B）；
4. MCP 请求超时 30 秒（`requestTimeout`）；注册面为启动时快照，无热发现。

## 3. 调用语义（对话中模型点名）

- 三要素（名/描述/inputSchema）直接来自 `tools/list` 返回；
- 参数**原样**转发（不增不删不改键名）；
- 结果：文本内容（TextContent）逐段拼接回填；非文本内容 `String.valueOf` 兜底；
- server 报错（`isError=true`）→ `ToolResult.error("MCP 调用失败: <内容>", retryable=true)`——网络/限流类瞬态失败值得循环再试；
- 审计：走 `ToolExecutor` 既有路径落 `tool_invocations`（`input_json` = 模型原始参数），零新增逻辑。

## 4. Profile.mcpServers 字段（本节不消费）

frontmatter `mcp_servers` 字段 16 节建全——本节 MCP 工具为**实例级全局注册面**（所有 server 的工具进同一注册表，Agent 经 `tools` 点名取用）；按 Agent 过滤 MCP server 的目录语义归 29 节。
