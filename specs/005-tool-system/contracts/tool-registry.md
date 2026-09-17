# Contract: ToolRegistry 与内置工具（第20节）

对外契约：注册表公共 API（业务方方式三的接入面）+ 内置工具的执行语义（含检查位约定）。消费方：`ToolExecutor`/`PromptBuilder`（既有 Map 形态 `asMap()`）、`YokeosRuntime` 装配、业务方 `@Tool` Bean。

## 1. ToolRegistry 公共 API

```java
public class ToolRegistry {
    void register(YokeTool tool);            // 直接实现/MCP adapter；重名抛 IllegalStateException（消息含名字）
    void registerAnnotated(Object bean);     // @Tool 注解 Bean（内置与方式三共用管道）
    boolean contains(String name);
    Optional<YokeTool> get(String name);
    List<YokeTool> all();                    // 不可变，插入序
    Map<String, YokeTool> asMap();           // 不可变快照——ToolExecutor/PromptBuilder 消费形态
    List<YokeTool> filterByNames(List<String> names);  // 结果恰好 = 声明∩注册面（声明顺序；未知名跳过）
}
```

**不变量**：
- 三种来源（注解/MCP/直接实现）注册后行为无差别——执行、审计、过滤只认 `YokeTool`；
- 注册表进程内启动时填充后只读（无热注册）；
- 重名永不静默覆盖（MCP 侧重名由 `McpClientService` 单工具容错：WARN 跳过不连坐，clarify B）。

## 2. AnnotatedToolAdapter（注解管道机制本体）

- `getName()/getDescription()/getInputSchema()` ← `callback.getToolDefinition()`（schema 为 Spring AI `@Tool` 自动生成——宪法 2 允许的第二件事）；
- `execute(JsonNode)` → `callback.call(input.toString())` **进程内直调**；异常不 catch，上抛给 `ToolExecutor` 统一转 `ToolResult.error` 落审计；
- **禁止**：任何 `ChatClient` 用法、`internalToolExecutionEnabled(true)`、框架自动执行路径（宪法 2；执行发起方永远是 `ToolExecutor`）。

## 3. 内置工具执行语义（六件 + notify 入册）

失败语义统一：确定性失败 `ToolResult.error(原因, retryable=false)`；瞬态失败（网络 IO）`retryable=true`；方法内异常上抛由 `ToolExecutor` 转 `error` 落审计。

| 工具 | 成功 | 确定性失败 | 可重试失败 |
|------|------|-----------|-----------|
| `read_file` | 文件文本（>8000 截断+注明） | 文件不存在/不是普通文件（点名路径）；参数缺失 | — |
| `write_file` | `已写入: <path>`（父目录自动创建） | 参数缺失；IO 失败（UncheckedIOException 上抛） | — |
| `list_dir` | 条目名逐行排序 | 目录不存在（点名路径） | — |
| `shell` | stdout | argv 空/空白元素；非零退出码（消息含退出码+stderr）；超时（消息含秒数）；启动失败 | — |
| `http_get`/`http_post` | 响应正文（>8000 截断） | 4xx/5xx（`HTTP <code>: <url>`）；URL 非法；参数缺失 | 网络 IO（`请求失败: <msg>`） |
| `notify` | `已推送`（19 节语义不变） | 19 节七态（渠道解析等） | — |

**Sandbox 检查位约定（24 节接线前）**：六件工具每个 `@Tool` 方法**第一行**注释钉死：

```java
// Sandbox 检查位：24 节接 sandbox.enforce(new SandboxAction(FILE_READ, path))——不过则异常上抛走既有失败审计
```

四类 ActionType 对应：`read_file`/`list_dir`→FILE_READ、`write_file`→FILE_WRITE、`shell`→SHELL_COMMAND（argv[0] 比对）、`http_get`/`http_post`→HTTP_REQUEST；`notify` 沿用 19 节既有检查位注释。三套白名单共享配置归 24 节。

## 4. 业务方扩展三档（接入面声明）

| 档 | 动作 | 落点 |
|----|------|------|
| 方式一（零代码，主推） | AGENT.md + `mcp_servers.yaml` 配社区 server | MCP 管道自动（本 contracts/mcp.md） |
| 方式二（轻代码） | 自写 MCP server，任意语言 | 同上 |
| 方式三（重代码） | `@Tool` 注解 Spring Bean 放进工程 | `ToolRegistry.registerAnnotated(bean)`——与内置工具写法完全一样 |

选择原则：能用一不用二，能用二不用三。
