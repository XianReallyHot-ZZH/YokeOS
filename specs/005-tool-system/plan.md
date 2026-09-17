# Implementation Plan: Tool 体系与 MCP——Agent 能干事的手（第20节）

**Branch**: `specs/005-tool-system` | **Date**: 2026-09-17 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/005-tool-system/spec.md`

## Summary

第 20 节交付 Tool 体系与 MCP：`ToolRegistry` 统一注册表（三来源汇合、重名拒绝、按 Profile.tools 过滤）+ `AnnotatedToolAdapter` 注解管道机制本体（`MethodToolCallbackProvider` 生成 ToolCallback、包装成 `YokeTool`，`call()` 进程内直调，执行发起方永远是 `ToolExecutor`——宪法 2）+ 内置工具六件（`FileTools` 三方法、`ShellTools` argv 直传、`HttpTools` 两方法——`HttpGetTool` 演进退役）+ MCP 子包四件（`McpServerConfig`/`McpConfigLoader`/`McpClientService`/`McpToolAdapter`，stdio 唯一、失联 WARN 隔离、单工具重名容错不连坐）；接线三处（`YokeosRuntime.tools()` 换 Registry 来源、`ToolListCommand` 改查注册表、`InitCommand` 补 `mcp_servers.yaml` 模板）+ `AgentLoader` 点名 WARN。审计零新增逻辑；六件工具每方法首行 Sandbox 检查位注释留 24 节（拍板①）。

## Technical Context

**Language/Version**: Java 21（虚拟线程处理并发）

**Primary Dependencies**: Spring Boot 3.5.16；**新增两件（软门禁⑥，教学文档拍板⑤）**——① `org.springframework.ai:spring-ai-model`（BOM 管版本 1.1.8；`@Tool`/`@ToolParam` 注解 + `MethodToolCallbackProvider`——**API 代差已 javap 实证**：参照 M6 时代的 `ToolCallbacks.from(bean)` 在 1.1.8 不存在，等价物 `MethodToolCallbackProvider.builder().toolObjects(bean).build().getToolCallbacks()`，见 [research.md](./research.md) D2）；② `io.modelcontextprotocol.sdk:mcp:1.1.1`（**BOM 不管此坐标，根 pom 显式钉版**；聚合件传递 `mcp-core` + `mcp-json-jackson3`——jackson3 独立包名 `tools.jackson.*` 与仓内 Jackson 2 零冲突并存，YokeOS 代码不碰它，见 research D1）；`McpSyncClient` 全同步门面（initialize/listTools/callTool，宪法 4 合规；mcp-core 传递 reactor-core 属 SDK 内核实现件，YokeOS 代码零 Reactor 类型，research D4）；JDK `java.net.http.HttpClient`（HttpTools，17 节口径）与 `com.sun.net.httpserver.HttpServer`（仅测试假 server，19 节先例）；SnakeYAML（mcp_servers.yaml）；Jackson（`JsonNode` 入参，既有）；SLF4J（WARN 日志编译期常量）

**Storage**: 无新表、无 schema 变更——`tool_invocations` 既有路径承载审计（宪法 7 零新增审计逻辑）；文件系统——`.yokeos/mcp_servers.yaml`（init 幂等补建注释模板）；`AGENT.md` frontmatter `tools`/`mcp_servers` 字段 16 节已建全，本节起消费 `tools`

**Testing**: JUnit 5 + Mockito 单测主体（mock `McpSyncClient`、`@TempDir` 真文件、JDK HttpServer 假 server，不碰真实网络与外部进程）：七个新测试类逐条对应验收点（教学文档第四部分）——`YokeToolContractTest`（参数化遍历注册面：契约三件套 + schema 含 properties）、`ToolRegistryTest`（三来源/重名拒绝/过滤恰好）、`FileToolsTest`、`ShellToolsTest`、`HttpToolsTest`、`McpClientServiceTest`（失联隔离最值钱 + 配置解析四态 + 单工具重名不连坐）、`McpToolAdapterTest`（三要素映射/参数原样转发/isError 可重试）；`AgentLoaderTest` 补点名 WARN 用例（ListAppender，19 节先例）；`ProviderToolListCommandTest` 断言更新（七件全量）；`YokeosRuntimeAssemblyTest` 装配面断言更新；boot 集成冒烟 `ToolMcpSmokeIntegrationTest`（真 stdio echo server + 真模型，`@Tag("integration")` 默认排除，环境缺失 assumeTrue 跳过）。白名单拦截用例与 InOrder 回归留 24 节（File/Shell/Http 三个测试类文件头注释注明待补）。测试方法名英文、`@DisplayName` 保留教学文档中文语义。完成定义 = `mvn clean verify` 九模块全绿（19 节基线 + 本节新增，前序零回归）

**Target Platform**: JVM（macOS + Windows 双平台开发环境；shell 工具测试用跨平台命令——`echo`/`sleep` 两侧皆可，Windows 侧 sleep 换 timeout 的兼容问题在 ShellToolsTest 用 `ping`/`sleep` 双分支或仅 POSIX 断言，实现时按 CI 实测调整）

**Project Type**: Maven 多模块 library + cli（本节触四模块：yokeos-tool / yokeos-core / yokeos-cli / yokeos-boot）

**Performance Goals**: 按需求文档 §8 全局口径，本节不设独立指标；MCP 请求超时默认 30 秒（对端挂起不拖死启动与 ReAct 循环，research D6）；shell 超时默认 30 秒；HTTP 连接/读取超时 10 秒（17 节口径）

**Constraints**: 宪法 2（注解管道只借 schema 生成；`callback.call()` 是进程内直调不是框架自动执行；`internalToolExecutionEnabled(false)` 16 节已钉，本节零 `ChatClient` 用法）、宪法 4（`McpSyncClient`/`HttpClient.send` 同步阻塞；无 Reactor/CompletableFuture 业务代码）、宪法 5（全部 Tool 相关落 yokeos-tool 一个模块不拆）、宪法 7（零新增审计逻辑）、语法禁区——避开 P3C/ASM 解析不了的 Java 18+ 语法形态；测试类名 ≤1 连续大写（冒烟类 …IntegrationTest）；日志消息编译期常量（CRLF 注入门禁），动态内容进异常堆栈；`ToolExecutor` 只 catch `RuntimeException`——工具内 checked `IOException` 包 `UncheckedIOException`（19 节 research D1 同款）

**Scale/Scope**: 注册面工具个位数到两位数；MCP server 个位数；无热注册（注册面启动时快照）；单工具调用结果截断保护上限 8000 字符

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | 原则 | 本节落点 | 状态 |
|---|------|---------|------|
| 1 | 自实现 ReAct 循环 | 不动循环；六件工具 + MCP 工具全部经既有 `YokeTool`/`ToolExecutor` 执行，异常转失败结果不炸循环 | ✓ |
| 2 | Spring AI 只用两件事 | 本节启用第二件事：`@Tool` schema 生成。`MethodToolCallbackProvider` 只产出 ToolCallback（schema 载体）；`AnnotatedToolAdapter.execute` 里 `callback.call()` 是**进程内直调**（方式三语义本体），执行发起方永远是 `ToolExecutor`，不存在框架自动执行路径；零 `ChatClient` 用法；不引入任何带 autoconfigure 的 starter（spring-ai-model 是裸库） | ✓ |
| 3 | Provider 显式映射 | 不涉及（无 ChatModel 交互；既有 providerMap 不动） | ✓ |
| 4 | 同步执行 + 虚拟线程 | `McpSyncClient`（initialize/listTools/callTool 同步阻塞门面）+ JDK `HttpClient.send` 同步；YokeOS 代码零 Reactor 类型——mcp-core 传递 reactor-core 属 SDK 内核实现件（类路径存在但业务代码不 import，grep 验证），与「Exception 25 节调度线程池」同性质的传递依赖论证，research D4 | ✓ |
| 5 | Tool 三合一 | `ToolRegistry`/`AnnotatedToolAdapter`/`FileTools`/`ShellTools`/`HttpTools`/mcp 子包四件全落 yokeos-tool 一个模块，不拆 | ✓ |
| 6 | Sandbox 接口先行 | 本节不建任何 Sandbox 概念；六件工具每方法首行检查位注释钉死 `sandbox.enforce(new SandboxAction(<TYPE>, ...))` 调用形态，24 节接线（拍板①，17/19 节同款先例） | ✓ |
| 7 | SQLite + 审计 day one | 零新增审计逻辑：全部工具调用经 `ToolExecutor` 既有路径落 `tool_invocations`；MCP env 凭证 `${ENV}` 占位缺失保留原样 WARN（不明文展开） | ✓ |
| 8 | 一个目录 = 一个 Agent | `tools` 是 frontmatter→Profile 派生字段的消费（点名过滤 + 存在性 WARN）；AGENT.md 仍归 ContextLoader 体系，不进 Tool 模块；Skill 不进注册表 | ✓ |
| 9 | 结构照抄，瑕疵不继承 | 落位镜像参照（builtin 子包 + mcp 子包同构）；四处显式偏差记录——argv 数组直传替 bash -c 拼接（拍板③，采信技 §6.2）、`MethodToolCallbackProvider` 替 `ToolCallbacks.from`（1.1.8 代差，D2）、read_file 8000 截断（参照无此防护，本仓补）、MCP 重名单工具容错不连坐（clarify B，参照整 server 中断属瑕疵不继承）；参照 tool list 硬编码不查注册表的瑕疵按 18 节预告补上（查注册表） | ✓ |

Phase 1 设计后复检：无新增违规（见 Complexity Tracking——空）。

## Project Structure

### Documentation (this feature)

```text
specs/005-tool-system/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output（API 代差 javap 实证 D1~D10）
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   ├── tool-registry.md # 注册表公共 API + 内置工具契约
│   └── mcp.md           # mcp_servers.yaml 格式 + MCP 注册/调用语义
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
pom.xml                                            # 修改：dependencyManagement 显式钉 io.modelcontextprotocol.sdk:mcp:1.1.1（BOM 不管，research D1）

