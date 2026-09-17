# Research: Tool 体系与 MCP（第20节）

Phase 0 产出。核心问题只有一个：**参照课程期（Spring AI 1.0.0-M6 / MCP SDK 0.x）与本地 1.1.8 / 1.1.1 的 API 代差**——全部经本地依赖 `javap`/`unzip` 实证（H3 纪律），核实时间 2026-09-17。

## D1: MCP SDK 坐标与版本——`io.modelcontextprotocol.sdk:mcp:1.1.1` 显式钉版

**Decision**: 根 pom `dependencyManagement` 显式钉 `io.modelcontextprotocol.sdk:mcp:1.1.1`；yokeos-tool 引用（无版本号）。

**Rationale（实证）**:
- spring-ai-bom 1.1.8 的 pom **不含** `io.modelcontextprotocol.sdk` 的版本管理（grep 零命中、无 import 段）——「BOM 管版本」的假设不成立，必须显式钉版。
- 本地 `~/.m2/repository/io/modelcontextprotocol/sdk/mcp/1.1.1/mcp-1.1.1.jar` 是**聚合件**（jar 仅 META-INF/pom，实体类在传递依赖 `mcp-core:1.1.1`），其 pom 声明依赖 `mcp-core:1.1.1` + `mcp-json-jackson3:1.1.1`——开箱即用（JsonMapper 装配好），参照（spring-ai-mcp 路线）最终也落在同一组实体类上。
- **双 Jackson 并存论证**：mcp-json-jackson3 属 Jackson 3（`tools.jackson.*` 独立包名、独立坐标），与仓内 Spring Boot 3.5 自带的 Jackson 2（`com.fasterxml.*`）零包名冲突、零版本冲突；YokeOS 代码不 import 它（SDK 内部 JSON-RPC 序列化用）。备选「mcp-core + mcp-json-jackson2」被否：本地 mcp-json-jackson2 仅有 0.18.3（与 1.1.1 不配套），自己适配 mapper 工程量与风险都大于并存一套 jackson3。

**Alternatives**: `spring-ai-mcp`（Spring AI 集成层，参照路线）——多一层 Spring 集成件、与本仓「直接 SDK」的技术栈表口径（CLAUDE.md「MCP | MCP Java SDK」）不符，否；SDK 降 0.18.3——旧版倒退，否。

## D2: `@Tool` → ToolCallback 管道——`MethodToolCallbackProvider`（1.1.8 代差实锤）

**Decision**: `ToolRegistry.registerAnnotated(bean)` 内部用 `MethodToolCallbackProvider.builder().toolObjects(bean).build().getToolCallbacks()` 遍历注册。

**Rationale（实证）**:
- 参照的 `org.springframework.ai.tool.ToolCallbacks.from(bean)` 在 1.1.8 **不存在**——15 个本地 spring-ai 1.1.8 jar 全量 `unzip -l | grep tool/ToolCallbacks` 零命中（M6 时代 API）。
- 1.1.8 等价物（spring-ai-model jar 内，javap 签名）：
  - `org.springframework.ai.tool.method.MethodToolCallbackProvider implements ToolCallbackProvider`：`static Builder builder()`、`ToolCallback[] getToolCallbacks()`；
  - `Builder.toolObjects(Object...)` → `build()`；
  - `@Tool`/`@ToolParam` 注解在 `org.springframework.ai.tool.annotation`（spring-ai-model）。
- `ToolCallback` 契约（17 节已用，javap 复核）：`getToolDefinition()` → `ToolDefinition`（name/description/inputSchema）+ `call(String)`。

**Alternatives**: 无（这是 1.1.8 唯一正路；`FunctionToolCallback` 是函数式路线，不适合注解 Bean）。

## D3: `McpSchema.Tool` 七参 record + builder（三参构造代差实锤）

**Decision**: 测试构造 MCP 工具规格用 `McpSchema.Tool.builder().name(..).description(..).inputSchema(mapper, "{\"type\":\"object\"}").build()`。

**Rationale（实证，mcp-core-1.1.1 javap）**:
- `McpSchema$Tool` 是 record，**七参构造** `(String name, String title, String description, JsonSchema inputSchema, Map outputSchema, ToolAnnotations annotations, Map meta)`——参照课件 `new McpSchema.Tool(name, desc, "{}")` 三参构造不存在（0.x 形态）。
- `Tool$Builder` 有 `name/title/description/inputSchema(JsonSchema)/inputSchema(McpJsonMapper, String)/...` 链式方法——`inputSchema(mapper, json)` 便捷重载正好用于测试；生产路径不构造 Tool（从 `listTools()` 结果直读）。
- `CallToolRequest(String, Map<String,Object>)` 双参构造存在（arguments 直传）；`CallToolResult(List<Content>, Boolean isError, ...)` record + builder；`ListToolsResult` 同族。

## D4: `McpSyncClient` 同步门面 + reactor-core 传递依赖论证

**Decision**: 用 `McpClient.sync(new StdioClientTransport(params)).requestTimeout(Duration.ofSeconds(30)).build()` 构造；`initialize()`/`listTools()`/`callTool()` 全同步阻塞。

