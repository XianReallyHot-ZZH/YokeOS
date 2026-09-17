# 验收报告：Tool 体系与 MCP（第 20 节，specs/005-tool-system）

**日期**: 2026-09-17 · **分支**: `specs/005-tool-system` · **教学文档**: `docs/class/020-tool-system.md`（拍板①~⑦ 2026-09-17 用户批准）

## 一、六项证据 DoD

### 1. `mvn clean verify` 九模块全绿

```
YokeOS Core / Provider / Storage / Memory / Tool / CLI Channel / Web / CLI / Boot —— 全部 SUCCESS
测试总数 184 = 19 节基线 139 + 本节新增 45（ToolRegistryTest 4 + YokeToolContractTest 14 +
FileToolsTest 5 + ShellToolsTest 5 + HttpToolsTest 4 + McpClientServiceTest 7 + McpToolAdapterTest 4 +
AgentLoaderTest 补 1 + InitCommandTest 补 1），前序零回归。
集成冒烟 ToolMcpSmokeIntegrationTest（@Tag("integration") 默认排除）真跑 1/1 绿（见第 6 节）。
```

### 2. 教学文档 harness 映射逐类对号

| 测试类 | 验收点 | 结果 |
|---|---|---|
| YokeToolContractTest | 契约三件套非空非空白 + schema 含 properties（7 工具 × 2 参数化） | 14/14 ✅ |
| ToolRegistryTest | 三来源统一注册（含真 @Tool bean 经 MethodToolCallbackProvider）/ 重名拒绝点名 / filterByNames 恰好 / 声明顺序 | 4/4 ✅ |
| FileToolsTest | 读/写回读/父目录创建/排序/不存在点名/8000 截断 | 5/5 ✅ |
| ShellToolsTest | stdout / 非零退出带 stderr / 挂死超时强杀 / argv 空白拒绝 / 参数字面量直传（`*$HOME` 不展开） | 5/5 ✅ |
| HttpToolsTest | GET / POST body 原样+JSON Content-Type / 4xx+5xx 点名状态码 / 截断 | 4/4 ✅ |
| McpClientServiceTest | **失联隔离**（一好一坏不炸启动）/ listTools 逐件注册 / 配置四态（占位保留、缺文件、解析失败、非 stdio）/ **单工具重名不连坐**（clarify B） | 7/7 ✅ |
| McpToolAdapterTest | 三要素映射 / ArgumentCaptor 参数逐键原样 / isError 可重试 / 多段拼接 | 4/4 ✅ |
| AgentLoaderTest（补） | tools 点名未注册 → 派生照常 + WARN 点名（ListAppender，拍板⑥） | +1 ✅ |

坑↔测试对号：坑一（管道误当自动执行/兜异常）↔ AnnotatedToolAdapter 异常直抛 + ToolExecutor 既有语义；坑二（失联拖垮启动）↔ McpClientServiceTest#oneMcpServerDown…；坑三（SDK 代差）↔ 全部 MCP 测试经 javap 实证签名（research D1~D4）；坑四（挂死拖死循环）↔ ShellToolsTest#hangingCommand…；坑五（大输出撑爆）↔ File/Http 截断用例；坑六（重名覆盖）↔ ToolRegistryTest#duplicateName… + 重名不连坐。

### 3. 「本节交付物」逐项存在性核对

- 代码（yokeos-tool）：`ToolRegistry.java`、`AnnotatedToolAdapter.java`、`builtin/{FileTools,ShellTools,HttpTools}.java`、`mcp/{McpServerConfig,McpConfigLoader,McpClientService,McpToolAdapter}.java` ✅ ls 证实；`HttpGetTool.java` 已删除且 `grep -rn HttpGetTool yokeos-*/src` 零命中 ✅
- 代码（yokeos-cli）：`YokeosRuntime.tools()` 换 Registry + MCP connectAll ✅、`profileRegistry` 传 keySet（analyze F1）✅、`ToolListCommand.listTools()` 查静态注册面 ✅、`InitCommand` 补 `mcp_servers.yaml` 模板 ✅
- 代码（yokeos-core）：`AgentLoader.loadAll` 四参重载 + `warnUnknownTools` ✅
- 测试：七类 + 三处补强 ✅（见第 2 节）
- 配置：init 模板实测创建（boot fat JAR `init` 真跑）✅；无新全局配置键 ✅
- 表：无新表 ✅；依赖：根 pom 钉 `io.modelcontextprotocol.sdk:mcp:1.1.1` + tool 模块 `spring-ai-model`/`mcp`/`snakeyaml`/`spotbugs-annotations`（软门禁⑥记理由，见偏差节）✅