yokeos-tool/pom.xml                                # 修改：+ spring-ai-model（BOM 管版本）+ io.modelcontextprotocol.sdk:mcp + snakeyaml（测试已有则不动）
yokeos-tool/src/main/java/com/yokeos/tool/
├── ToolRegistry.java                              # 新增：register/registerAnnotated/contains/get/all/asMap/filterByNames；重名 IllegalStateException 点名
├── AnnotatedToolAdapter.java                      # 新增：ToolCallback→YokeTool；getToolDefinition() 三要素；call() 直调、异常不 catch（坑一）
├── builtin/
│   ├── FileTools.java                             # 新增：@Tool read_file（8000 截断）/write_file（建父目录）/list_dir（排序）；
│   │                                              #   每方法首行 Sandbox 检查位注释（FILE_READ/FILE_WRITE，24 节接线）
│   ├── ShellTools.java                            # 新增：@Tool shell——argv 数组直传 ProcessBuilder、超时 30s destroyForcibly、
│   │                                              #   非零退出带 stderr；检查位注释（SHELL_COMMAND）；COMMAND_INJECTION 类级抑制+javadoc 理由
│   └── HttpTools.java                             # 新增：@Tool http_get（17 节逻辑迁入）+ http_post（JSON body）；JDK HttpClient、
│                                                   #   8000 截断、超时 10s；检查位注释（HTTP_REQUEST）
├── HttpGetTool.java                               # 删除：演进并入 HttpTools（17 节注释预告）
└── mcp/
    ├── McpServerConfig.java                       # 新增：record(name, transport, command, env)（compact ctor 防御副本）
    ├── McpConfigLoader.java                       # 新增：SnakeYAML 读顶层 servers 列表；${ENV} 占位缺失保留+WARN；
    │                                              #   文件缺失/解析失败→零 server WARN
    ├── McpClientService.java                      # 新增：clientFactory 构造注入（测试替身）；stdio 唯一其余 WARN 跳过；
    │                                              #   connectAll：initialize+listTools+逐工具注册；连接失败 WARN 隔离；
    │                                              #   单工具注册失败（含重名）WARN 跳过不连坐（clarify B）
    └── McpToolAdapter.java                        # 新增：三要素映射；callTool 参数原样转发；TextContent 拼接；
                                                   #   isError=true→ToolResult.error(retryable=true)