**Rationale（实证，mcp-core-1.1.1 javap）**:
- `McpClient$SyncSpec` 有 `requestTimeout(Duration)`（30 秒默认拍板落点）与 `build()`；`McpSyncClient.initialize()/listTools()/listTools(cursor)/callTool(CallToolRequest)/close()/closeGracefully()` 全部无 Reactor 类型返回。
- `ServerParameters$Builder`（`builder(cmd).args(..).env(..)`）与 `StdioClientTransport` 均在 `io.modelcontextprotocol.client[.transport]`。
- **宪法 4 论证**：mcp-core pom 传递依赖 `reactor-core`（SDK 内核实现件）；YokeOS 代码零 Reactor 类型（验收 grep `import reactor` 于 yokeos-* src 零命中）。宪法 4 禁的是业务代码引入异步编程模型，不是 classpath 上存在 reactor（同性质先例：Boot 自身也带 reactor 依赖树分枝）。`McpSyncClient` 正是为同步使用场景提供的官方门面。

**Alternatives**: `McpAsyncClient`——异步模型直接违宪，否。

## D5: MCP 重名单工具容错实现形态（clarify B）

**Decision**: `ToolRegistry.register` 重名仍抛 `IllegalStateException`（注册表面语义不变）；`McpClientService.connectAll` 的**单工具注册**用独立 try-catch 包裹，捕获注册异常记 WARN 点名跳过，继续该 server 其余工具。

**Rationale**: spec clarify B（2026-09-17 用户拍板）——「外部依赖的局部问题不连坐」；参照实现因 try 粒度包整个 server 会导致重名连坐（同 server 后续工具全部丢注册），属「瑕疵不继承」（宪法 9）。连接/初始化/listTools 失败仍按 server 粒度隔离（整 server 工具本来就拿不到）。

## D6: MCP 请求超时 30 秒 + shell 超时 30 秒

**Decision**: MCP `requestTimeout(Duration.ofSeconds(30))`；`ShellTools` 超时默认 30 秒（`Duration` 构造可注入，测试用毫秒级）。

**Rationale**: MCP 对端挂起不拖死启动与循环（参照 REQUEST_TIMEOUT 同值）；shell 30 秒是参照 clarify 既定默认。配置化（配置键暴露）随 24 节白名单配置一并处理，本节构造注入即可。

## D7: `mcp_servers.yaml` 的 `command` 字符串按空白拆 argv

**Decision**: `command: npx -y server-github` 字符串形态，`connectStdio` 按 `\\s+` 拆分为 argv 传 `ServerParameters`（照参照）。

**Rationale**: 与 YAML 书写习惯一致、参照同构。局限显式注明：含空格的单参数不可表达（如 Windows 路径带空格）——凭证一律走 `env` 段 `${ENV}` 占位不走 command 内联，第一阶段场景够用；数组形态留扩展。

## D8: `read_file` 8000 字符截断（实施偏差：参照无此防护）

**Decision**: `read_file` 超过 8000 字符截断并在尾部注明总长（与 17 节 `http_get`、本节 `HttpTools` 同款常量与措辞）。

**Rationale**: 超大文件全文回填一轮撑爆上下文——参照 `read_file` 无截断是其缺口，本仓补上（「结构照抄，瑕疵不继承」）；数值与措辞复用 17 节既有口径（`MAX_BODY_CHARS = 8000`）。记验收报告实施偏差。

## D9: `AgentLoader` 点名 WARN 的注入形态

**Decision**: `AgentLoader.loadAll` 增参 `Set<String> knownToolNames`（缺省空集 = 不校验，既有调用与测试零改动）；点名了但集合没有的名字记 WARN（含 Agent 名与工具名），不阻断派生。

**Rationale**: 拍板⑥兑现 17 节 `PromptBuilder` 注释「注册校验归 20 节 ToolRegistry」的预告；core 不能依赖 tool 模块（依赖方向），校验面（静态注册名集合）由装配层（YokeosRuntime/测试）传入。MCP 动态工具不在静态面——server 失联时点名其工具也会 WARN，语义自然（启动日志看得见）。

**Alternatives**: 校验挪到 `ProfileRegistry.register`——职责混杂（注册表管注册不管配置校验），否；YokeosRuntime 启动后扫一遍——时序绕，否。

## D10: `tool list` 静态注册面构造（轻命令零 Spring）

**Decision**: `ToolListCommand.listTools()` 手动构造静态注册面：`new ToolRegistry()` + `registerAnnotated(new FileTools()/new ShellTools()/new HttpTools())` + `register(new NotifyTools(Map.of("webhook", new WebhookNotifyAdapter())))`，遍历输出名+描述；尾注「MCP 工具随 chat/serve 启动注册，此处不列」。

**Rationale**: 18 节注释预告「20 节改查注册表」的唯一诚实口径延续——清单来自真实工具实例（名/描述非手写字符串），新工具自动出现；零 Spring 启动开销（18 节坑二口径）。与 `YokeosRuntime.tools()` 的装配意图本就不同（静态面 vs 完整面含 MCP），~6 行构造各自持有、注释互指，不抽共享工厂（避免为去重引新概念）。`WebhookNotifyAdapter` 构造零网络副作用（HttpClient 惰性连接），轻命令安全。

## 汇总：needs clarification 清零状态

Technical Context 无 NEEDS CLARIFICATION 残留——D1~D4（依赖坐标/注解管道/record 构造/同步客户端）全部本地实证，D5~D10 为设计决策点（拍板链：spec clarify B + 教学文档拍板①~⑦）。
