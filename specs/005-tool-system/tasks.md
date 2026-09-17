---
description: "Task list for feature implementation"
---

# Tasks: Tool 体系与 MCP——Agent 能干事的手（第20节）

**Input**: Design documents from `/specs/005-tool-system/`（plan.md / research.md D1~D10 / data-model.md / contracts/tool-registry.md + mcp.md / quickstart.md）

**Tests**: TDD 纪律显式启用——测试任务先于或伴随对应实现任务（验收 harness 先行），实现与测试在同一任务内闭环、该模块测试红了当场修；集成冒烟单列。测试方法名英文，教学文档语义以 `@DisplayName` 保留。

**Organization**: 注册面与内置六件（US1，MVP）→ MCP 子包与接线（US2+US3）→ 工具面可查与配置有痕（US4）→ 集成冒烟与收尾。分批明文：① File/Shell/Http 三个测试类的「白名单拦截」用例与 InOrder 顺序回归**留 24 节**（Sandbox 接口未就位，教学文档拍板①），三个测试类文件头注释注明待补；② AnnotatedToolAdapter 的 schema 有效性由 `YokeToolContractTest` 的 properties 断言覆盖，不单开测试类；③ AgentLoaderTest 补点名 WARN 用例（Logback ListAppender，19 节先例）；④ 检查位注释形态一致性由 quickstart 人工核对清单承载（grep 抽查），不进自动化。

## Phase 1: Setup

- [x] T001 记录改造前基线：`mvn test` BUILD SUCCESS 全绿——记录测试数（19 节合流后基线），零 pom 变更确认 ✅ 2026-09-17 基线 **139** 全绿（18 节 125 + 19 节 14）
- [x] T002 依赖落地（H3，research D1）：根 `pom.xml` `dependencyManagement` 显式钉 `io.modelcontextprotocol.sdk:mcp:1.1.1`（spring-ai-bom 不管此坐标，javap/依赖树已实证）；`yokeos-tool/pom.xml` 增 `org.springframework.ai:spring-ai-model`（无版本号走 BOM）+ `io.modelcontextprotocol.sdk:mcp` + `org.yaml:snakeyaml`（新增 compile 依赖，Boot 管版本——analyze F4，现状该模块无 snakeyaml）；验证 `mvn -pl yokeos-tool -am dependency:resolve` 绿——传递件 mcp-core/mcp-json-jackson3/reactor-core 进依赖树（jackson3 独立包名零冲突，research D1/D4 论证）

## Phase 2: US1 统一注册面与内置六件（P1）🎯 MVP

**Goal**: ToolRegistry 三来源汇合 + 注解管道 + 六件内置工具 + 主链路换源（ToolExecutor/PromptBuilder 只换 Map 来源）。
**Independent Test**: `mvn test -pl yokeos-tool -am` 全绿 + `mvn test -pl yokeos-cli -am` 全绿（YokeosRuntimeAssemblyTest 断言注册面 ≥7 件）。