yokeos-tool/src/test/java/com/yokeos/tool/
├── YokeToolContractTest.java                      # 参数化遍历注册面（MethodSource 与装配同款）：三件套非空非空白+schema 含 properties
├── ToolRegistryTest.java                          # 三来源替身（直接实现/@Tool bean/MCP adapter mock）/重名拒绝/过滤恰好
├── builtin/FileToolsTest.java                     # 读/写/列正常+回读+父目录+不存在点名+截断；文件头注明 Sandbox 拦截留 24 节
├── builtin/ShellToolsTest.java                    # stdout/非零退出带 stderr/挂死超时终止；文件头注明拦截留 24 节
├── builtin/HttpToolsTest.java                     # JDK HttpServer 假 server：GET/POST body 与 Content-Type/4xx/5xx/截断；注明拦截留 24 节
└── mcp/
    ├── McpClientServiceTest.java                  # 失联隔离（最值钱）/listTools 注册/配置四态/非 stdio 跳过/单工具重名不连坐
    └── McpToolAdapterTest.java                    # 三要素/ArgumentCaptor 参数原样/isError 可重试（McpSchema.Tool 用 builder 构造，D3）

yokeos-core/src/main/java/com/yokeos/core/profile/
└── AgentLoader.java                               # 修改：loadAll 增参已知工具名集合（缺省空集=不校验，兼容既有调用）；
                                                   #   tools 点名不存在→WARN 不阻断（拍板⑥；core 不依赖 tool 模块，校验面由调用方传入）
