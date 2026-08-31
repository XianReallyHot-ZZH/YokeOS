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
| API 文档 | springdoc-openapi 2.6.0 |
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

**AGENT.md frontmatter**（→ `deriveProfile` → `Profile`）：`name`、`description`、`identity`（`agent_name`、`prompt`）、`provider`（`name`、`model`、`temperature`、`api_key: ${ENV_VAR}`）、`tools`、`skills`（按名引用）、`mcp_servers`、`channels`、`notify.channels`（`name`/`type: webhook`/`config`）、`schedules`（cron + 时区 + 消息）、`bootstrap`、`settings`（`max_iterations` 默认 10、`max_history_turns` 默认 20）。

**SQLite 六张表**（手工建表脚本）：

| 表 | 用途 | 要点 |
|----|------|------|
| `sessions` | 会话元数据 + JSON 对话历史 | `session_id` = channel+user+agent 联合生成；`active`/`archived` |
| `tool_invocations` | 审计：每次 Tool 调用 | **day one 写入**；Sandbox 拒绝也走此表（`success=false`） |
| `llm_calls` | 审计：每次 LLM 调用 | **day one 写入**；token 用量 + 耗时 |
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

统一前缀 `/api/v1`，统一信封 `{code, message, data, timestamp}`。第一阶段 **18 个端点**按五组：会话管理 4（sessions CRUD + messages）· Agent 调用与动态管理 7（`generate` 一句话草稿不落盘、CRUD、`invoke`）· 工作区 2（tree / file 只读）· 信息查询 3（profiles / memory / tools）· 系统状态 2（health / info）。

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
| Provider 靠类型扫描区分 | 多 Provider 路由错乱 | 显式 `Map<String, ChatModel>`（宪法 3） |
| `AGENT.md` / 子指令放进 Tool 模块 | Agent 目录被当 Tool 注册报错 | 归 `ContextLoader`（宪法 8） |
| 审计只写日志不落库 | 扩展期反解析返工 | 两表 day one 写入（宪法 7） |
| `ddl-auto=update` 迁移 SQLite 表结构 | ALTER TABLE 报错 | 手工建表脚本（宪法 7） |
| ReAct 里用异步 | 复杂度激增 | 同步 + 虚拟线程（宪法 4） |
| `MEMORY.md` 超长不截断 | 注入超 context window | 归档区 4000 字截断，核心区永不截断 |
| Tool 模块拆成多个 | 依赖混乱 | 三合一（宪法 5） |

---

## 对外门面（website/）

VitePress 双语站点：en 根 + `/zh/`，深色默认 + 浅色切换，base 硬编码 `/YokeOS/`（与仓库名一致；换自定义域需全局改）。base 行为：md 正文与 `themeConfig.logo` 自动加前缀，`head` 里的 favicon 不被处理需硬编码。部署 workflow：push master + paths `website/**` 触发 + workflow_dispatch，concurrency 防并发。首页为编辑部式排版（左对齐 + 琥珀高亮 + 词马灯 + 滚动显影），动效尊重 `prefers-reduced-motion`；文档页口径 = 目标形态先行，实现承载页标注「第一阶段进行中，随实现回写」。

---

## Agent skills

### 立项文档链 skill（本仓 `.claude/skills/`）

`industry-research` → `product-positioning` → `demand-analysis` → `technical-solution` → `ai-programming-guide`：五篇的过程固化，触发词见各 SKILL.md；`arch-diagram` 为设计文档手绘 SVG。文档链已完结，后续仅随 ADR 演进。

### Issue tracker

工单与规格存放在 GitHub Issues（XianReallyHot-ZZH/YokeOS），通过 `gh` CLI 操作。见 `docs/agents/issue-tracker.md`。

### Triage labels

默认五角色标签，标签字符串与角色名相同。见 `docs/agents/triage-labels.md`。

### Domain docs

单上下文布局：根 `CONTEXT.md` + `docs/adr/`。见 `docs/agents/domain.md`。术语以 CONTEXT.md 为准（人推/钟推、复刻型起步、Harness 等）。

### Initiation docs

立项文档链五篇与参照库立项期 docs 一一对应（ADR 0007）：业界调研 `docs/IndustryResearch.md` → 产品定位 `docs/yokeos.md` → 需求分析 `docs/DemandAnalysis.md` → 技术方案 `docs/TechnicalSolution.md` → AI 编程指南 `docs/AiProgrammingGuide.md`。