- [x] T003 [P] [US1] ToolRegistryTest：`yokeos-tool/src/test/java/com/yokeos/tool/ToolRegistryTest.java`——替身三来源：直接实现（匿名 YokeTool）/ `@Tool` 注解 bean（静态内部类带 `@Tool` 方法）/ MCP 形态（mock ToolCallback 经 AnnotatedToolAdapter 包装）：①三来源都以 YokeTool 身份注册、`contains`/`get`/`all`/`asMap` 行为正确（`@DisplayName("三种来源的工具都以YokeTool身份注册进来")`）；②重名注册抛 `IllegalStateException` 且消息含名字（`@DisplayName("重名注册被拒绝并点名")`）；③`filterByNames` 结果恰好等于声明∩注册面——多一个（未过滤净）少一个（过滤过头）都断言失败、未知名跳过不报错（`@DisplayName("按Profile的tools字段过滤_子集恰好等于声明列表")`）
- [x] T004 [P] [US1] ToolRegistry：`yokeos-tool/src/main/java/com/yokeos/tool/ToolRegistry.java`——`register(YokeTool)`（LinkedHashMap，重名 `IllegalStateException` 点名）/ `registerAnnotated(Object bean)`（`MethodToolCallbackProvider.builder().toolObjects(bean).build().getToolCallbacks()` 逐个 `register(new AnnotatedToolAdapter(cb))`——research D2，1.1.8 无 `ToolCallbacks.from`）/ `contains` / `get→Optional` / `all()` 不可变 / `asMap()` 不可变快照（ToolExecutor/PromptBuilder 消费形态）/ `filterByNames` 声明顺序未知名跳过
- [x] T005 [P] [US1] AnnotatedToolAdapter：`yokeos-tool/src/main/java/com/yokeos/tool/AnnotatedToolAdapter.java`——`getName()/getDescription()/getInputSchema()` ← `callback.getToolDefinition()`（宪法 2：只借 schema 生成）；`execute(JsonNode)` → `callback.call(input == null ? "{}" : input.toString())` **进程内直调**，异常不 catch 上抛（坑一：adapter 内兜异常会让 tool_invocations 永远记成功）；Javadoc 钉死「执行发起方是 ToolExecutor，不存在框架自动执行路径」
- [x] T006 [P] [US1] FileToolsTest：`yokeos-tool/src/test/java/com/yokeos/tool/builtin/FileToolsTest.java`——`@TempDir` 真文件：①read_file 正常读到内容；②write_file 写入后回读一致且父目录自动创建；③list_dir 列出文件与子目录（排序稳定断言）；④读不存在文件 → `IllegalArgumentException` 消息含路径；⑤read_file 超 8000 字符 → 截断并注明总长（坑五）。**文件头注释注明：白名单拦截用例（Sandbox 拒绝时文件动作零发生）与 InOrder 回归留 24 节**
- [x] T007 [P] [US1] FileTools：`yokeos-tool/src/main/java/com/yokeos/tool/builtin/FileTools.java`——`@Tool(name="read_file")`（Files.readString，超 `MAX_CONTENT_CHARS=8000` 截断+注明总长，与 HttpTools 同款常量语义）/ `@Tool(name="write_file")`（父目录 `Files.createDirectories` + 覆盖写，返回「已写入: path」）/ `@Tool(name="list_dir")`（Files.list 排序 joining）；每方法**第一行 Sandbox 检查位注释**（read/list→`FILE_READ`、write→`FILE_WRITE`，24 节接线）；不存在/非法 → `IllegalArgumentException` 点名路径；checked IOException 包 `UncheckedIOException`（ToolExecutor 只 catch RuntimeException，19 节 research D1 同款）
- [x] T008 [P] [US1] ShellToolsTest：`yokeos-tool/src/test/java/com/yokeos/tool/builtin/ShellToolsTest.java`——①正常命令拿到 stdout（`echo`，跨平台）；②非零退出码 → 失败异常消息含退出码与 stderr；③挂死命令（`sleep`）+ 构造注入 300ms 超时 → 强杀终止且消息含超时秒数（坑四）。**文件头注释注明：白名单拦截用例留 24 节**
- [x] T009 [P] [US1] ShellTools：`yokeos-tool/src/main/java/com/yokeos/tool/builtin/ShellTools.java`——`@Tool(name="shell")` 入参 `List<String> command`（argv 数组直传，拍板③）：空/空白元素 → `IllegalArgumentException` 点名；`ProcessBuilder(command)` 直传零 shell 解释；超时默认 30s（`Duration` 构造可注入）到点 `destroyForcibly` + 失败消息含秒数；非零退出码 → 失败含退出码+stderr；成功返回 stdout；检查位注释（`SHELL_COMMAND`，24 节对 argv[0] 比对）；SpotBugs `COMMAND_INJECTION` 类级 `@SuppressFBWarnings` + Javadoc 理由（参照先例：命令执行是功能本体，治理在校验位）
- [x] T010 [P] [US1] HttpToolsTest：`yokeos-tool/src/test/java/com/yokeos/tool/builtin/HttpToolsTest.java`——JDK `com.sun.net.httpserver.HttpServer` 假 server（port 0，19 节先例）：①GET 取回正文；②POST 断言 body 原样到达 + Content-Type 为 JSON；③4xx 与 5xx → 失败消息含状态码与 URL；④响应超 8000 字符截断。**文件头注释注明：域名白名单拦截用例留 24 节**
- [x] T011 [P] [US1] HttpTools：`yokeos-tool/src/main/java/com/yokeos/tool/builtin/HttpTools.java`——`@Tool(name="http_get")`（逻辑从 17 节 `HttpGetTool` 迁入：JDK HttpClient 同步、连接/读取超时 10s、8000 截断、非 [200,300) 失败、IOException→retryable=true）+ `@Tool(name="http_post")`（`@ToolParam url/body`，body 为 JSON 字符串，`content-type: application/json`）；检查位注释（`HTTP_REQUEST`）；HttpClient 构造注入（测试定制）
- [x] T012 [US1] YokeToolContractTest（伴随）：`yokeos-tool/src/test/java/com/yokeos/tool/YokeToolContractTest.java`——`MethodSource` 构造与装配同款静态注册面（FileTools/ShellTools/HttpTools 注解注册 + NotifyTools 直接注册）：①参数化遍历——name/description/inputSchema 非空非空白（`@DisplayName("每个工具的契约三件套都不能缺")`）；②schema 含 `properties`（`@DisplayName("注解管道生成的schema含参数定义")`，分批②的承载）
- [x] T013 [US1] YokeosRuntime 换源：`yokeos-cli/src/main/java/com/yokeos/cli/YokeosRuntime.java` `tools()`——唯一替换点：构造 `ToolRegistry`，`registerAnnotated(new FileTools()/new ShellTools()/new HttpTools())` + `register(new NotifyTools(Map.of("webhook", new WebhookNotifyAdapter())))`（MCP connectAll 留 US2 T021 接入位，注释注明），`registry.asMap()` 返回喂 PromptBuilder/ToolExecutor（两消费方构造签名不动，17 节预告兑现）；`YokeosRuntimeAssemblyTest` 断言更新：注册面工具数 ≥7
- [x] T014 [US1] HttpGetTool 退役：删除 `yokeos-tool/src/main/java/com/yokeos/tool/HttpGetTool.java` + 引用清理——`yokeos-boot/src/test/.../ReActSmokeIntegrationTest.java`（真 http_get 换 `HttpTools` 实例经注册面/直接构造）与 cli 侧若有引用同改；`grep -rn "HttpGetTool" yokeos-*/src` 零命中为完成判据（17 节注释预告的演进，plan 显式偏差）
- [x] T015 [US1] 阶段门禁：`mvn test -pl yokeos-tool -am` + `mvn test -pl yokeos-cli -am` + `mvn test -pl yokeos-boot -am` 全绿（19 节基线零回归），红了当场修