yokeos-core/src/test/java/com/yokeos/core/profile/
└── AgentLoaderTest.java                           # 补点名 WARN 用例（ListAppender；点名未注册名→派生成功+日志点名）

yokeos-cli/src/main/java/com/yokeos/cli/
├── YokeosRuntime.java                             # 修改：tools() 唯一替换点——构造 ToolRegistry（registerAnnotated 三组内置
│                                                  #   + register NotifyTools + mcpClientService.connectAll），asMap() 喂两消费方
├── InitCommand.java                               # 修改：工作区模板补 mcp_servers.yaml（注释模板，幂等不覆盖）
└── command/ToolListCommand.java                   # 修改：listTools() 改查静态注册面（手动构造三组内置+notify，零 Spring；
                                                   #   MCP 动态工具注明随重命令启动注册不列；与 YokeosRuntime 构造注释互指）
yokeos-cli/src/test/java/com/yokeos/cli/
├── InitCommandTest.java                           # 补 mcp_servers.yaml 幂等用例
├── command/ProviderToolListCommandTest.java       # 断言更新：七件全量+MCP 注记
└── YokeosRuntimeAssemblyTest.java                 # 装配面断言更新：注册面工具数≥7、HttpGetTool 退役零引用

yokeos-boot/src/test/java/com/yokeos/boot/
└── ToolMcpSmokeIntegrationTest.java               # @Tag("integration")：真 stdio echo MCP server + 真模型点名调用；
                                                   #   ReActSmoke/NotifyE2E 框架同款；assumeTrue 环境守卫
```

**Structure Decision**: 九模块既有骨架内落位，本节触 tool / core / cli / boot 四模块 + 根 pom。依赖方向不变：tool → core（消费 `YokeTool`/`ToolResult`，既有）+ tool → spring-ai-model / mcp（新增，宪法 2/5 论证见 Check）；cli 装配（既有）+ boot 冒烟（既有模式）。`builtin` 子包镜像参照 `io.oryxos.tool.builtin`；mcp 子包镜像 `io.oryxos.tool.mcp`；`ToolRegistry`/`AnnotatedToolAdapter` 在 tool 包根镜像参照。`HttpGetTool` 退役是本节唯一前序类删除（17 节注释预告的改造点），引用方（YokeosRuntime/相关测试）同节更新，19 节基线测试零回归是门禁。

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

无违规，无条目。