### 4. 前序节测试回归绿

全仓 184 全绿含 16~19 节全部既有测试；两处前序测试按预告同步更新（非削弱）：`ReActSmokeIntegrationTest`/`NotifyEndToEndIntegrationTest` 的 http_get 换经注册面取件（17 节注释预告的演进）、`ProviderToolListCommandTest` 断言从「两件+注明」升为「七件全量+MCP 注记」（18 节注释预告兑现）。

### 5. H4 七条全局不变量逐条自查

1. 涉外 IO 首行过 Sandbox——**检查位注释留位**：FileTools×3 / ShellTools×1 / HttpTools×2 共 6 处 + NotifyTools（19 节「沙箱检查位」中文措辞 1 处）；24 节接线（拍板①）✅
2. llm_calls / tool_invocations 成败都落——全部新工具经 ToolExecutor 既有路径，零新增审计逻辑；冒烟实证 echo 调用留痕 ✅
3. grep 无明文 key——代码/配置零命中（env 占位 `${ENV}` 形态，缺失保留原样 WARN）✅
4. session_id 拼接——本节未触碰 SessionManager ✅
5. 核心链路无 Reactor/异步——`grep "import reactor" yokeos-*/src/main` 零命中（mcp-core 传递 reactor-core 属 SDK 内核件，research D4 论证）✅
6. 无 Spring AI 自动执行路径——`grep "internalToolExecutionEnabled(true)|prompt().tools(|.tools(tools)" yokeos-*/src/main` 零命中；注解管道 `call()` 是进程内直调（宪法 2）✅
7. 新触发入口汇入 AgentService.process——本节无新触发入口（对话内工具调用走既有 ReActLoop→ToolExecutor）✅

### 6. 人工项当场跑完 + 剩余清单

当场跑完：
- ✅ **真 stdio MCP server + 真模型**（可演示成果主口径）：`ToolMcpSmokeIntegrationTest` 真跑 1/1 绿（13.5s）——npx everything server 工具进注册面 → 真 DeepSeek 点名调 `echo` → `tool_invocations` 留 success 行 + llm_calls 有记录（跑法 quickstart 场景 5）
- ✅ `tool list` 真实命令核对：boot fat JAR 实跑输出恰好七件（名+描述来自真实工具实例）+ MCP 注记行；`init` 实测补建 `mcp_servers.yaml` 且幂等
- ✅ 凭证卫生 grep（sk-/api_key 零明文命中）；宪法 2/4 专项 grep（见第 5 节）
- ✅ 点名 WARN：单测 ListAppender 断言 + 真实启动路径同代码（YokeosRuntime 装配实测经 AssemblyTest）

剩余人工项：**0**（集成冒烟与命令核对均已完成；npx 冷缓存首跑会超时跳过——预热后正常，见陷阱回填）。

## 二、分批说明（留 24 节）

- File/Shell/Http 三个测试类的「白名单拦截」用例与 InOrder「校验先于 IO」顺序回归——Sandbox 接口未就位（拍板①），三个测试类文件头注释已注明待补；NotifyTools 同款（19 节既有注明）。
- 检查位注释形态一致性由本报告第 5 节第 1 条 + quickstart 人工核对清单承载。

## 三、实施偏差