## Phase 3: US2+US3 MCP 子包与接入（P1）

**Goal**: mcp 四件（配置/连接/适配）+ 失联隔离 + 透传与可重试语义 + YokeosRuntime 接入 MCP。
**Independent Test**: `mvn test -pl yokeos-tool -am -Dtest='McpClientServiceTest,McpToolAdapterTest'` 全绿。

- [x] T016 [P] [US2] McpClientServiceTest：`yokeos-tool/src/test/java/com/yokeos/tool/mcp/McpClientServiceTest.java`——`@TempDir` 写 yaml + mock `McpSyncClient` + `Function<McpServerConfig, McpSyncClient>` 工厂注入：①**失联隔离（最值钱）**：一好一坏 server → `connectAll` 不抛异常、好工具注册、坏工具零注册（`@DisplayName("某个MCP_server失联_不能拖垮启动和其他工具")`，坑二）；②listTools 多工具逐个包装注册；③McpConfigLoader 四态：command/env `${ENV}` 占位缺失保留原样、文件缺失零 server、解析失败零 server、transport 非 stdio 跳过；④**单工具重名不连坐**：registry 预置同名 → 该工具 WARN 跳过、同 server 其余工具照常注册（clarify B，`@DisplayName("MCP工具与已注册重名_单件跳过不连坐其余工具")`）
- [x] T017 [P] [US2] 配置两件：`mcp/McpServerConfig.java`（record name/transport/command/env，compact ctor `Map.copyOf`）+ `mcp/McpConfigLoader.java`（SnakeYAML 读顶层 `servers` 列表；`${ENV}` 占位正则解析、缺失保留原样+WARN；文件缺失/解析失败 → 零 server+WARN；日志编译期常量、动态值进参数——CRLF 门禁）
- [x] T018 [US2] McpClientService：`mcp/McpClientService.java`——`connectAll(ToolRegistry)`：非 stdio WARN 跳过；`clientFactory.apply(config)` 构造注入（生产默认 `McpClient.sync(new StdioClientTransport(ServerParameters.builder(cmd).args(..).env(..).build())).requestTimeout(30s).build()`，command 按空白拆 argv，research D4/D7）；`initialize()` + `listTools()` 失败 → WARN 跳过整 server（外部依赖可用性≠自身可用性）；**单工具注册独立 try-catch**：`IllegalStateException`（重名）等 → WARN 点名跳过、继续该 server 其余工具（research D5，与外层 server 级隔离双层容错）
- [x] T019 [P] [US3] McpToolAdapterTest：`yokeos-tool/src/test/java/com/yokeos/tool/mcp/McpToolAdapterTest.java`——mock `McpSyncClient`，Tool 规格**用 `McpSchema.Tool.builder().name(..).description(..).inputSchema(mapper, json)` 构造**（research D3：三参构造不存在）：①三要素直接映射；②execute 参数原样转发（ArgumentCaptor 断言 `CallToolRequest.arguments()` 与入参逐键一致）；③成功 TextContent 拼接为 `ToolResult.ok`；④`isError=true` → `ToolResult.error` 且 `retryable=true`（`@DisplayName("MCP调用失败_包装为可重试的失败结果")`）
- [x] T020 [US3] McpToolAdapter：`mcp/McpToolAdapter.java`——implements YokeTool；三要素 ← `tools/list` 的 Tool 规格（inputSchema 经 ObjectMapper 序列化 JsonSchema）；`execute`：JsonNode→`Map<String,Object>` convertValue → `callTool(new CallToolRequest(name, args))` 原样转发 → TextContent 逐段 join（非文本 `String.valueOf` 兜底）→ isError 判定包装；`EI_EXPOSE_REP2` 类级抑制+Javadoc 理由（参照先例：连接生命周期归 McpClientService）
- [x] T021 [US2] YokeosRuntime 接入 MCP：`YokeosRuntime.java` `tools()` 内增 `new McpClientService(new McpConfigLoader(workspace().resolve("mcp_servers.yaml"))).connectAll(registry)`（T013 留位兑现）；`YokeosRuntimeAssemblyTest` 注释注明 MCP 面随配置文件动态
- [x] T022 [US2] [US3] 阶段门禁：`mvn test -pl yokeos-tool -am` + `mvn test -pl yokeos-cli -am` 全绿

