# Data Model: Tool 体系与 MCP（第20节）

本节**无新表、无 schema 变更**（宪法 7：审计走 `tool_invocations` 既有路径，零新增逻辑）。数据模型集中在三个既有/新增的结构上：注册面（内存）、MCP server 配置（文件）、Profile.tools 消费关系。

## 1. ToolRegistry（内存，进程生命周期）

| 属性 | 类型 | 约束 |
|------|------|------|
| tools | `Map<String, YokeTool>`（LinkedHashMap，插入序） | 键 = `tool.getName()`；重名注册抛 `IllegalStateException`（消息含名字）；`asMap()` 返回不可变快照 |

**注册路径两条**：
- `register(YokeTool)`：直接实现（`NotifyTools`）与 `McpToolAdapter` 走此路；
- `registerAnnotated(Object bean)`：`@Tool` 注解 Bean（`FileTools`/`ShellTools`/`HttpTools` 与业务方方式三）——`MethodToolCallbackProvider` 生成 ToolCallback 数组，逐个经 `AnnotatedToolAdapter` 包装注册。

**查询**：`contains(name)` / `get(name)→Optional` / `all()→不可变列表` / `filterByNames(names)→声明顺序的结果列表`（未知名跳过、结果恰好等于声明∩注册面）。

**生命周期**：启动时一次性填充（静态注册 + MCP connectAll），进程内只读快照——无热注册、无注销（扩展阶段按需加载再议）。

## 2. McpServerConfig（`.yokeos/mcp_servers.yaml` 条目 → record）

| 字段 | 类型 | 约束与缺省 |
|------|------|-----------|
| name | String | server 名，日志点名用；无唯一性约束（重复条目各自独立连接） |
| transport | String | 第一阶段仅 `stdio`；其他值（sse 等）跳过并 WARN |
| command | String | 启动命令字符串，按空白拆 argv（research D7）；凭证不内联 |
| env | `Map<String, String>` | 值支持 `${ENV}` 占位；缺失环境变量保留原样 + WARN（连接时由对端失败兜底，加载层不阻断） |

**文件级语义**：文件缺失 = 零 server；解析失败（非 YAML/结构不对）= 零 server + WARN；顶层结构 `servers: [ ... ]` 列表（空列表合法）。

**init 模板**（幂等不覆盖）：注释说明 + `servers: []` 占位。

## 3. Profile.tools 消费关系（既有字段，本节激活消费）

```
AGENT.md frontmatter tools: [read_file, http_get, ...]
        │ (16 节 AgentLoader 派生，既有)
        ▼
Profile.tools : List<String>（不可变）
        │
        ├── PromptBuilder.availableTools（17 节就位）：knownTools.get(name)，null 跳过
        │       → 点名过滤结果 = 声明 ∩ 注册面（恰好，不多不少）
        │
        └── AgentLoader.loadAll(…, knownToolNames)（本节拍板⑥）：
                点名 ∉ knownToolNames → WARN（含 Agent 名 + 工具名），不阻断派生
```

`Profile.mcpServers` 字段本节**不消费**（16 节建全、29 节目录语义完整消费；本节 MCP 工具为实例级全局注册面）。

## 4. 工具调用审计（既有 `tool_invocations`，零变更）

每次调用一行最终态：`session_id / tool_name / input_json / result_json / success / error_message / duration_ms`。本节新增的全部工具（含 MCP 转发）经 `ToolExecutor` 既有路径自动落账——MCP 调用的 `input_json` 是模型给出的原始参数、`result_json` 是 TextContent 拼接文本或错误信息。

## 5. 内置工具入参 schema（注册面契约，@Tool 自动生成的目标形态）

| 工具 | 入参 | 出参（成功） |
|------|------|-------------|
| `read_file` | `{path: string}`（必填） | 文件文本（>8000 字符截断+注明总长） |
| `write_file` | `{path: string, content: string}`（均必填） | `已写入: <path>` |
| `list_dir` | `{path: string}`（必填） | 条目名逐行（排序稳定） |
| `shell` | `{command: string[]}`（必填，argv 数组；空数组/空白元素报错） | stdout 文本 |
| `http_get` | `{url: string}`（必填） | 响应正文（>8000 字符截断） |
| `http_post` | `{url: string, body: string}`（均必填，body 为 JSON 字符串） | 响应正文（>8000 字符截断） |
| `notify` | `{content: string, channel?: string}`（19 节既有，本节入册） | `已推送` |

schema 有效性由 `YokeToolContractTest` 参数化兜底（含 properties 定义）。
