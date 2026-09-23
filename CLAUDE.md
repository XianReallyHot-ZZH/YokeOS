# YokeOS — Claude Code 项目指南

YokeOS 是用 Java 实现的面向企业场景的 **Agent 底座（Agent Harness OS）**。装在企业自己的 K8s 或服务器上，作为统一底座运行多个业务 Agent，共享渠道接入、模型路由、工具调用、记忆系统、沙箱执行与通知定时能力。数据完全留在企业自己的基础设施，不锁任何云生态。

第一阶段以 [oryx-labs/oryxos](https://github.com/oryx-labs/oryxos) 为参照实现「复刻型起步」：同类、同栈、同锚点的后来者，不掩饰起点；差异化立在过程——全程规格驱动，每一步有可追溯的规格与验收证据。

> 详细背景：`docs/IndustryResearch.md`（业界调研）、`docs/yokeos.md`（产品定位）、`docs/DemandAnalysis.md`（需求）、`docs/TechnicalSolution.md`（技术方案）、`docs/AiProgrammingGuide.md`（AI 编程指南）、`CONTEXT.md`（词汇表）、`docs/adr/`（决策记录）。

---

## 技术栈

| 组件 | 选型 |
|------|------|
| 语言 / 运行时 | Java 21（必须，virtual thread 处理并发） |
| 框架 | Spring Boot 3.5.16（与参照逐节可比的刻意选择，升级 Boot 4 + Spring AI 2.0 列扩展阶段） |
| LLM 调用 | Spring AI 1.1.8 + Spring AI Alibaba（仅用协议转换 + `@Tool` schema 生成） |
| HTTP 服务 | Spring MVC + Java 21 Virtual Thread |
| 命令行 | Picocli 4.7.6 |
| YAML 解析 | SnakeYAML |
| 持久化 | SQLite（sqlite-jdbc 3.53.2.1）+ Spring Data JPA |
| MCP | MCP Java SDK |
| 日志 | Logback + SLF4J（结构化 JSON，禁 `System.out`） |
| 管理台 | Vue 3 + Vite，经 frontend-maven-plugin（Node v20.18.0）构建，第 26 节落地 |
| API 文档 | springdoc-openapi 2.8.13（26 节实证：2.6.0 与 Boot 3.5 二进制不兼容，NoSuchMethodError） |
| 构建 | Maven 多模块（groupId `com.yokeos`），fat JAR |

**代码注释约定**：中文为主，技术术语保留英文原词（traceId、fat JAR、`${ENV_VAR}` 等）；注释只写为什么（H5），出处逐字引用宪法/文档条款（如「宪法 7：审计 day one」）。

---

## 模块结构（9 个）

```
yokeos/
├── yokeos-core          # 核心抽象：YokeTool 接口、Session、Profile、AgentLoader、ContextLoader、
│                        #   ReActLoop、PromptBuilder、ToolExecutor、AgentService、AgentScheduler、
│                        #   AgentLifecycleService、ScheduledTaskStore 接口
├── yokeos-provider      # 能力一：ProviderService、Function Calling 适配、
│                        #   provider name → ChatModel 显式映射
├── yokeos-memory        # 能力三：MemoryService 统一门面、LongTermMemoryStore 三档后端、
│                        #   MemoryTools（save/recall）
├── yokeos-tool          # 能力四：内置 Tool（文件/Shell/HTTP/Notify）、MCP Client、
│                        #   ToolRegistry、Sandbox 接口 + WhitelistSandbox、
│                        #   NotifyChannelAdapter 接口 + WebhookNotifyAdapter（三合一模块）
├── yokeos-channel-cli   # CLI Channel：yokeos chat 实现
├── yokeos-web           # 能力六：7 个 ApiController、Web 管理台托管、
│                        #   GlobalExceptionHandler、OpenAPI
├── yokeos-storage       # 持久化：SQLite、SessionRepository、ToolInvocationRepository、
│                        #   LlmCallRepository、JpaScheduledTaskStore
├── yokeos-cli           # 命令行入口：Picocli 主入口、12 个子命令、ConfigLoader
└── yokeos-boot          # Spring Boot 启动模块：主类、自动配置、依赖聚合
```

跨模块契约（接口 + 值对象）放 `yokeos-core`，下游模块实现（依赖倒置），禁止循环依赖。新增 Channel 或 Tool 只加新模块，不改 `yokeos-core`。九个模块每个都要有测试覆盖，入口模块不例外。

---

## 不可违背的原则（宪法·运行时版）

以下九条提炼自 `docs/AiProgrammingGuide.md` §3.2 与 `docs/TechnicalSolution.md`，所有代码必须遵守。宪法原文落 `.specify/memory/constitution.md`（准备阶段创建），与本文件**双向一致**；修订宪法属设计变更，AI agent 不得自行修改，且修订必须同步本文件。

1. **自实现 ReAct 循环**：`ReActLoop` 自己实现，不用 Spring AI 的 Agent 抽象。核心循环约数十行 Java，完整掌握工作机制。
2. **Spring AI 只用两件事** ⚠️：只用协议转换 + `@Tool` schema 生成；**禁用自动 tool 执行**与 eager 自动装配（最容易被写错的一条，会导致 tool 被调两次）：

   ```java
   // 错误：启用自动 tool 执行
   chatClient.prompt(prompt).tools(tools).call().content();

   // 正确：自己检查 tool call、自己执行、自己回填
   ChatResponse response = chatModel.call(new Prompt(messages, options));
   ```

3. **Provider 显式映射**：维护 `provider name → ChatModel` 显式映射，不做容器类型扫描（Bean 类型相同，扫描必乱）。
4. **同步执行 + 虚拟线程**：全程同步阻塞，禁引入 Reactor / WebFlux / `CompletableFuture`（唯一例外：第 25 节 `ThreadPoolTaskScheduler` 调度线程池）。
5. **Tool 三合一**：`YokeTool` 统一抽象；内置 Tool、MCP Client、`ToolRegistry`、Sandbox、Notify 合并在 `yokeos-tool` 一个模块，不拆分。
6. **Sandbox 接口先行**：`Sandbox.enforce(action)` 接口不携带任何实现特有概念；第一阶段只填 `WhitelistSandbox`（路径/命令/域名三重白名单，校验真实路径）；不用 `SecurityManager`（JDK 21 已不可用）。
7. **SQLite + MEMORY.md，审计 day one**：审计两表（`tool_invocations` / `llm_calls`）从第 16 节起写入落库，不以「日志够了」推迟；表结构演进不走 `ddl-auto=update`，手工维护建表脚本。
8. **一个目录 = 一个 Agent**：`AGENT.md` frontmatter 经 `AgentLoader.deriveProfile()` 派生 Profile；正文与引用 Skill 正文注入 system prompt；Skill 不进 `ToolRegistry`、正文不预载；附属资源经既有工具按需取用。
9. **结构照抄，瑕疵不继承**：九模块边界与依赖方向镜像参照实现；参照已知工程瑕疵（如 CLI 入口零测试）补上，不继承。

---

## 工作区结构（运行时）

`yokeos init` 在当前目录创建 `.yokeos/`（幂等，已存在一律不覆盖）：

```
.yokeos/
├── agents/            # 每个子目录 = 一个 Agent（AGENT.md + 可选 skills/引用、scripts/、REFERENCE.md）
├── skills/            # 公共 Skill 库（每个子目录一个 SKILL.md，兼容 agentskills.io）
├── output/            # Agent 产出物
├── memory/
│   └── MEMORY.md      # 长期记忆（## 核心记忆 / ## 归档记忆 两分区）
├── sessions/          # 会话导出（真相源在 SQLite）
├── logs/              # 结构化日志
├── AGENTS.md          # Bootstrap：项目级 agent 行为说明
├── SOUL.md            # Bootstrap：默认 agent 人格定义
├── USER.md            # Bootstrap：用户偏好（只读，agent 不写）
├── mcp_servers.yaml   # MCP 配置
└── yokeos.db          # SQLite
```

`USER.md`（用户手写初始设定，只读）与 `MEMORY.md`（Agent 经 `save_memory` 写入的成长记录，读写）都进 system prompt，来源与生命周期不同。

---

## 核心数据模型

**AGENT.md frontmatter**（→ `deriveProfile` → `Profile`）：`name`、`description`、`identity`（`agent_name`、`prompt`）、`provider`（`name`、`model`、`temperature`、`api_key: ${ENV_VAR}`）、`tools`、`skills`（按名引用）、`mcp_servers`、`channels`、`notify.channels`（`name`/`type: webhook`/`config`）、`schedules`（`id` 必填且 profile 内唯一 + cron + 时区 + 消息，25 节修正案）、`bootstrap`、`settings`（`max_iterations` 默认 10、`max_history_turns` 默认 20）。

**SQLite 六张表**（手工建表脚本）：

| 表 | 用途 | 要点 |
|----|------|------|
| `sessions` | 会话元数据 + JSON 对话历史 | `session_id` = channel+user+agent 联合生成；`active`/`archived` |
| `tool_invocations` | 审计：每次 Tool 调用 | **day one 写入**；Sandbox 拒绝也走此表（`success=false`） |
| `llm_calls` | 审计：每次 LLM 调用 | **day one 写入**；token 用量 + 耗时 + 成败与失败原因（`success`/`error_message`） |
| `scheduled_tasks` | 定时任务登记与运行状态 | 定义源仍是 frontmatter，此表只存状态+历史 |
| `task_executions` | 定时任务执行历史 | 成功失败都记 |
| `memory_entries` | 长期记忆条目 | 仅 `SqliteMemoryStore` 档使用 |

**MEMORY.md 两分区**：核心记忆区全量注入永不截断；归档区超 4000 字保留最近内容，`recall_memory` 只检索归档区。`scope` 由 Agent 显式指定，系统不猜。

---

## ReAct Loop 工作机制

```
用户消息（CLI / REST / 定时三入口 → 同一个 AgentService.process）
  → 追加到 Session 对话历史
  → PromptBuilder 组装 Prompt（固定顺序）：
      [1] system prompt（AGENT.md 正文 + Bootstrap + 引用 Skill 正文；末尾附当前日期时间）
      [2] Memory（会话历史 + 长期记忆）
      [3] 对话历史（按 max_history_turns 截断）
      [4] 可用 Tool 列表（Function Calling 格式）
  → ProviderService 调 LLM（写 llm_calls）
  → [无 Tool 调用] → 返回最终响应
  → [有 Tool 调用] → ToolExecutor：
      Sandbox.enforce 校验 → 执行（内置进程内 / MCP 转发）→ 写 tool_invocations
      → 结果追加进对话历史 → 回到组装 Prompt（默认最多 10 轮）
```

钟推（定时触发）落 Session 时 channel 与 user 固定为 `scheduler`，与人推复用同一条链路，不为定时新设概念。上下文超限简单截断（保留 system prompt + 最近 N 轮）。

---

## Tool 体系

**内置 Tool（9 个）**：`read_file` / `write_file` / `list_dir`（路径白名单，真实路径校验）、`shell`（命令白名单 + argv 直传 + 超时）、`http_get` / `http_post`（域名白名单）、`save_memory` / `recall_memory`、`notify`（Webhook 推送，共享域名白名单）。

**扩展三档**：零代码（AGENT.md + 社区 MCP server，主推）→ 轻代码（自写 MCP server，配 `mcp_servers.yaml`）→ 重代码（`@Tool` Java Bean 进程内直调）。能用一不用二，能用二不用三。

**Sandbox**：`Sandbox.enforce(SandboxAction)` 接口先行，`ActionType` 取四值（FILE_READ / FILE_WRITE / SHELL_COMMAND / HTTP_REQUEST）；校验失败抛 `SandboxViolationException` 走既有审计路径。信任边界：装一个带脚本的 Agent = 信任其作者（解释器入白名单即授予代码执行权）。

---

## Web Service API

统一前缀 `/api/v1`，统一信封 `{code, message, data, timestamp}`。第一阶段 **19 个端点**按五组：会话管理 5（sessions CRUD + messages + 列表，列表端点为 26 节「上游赢」补位）· Agent 调用与动态管理 7（`generate` 一句话草稿不落盘、CRUD、`invoke`）· 工作区 2（tree / file 只读）· 信息查询 3（profiles / memory / tools）· 系统状态 2（health / info）。

Web 管理台第一版（第 26 节）：只读观察五页 + Agent 管理页 + 工作区页，与 REST 同端口同进程，只调同一组端点。**不做**：认证（假设内网）、SSE、WebSocket、RBAC、限流。定时任务管理端点与白名单管理端点显式列为扩展规划位（ADR 0008，不悄悄补进第一阶段）。

---

## 命令行（12 个）

```
yokeos init / status / chat [--profile <name>] / serve [--port 8080] / gateway
yokeos profile list | create <name> | show <name> | delete <name>
yokeos provider list / tool list / session list
```

三种运行模式：`chat`（交互）、`serve`（REST + 管理台，定时任务随行常驻）、`gateway`（多渠道守护）。共享同一份 Agent 配置与 Session 存储。命令分两类启动：不需要 Spring 上下文的（init、profile list）直接文件操作。

**配置与密钥**：敏感配置走 `${ENV_VAR}` 占位从环境变量解析，不明文写死；`ConfigLoader` 启动校验必填项与格式，缺失或非法给清晰报错，不静默失败。

---

## 实施节奏（第 16→31 节）

按参照公开构建过程课节序组织，**节奏自定、顺序不乱**，不设日历时间盒。节 ↔ 技术方案章节映射（H0 必读，全文见 AI 编程指南 §3.3）：16→§3/§8.2/§8.8/§9.2 · 17→§4/§8.3/§9.2 · 18→§8.4/§8.6~8.7/§9.2 · 19→§6.8 · 20→§6.1~6.6 · 22→§5 · 24→§6.7 · 25→§8.5 · 26→§7 · 29→§11.1~11.2 · 30→§11.3~11.4 · 27/28/31→§12。需求侧每节读需求文档第 11 章对应行。

课型分流：代码课走完整规格流程并产码；评审课（21 Memory、23 Sandbox）只评审不产码；串联课（27/28）不开新规格只固化端到端；Demo 课（31）真实运行与发布。每节完成判据 = 需求篇第 11 章「可演示成果」，产出节级验收报告。节级工作流（七步）与门禁体系（H0~H6、双轨门禁、全局不变量）见 AI 编程指南第 4~5 章。

**验收硬条件**：两个日跑 Demo——每日天气（光杆 AGENT.md，能力一+二+四+五）与每日科技日报（AGENT.md + 公共 Skill + MCP + Memory），都是钟推、支持人推补跑，合起来覆盖全部六个核心能力加第三触发源。

---

## 常见陷阱

从第一天开始记：实施中发现一条记一条（陷阱/症状/修复），高频条目升格为 review 检查单与回归测试守点。文档链已预判的：

| 陷阱 | 症状 | 修复 |
|------|------|------|
| Spring AI 自动执行 tool | Tool 被调两次 | 禁用自动执行，`ToolExecutor` 接管（宪法 2） |
| Spring AI starter 的 eager 自动装配 | boot 上下文启动即强索 `spring.ai.openai.api-key`，显式构造被架空 | 用裸 `spring-ai-openai` 依赖 + 手工构造 `ChatModel`（第 16 节实证，宪法 2/3） |
| Spring AI 默认 RetryTemplate 重试退避极长 | 失败调用挂线程约 19 分钟（16 节上手实测 `duration_ms=1146697`），同步模型下 ReAct 会被拖死 | 17 节接入循环前显式收紧 retry/超时（maxAttempts 与 backoff 按需配置） |
| Spring AI 1.1.x 无静态 toolDefinitions | 课件 M6 写法编译不过（代差） | 工具经 `ToolCallback` 载体（`call()` 抛异常钉死不执行）+ `internalToolExecutionEnabled(false)` |
| Provider 靠类型扫描区分 | 多 Provider 路由错乱 | 显式 `Map<String, ChatModel>`（宪法 3） |
| `AGENT.md` / 子指令放进 Tool 模块 | Agent 目录被当 Tool 注册报错 | 归 `ContextLoader`（宪法 8） |
| 审计只写日志不落库 | 扩展期反解析返工 | 两表 day one 写入（宪法 7） |
| `ddl-auto=update` 迁移 SQLite 表结构 | ALTER TABLE 报错 | 手工建表脚本（宪法 7） |
| ReAct 里用异步 | 复杂度激增 | 同步 + 虚拟线程（宪法 4） |
| `MEMORY.md` 超长不截断 | 注入超 context window | 归档区 4000 字截断，核心区永不截断 |
| Tool 模块拆成多个 | 依赖混乱 | 三合一（宪法 5） |
| P3C「实现类以 Impl 结尾」拦契约实现类命名 | `SpringAiProviderService` implements core 接口被 PMD 阻断，但类名是教学文档拍板定死字面量 | 类级 `@SuppressWarnings("PMD.<Rule>")` 显式抑制 + javadoc 记理由（文档链一致性优先于风格规则，第 17 节实证） |
| 对 Spring AI `@NonNull` 返回值防御判空 | SpotBugs `RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE`（`getResult()`/`getOutput()` 等带 JSR-305 注解） | 按 API 契约直取不判空；对接 1.1.x 返回值前先看注解（第 17 节实证） |
| Windows GBK 控制台跑 spec-kit Python 脚本 | `setup_tasks.py` 等输出 ⚠ 字符触发 `UnicodeEncodeError` 退出非零 | 命令前缀 `PYTHONIOENCODING=utf-8`（另注意本机 localRepository 非默认 `~/.m2`，在 `D:\Developer\DeveloperInstall\maven-repo`） |
| Picocli「组+子命令」语法做成平命令或靠 aliases | `yokeos provider list` 报 `Unmatched argument: 'list'`——aliases 只造别名不造子命令语法；单测只断注册不断调用路径，fat JAR 冒烟才现形（18 节实证） | 组命令挂嵌套 `@Command(name="list")` 子类；harness 断言子命令集 + 冒烟兜底跑真实命令路径 |
| SQLite 并发写立即 `SQLITE_BUSY` | 无池连接默认无 busy_timeout，并发 insert 直接失败而非等待——并发回归测试 flaky | 测试数据源 `SQLiteConfig.setBusyTimeout`；并发用例取「预置后并发命中」保确定性，撞键兜底分支留防御实现（18 节实证） |
| 非交互 shell 读不到 `~/.zshrc` 里的 key；多 provider validate 连坐 | Bash 工具会话 `DEEPSEEK_API_KEY` 时有时无；boot yaml 列了 kimi 而本机无 `KIMI_API_KEY` 时启动即被 validate 拦（清单里 N 个 provider 要 N 个 env 全在，哪怕只用一个） | 真 key 冒烟显式 `source ~/.zshrc`；不用到的 provider 给哑值过存在性校验（validate 只查存在不查真伪，18 节实证） |
| Checkstyle 测试方法名禁下划线与连续大写 | 参照钉版树 snake_case 测试名（如 `xxx_yyy_zzz`）照抄即被 `MethodName`（禁 `_`）与 `AbbreviationAsWordInName`（`IO`/`URL` 等连续大写）双拦，TDD 首跑即红 | 方法名 camelCase 化、避开连续大写缩写词，中文原语义进 `@DisplayName`（参照风格属「瑕疵不继承」，19 节实证）。**28 节细化**：`@Test` 注解方法实际允许 snake_case（google_checks `SuppressionXpathSingleFilter` 按 message 正则 `[a-z][a-z0-9][a-zA-Z0-9]*(?:_…)*` 豁免，25/27 节 snake_case 测试名一直在用），真拦的是**段内第二字符大写**（`cFails` 违段首 `[a-z][a-z0-9]` 形态，28 节实证）——局部变量同款（`LocalVariableName` 拦 `cHistory`） |
| surefire `environmentVariables` 硬编码哑 key 顶掉真实环境变量 | 18 节给 boot 测试注入哑 `DEEPSEEK_API_KEY` 后，`@Tag("integration")` 真调用例全拿哑 key 而 401——env 覆盖对全模块测试生效，「真 key 走 assumeTrue」的假设不成立；离线全绿掩盖了它 | 哑值改 `${env.X}` 透传：真值在则透传、不在则 Maven 保留字面占位串（非空）保存在性校验（19 节 E2E 暴露并修复，17 节冒烟随之复活） |
| AGENT.md 漏写 `tools:` 清单 → 模型零工具可用 | `PromptBuilder` 只带 `Profile.tools` **点名**的工具（点名不在候选集的静默略过）——frontmatter 不写 `tools:` 时模型看不到任何工具，只会口头答复；单测 mock 链路发现不了 | AGENT.md 声明用到的工具清单；端到端用例锚「模型真调到工具」而非只锚答复（19 节 E2E 实证）；20 节补 AgentLoader 启动 WARN 把静默变有痕 |
| Spring AI 1.1.8 `MethodToolCallback.call()` 把 String 返回值 JSON 字面量化、方法异常包 `ToolExecutionException` | `@Tool` 工具回显带引号（`"yoke"`）、`assertThrows` 工具自身异常类型失败（实际抛的是包装类）；引号与包装会污染对话历史与审计 | 包装层（`AnnotatedToolAdapter`）统一剥壳：JSON 字符串字面量还原、`ToolExecutionException` 取 cause 上抛——工具层契约保持干净（20 节实证，引号与壳在 adapter 收口） |
| MCP Java SDK 1.1.1 与课件 0.x 全面代差 | `McpSchema.Tool` 三参构造不存在（七参 record）、`CallToolResult` 无双参构造、`StdioClientTransport` 必须显式传 `McpJsonMapper`（否则编译错）；BOM 也不管此坐标版本 | 测试用 `Tool.builder()`；构造补全四参；transport 传 `JacksonMcpJsonMapper(JsonMapper.builder().build())`（jackson3 传递件唯一消费点）；根 pom 显式钉 `mcp:1.1.1`——全部写前 javap（20 节实证） |
| Checkstyle（google_checks）把 javadoc **行首** `@Tool`/`@Param` 认作 block tag | `JavadocTagContinuationIndentation` 对后续行连锁报错（缩进级别错），误以为格式问题反复 spotless 无效 | javadoc 内 @ 词不置行首：内嵌句中或 `{@code @Tool}` 包裹（20 节实证） |
| SpotBugs CRLF 门禁连**参数化**日志都拦 | `log.warn("…: {} {}", a, b)`（哪怕值已 sanitize）被 `CRLF_INJECTION_LOGS` 拦截——静态分析只认 API 形态不看值 | 唯一通过形态 = 编译期常量消息 + 动态值进异常堆栈（`log.warn("常量", new IllegalArgumentException("name=" + …))`，19/20 节同款；原 `sanitize()` 函数随之无必要） |
| npx 冷缓存起 MCP server 超过 initialize 超时 | 首跑 `npx -y @modelcontextprotocol/server-everything` 下载耗时 > SDK 默认 initializationTimeout 20s → `connectAll` WARN 跳过（集成测试 assumeTrue 跳过不失败），二跑缓存热即正常 | CI/新机先预热一次 npx（或接受首跑 skip）；集成冒烟跑法注明「冷缓存 skip 属正常」（20 节实证） |
| Mockito 逐环 stub builder 式深链（`restClient.post().uri().body().retrieve()`） | 某一环 stub 未命中即返回 null，后续 `.retrieve()` 直接 NPE——排查方向误导为生产代码 | mock 链式接口用 `mock(X.class, Mockito.RETURNS_SELF)` 让 uri/body 自动回环，只显式 stub 链尾（retrieve/toEntity）两处（22 节实证） |
| `@ConfigurationProperties` 绑定提前解析 yaml 里的 `${ENV}` 占位 | 含占位的可选配置段（如 `yokeos.memory.mem0.base-url: ${MEM0_BASE_URL}`）一绑定时就解析，env 缺失启动即失败——「缺省空不阻断启动、使用时才报错」的设计被架空 | 可选段的配置走 classpath yaml 原文读取（SnakeYAML，占位原样保留、切档使用时解析）——16 节 provider 清单同款策略的泛化（22 节实证，research D6） |
| Spotless 与 Checkstyle 对 switch 块内首条注释的缩进要求互斥 | google-java-format 要 8 空格、Checkstyle `CommentsIndentation` 要 4/6——来回 `spotless:apply` 与 checkstyle 轮番红 | 唯一双过形态 = 注释放到 switch **语句之前**，不进块内（24 节实证） |
| P3C `SwitchStatementRule` 对 Java 14+ 箭头 switch 的 default 识别不了 | default 分支实际在位仍报「switch块缺少default」（PMD 6.55 的 AST 不认箭头形态） | `@SuppressWarnings("PMD.SwitchStatementRule")` + javadoc 记工具代差理由（17 节 Impl 命名抑制同款先例，24 节实证） |
| 起 YokeosRuntime 真上下文的 E2E 测试被 deny-all 缺省拦自家 | 加沙箱后清点「构造调用点」不够——`CliFullFlowTest` 这类不经构造语句、直接起真装配上下文的测试，其 http_get 在 classpath 无 yaml 时撞上空域名白名单（deny-all 缺省）而红 | 这类测试用 `@Primary` 覆盖 tools Bean 自备白名单（与 boot 集成测试同款），生产缺省语义不动；改造面清点要 grep「构造调用」+「真上下文测试」两维（24 节实证） |
| Mockito 对 primitive 参数用 `any()` | `recordExecution(..., boolean, ..., long, ...)` 的 matcher 传 `any()`/`any(Long.class)` 返回 null，拆箱即 NPE；且 NPE 抛出后 matcher 栈悬空，**后续用例连环报 InvalidUseOfMatchers/UnfinishedVerification**（报错位置在别人的 setUp，误导排查方向） | primitive 参数一律 `anyBoolean()/anyLong()`；看到「setUp 里第一个 mock() 就报 matcher 误用」先查上一个用例是否拆箱 NPE（25 节实证） |
| 重叠跳过测试用同线程占锁 | 测试线程自己 `lockFor(id).lock()` 后同线程 `tryLock()` 必成功——ReentrantLock 对同线程可重入，「上一次还在跑」的模拟完全失效，verify(never()) 反而红 | 真实重叠是跨线程的（调度线程池）：另一线程占锁 + `CountDownLatch` 双闩协调（占锁完成再触发、断言完再放），参照钉版树同款（25 节实证） |
| core 主代码此前零 Spring 依赖 | 宪法 4 调度池（TaskScheduler/CronTrigger）落 core 时编译即红「程序包 org.springframework.scheduling 不存在」——core 只在 test 域经 starter-test 间接可见 spring-context，plan 层「传递件已有」的假设不查模块依赖就落笔会漏 | 模块级依赖显式声明 `org.springframework:spring-context`（BOM 管版本非新坐标），pom 注释记理由；「零新增依赖」表述要核到**模块 pom** 一级而非全仓 classpath（25 节实证） |
| 静态单例 SQLite 测试库跨用例污染 | 22 节 `MemoryEntryRepositoryTest` 的静态临时库形态被照抄到有「恰一行/唯一任务」全表断言的测试——前序用例的 setEnabled(false)/历史行全部串场，断言「恰一行」变「恰三行」 | 同库形态 + 全表断言 = 必须 `@BeforeEach` 清两表；照抄基建形态时先核对断言口径是否查全表（25 节实证） |
| task_id 用声明序号派生 | `{profileName}#{序号}` 绑定的是声明位置不是任务——**调换顺序/删中间条目/中间插入后重启，早报的 run_count 与执行历史整体错位嫁接给晚报**（reconcile 原地更新定义字段，旧数据无声接错对象）——不是丢状态，是接错账，审计语义被污染 | id 由 frontmatter 作者声明（必填 + profile 内唯一，AgentLoader 剔除坏条目有声日志），task_id = `{profileName}:{id}` 前缀防跨 Agent 撞名（25 节用户实证后修正案） |
| springdoc 2.6.0 与 Boot 3.5 二进制不兼容 | /v3/api-docs 与 swagger-ui 500：`NoSuchMethodError: ControllerAdviceBean.<init>`（2.6.x 面向 Boot 3.3/Spring FW 6.1） | 升 2.8.x 线（定 2.8.13），CLAUDE.md/技 §1.2 已同步；宪法文件内版本字面量留用户 PATCH（26 节实证） |
| `@WebMvcTest` 用在无主类的库模块 | 向上搜不到 `@SpringBootConfiguration` 切片起不来；而给引导类加 `@ComponentScan` 又会**绕过 slice 类型过滤器**——未测 Controller 的依赖把上下文炸掉 | 测试包放裸 `@SpringBootConfiguration + @EnableAutoConfiguration` 引导类（不带扫描），每个测试 `@Import({被测Controller, GlobalExceptionHandler})` 显式登记（26 节实证，参照 `WebSliceTestBoot`） |
| sqlite-jdbc 3.53 的 busy handler 对写冲突不可靠 | 并发 insert/commit 直接 `SQLITE_BUSY` 立即失败——`PRAGMA busy_timeout`、`Properties.busy_timeout`、`SQLiteDataSource.setBusyTimeout` 三形态探针**全部不等待**（18 节坑表「busy_timeout 解并发」只在读并发成立）；Hikari `connection-init-sql` PRAGMA 同样无效 | 生产源 `spring.datasource.hikari.maximum-pool-size=1`（单连接串行化，连接获取层排队）+ `spring.jpa.open-in-view=false`（OSIV 把连接绑到整个请求、跨秒级 LLM 调用，是并发耗尽的放大器）——8 并发 invoke 全 200 实证（26 节） |
| 测试类改系统属性不还原 → 跨类污染 | 25 节 Scheduler E2E `@BeforeAll` 设 `yokeos.root/db.dir` 指向 @TempDir 且不还原——类结束 TempDir 被清，**同 JVM 后跑**的 `@SpringBootTest` 上下文拿到悬空路径 → `SQLITE_CANTOPEN`（单跑绿合跑红，排查方向被误导为新建测试） | 污染源 `@AfterAll System.clearProperty` 还原；新测试 `@SpringBootTest(properties=...)` 自钉关键属性免疫（26 节实证） |
| Spring AI Provider 故障族直穿兜底 500 | 错 key 401 → `NonTransientAiException`（非 IllegalStateException）→ 落 500 兜底，违背技 §7.4「Provider 故障 503」口径 | `GlobalExceptionHandler` 显式映射 `{NonTransientAiException, TransientAiException}` → 503（消息是 Provider 侧原文非内部细节）；错 key 注入实证（26 节） |
| 手跑真 serve（fat JAR 前）三连坑 | ① boot pom mainClass 硬编码 CLI 入口且 XML 配置优先于 `-Dspring-boot.run.mainClass` 覆盖；② `dependency:build-classpath` 解析的是 m2 旧 jar——前序节新类（如 Sandbox/McpJsonMapper）CNFE；③ boot fat jar 嵌套结构不进 `-cp` classpath，application.yaml 丢失 → datasource 报「no driver」 | ① 不用 spring-boot:run，直接 `java -cp ... com.yokeos.cli.YokeOsCli serve`；② 先 `mvn install -DskipTests` 刷新 m2；③ classpath 前置 `yokeos-boot/target/classes`（原始 classes 含 application.yaml）；另 kimi 连坐坑照旧给哑值（25 节实证，31 节 fat JAR 打包课直接受益） |
| Spring AI 1.1.8 `AssistantMessage` 带 tool call 的构造只经 `builder()` | 四参构造（media 尾参）是 **protected**，参照课件 0.x 的三参直构编译不过；`Prompt(Message, Map)` 构造也不存在（只有单 Message / +ChatOptions 两族） | `AssistantMessage.builder().content("").toolCalls(...).build()`——20 节 MCP 七参 record 同款代差家族，写前 javap（27 节实证） |
| 配置走 classpath yaml 原文手工读取的模块，测试属性注入清单**无效** | providers 清单 / Sandbox / memory（16/22/24 节占位策略）不经 Spring 绑定，`@SpringBootTest(properties=...)` 只改 Environment 不改 classpath 原文——想「测试注入一条 mock provider」根本进不了清单 | 注入改在**工厂层**：`providerMap()` 内置常挂 `putIfAbsent("mock", ...)`（生产 yaml 零改动），单测 `ProviderMapMockWiringTest` 钉死；泛化推论：凡 SnakeYAML 直读 classpath 的配置，测试只能改读取输入或换工厂级注入点（27 节实证） |
| 测试类名连续大写同样被 `AbbreviationAsWordInName` 拦（19 节坑只记了方法名） | `MockAgentE2ETest`/`HumanTriggerFlowIT` 类名即红；且「IT 后缀不进 gate」手法在本仓行不通 | 类名 `…EndToEndTest`/`…IntegrationTest`；黑盒类要「不进常规 gate」用**无 Test 后缀**类名（surefire 默认 include 不匹配，`-Dtest` 显式才跑），tag 排除仍是主机制（27 节实证） |
| 坏 provider 名造「Provider 层失败」走不通——AgentLoader 启动期就拦 | 想用 `provider: ghost` 的 Agent 驱动 `task_executions success=false`：校验面即 `providerMap.keySet()`，不在表整目录被 `deriveQuietly` 跳过，Agent 根本进不了注册表 | 测试本地 `FailingChatModel`（`call` 必抛）挂 `@Primary` 覆盖 providerMap、映射名如 `boom`——名字过启动校验、调用期必炸，正复现「map 里有、调用必炸」的故障形态（28 节实证） |
| 钟推会话复用下历史累积影响模型行为——短间隔连续触发可能不重推 | 会跑第三次 `runNow` 断言「推送 ≥3」即红：prompt 里带着几秒前「刚查过、刚推过」的历史，真模型判定无需重复动作（任务成功、账面齐全，物理推送停在 2） | 断言分层：「调度器不死」锚**账面**（run_count 自增、执行历史 success、新 llm_calls 落账），物理推送只锚前几次；日跑 Demo 报文写明「无论历史如何本次都要重新执行」可压此象（28 节实证） |
| AGENT.md 正文用 Agent 目录内相对路径指引附属脚本 | `python3 scripts/reconcile.py` 在 shell 工具的 cwd（工作区根）下找不到文件——真模型 10 轮重试同一条注定失败的命令烧穿 max_iterations，回复被「达到最大轮数」吞掉；审计表里 10 条 success=false 的 shell 是唯一线索 | 正文指引写**工作区根相对路径**（`.yokeos/agents/<name>/scripts/x.py`）并注明「shell 的工作目录是工作区根」；排查多轮不收敛先查 `tool_invocations` 的 error_message（29 节真跑实证，审计表反解即宪法 7 的价值现场） |
| notify 占位 `${OPS_WEBHOOK_URL}` 未配置即真跑示例 Agent | 占位解析失败保留字面量 → webhook「no host」失败 → 模型把「推送未完成」当任务未竟，从头重跑全流程（跑脚本→写报告→推→又失败→循环） | 演示环境必须 export 真值（webhook.site 等一次性端点即可）；或正文明确「推送失败不阻断报告产出」——模型对失败渠道的执念只能靠指令拆解（29 节真跑实证） |
| 给共享值对象/注册表加 mutator 触发 SpotBugs 可变性判定连锁 | `ProfileRegistry` 补 `remove` 后，**全部构造持有方**（AgentService/CliChannel/三个 ApiController）新报 `EI_EXPOSE_REP2` 逐模块拦 verify——单模块绿≠全量绿，连锁在下游模块才现形 | 持有方逐个补类级 `@SuppressFBWarnings({"EI_EXPOSE_REP","EI_EXPOSE_REP2"})` + justification（25 节 AgentScheduler 先例）；缺 `spotbugs-annotations` 依赖的模块按 web 模块同款补 provided 依赖（29 节实证，连锁面= grep `private final ProfileRegistry` 清点） |
| `.formatted` 写在分段拼接的最后一段字面量上 | `"A%s" + "B".formatted(x)`——formatted 只作用于紧邻字面量，前段 `%s` 原样落盘（30 节集成测试 AGENT.md 落盘 `name: %s` → SnakeYAML 炸 `%`） | 分段拼接先整体括号再 `.formatted`（`("A%s" + "B").formatted(x)`） |
| MockMvc 轮询探针用 jsonPath `exists()` 断言 | `exists()` 失败抛 **AssertionError（Error 族）不进 `catch (Exception)`**——列表为空时第一圈就炸出测试，0.018s「假超时」、从未真正等待 Watcher（30 节实证） | 轮询探针取响应 body 解析比对返回 boolean、不抛断言；断言只放轮询出口 |
| macOS 上写子目录内文件**不触发**父目录级 WatchService 事件 | 「目录 CREATE 先到、AGENT.md 后落盘，靠后续事件二次注册收敛」的假设在 macOS 不成立——首次注册失败 WARN 后**永不收敛**（参照回写 5.2.3 同结论，其钉版树无真丢目录测试故未暴露；30 节集成测试实证） | Watcher 对 CREATE/MODIFY 注册失败走**有界延迟重试**（5×500ms，经执行器排队）主动收敛，耗尽才 WARN 放弃 |
| 集成测试用 mock provider 但 AGENT.md 不写 `model:` 行 | `MockChatModel` 响应无 model 元数据 → `llm_calls.model` NOT NULL 约束炸 → invoke 500（30 节实证） | mock Agent 的 frontmatter 必须写 `model:` 行（任意值）——审计列非空是 day one 纪律的硬约束 |
| 测试方法名数字段后缀被 Checkstyle `MethodName` 拦 | `xxx_400`/`xxx_404` 违段形态（下划线后跟数字）；字母段 `_notFound` 放行——19/28 节坑的细化（30 节再实证） | 后缀语义用英文词（`BadRequest`/`NotFound`），中文原语义进 `@DisplayName` |
| create 路径漏 name 一致性校验 → 幽灵 Agent | 草稿 frontmatter name（模型起，常为中文）≠ create 的 name 参数时：注册键取 profile.name()、目录锚 name 参数——**错位上线**，列表可见但「查看/编辑」读 AGENT.md 400（30 节 IDEA 真跑实证）；E2E 测试的「人在环模拟」替用户改写了草稿 name，把真实路径掩盖 | create 与 update 同款加 `parse` 前置（name 不一致 400 零写入）+ 前端创建时自动把草稿 name 行改写为用户填的名字（防线 + 体验双层） |

---

## 对外门面（website/）

VitePress 双语站点：en 根 + `/zh/`，深色默认 + 浅色切换，base 硬编码 `/YokeOS/`（与仓库名一致；换自定义域需全局改）。base 行为：md 正文与 `themeConfig.logo` 自动加前缀，`head` 里的 favicon 不被处理需硬编码。部署 workflow：push master + paths `website/**` 触发 + workflow_dispatch，concurrency 防并发。首页为编辑部式排版（左对齐 + 琥珀高亮 + 词马灯 + 滚动显影），动效尊重 `prefers-reduced-motion`；文档页口径 = 目标形态先行，实现承载页标注「第一阶段进行中，随实现回写」。

---

## Agent skills

### 立项文档链 skill（本仓 `.claude/skills/`）

`industry-research` → `product-positioning` → `demand-analysis` → `technical-solution` → `ai-programming-guide`：五篇的过程固化，触发词见各 SKILL.md；`arch-diagram` 为设计文档手绘 SVG。文档链已完结，后续仅随 ADR 演进。

### 节级开发 skill

`yokeos-lesson-dev`：逐节开发（16→31）的一站式流程——H0 备料 → specify → clarify → plan（人工 review）→ tasks（固定停点）→ analyze → implement（三层门禁）→ 六项证据验收。输入节号即用（`/yokeos-lesson-dev 17`）；七步细则与门禁以 `docs/AiProgrammingGuide.md` §4~5 为权威，worked example 见 `specs/001-provider-abstraction/`。

### Issue tracker

工单与规格存放在 GitHub Issues（XianReallyHot-ZZH/YokeOS），通过 `gh` CLI 操作。见 `docs/agents/issue-tracker.md`。

### Triage labels

默认五角色标签，标签字符串与角色名相同。见 `docs/agents/triage-labels.md`。

### Domain docs

单上下文布局：根 `CONTEXT.md` + `docs/adr/`。见 `docs/agents/domain.md`。术语以 CONTEXT.md 为准（人推/钟推、复刻型起步、Harness 等）。

### Initiation docs

立项文档链五篇与参照库立项期 docs 一一对应（ADR 0007）：业界调研 `docs/IndustryResearch.md` → 产品定位 `docs/yokeos.md` → 需求分析 `docs/DemandAnalysis.md` → 技术方案 `docs/TechnicalSolution.md` → AI 编程指南 `docs/AiProgrammingGuide.md`。