## Phase 4: US4 工具面可查与配置有痕（P2）

**Goal**: tool list 查真实注册面 + init 补模板 + AgentLoader 点名 WARN（静默变有痕）。
**Independent Test**: `mvn test -pl yokeos-core -am -Dtest='AgentLoaderTest'` 与 `mvn test -pl yokeos-cli -am` 全绿。

- [x] T023 [P] [US4] AgentLoaderTest 补点名 WARN 用例：`yokeos-core/src/test/java/com/yokeos/core/profile/AgentLoaderTest.java`——`tools:` 含未注册名 `foo_tool`：①Profile 派生成功不阻断；②WARN 日志点名 Agent 名与工具名（logback `ListAppender` 挂 AgentLoader logger，19 节先例）；③既有用例（不传校验面）零改动通过
- [x] T024 [US4] AgentLoader 点名校验：`yokeos-core/src/main/java/com/yokeos/core/profile/AgentLoader.java`——`loadAll` 增参 `Set<String> knownToolNames`（重载缺省空集=不校验，既有调用零改动，research D9）；`tools` 点名 ∉ 集合 → SLF4J WARN（编译期常量消息，Agent 名与工具名进参数）不阻断；`YokeosRuntime.profileRegistry()` Bean 追加参数 `Map<String, YokeTool> tools`（注入现成 tools() Bean，取 `keySet()` 传 loadAll——analyze F1）
- [x] T025 [P] [US4] cli 侧测试先行：`ProviderToolListCommandTest` 断言更新（七件全量：read_file/write_file/list_dir/shell/http_get/http_post/notify + MCP 动态工具注记行）+ `InitCommandTest` 补 mcp_servers.yaml 幂等用例（首建存在、重复 init 零覆盖）
- [x] T026 [US4] 两处实现：`ToolListCommand.java` `listTools()` 改查静态注册面（手动构造 ToolRegistry：registerAnnotated 三组内置 + register NotifyTools，零 Spring，research D10；输出名+描述来自真实工具实例；尾注 MCP 动态工具不列；与 YokeosRuntime.tools() 构造注释互指）+ `InitCommand.java` `buildBootstrapTemplates` 补 `mcp_servers.yaml` 注释模板（`servers: []` 占位）
- [x] T027 [US4] 阶段门禁：`mvn test -pl yokeos-core -am` + `mvn test -pl yokeos-cli -am` 全绿