1. **argv 数组直传替 bash -c 拼接**（拍板③）：技 §6.2 明文，参照实现相左时采信自家技术方案；24 节白名单对 argv[0] 精确比对。
2. **`MethodToolCallbackProvider` 替 `ToolCallbacks.from`**：1.1.8 无后者（research D2 javap 实证），非选择性偏差。
3. **`AnnotatedToolAdapter` 剥壳逻辑（计划外新增）**：1.1.8 管道把 String 返回 JSON 字面量化（带引号）、方法异常包 `ToolExecutionException`——adapter 统一剥壳保 YokeTool 契约干净（引号不进对话历史/审计、异常类型原样）。实施中发现，已记 CLAUDE.md 陷阱。
4. **`read_file` 8000 截断**：参照无此防护，本仓补（坑五，17 节同款意识）。
5. **MCP 重名单工具容错不连坐**（clarify B）：参照整 server 中断属「瑕疵不继承」（宪法 9）。
6. **`HttpGetTool` 退役**：技 §13/17 节注释预告的改造点；HTTP 工具失败语义随之统一为注解管道形态（异常上抛→ToolExecutor 转 error，网络瞬态不再走单工具 retryable=true 特殊路径——参照终态同构）。
7. **依赖四件**（软门禁⑥）：spring-ai-model + mcp SDK（拍板⑤）之外，实施中补 snakeyaml（mcp_servers.yaml 解析，技 §6.4 必需件）与 spotbugs-annotations provided（ShellTools COMMAND_INJECTION 抑制，core 同款先例）。
8. **MCP SDK 三处 API 代差适配**（research D1/D3 + 实施发现）：`McpSchema.Tool` 七参 record 走 builder；`CallToolResult` 四参构造；`StdioClientTransport` 构造器必须显式传 `JacksonMcpJsonMapper`（jackson3 传递件在 YokeOS 的唯一消费点，业务代码零接触）。

## 四、门禁拦下了什么、怎么修的（过程证据）

1. **Checkstyle javadoc 行首 `@Tool`**：被认作 block tag 触发 JavadocTagContinuationIndentation 连锁（6 处）——@ 词内嵌句中或 `{@code @Tool}` 包裹。
2. **Checkstyle MissingJavadocMethod**：显式 public 构造器两处缺 javadoc——补。
3. **SpotBugs CRLF_INJECTION_LOGS（3 轮）**：参数化 `log.warn("…: {} {}", a, b)` 形态被拦（参数化日志也过不了 Find Security Bugs）——统一改「编译期常量消息 + 动态值进异常堆栈」（19 节 AgentLoader 同款形态），MCP 侧 `sanitize()` 函数随之退役。
4. **编译错一批（API 代差）**：`ToolCallbacks` 不存在、`CallToolResult` 双参构造不存在、`StdioClientTransport` 单参构造不存在、`executeSqlScript(Connection, InputStream)` 不存在——全部 javap/参照既有测试实证后修正（陷阱表回填）。
5. **HttpToolsTest `/echo` handler 请求流二次读空**：recordRequest 与回显各读一次流——改为单次读取双用（测试自身 bug）。
6. **测试 javadoc 化的 public 构造器 + VariableDeclarationUsageDistance/LineLength**：常规修正。

## 五、方法论对照

- 「能机器判的绝不留给人」：契约测试参数化遍历注册面（新工具自动纳入）、H4 逐条 grep 化、命令核对真跑 fat JAR。
- 「API 代差以本地依赖为准」（H3）：research D1~D4 全 javap 实证，实施期四处编译错全部在实证过的签名范围内快速收敛。
- 「接口先行/结构照抄，瑕疵不继承」：三合一单模块镜像参照落位；参照的整 server 重名连坐、tool list 硬编码清单两处瑕疵按预告补正。
- 「修文档优先」：需 §11「白名单校验生效」与技 §13 的课位张力经教学文档拍板①显式裁决（留 24 节），未静默扩 scope。

## 六、验证命令（可复制）

```bash
# 全量门禁（预期：九模块 SUCCESS，184 测试全绿）
mvn clean verify
# 本节测试（预期：55 全绿）
mvn test -pl yokeos-tool -am
# 集成冒烟（预期：真跑 1/1 绿；npx 冷缓存首跑 skip 属正常，二跑绿）
source ~/.zshrc && mvn -pl yokeos-boot -am test -Dgroups=integration -DexcludedGroups= -Dtest='ToolMcpSmokeIntegrationTest'
# 宪法专项（预期：全部零输出）
grep -rn "internalToolExecutionEnabled(true)\|prompt()\.tools(" yokeos-*/src/main --include="*.java"
grep -rn "import reactor" yokeos-*/src/main --include="*.java"
grep -rn "HttpGetTool" yokeos-*/src --include="*.java"
# 检查位（预期：6/2/4 = 方法 6 处 + javadoc 提及；NotifyTools 为中文「沙箱检查位」）
grep -rc "Sandbox 检查位" yokeos-tool/src/main/java/com/yokeos/tool/builtin/*.java
# 可演示成果（预期：七件全量 + MCP 注记）
java -jar yokeos-boot/target/yokeos-boot-0.1.0-SNAPSHOT.jar tool list
```