## Phase 5: 集成冒烟与收尾

- [x] T028 ToolMcpSmokeIntegrationTest：`yokeos-boot/src/test/java/com/yokeos/boot/ToolMcpSmokeIntegrationTest.java`——`@Tag("integration")`：真 stdio MCP server（`npx -y @modelcontextprotocol/server-everything`，无 key；npx 不可用 assumeTrue 跳过）+ 真模型（`DEEPSEEK_API_KEY` assumeTrue 守卫）点名调 MCP 工具，断言：MCP 工具进注册面、`tool_invocations` 记到该调用、答复非空；构造形态照 `ReActSmokeIntegrationTest`/`NotifyEndToEndIntegrationTest`（19 节框架同款）；跑法 `mvn -pl yokeos-boot -am test -Dgroups=integration -DexcludedGroups= -Dtest=ToolMcpSmokeIntegrationTest`
- [x] T029 全仓硬门禁：`mvn clean verify` 九模块全绿（测试数 = 19 节基线 + 本节新增；Spotless/P3C/Checkstyle/SpotBugs/FindSecurityBugs 全过），红了修实现不改规则
- [x] T030 H4 全局不变量逐条自查 + 教学文档「本节交付物」逐项 ls/grep 存在性核对 + 宪法专项 grep：`grep -rn "internalToolExecutionEnabled(true)\|\.tools(" yokeos-*/src/main` 零命中（宪法 2）、`grep -rn "import reactor" yokeos-*/src/main` 零命中（宪法 4，research D4）、`grep -rn "Sandbox 检查位" yokeos-tool/src/main` 命中 6+1 处形态一致 + 凭证卫生抽查（quickstart.md 命令）
- [x] T031 验收报告：`specs/005-tool-system/acceptance-report.md`（结构照 specs/004：六项证据 DoD + harness 映射表 + 分批说明——拦截用例与 InOrder 留 24 节 + 实施偏差节——argv 直传/MethodToolCallbackProvider 代差/read_file 截断/重名不连坐/HttpGetTool 退役五处 + 剩余人工项）；CLAUDE.md 常见陷阱表回填（如有新坑）；对话内输出三段式变更总结

## Dependencies

- T001/T002 先行（T002 阻塞全部——pom 依赖是编译前提）。
- Phase 2 内：T003→T004/T005（测试先行）；T006→T007、T008→T009、T010→T011 同理；T012 依赖 T004/T005/T007/T009/T011 全在（契约测试遍历真实注册面，伴随）；T013 依赖 T004/T005/T007/T009/T011；T014 依赖 T013（引用消失后方可删）；T015 收口。
- Phase 3 内：T016→T017/T018；T019→T020；T021 依赖 T018/T020 与 T013。
- Phase 4 内：T023→T024；T025→T026；T024 的 YokeosRuntime 传参依赖 T021（registry 建立后）。
- 并行机会：T004/T005、T006~T011 四对（不同文件）、T017 与 T019、T023/T025 不同模块。

## Implementation Strategy

- MVP = Phase 2（US1：注册面 + 六件 + 主链路换源 + 契约兜底）；Phase 3 补 MCP（US2/US3）；Phase 4 工具面与有痕（US4）；Phase 5 冒烟与收尾。
- 每阶段门禁当场修红；本节结束时 yokeos-tool 新增 builtin/mcp 两子包与注册面两件，全仓测试数 = 19 节基线 + 本节新增（估算 +25 左右）。
