# OryxOS 架构调研报告（供 YokeOS 产品层复刻使用）

> 调研对象：https://github.com/oryx-labs/oryxos
> 本地参照库（git submodule，只读）：`D:\Developer\Github\my-projects\YokeOS\vendors\oryxos`
> 调研基准：submodule HEAD `45fa211`（main），共 64 个 commit
>
> **路径约定**：下文所有 ` vendors/oryxos/...` 相对路径均指 `vendors/oryxos/` 下的文件；
> 关键结论同时给出 GitHub 链接 `https://github.com/oryx-labs/oryxos/blob/main/<同路径>`。
> 本仓库代码注释为中文，且以「第 N 节」标注来源——它是一套课程课件的配套实现
> （`git log` 中 `feat: 第16节：Agent Provider...` ~ `第30节`），「节号」同时也是 `specs/` 特性目录的编号依据（见 §6）。

---

## 目录

1. [项目定位与总体描述](#1-项目定位与总体描述)
2. [技术栈与构建/运行/测试](#2-技术栈与构建运行测试)
3. [模块分解（核心章节）](#3-模块分解核心章节)
4. [核心功能清单：作为 Agent OS 它能做什么](#4-核心功能清单作为-agent-os-它能做什么)
5. [架构模式与扩展点](#5-架构模式与扩展点)
6. [specs/ 与 .specify/ 目录概览](#6-specs-与-specify-目录概览)
7. [CLAUDE.md 内容摘要](#7-claudemd-内容摘要)
8. [仓库规模统计](#8-仓库规模统计)
9. [复刻要点与避坑清单](#9-复刻要点与避坑清单)

---

## 1. 项目定位与总体描述

### 1.1 一句话定位

**OryxOS 是一个 Java 21 实现的开源「分布式 AI Agent OS」**：一份配置定义一个 Agent，一个平台运行一队 Agent。
它把自己定位为「OS 层的地基」而不是「又一个 Agent 框架」——让 Agent 像 OS 上的进程一样被运行、调度、共享基础设施
（渠道接入、LLM 路由、工具、记忆、沙箱执行）。

> 出处：`vendors/oryxos/README.md:5-6`（"Distributed AI Agent OS — let agents run and collaborate like processes on an OS"）、
> `README.md:18`（"One config defines one Agent; one platform runs a fleet"）
> https://github.com/oryx-labs/oryxos/blob/main/README.md

### 1.2 它要解决的痛点（Why）

README 给出的论证链非常清晰，值得在复刻时继承这套叙事：

- 现有 Agent 生态几乎全是 **Python 系 / 云耦合 / 开发者原型**；对「后端标准是 Java、私有化部署是合规红线」的企业，
  Java 生态里没有一个原生、生产可用的 Agent OS。OryxOS 填这个空位。（`README.md:22-26`）
- **更根本的判断：生产环境里可靠 Agent 的瓶颈不是模型，而是运行时环境**——正确的上下文、受控的工具、隔离可审计的执行、
  跨节点可靠的投递。模型决定聪明程度，底座决定能不能真的干活。（`README.md:26`）

### 1.3 Agent Runtime vs Agent OS（README:28-36）

| | Agent Runtime | Agent OS |
| --- | --- | --- |
| 范围 | 跑**一个** agent | 管理**一队** agents |
| 类比 | 进程执行环境 | 管理进程、调度、共享服务的 OS |
| 提供 | 模型调用、工具执行、上下文、循环 | 生命周期、渠道、记忆、治理、跨节点协同 |

OryxOS 自我定位为后者。

### 1.4 五大核心能力（README:61-69）

| 能力 | 说明 | 落点模块 |
| --- | --- | --- |
| **LLM Routing** | Provider 抽象统一主流模型，Agent 与 provider 解耦，改 YAML 即切换，支持本地推理 | `oryxos-provider` |
| **ReAct Loop** | 自实现推理引擎，不用任何外部框架。LLM 决定是否/调哪个工具，OryxOS 执行并回填，LLM 决定下一步；循环完全可控 | `oryxos-core` |
| **Memory** | 跨会话状态持久化：会话记忆 + 长期记忆（文件基、关键词检索、向量检索升级路径），自动注入每条 system prompt | `oryxos-memory` |
| **Tool System** | 内置 file/shell/http 工具 + 三档扩展：零代码 SKILL.md/社区 MCP → 轻代码自建 MCP server → 重代码原生 `@Tool` | `oryxos-tool` |
| **REST API** | 全部能力走 REST，任意语言接入 | `oryxos-web` |

### 1.5 路线图与「分布式」的真实落点（重要，避免误读）

README:71-82 明确写了三阶段，**当前仓库（Phase 1）是单机运行时内核，"分布式"是 Phase 2/3 的目标而非已实现能力**：

- **Phase 1 — 单机运行时内核**（当前）：config-as-agent、多 Agent 并存、REST API、MCP 集成。目标是「单机跑起来并管理一队 Agent，真的能用」。
- **Phase 2 — 分布式地基**（规划）：无状态实例、状态外置、多副本部署。
- **Phase 3 — 跨节点 Agent 协同**（愿景）：引入 Agent 通信基础设施、集成 A2A 协议、跨节点发现/委派/可靠异步协同。

> **对复刻的直接启示**：仓库里**没有**任何消息队列、服务注册、RPC 框架、分布式锁。它为分布式做的唯一准备是**架构纪律**
> （状态全部外置到 SQLite/文件系统、实例无状态、接口契约上移 core），见 §5.4。
> 出处：`vendors/oryxos/README.md:71-82`、`README.md:226`（"Stateless instances — state externalized from the start"）

### 1.6 设计原则（README:220-228）

- **Platform before Agent**——最重要的交付不是某个强大的 Agent，而是让任意 Agent 可靠运行的环境
- **Self-implement the core**——推理循环手写；协议适配复用成熟库
- **Config = Agent**——Agent 由配置定义，不由代码定义
- **Open standards**——工具用 MCP、协作用 A2A、技能用 SKILL.md / Agent 目录形态，不自造协议
- **Stateless instances**——状态从第一天就外置
- **Security as foundation**——工具来源受控、最小权限、强制沙箱、凭证永不落盘、审计从第一天就写库
- **Phased and disciplined**——先做最小完整内核，架构升级由真实使用数据验证

---

## 2. 技术栈与构建/运行/测试

### 2.1 技术栈总表

出处：`vendors/oryxos/pom.xml`、`vendors/oryxos/README.md:230-242`、`vendors/oryxos/CLAUDE.md:11-24`

| 组件 | 选型 | 出处 |
| --- | --- | --- |
| 语言 / 运行时 | **Java 21**（必选，virtual thread 处理并发） | `pom.xml:23` `<java.version>21</java.version>` |
| 框架 | **Spring Boot 3.3.5**（`spring-boot-starter-parent`） | `pom.xml:7-12` |
| LLM 集成 | **Spring AI 1.0.0-M6**（BOM）+ **Spring AI Alibaba 1.0.0-M6.1**——**只**用协议转换 + `@Tool` schema 生成 | `pom.xml:24-25` |
| HTTP 服务 | Spring MVC（**非** WebFlux）+ 虚拟线程 | `vendors/oryxos/oryxos-boot/src/main/resources/application.yml` `spring.threads.virtual.enabled: true` |
| CLI | **Picocli 4.7.6** | `pom.xml:26` |
| YAML | SnakeYAML（Profile / mcp_servers.yaml） | `vendors/oryxos/oryxos-tool/pom.xml` |
| 持久化 | **SQLite**（sqlite-jdbc 3.46.1.0）+ **Spring Data JPA** + Hibernate community SQLite 方言 | `pom.xml:27`、`oryxos-storage/pom.xml` |
| MCP | 官方 MCP Java SDK，经 `spring-ai-mcp` 传递引入，**仅 stdio transport** | `oryxos-tool/pom.xml` |
| 日志 | Logback + SLF4J + logstash-logback-encoder（结构化 JSON） | `pom.xml:30` |
| API 文档 | springdoc-openapi 2.6.0（Swagger UI） | `pom.xml:31` |
| 可观测 | Spring Boot Actuator + Micrometer/Prometheus | `oryxos-boot/pom.xml` |
| 前端（管理台） | **Vue 3.5 + Vite 6**，单文件 `App.vue`（899 行），仅 `vue` 一个运行时依赖 | `oryxos-web/src/main/frontend/package.json` |
| 官网 | **VitePress 1.6.4**，中英双语 | `website/package.json` |
| 构建 | Maven 多模块（9 个） | `pom.xml:50-60` |

### 2.2 Maven 多模块结构

`vendors/oryxos/pom.xml:50-60` 声明 9 个 module（注意：`my-agent/` 不是 Maven 模块，只是个种子工作区样例，见 §3.10）：

```
oryxos-core → oryxos-provider → oryxos-memory → oryxos-tool
            → oryxos-channel-cli → oryxos-web → oryxos-storage → oryxos-cli → oryxos-boot
```

**根 POM 的工程质量门禁**（`pom.xml:160-363`，这是它作为「企业级方法论示范仓」的另一半价值）：

| 插件 | 作用 | 备注 |
| --- | --- | --- |
| `spotless-maven-plugin` 2.43.0 | google-java-format (GOOGLE style) + 去无用 import + 统一 import 顺序 | `check` goal 绑定构建 |
| `maven-pmd-plugin` 3.21.2 + **阿里 P3C 2.1.1** | 8 个 `ali-*.xml` 规则集 | `targetJdk=17`（P3C 2.1.1 最高只支持 PMD 6.55 / Java 19 语法），并**升级 ASM 到 9.7** 以解析 Java 21 字节码（`pom.xml:222-230`） |
| `maven-checkstyle-plugin` 3.5.0 | google_checks，仅 error 级 | 兜底格式门禁，绑定 `validate` 阶段 |
| `spotbugs-maven-plugin` 4.8.6.4 + **findsecbugs 1.13.0** | effort=Max, threshold=Low | 安全静态扫描 |
| `owasp dependency-check` 10.0.4 | `failBuildOnCVSS=8` | **默认 skip**（`owasp.skip=true`，NVD 下载太慢），CI 里 `-Dowasp.skip=false` 打开（`pom.xml:45-47`） |
| surefire | 默认 `excludedGroups=integration` | 冒烟 IT 本地跳过，`mvn test -Dgroups=integration -DexcludedGroups=` 打开（`pom.xml:162-171`） |

其他工程约定：

- `.editorconfig`：全仓 LF、**Java 也用 2 空格缩进**、`max_line_length=100`（与 google-java-format 对齐）
- `.mvn/jvm.config`：5 条 `--add-exports jdk.compiler/com.sun.tools.javac.*=ALL-UNNAMED`（google-java-format 在 JDK 21 上需要反射访问 javac 内部 API —— **JDK 升级时第一个会炸的点**）
- `.pre-commit-config.yaml`：两个 local hook（`spotless-apply` + `spotless-check`），本地与 CI 用同一套格式化器

### 2.3 构建

```bash
mvn package -DskipTests     # 编译全部模块 + 把 Vue 管理台打进 fat JAR
```

关键机制：**`oryxos-web/pom.xml` 用 `frontend-maven-plugin 1.15.1`，`nodeVersion=v20.18.0`，
首次构建自动安装本地 Node.js —— 不要求机器上有全局 Node**。三个 execution（install-node-and-npm / npm install / npm run build）
全部绑在 `generate-resources` 阶段，所以「一条 mvn 命令出全量包」。
可用 `-Dfrontend.skip=true` 跳过前端加速纯 Java 构建。

> 出处：`vendors/oryxos/oryxos-web/pom.xml:50-90`、`vendors/oryxos/README.md:103`
> https://github.com/oryx-labs/oryxos/blob/main/oryxos-web/pom.xml

### 2.4 运行

fat JAR 的 **mainClass 指向 picocli CLI 而非 Spring 主类**（`vendors/oryxos/oryxos-boot/pom.xml:81`）：
`<mainClass>io.oryxos.cli.OryxOsCli</mainClass>`——**一个 jar 同时是 `oryxos` 命令行和服务器**。

```bash
JAR=oryxos-boot/target/oryxos-boot-*.jar
java -jar $JAR init                       # 初始化 .oryxos/ 工作区
export DEEPSEEK_API_KEY=xxx               # CLI 从环境变量读 key
java -jar $JAR chat --profile default     # 交互式多轮对话
java -jar $JAR serve --port 8080          # REST API + Web Manager
```

一键脚本：

| 脚本 | 作用 |
| --- | --- |
| `vendors/oryxos/bin/start.sh` | 定位 jar → `config/application.yml` 不存在则 cp 模板并退出提示填 key → pid 防重 → `nohup java -Dspring.config.additional-location="optional:file:$ROOT/config/" -jar $JAR serve --port $PORT &` → **轮询 45 次 `curl /api/v1/health`**，进程中途死则贴 `tail -15` 日志并清 pidfile（"不再谎报 OK"）→ 打印 `/api/v1/health`、`/admin/`、`/swagger-ui`、日志四个 URL |
| `vendors/oryxos/bin/stop.sh` | 按 `bin/oryxos.pid` 优雅 kill，10 秒未退则 `kill -9`；pidfile 缺失/进程已死都安静返回 0 |
| `vendors/oryxos/scripts/package.sh` | 增量打包 + 远端同步部署（`git diff` 三路合并出变更文件 → tar.gz → scp 到远端 → 远端解包覆盖 + 按删除清单 `rm` → 远端 commit/push 带退避重试 → 本地 `fetch` + `reset --hard` 收敛），与复刻无关但体现工程纪律 |

### 2.5 配置体系（三层）

1. **jar 内置** `vendors/oryxos/oryxos-boot/src/main/resources/application.yml`——所有默认值（端口、虚拟线程、autoconfigure 排除、SQLite、沙箱白名单、memory backend、Actuator、springdoc）
2. **外部覆盖** `vendors/oryxos/config/application.yml.example`（真身 gitignored）——**只放 `oryxos.providers` 一段**，其余继承 jar 内置
3. **Agent 级** `.oryxos/agents/<name>/AGENT.md` 的 YAML frontmatter——每个 Agent 自己的 provider/model/tools/notify_channels/schedules

凭证纪律：`api_key: ${DEEPSEEK_API_KEY}` 走环境变量占位，**绝不明文落 YAML**；`ProvidersProperties.validate()` 在启动时点名报错
（名字空/重复、api-key 缺失或仍含 `${`、base-url 缺失），不静默失败。
> 出处：`vendors/oryxos/oryxos-provider/src/main/java/io/oryxos/provider/ProvidersProperties.java`、`vendors/oryxos/CLAUDE.md:342-350`

### 2.6 测试

- 单测 61 个文件 / 6708 行（**测试行数接近主代码行数的 87%**，见 §8）
- IT 用 `@Tag("integration")` 标注，本地默认跳过，CI 与手动 `-Dgroups=integration` 打开
- `oryxos-boot/src/test/java/io/oryxos/boot/` 是 E2E 主战场：`MockAgentE2ETest`、`MockProviderFlowTest`、`HumanTriggerFlowIT`、
  `RestartRecoveryIT`（**跨重启恢复**）、`ScheduledTaskE2ETest`、`SchedulerFlowIT`、`LiveApiIT`——
  全部用 `@SpringBootTest(classes = OryxOsRuntime.class, webEnvironment = RANDOM_PORT)`
- 亮点：`MockChatModel` 让**整条链路不需要任何 API key 即可 E2E**（`oryxos-provider/.../MockChatModel.java`）；
  `MockAgentE2ETest` 即依赖它

---

## 3. 模块分解（核心章节）

### 3.0 依赖方向总图

读自各模块 `pom.xml` 的 `<dependencies>`（`vendors/oryxos/<module>/pom.xml`）：

```
                          ┌──────────────────────────────────────────────┐
                          │  oryxos-core   （契约层，零内部依赖）             │
                          │  OryxTool/ReActLoop/Profile/ProviderService/  │
                          │  MemoryService/SessionManager/SandboxWhitelist│
                          └──────────────────────────────────────────────┘
                              ▲          ▲          ▲          ▲
        ┌─────────────────────┘          │          │          └──────────────────┐
        │                                │          │                             │
  oryxos-provider                  oryxos-tool  oryxos-storage               oryxos-channel-cli
  依赖: core                       依赖: core    依赖: core                  依赖: core
  + spring-ai-alibaba-starter      + spring-ai-core  + spring-boot-starter-data-jpa
  + spring-ai-openai               + spring-ai-mcp   + sqlite-jdbc
                                   + snakeyaml       + hibernate-community-dialects
                                   + spring-web
        │                                │          │
        │                                │          │  (★ 注意：memory → storage)
        │                                └──────────┼──────────────┐
        │                                           ▼              │
        │                                     oryxos-memory        │
        │                                     依赖: core + storage  │
        │                                     + spring-web + spring-ai-core
        │                                                          │
        └──────────────┬──────────────┬──────────────┬─────────────┘
                       ▼              ▼              ▼
                  oryxos-cli   （依赖 core + channel-cli + provider
                       │              + storage + tool + memory；不含 web）
                       │                    ▲
                       └────────────────────┘
                  oryxos-boot  （依赖全部 8 个模块 + actuator/prometheus）

   oryxos-web  ──依赖── 仅 oryxos-core  ★ 关键：web 不依赖 tool / storage / provider
```

**三条铁律（复刻时必须保持）**：

1. **`oryxos-core` 零内部依赖**，只依赖 `spring-boot-starter`（为 `TaskScheduler`）+ jackson。所有跨模块契约（接口 + 值对象）都放 core，下游实现 —— 依赖倒置。若契约留在下游模块必然成环（如 `MemoryService` 留在 memory 会让 core→memory 依赖反向成环，代码注释里明确写了这条推理，见 `vendors/oryxos/oryxos-core/src/main/java/io/oryxos/core/memory/MemoryService.java`）。
2. **禁止循环依赖**（`vendors/oryxos/CLAUDE.md:50`）。
3. **`oryxos-web` 只依赖 `oryxos-core`**——它注入的全是 core 接口（`ProviderService`、`SessionManager`、`SandboxWhitelist`、`OryxTool`），实现类由装配方注入。新增一个 Tool/Channel 模块，web 层一行不改。

### 3.1 `oryxos-core` —— 契约与推理内核（36 个主类 / 2113 行）

**职责**：全部跨模块契约 + ReAct 推理内核 + Agent 生命周期编排。**不含任何 Spring AI 类型**。

包结构（`vendors/oryxos/oryxos-core/src/main/java/io/oryxos/core/`）：

| 包 | 类 | 职责 |
| --- | --- | --- |
| (根) | `OryxTool` | **所有 Tool 的统一抽象**：`getName()/getDescription()/getInputSchema()/execute(JsonNode)`（`getInputSchema()` 返回 JSON Schema **文本**）。出处：`core/OryxTool.java` |
| (根) | `ToolResult` | `success / content / errorMessage / retryable`（`retryable` 用于 MCP 网络抖动提示模型重试） |
| `agent/` | `ReActLoop` | **Agent 的大脑，核心循环仅 20 行**。循环只做调度：转圈、判停、攒结果。拼上下文归 `PromptBuilder`、调模型归 `ProviderService`、执行工具归 `ToolExecutor`——"循环里塞的东西越少越不容易出 bug"。见下方代码 |
| `agent/` | `PromptBuilder` | 每轮 Prompt 组装者，四段按固定顺序：① system（`ContextLoader` 供给 + 当前日期时间行）② 长期记忆（`MemoryService.buildContext(session)`）③ 对话历史（按**轮**截断，一轮 = 一条 user 消息及其后全部）④ 工具列表（不进文本，经 `ProviderRequest.availableTools()` 传给 Provider 翻译成 Function Calling） |
| `agent/` | `ToolExecutor` | **工具执行的唯一路径**。置入 `ToolExecutionContext.setAgentName(agentName)` → 执行 → **先落审计再还结果**（成功失败都记）→ `finally` 清 ThreadLocal。工具异常转失败 `ToolResult` 交还循环，不上抛不中断 |
| `agent/` | `AgentService` | **三种触发源（CLI / Web / 定时）的统一编排入口** `process(Session, String)`。`ProfileContext` 生命周期在此收口：入口 set、出口 finally clear（防复用线程串号），`sessionManager.save(session)` 只在正常路径 |
| `agent/` | `AgentLoader` | **「一个目录 = 一个 Agent」的执行者**：扫 `.oryxos/agents/`，每个子目录的 `AGENT.md` 派生成 `Profile` 并登记。坏目录记 ERROR 跳过、不阻断其余加载；`tools` 引用未注册能力 WARN 但仍登记 |
| `agent/` | `AgentMarkdown` | 把 `AGENT.md` 拆成 frontmatter（YAML）与正文（任务指令）。`---` 围栏；无围栏时整篇当正文 |
| `agent/` | `AgentLifecycleService` | **Agent 生命周期编排（第 30 节）**：三条录入路径（API create / WorkspaceWatcher 事件 / 启动扫描）汇到同一段 `register(Path)`。`create()` 只需 name+description，后台按模板脚手架出**完整目录**（AGENT.md + `scripts/example.py` + `skills/example.md` + `REFERENCE.md`）→ 注册，中途失败回滚已写目录。`generateFiles()` 用 LLM 按一句话需求生成 AGENT.md 草稿（**只生成、不落盘、不注册**），并剥掉模型多吐的 ` ``` ` 围栏。`delete()` 顺序严格：注销定时 → 移出注册表 → 目录归档（不物理删） |
| `agent/` | `AgentScheduler` | **第三触发源「钟推」**：到点自己拼一条消息交给 `AgentService.process`，走跟人推完全一样的入口。四个坑的解法：配置驱动（`TaskScheduler.schedule(...)` 动态注册，不用 `@Scheduled`）、重叠跳过（按任务 id 的进程内 `ReentrantLock` + `tryLock`，非分布式锁）、失败隔离（单次失败只记日志不外抛、`finally` 必放锁）、时区显式（`CronTrigger` 带 `ZoneId`） |
| `agent/` | `WorkspaceWatcher` | 第二条录入路径：JDK `WatchService` 实时监听 `.oryxos/agents/`，任何目录增/改/删汇到与 API 上传**同一段** `AgentLifecycleService.register(Path)`。守护线程跑，不把异步模型引进请求链路 |
| `agent/` | `AgentStore` | Agent 目录文件读写，限定在 `.oryxos/` 内；`archive()` 把目录移进 `.oryxos/archive/`；name 必须是安全目录段（防路径穿越） |
| `agent/` | `ProfileContext` | ThreadLocal「当前是哪个 Agent」——`OryxTool.execute` 签名不带 Profile，但 notify 等工具需要读当前 Agent 的 `notify_channels` |
| `agent/` | `ToolExecutionContext` | ThreadLocal「这一步是替哪个 Agent 跑的」——第 30 节 Agent 专属记忆（`save_memory` 落到本 Agent 自己的 MEMORY.md） |
| `agent/` | `ToolInvocationAuditor` / `ScheduledTaskStore` / `ScheduledTaskView` / `TaskExecutionView` | 契约 / 视图 |
| `context/` | `ContextLoader` | **system prompt 上下文供给者**：`identity.prompt` + 该 Agent 自己 `AGENT.md` 去掉 frontmatter 的正文 + `profile.bootstrap()` 文件按序拼接。**两条铁律：每次调用重新读文件、无任何缓存**（用户改完正文下一次触发立即生效）；Bootstrap 缺失 WARN（静默跳过会造成"人格悄悄丢了"） |
| `memory/` | `MemoryService` / `MemoryScope` | 记忆统一门面契约 + `CORE`（始终完整在场）/ `ARCHIVAL`（量大、超限截断）两分区枚举。**接口上移 core 的原因：PromptBuilder（core）必须注入它** |
| `profile/` | `Profile` | **一个 Agent 的完整配置载体，不可变 Java record**。字段：`name, description, identity(agentName, prompt), provider(name, model, temperature), tools[], mcpServers[], channels[], notifyChannels[], schedules[], bootstrap[], settings(maxIterations, maxHistoryTurns)`。集合字段一律防御性拷贝 |
| `profile/` | `ProfileLoader` / `ProfileRegistry` / `ProfileValidationException` | 启动扫描 / 内存索引（第 29 节起改为可变并发 Map，补 `register/remove/exists`）/ 校验异常 |
| `provider/` | `ProviderService` / `ProviderRequest` / `ProviderResponse` / `ToolCallRequest` / `Usage` / `LlmCallAuditor` | LLM 调用契约。`ToolCallRequest` 注释明确 **"Provider 绝不执行"**；`LlmCallAuditor` 契约要求实现方自吞内部异常 |
| `sandbox/` | `SandboxWhitelist` | 白名单运行时管理契约（查询/增加/删除）。**安全提示写在接口注释里**：核心阶段 Web API 假设内网无认证，暴露这组端点必须靠网络层兜底 |
| `session/` | `Session` / `SessionManager` / `Message` / `SessionSummary` | 会话。`SessionManager` 契约要求 **session_id 由 channel+user+profile 三元组生成，拼接只允许发生在实现内部一处**（"两处各拼一遍、格式差一个分隔符，同一个人就会出现两条互不相认的历史"） |

**ReActLoop 全文**（`vendors/oryxos/oryxos-core/src/main/java/io/oryxos/core/agent/ReActLoop.java`）——这就是整个「Agent Loop」：

```java
public String run(Session session, String userMessage, Profile profile) {
  session.appendUser(userMessage);
  // 最大轮数兜底：模型可能反复要调工具永不收敛，转够强制退出
  for (int i = 0; i < profile.settings().maxIterations(); i++) {
    ProviderRequest prompt = promptBuilder.build(session, profile);
    ProviderResponse response = providerService.chat(session.sessionId(), profile, prompt);
    // 先累积再判停：每一轮都留痕，事后可审计、下一轮接得上
    session.appendAssistant(response);
    if (!response.hasToolCalls()) {
      return response.text() == null ? "" : response.text();
    }
    for (ToolCallRequest call : response.toolCalls()) {
      ToolResult result = toolExecutor.execute(session.sessionId(), profile.name(), call);
      session.appendToolResult(call.name(), result);
    }
  }
  return MAX_ITERATIONS_REPLY;   // "达到最大轮数，已停止"
}
```

### 3.2 `oryxos-provider` —— Provider 抽象（7 个主类 / 345 行）

**职责**：把 core 的 `ProviderService` 契约用 Spring AI 落地——按 Profile 显式路由到对应 `ChatModel`，只做协议转换与 schema 翻译，**绝不执行工具**。

| 类 | 职责 |
| --- | --- |
| `ProvidersProperties` | `@ConfigurationProperties(prefix="oryxos")` record，读全局 `oryxos.providers` 列表；`validate()` 启动即点名报错 |
| `ProviderChatModelFactory` | 逐条手工构造 ChatModel，产出 `Map<String, ChatModel>`。核心就三行：`OpenAiApi api = new OpenAiApi(config.baseUrl(), config.apiKey()); providerMap.put(config.name(), new OpenAiChatModel(api));`。`name == "mock"` 特判放 `MockChatModel` |
| `SpringAiProviderServiceImpl` | 实现 core `ProviderService`：按 `profile.provider().name()` 查 map（查不到抛 `ProviderNotFoundException`）；`model.call(new Prompt(...))` 一次调用；**成功失败都写 `LlmCallAuditor`**（失败先落账再上抛）；把 `AssistantMessage.getToolCalls()` 原样转成 core `ToolCallRequest` 返回，本模块零执行 |
| `ToolSchemaAdapter` | 把 `OryxTool` 三要素翻译成 `FunctionCallback`；包装类 `SchemaOnlyCallback` 的 `call()` **永远抛 `IllegalStateException("Provider 只翻译工具、不执行工具")`** ——「绝不执行」的第二道保险 |
| `MockChatModel` | 无 key 全链路自测用假模型：判轮只看渲染文本中 `\ntool: ` 与 `\nuser: ` 行的相对位置 |
| `ProviderNotFoundException` | 点名报错"未知的 provider: X"，绝不静默换家 |
| `ProviderModule` | 空标记类（仅 Javadoc），见 §5.7 |

**多 Provider 怎么支持 deepseek / qwen / openai / ollama / moonshot？**
**没有任何 per-provider 类**。前提是这些全是 OpenAI 兼容端点，所以「映射表」是**配置驱动 + 工厂统一构造**：
换 provider = 在 YAML 加一条 `name` + `base-url`，不改 Java。
（`vendors/oryxos/website/docs/provider.md` 给出 `openai: https://api.openai.com`、`kimi: https://api.moonshot.cn/v1`、
`ollama: http://localhost:11434`；`website/docs/profile.md` 给出 `qwen: https://dashscope.aliyuncs.com/compatible-mode`）

**Spring AI 自动 tool 执行是怎么被禁用的（三道闸）**——这是全仓最值得复刻的经验：

1. **配置层**：`vendors/oryxos/oryxos-boot/src/main/resources/application.yml:7-13` 排掉 `DashScopeAutoConfiguration` 和 `OpenAiAutoConfiguration`（后者会急切建 `OpenAiChatModel` 并索要 `spring.ai.openai.api-key`）
2. **选项层**：`SpringAiProviderServiceImpl` 里 `.proxyToolCalls(Boolean.TRUE); // 关闭自动执行：执行权只在 ToolExecutor`
3. **保险层**：`ToolSchemaAdapter.SchemaOnlyCallback.call()` 抛异常

并用测试钉成回归门：`oryxos-provider/src/test/java/io/oryxos/provider/ProviderServiceTest.java:106`
`assertTrue(options.getProxyToolCalls()); // 坑二的回归：一旦有人改回自动执行，这里立刻红`

### 3.3 `oryxos-tool` —— Tool 体系 + MCP + 沙箱 + 通知（34 个主类 / 1476 行）

**职责**：内置工具（文件/Shell/HTTP/搜索/交互/通知）、MCP Client、`ToolRegistry`、白名单沙箱——四类工具来源统一成 `OryxTool`。

#### ToolRegistry 与两条注册路径（`vendors/oryxos/oryxos-tool/src/main/java/io/oryxos/tool/ToolRegistry.java`）

- `register(OryxTool)` —— 手写实现 / MCP / notify
- `registerAnnotated(Object bean)` —— `ToolCallbacks.from(bean)` 扫 `@Tool` 方法 → 逐个包成 `AnnotatedToolAdapter`（**schema 由 Spring AI 自动生成**，即「宪法 II 的第二件事」）
- **重名直接 `throw IllegalStateException("工具重名，拒绝注册")`**，不静默覆盖
- `filterByNames(names)` 按 Profile 的 `tools` 字段过滤（未知名跳过）
- `asMap()` 产出 `Map<String, OryxTool>`，即 web 层 `@Qualifier("tools")` 注入的那个 Bean

#### 内置工具 12 个（`tool/builtin/` 6 类）

| 类 | Tool 名 | 要点 |
| --- | --- | --- |
| `FileTools` | `read_file` / `write_file` / `list_dir` / `edit_file` / `grep` / `glob` | 每个方法**第一行** `sandbox.enforce(new SandboxAction(FILE_READ/FILE_WRITE, path))`，校验不过文件根本不碰。`edit_file` 要求 oldString 在文件中唯一出现（多处匹配即拒绝，Claude Code/Cursor 同款约束）；`grep`/`glob` 有 `MAX_MATCHES=200` 截断防撑爆上下文 |
| `ShellTools` | `shell` | enforce(SHELL_COMMAND) 后 `new ProcessBuilder("bash","-c",command)`，`DEFAULT_TIMEOUT=30s`，超时 `destroyForcibly()`；非零退出码抛异常带 stderr |
| `HttpTools` | `http_get` / `http_post` | enforce(HTTP_REQUEST, url) 后用 `RestClient` |
| `WebSearchTools` | `web_search` | `MAX_RESULTS=8`，渲染成 `- title\n  url\n  snippet` |
| `InteractionTools` | `ask_user` | 纯转发 `UserInteraction.ask(question)`（human-in-the-loop 落点） |
| `NotifyTools` | `notify` | **唯一一个直接实现 `OryxTool`**（手写 JSON schema，不用 `@Tool`）——因为渠道来自运行时 `ProfileContext.current().notifyChannels()`，不是模型该知道的信息。请求的 channel 类型不存在则报错且**不回退默认**（避免发错地方） |

#### 沙箱（`tool/sandbox/` 9 类）—— 白名单机制

| 类 | 职责 |
| --- | --- |
| `Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` | 接口仅 `void enforce(SandboxAction)`；`SandboxAction(type, target)`；枚举 `FILE_READ / FILE_WRITE / SHELL_COMMAND / HTTP_REQUEST` |
| `WhitelistSandbox` | 核心实现，**同时实现 core 的 `SandboxWhitelist`**（同一实例既是校验墙又是可管理白名单）。`switch` 的 `default` 分支对未知动作类型 **deny** 而非放行 |
| `FileSandboxProperties` / `ShellSandboxProperties` / `HttpSandboxProperties` | `@ConfigurationProperties(prefix="file"/"shell"/"http")`，三块白名单各自独立 |
| `PermissiveSandbox` | 临时放行实现，每次放行打 WARN 留痕；生产装配已不再引用、仅留档 |

**三类校验算法**（`vendors/oryxos/oryxos-tool/src/main/java/io/oryxos/tool/sandbox/WhitelistSandbox.java`）：

- 文件：`Path.of(raw).normalize().toAbsolutePath()` 后 `startsWith` 对称比对（**挡相对路径穿越**）
- Shell：`command.trim().split("\s+")[0]` 取**命令首 token** 再 `contains`
- HTTP：`URI.create(url).getHost()` 后走 `matchesDomain`

**通配符点号边界**（第 121-128 行是关键）：`*.example.com` 转成 `endsWith(".example.com")` —— 挡住形似域名
`evil-example.com`（`endsWith("example.com")` 的经典漏洞）与裸域；非通配项精确相等；`Locale.ROOT` 小写化。

**deny-all 语义**：三份 Properties 都把 `null` 归一成 `List.of()`，注释明说
"空列表天然 deny-all（`anyMatch` 对空流恒 false），配置缺失绝不退化为放行"。
生产默认白名单在 `application.yml:30-48`：`file.allowed_paths:[.oryxos]`、`shell.allowed_commands:[ls,cat,echo,grep]`、`http.allowed_domains:[api.deepseek.com, "*.feishu.cn"]`。

#### MCP Client（`tool/mcp/` 4 类）

| 类 | 职责 |
| --- | --- |
| `McpClientService` | 启动时 `connectAll(registry)`：只认 `transport == "stdio"`（其他 WARN 跳过）；`client.initialize()` → `listTools()` → 每个 tool `registry.register(new McpToolAdapter(client, tool))`。**失联 server 只 WARN 跳过，不拖垮自身启动**。连接工厂 `Function<McpServerConfig, McpSyncClient>` 可注入作测试替身 |
| `McpConfigLoader` | SnakeYAML 解析 `.oryxos/mcp_servers.yaml`（顶层 `servers:` 列表）。文件缺失/解析失败/结构不对 → 零 server 照常启动。`env` 值支持 `${ENV}` 占位，缺失时保留原样并 WARN |
| `McpServerConfig` | record `(name, transport, command, env)` |
| `McpToolAdapter` | 把一个 MCP 工具适配成 `OryxTool`；`execute` 走 `client.callTool(new McpSchema.CallToolRequest(name, args))`；`result.isError()` 时返回 `ToolResult.error(..., retryable=true)` **标记可重试** |

**协议/库**：官方 MCP Java SDK（`io.modelcontextprotocol.client.*`），经 `spring-ai-mcp` 传递引入。**核心阶段只支持 stdio transport（JSON-RPC over stdio）**，SSE/HTTP 留扩展。

#### 通知渠道（`tool/notify/` 6 类）

| 类 | type | 要点 |
| --- | --- | --- |
| `NotifyChannelAdapter` / `NotifyTarget` | — | 接口仅 `void send(NotifyTarget, String)`，失败以异常表达不许静默吞；`NotifyTarget(channelType, config)`，config 是黑盒由实现类自己解释 |
| `WebhookNotifyAdapter` | `webhook` | POST `{"content":"..."}` |
| `WeComNotifyAdapter` | `wecom` | 企业微信群机器人 `{"msgtype":"text","text":{"content":...}}` |
| `FeishuNotifyAdapter` | `feishu` | 飞书/Lark（协议同、仅域名不同，一个实现覆盖两者）`{"msg_type":"text","content":{"text":...}}` |
| `DingTalkNotifyAdapter` | `dingtalk` | 有 `secret` 时走官方加签：`sign = urlEncode(base64(HmacSHA256(timestamp+"\n"+secret, secret)))` |

**type → 实现的映射不在框架里，而在装配处的一个 `Map.of(...)`**（`OryxOsRuntime.toolRegistry()`），与 Provider 显式映射同一思想。

#### 交互与搜索（`tool/interaction/` 4 类 + `tool/web/` 2 类）

- `UserInteraction` 接口 `String ask(String)`；`ConsoleUserInteraction`（CLI，EOF 抛异常）；`UnsupportedUserInteraction`（**无人值守渠道直接抛"当前渠道不支持向用户提问"，绝不静默卡住**）
- `SearchProvider` 接口 + `DuckDuckGoSearchProvider`（DuckDuckGo Instant Answer API，无需 key，endpoint 可注入）

### 3.4 `oryxos-memory` —— 可插拔长期记忆（8 个主类 / 473 行）

**职责**：`MemoryService` 门面 + 三档可插拔后端（Markdown / SQLite / Mem0）+ `save_memory`/`recall_memory` 内置工具。

**关键前提**：`MemoryService` 接口和 `MemoryScope` 枚举**不在本模块，而在 oryxos-core**（`core/memory/`）。
理由写在接口注释里：PromptBuilder（core）必须注入它，接口若留在 memory 会成环 —— 依赖倒置。

| 类 | 职责 |
| --- | --- |
| `MemoryServiceImpl` | 门面，4 个方法全部一行委托给注入的 `LongTermMemoryStore`。**亮点 `withAgent()`**：读路径不经 ToolExecutor，所以在委托 `store.load()` 前临时 `ToolExecutionContext.setAgentName(...)`，`finally` 复原 |
| `LongTermMemoryStore` | 可插拔后端接口：`append(content, scope)` / `load()` / `recallByKeyword(keyword)` + **四条行为契约**：①不缓存（每次重读，写完立即可见）②核心区永不被截断 ③写核心还是归档由调用方经 scope 显式指定 ④recall 只搜归档区 |
| `MarkdownMemoryStore` | 档一（默认）。文件按 `## 核心记忆` / `## 归档记忆` 两区块组织。**按 Agent 隔离**：`memoryFile()` 读 `ToolExecutionContext.agentName()`，合法（正则 `[A-Za-z0-9_-]+`，防目录穿越）时落 `.oryxos/agents/<name>/MEMORY.md`，否则回退全局 `.oryxos/memory/MEMORY.md`。截断只对**归档段字符串**裁尾 4000 字——核心区不在入参里，物理上碰不到 |
| `SqliteMemoryStore` | 档二。走 `MemoryEntryRepository` 入 `memory_entries` 表；截断从字符串裁尾变成归档查询 `LIMIT` 取最近 N 再 `reversed()` 翻回时间正序；recall 变 SQL `LIKE` |
| `Mem0MemoryStore` | 档三。REST 调自托管 Mem0：`POST /v1/memories/`、`GET /v1/memories/?user_id&scope`、`POST /v1/memories/search/`（**语义检索**）。注释明说"Mem0 的 search 是语义检索——契约四的加强版实现"，**调用方签名不变**，这就是「向量检索升级路径」的真实形态 |
| `InMemoryMemoryStore` | 进程内两个 `ArrayList`，契约测试里当 Mem0 替身 |
| `builtin/MemoryTools` | `@Tool(name="save_memory")`（参数 content + scope，缺省→ARCHIVAL，非法值返回可读错误**不静默落错区**）与 `@Tool(name="recall_memory")`。只认门面，对后端完全无感 |

**契约测试范式**：`vendors/oryxos/oryxos-memory/src/test/java/io/oryxos/memory/MemoryStoreContractTest.java` 用**同一套断言**对三档后端统一跑（`@ParameterizedTest` + `Stream<Arguments>`）。新增 store = 实现接口 + 加一行测试参数 + 加一个 switch case，`MemoryTools`/`MemoryServiceImpl` 完全不用改。

### 3.5 `oryxos-storage` —— SQLite + JPA（17 个主类 / 1104 行）

**职责**：持久层 —— 6 实体 + 6 仓库 + 4 个「core 接口的 JPA 适配器」。

| 分类 | 类 |
| --- | --- |
| **Entity（6）** | `Session`(`@Id String sessionId`)、`ToolInvocation`、`LlmCall`、`ScheduledTask`(`@Id String taskId`)、`TaskExecution`、`MemoryEntry` |
| **Repository（6）** | `SessionRepository`、`ToolInvocationRepository`、`LlmCallRepository`、`ScheduledTaskRepository`、`TaskExecutionRepository`、`MemoryEntryRepository` |
| **core 接口适配（4）** | `JpaSessionManager` → `core.session.SessionManager`；`JpaLlmCallAuditor` → `core.provider.LlmCallAuditor`；`JpaToolInvocationAuditor` → `core.agent.ToolInvocationAuditor`；`JpaScheduledTaskStore` → `core.agent.ScheduledTaskStore` |
| **标记类** | `StorageModule`（空类，仅 Javadoc） |

全部实体文件头都声明 **"表结构以手工 schema.sql 为唯一权威"**。

**表结构唯一权威：`vendors/oryxos/oryxos-storage/src/main/resources/schema.sql`**（6 张表 + 5 个索引，全部 `CREATE TABLE IF NOT EXISTS`）：

| 表 | 关键字段 |
| --- | --- |
| `sessions` | `session_id` PK(512)、`profile_name`、`channel`、`user_id`、**`messages_json`(对话历史整体 JSON 序列化一列)**、`status`、`created_at`、`last_active_at`、`archived_at` |
| `tool_invocations` | 审计：`session_id`、`tool_name`、`input_json`、`result_json`、`success`、`error_message`、`duration_ms` |
| `llm_calls` | 审计：`session_id`、`provider`、`model`、`prompt_tokens`/`completion_tokens`/`total_tokens`、`success`、`error_message`、`duration_ms` |
| `scheduled_tasks` | `task_id` PK、`profile_name`、`cron`、`zone`、`message`、`enabled`、`next_run_at`、`last_run_at`、`last_status`、`run_count` |
| `task_executions` | `task_id`、`session_id`、`started_at`、`success`、`error_message`、`duration_ms` |
| `memory_entries` | `scope`(CORE/ARCHIVAL)、`content`、`created_at` |

**三处互锁的 DDL 配置**：
1. `schema.sql` 是唯一权威（文件头："SQLite ALTER TABLE 能力弱：本脚本是表结构唯一权威，禁用 hibernate.ddl-auto=update"）
2. `application.yml`：`hibernate.ddl-auto: none` + `spring.sql.init.mode: always`
3. `sqlite-jdbc` + `hibernate-community-dialects`（**SQLite 方言在 community 包，不在 hibernate-core** —— 复刻时容易漏）

**4 个适配器的关键取舍**：
- `JpaSessionManager`：**全库唯一的 session_id 拼接点** `channel + ":" + userId + ":" + profileName`；`archive` 不存在返 `false`（**core 不依赖 Web 异常，由 Web 层翻成 404**）
- `JpaLlmCallAuditor` / `JpaToolInvocationAuditor`：**审计自身失败只 `LOG.error` 不上抛**（"可用性优先，审计故障不阻断主链路"）；日志值 `sanitize()` 去 CR/LF（防 CWE-117 日志伪造）
- `JpaScheduledTaskStore`：`register` 是幂等 upsert（**已存在则保留 enabled 与 run_count**——任务定义来源是文件、重启会重注册）；`isEnabled` 查不到时 **fail-open 返 true**
- 全部是**普通 POJO，构造注入 Repository，不是 `@Service`** —— 由 `OryxOsRuntime` 用 `@Bean` 显式装配

### 3.6 `oryxos-web` —— REST 层 + 管理台（35 个主类 / 1240 行 + 前端）

**职责**：`/api/v1` 下的 Spring MVC REST 层 + 统一响应信封/全局异常 + 把 Vue 管理台打进 jar 托管在 `/admin`。
**只依赖 `oryxos-core`**。

#### 8 个 Controller、30 个端点

| Controller | 端点 |
| --- | --- |
| `AgentApiController` (`/api/v1/agents`) | POST ``（create：只需 name+description）、GET ``、GET `/{name}`、PUT `/{name}`（整段覆写 AGENT.md）、DELETE `/{name}`、POST `/{name}/invoke`（无状态，固定 `channel="invoke"`）、GET `/{name}/memory`（**Agent 专属记忆**）、GET `/{name}/session`（**管理台固定会话** `channel="admin", user="console"`，`getOrCreate` 幂等 → 每个 Agent 恰好一条、上下文累积）、POST `/{name}/session/messages`、POST `/{name}/generate-files`（LLM 生成 AGENT.md 草稿，**不落盘不注册**）、POST `/{name}/files`（保存即生效） |
| `SessionApiController` (`/api/v1/sessions`) | POST ``（`channel="web"`）、POST `/{id}/messages`（**触发一次完整 ReAct，与 `oryxos chat` 同一入口**）、GET ``（`?status=active`）、GET `/{id}`、DELETE `/{id}` |
| `ProfileApiController` (`/api/v1/profiles`) | GET ``（只读列出已加载 Profile） |
| `SandboxWhitelistController` (`/api/v1/sandbox/whitelist`) | GET ``、POST `/{category}`、DELETE `/{category}?value=`。**注入 core 的 `SandboxWhitelist` 契约而非 oryxos-tool 的实现类** |
| `ScheduleApiController` (`/api/v1/schedules`) | GET ``、GET `/{id}/executions?limit=`、POST `/{id}/run`（立即执行，无视启用状态）、PUT `/{id}`（启用/停用）。**读走 store（SQLite 持久态）、写走 scheduler（运行时）——两条线分开** |
| `ToolApiController` (`/api/v1/tools`) | GET ``。构造注入 **`@Qualifier("tools") Map<String,OryxTool>`**，注释点明"web 无需依赖 oryxos-tool 模块" |
| `SystemApiController` (`/api/v1`) | GET `/health`、GET `/info`（providers 取"已加载 Profile 引用到的 Provider 名单"，**核心阶段不做 live 探活**） |
| `WorkspaceApiController` (`/api/v1/workspace`) | GET `/tree`（`agents/` + `archive/` 两棵目录树）、GET `/file?path=`、POST `/file`。**唯一安全要点**：`resolve(path).normalize()` 后必须 `startsWith(oryxosRoot)`，越界即 400 |

#### 横切件

- **`ApiResponse<T>`**（`web/common/ApiResponse.java`）：`{code, message, data, timestamp}`；`ok(data)` → `code=0,"success"`。所有端点统一信封
- **`GlobalExceptionHandler`**（`@RestControllerAdvice`）——异常即 HTTP 状态码的领域语言：

| 异常 | HTTP |
| --- | --- |
| `IllegalArgumentException`、`ProfileValidationException`（core 的！AGENT.md 非法） | 400 |
| `NoResourceFoundException`、`SessionNotFoundException`、`ResourceNotFoundException` | 404 |
| `IllegalStateException`、`ProviderUnavailableException` | 503 |
| `AgentTimeoutException` | 504（Agent 调用超 60s 上限） |
| `Exception` | 500（body 固定 `"Internal server error"`，不泄露栈） |

  全部 handler 对 `getMessage()` 做 `replaceAll("[\r\n]","_")` 防 CWE-117
- `web/error/` 4 个异常全是无字段 `RuntimeException`，纯粹当"HTTP 状态码的领域语言"用
- **`WebConfig`** —— Vue SPA 托管三分法：`/admin` 301 → `/admin/`；`/admin/` forward → `/admin/index.html`；`/admin/assets/**`（带内容 hash）`maxAge(365d).cachePublic().immutable()`，而 `/admin/**` `noCache()` + 自定义 `PathResourceResolver` 回落 `index.html`。注释解释了为什么 index.html 必须 no-cache："否则重建后浏览器会用旧壳指向已删除的旧 bundle（表现为只有某个页签能用）"

#### 19 个 DTO（`web/controller/dto/`，全部 Java `record`）

请求体 8 个：`CreateAgentRequest`、`UpdateAgentRequest`、`GenerateFilesRequest`、`SaveFilesRequest`、`CreateSessionRequest`、`MessageRequest`、`SetEnabledRequest`、`WriteFileRequest`。
响应视图 11 个：`AgentView`、`ProfileView`、`SessionView`、`SessionSummaryView`、`MessageResponse`、`ToolView`、`InfoView`、`ScheduleView`、`ExecutionView`、`GeneratedFilesView`、`FileNode`。
多数带静态工厂 `from(领域对象)`，canonical constructor 里做 null→`List.of()` 防御。

#### 前端：`oryxos-web/src/main/frontend/`

```
frontend/
├── package.json          # 仅 1 个运行时依赖 vue@^3.5.13
├── vite.config.js        # base:'/admin/'  outDir:'../resources/static/admin'
└── src/
    ├── App.vue           # ★ 899 行，整个管理台是单文件 SPA
    ├── main.js           # 5 行
    └── styles/tokens.css # 30 行设计 token（深色底 + 橙色主色）
```

**这是本次调研最 surprising 的一个事实**：README 宣称的「overview / agents / providers / tools / sandbox whitelist /
long-term memory / runtime status / sessions」八个页面，全部实现在**一个 899 行的 `App.vue`** 里，顶栏分组为
「概览 / Agent 列表 / 定时任务」+「OS 运行时」折叠分组（会话/Provider/Tool/Sandbox 白名单），
全部 `fetch('/api/v1/...')` + 解 `ApiResponse` 信封（`if (body.code !== 0) throw ...`）。
**没有任何 vue-router、pinia、组件库。**

> 出处：`vendors/oryxos/oryxos-web/src/main/frontend/src/App.vue`、`vite.config.js`、`package.json`

### 3.7 `oryxos-cli` —— 命令行入口 + **全仓装配中枢**（11 个主类 / 876 行）

**职责**：picocli 命令行入口 + **整个系统的 Spring 装配中枢 `OryxOsRuntime`**。

#### `OryxOsRuntime.java` —— 全仓最核心的装配类

类级注解：
```java
@SpringBootApplication(scanBasePackages = "io.oryxos")
@EnableJpaRepositories(basePackages = "io.oryxos.storage")
@EntityScan(basePackages = "io.oryxos.storage")
@EnableConfigurationProperties({ProvidersProperties.class, FileSandboxProperties.class,
                                ShellSandboxProperties.class, HttpSandboxProperties.class})
```

注释直接写明两条坑位结论：
- **"轻命令不进这里（课件坑二：为列个目录不值得等 4 秒）"**
- **"课件坑四：`scanBasePackages` 只管普通 Bean，不会带动 JPA 仓库与实体扫描跟着跨模块 —— 存储在独立模块（io.oryxos.storage），必须显式 `@EnableJpaRepositories` + `@EntityScan`，否则 'Found 0 JPA repository interfaces'，审计与会话静默写不进去"**

约 25 个 `@Bean` 全部显式 new（各模块交付的类保持**纯 POJO 零框架依赖**），按装配顺序读下来就是整条运行链：

```
providerMap (ProvidersProperties.validate() → ProviderChatModelFactory)
  → llmCallAuditor / toolInvocationAuditor (new Jpa*)
  → providerService (SpringAiProviderServiceImpl)
  → agentLoader (扫 oryxos.root/agents，把 providerKey/toolName 集合喂给它做校验)
  → profileRegistry = agentLoader.loadAll()      ← 启动全量扫
  → agentStore → agentLifecycleService
  → workspaceWatcherExecutor (1 线程 daemon) → workspaceWatcher (initMethod="start")
  → contextLoader
  → sandbox   ← @Bean 返回具体类型 WhitelistSandbox 而非 Sandbox 接口，
  │              "同一实例既是校验墙 Sandbox 又是可管理白名单 SandboxWhitelist，
  │               具体类型让 Spring 同时按两个接口装配"
  → restClient
  → longTermMemoryStore  ← switch(memory.backend) 三选一：sqlite / mem0 / 默认 markdown
  → memoryService
  → toolRegistry  ← 6 个内置工具 registerAnnotated + NotifyTools register + MemoryTools
  │                 + new McpClientService(new McpConfigLoader(...)).connectAll(registry)
  → tools (= toolRegistry.asMap())
  → promptBuilder (注入 Clock) → toolExecutor → reActLoop
  → sessionManager (new JpaSessionManager) → agentService → cliChannel
  → taskScheduler (pool 2, daemon)
  → scheduledTaskStore → agentScheduler (initMethod="registerAll")
```

#### 9 个 command 类

| 类 | 子命令 | 做什么 | 起 Spring？ |
| --- | --- | --- | --- |
| `InitCommand` | `init` | 建 `.oryxos/{agents,memory,sessions,logs}` + 幂等写 `AGENTS.md`/`SOUL.md`/`USER.md` | 否 |
| `StatusCommand` | `status` | 工作区是否初始化、几个 Agent、`oryxos.db` 是否存在 | 否 |
| `ProfileCommand` | `profile list/create/show/delete` | **直接读写 `.oryxos/agents/<name>/AGENT.md`**；create 写入模板 | 否 |
| `ProviderListCommand` | `provider list` | SnakeYAML 直接读 classpath 的 `application.yml` | 否 |
| `ToolListCommand` | `tool list` | 硬编码打印工具名（注释承认实时清单应由 ToolRegistry 提供） | 否 |
| `SessionListCommand` | `session list` | **绕开 JPA，裸 `DriverManager.getConnection("jdbc:sqlite:oryxos.db")` 直查** | 否 |
| `ChatCommand` | `chat [--profile]` | **起 Spring 但关 Web**：`new SpringApplicationBuilder(OryxOsRuntime.class).web(WebApplicationType.NONE).bannerMode(OFF).run()` | 是（非 web） |
| `ServeCommand` | `serve [--port]` | `System.setProperty("server.port", port)` → `SpringApplication.run(OryxOsRuntime.class)` → 主线程 `join()` 常驻 | 是（web） |
| `GatewayCommand` | `gateway` | 同 serve；注释"多 Channel 挂载属扩展阶段"，目前只是常驻骨架 | 是（web） |

**「双速 CLI」是关键设计**：轻命令完全绕开容器（NIO 文件 API / SnakeYAML / 裸 JDBC），
重命令才付 4 秒容器启动成本。代价是轻命令各自硬编码了与 `application.yml` 相同的路径常量
（`.oryxos/agents`、`oryxos.db`、工具名清单）——这是一个已知的重复，复刻时可以考虑用常量类收口。

### 3.8 `oryxos-channel-cli` —— 唯一 Channel 实现（2 个主类 / 65 行）

- `CliChannel`：构造注入 `AgentService + SessionManager`；`run(profileName, userId)` 里
  `sessionManager.getOrCreate("cli", userId, profileName)` 后进入 `while(true)`：`readLine` → 空行跳过 →
  `/quit` 或 EOF（Ctrl-D/管道结束）→ 否则 `agentService.process(session, line)` → `out.println(reply)`。
  类注释自陈定位：**"CLI 是消息进出的门，不是干活的人 —— 本类没有任何 Agent 智能"**
- `ChannelCliModule`：空标记类

**⚠️ 重要结论：core 里没有 `Channel` 接口。**
全仓 `Channel` 只在三处出现：`Profile` 的数据字段 `List<String> channels`、
`NotifyChannel`（那是通知渠道不是消息渠道）、`session_id` 三元组里的 channel 字符串（`"cli"` / `"web"` / `"invoke"` / `"admin"`）。
也就是说 **Channel 抽象在核心阶段被「降维」成 `AgentService.process(Session, String)` 这一个入口 + channel 字符串**，
而不是 `interface Channel { connect(); onMessage(); }`。

> 复刻权衡：好处是三种触发源（CLI/Web/定时）天然同源、复用同一套审计与循环；坏处是新增 IM/Webhook Channel 没有 SPI 可插，需要自建接口。
> 出处：`vendors/oryxos/oryxos-channel-cli/src/main/java/io/oryxos/channel/cli/CliChannel.java`、
> `vendors/oryxos/CLAUDE.md`（IM Channel 列为扩展阶段）

### 3.9 `oryxos-boot` —— 聚合与启动壳（1 个主类 / 11 行 + resources）

- `OryxOsApplication.java` 全文 11 行：`@SpringBootApplication(scanBasePackages = "io.oryxos")` + `main`
- **⚠️ 这个类实际上是「死入口」**：全仓 grep 只有它自己引用自己；boot 模块里所有测试统一写
  `@SpringBootTest(classes = OryxOsRuntime.class, webEnvironment = RANDOM_PORT)`，
  fat JAR 的 main 也是 `io.ryxos.cli.OryxOsCli`。
  **真正的整机装配类是 `io.oryxos.cli.OryxOsRuntime`（在 oryxos-cli 模块里）** —— 这是反向于直觉的模块职责划分
- 聚合机制：**没有 spring.factories、没有 `AutoConfiguration.imports`**（全仓 find 为空）。跨模块装配不走 Spring Boot 自动配置，只靠 `scanBasePackages` + 显式 JPA 扫描
- resources：`application.yml`（见 §2.5）、`logback-spring.xml`（双 profile：非 prod 彩色控制台带 `%X{traceId}`；`prod` 用 `LogstashEncoder` 单行 JSON + `customFields {"application":"oryxos"}`）
- `pom.xml`：`spring-boot-maven-plugin` + `repackage` + **`<mainClass>io.oryxos.cli.OryxOsCli</mainClass>`**

### 3.10 `my-agent/` —— 种子工作区样例（非 Maven 模块）

只有 3 个文件，全部是占位模板：
`vendors/oryxos/my-agent/.oryxos/AGENTS.md` → 内容 `# AGENTS.md`；`SOUL.md` → `# SOUL.md`；`USER.md` → `# USER.md`。

**它不是「一个目录 = 一个 Agent」的示例**（没有 `agents/` 子目录、没有 `AGENT.md`），
而是 `oryxos init` 产出的**工作区形态**（Bootstrap 三件套）的种子样例，与 `InitCommand` 的 `DIRS`/`BOOTSTRAP_FILES` 常量对应。
`.gitignore` 第 51 行忽略 `.oryxos/`（无前导 `/`，理论上匹配所有层级），这个目录能进版本库是个潜在的仓库不一致点，复刻时值得澄清。

---

## 4. 核心功能清单：作为 Agent OS 它能做什么

### 4.1 Agent 如何定义与运行 ——「一个目录 = 一个 Agent」

这是本项目最核心的产品机制（README 称之为 "Config = Agent"，宪法原则 IV）。

**目录形态**（借 Anthropic Agent Skills 的**目录形态**，但定义的是 Agent 而非技能）：

```
.oryxos/agents/<name>/
├── AGENT.md          # frontmatter = 这个 Agent 的 profile；正文 = 任务指令
├── skills/*.md       # 可选：较长的子指令 / 规范 / 清单
├── scripts/          # 可选：脚本（python/shell）
└── REFERENCE.md      # 可选：字段字典、已知边界、阈值、联系人
```

**AGENT.md 实例**（`vendors/oryxos/CLAUDE.md:150-182`）：

```markdown
---
name: ops-agent
description: 运维助手
identity:
  agent_name: 运维小欧
  prompt: 你是一个专业的运维助手...
provider:
  name: deepseek          # 对应 ProviderService 里的显式映射 key
  model: deepseek-chat
  temperature: 0.7
  api_key: ${DEEPSEEK_API_KEY}   # 从环境变量读取，不明文写死
tools:      [read_file, shell, http_get, save_memory, recall_memory]
mcp_servers: [github-mcp]
channels:   [{name: cli}]
bootstrap:  [AGENTS.md, SOUL.md, USER.md]
notify_channels: [...]
schedules:  [{id: ..., cron: ..., zone: ..., message: ...}]
settings:   {max_iterations: 10, max_history_turns: 20}
---

你是一个专业的运维助手。被触发时……（Agent 的任务指令正文，注入 system prompt）
```

**加载路径**（渐进式披露）：
1. `AgentLoader.loadAll()` 扫 `.oryxos/agents/` → 每个子目录 `AgentMarkdown.split()` 拆 frontmatter/正文
   → 复用 `ProfileLoader.fromMap` 校验（同一异常同一消息）→ 派生成 `Profile` → 登记 `ProfileRegistry`
2. `AGENT.md` **正文**由 `ContextLoader`/`PromptBuilder` 注入 system prompt（**常驻**，与 Bootstrap 文件同一层）
3. 目录里的**子指令/参考**用底座既有 `read_file` **按需读**、**脚本**用 `shell` **按需跑** —— 没有跨 Agent 的能力库、没有 `use_skill`、没有全局能力索引
4. **一个 Agent 目录永远不是一个可执行 Tool**，不进 `ToolRegistry`、不放在 `oryxos-tool` 模块里

**三条录入路径**（全部汇到 `AgentLifecycleService.register(Path)` 同一段代码）：
启动扫描（`AgentLoader.loadAll`）/ API create / `WorkspaceWatcher` 实时事件

### 4.2 Agent Loop 在哪 —— `ReActLoop` + 三段式

见 §3.1 的代码全文。每次循环组装的 Prompt 四段（`PromptBuilder.build`）：

```
① system：identity.prompt + AGENT.md 正文 + bootstrap 文件   ← ContextLoader（每次现读、无缓存）
   + "当前日期时间：2026-08-27 23:45"                        ← PromptBuilder（"模型自己不知道今天几号"）
② 长期记忆：MemoryService.buildContext(session)             ← MemoryService（核心区全量 + 归档区截断）
③ 对话历史：只留最近 max_history_turns 轮                    ← PromptBuilder（按轮截断，不截半轮）
④ 可用工具：经 ProviderRequest.availableTools() 传递          ← PromptBuilder 按 profile.tools() 过滤
```

然后 `ProviderService.chat()` → 无 tool call 则返回文本；有则 `ToolExecutor.execute()` → 结果 `appendToolResult` → 回到①。
默认 `max_iterations=10`。

### 4.3 Tool 系统

四类来源统一进 `OryxTool`，`ToolRegistry` 对来源无差别注册：

| 来源 | 门槛 | 实现 |
| --- | --- | --- |
| **内置工具** | — | 12 个（`FileTools` 6 + `ShellTools` 1 + `HttpTools` 2 + `WebSearchTools` 1 + `InteractionTools` 1 + `NotifyTools` 1） |
| **零代码 MCP**（⭐ 主推） | 最低 | 写 `mcp_servers.yaml` 一条 + 复用社区 MCP server |
| **轻代码 MCP** | 中 | 任意语言自建 MCP server，配置在 `mcp_servers.yaml` |
| **重代码原生 `@Tool`** | 高 | Java 方法标 `@Tool` + `@ToolParam` 的 Spring Bean，`registry.registerAnnotated(bean)`，进程内直接调用 |

> 选择原则写在 CLAUDE.md:291："能用方式一就不用方式二，能用方式二就不用方式三"。
> 注意：CLAUDE.md 写的是 9 个内置工具，实际代码已到 12 个（第 20 节后新增了 `edit_file`/`grep`/`glob`/`web_search`/`ask_user`）——**文档落后于代码**，复刻时以代码为准。

### 4.4 Provider 抽象

见 §3.2。核心是 `Map<String, ChatModel>` 显式映射 + OpenAI 兼容端点统一工厂，换 provider 只改 YAML。

### 4.5 Memory

三层（`docs/TechnicalSolution.md` §5「Memory 三层记忆」）：

| 层 | 实现 | 生命周期 |
| --- | --- | --- |
| 会话内短期 | `Session.messages()` + `max_history_turns` 按轮截断 | 一次会话 |
| 长期-核心区 | `MemoryScope.CORE`，**永不被截断** | 跨会话，始终完整注入 |
| 长期-归档区 | `MemoryScope.ARCHIVAL`，**超限截断/只取最近 N** | 跨会话，`recall_memory` 关键词检索 |

三档后端可插拔（`memory.backend: markdown | sqlite | mem0`），见 §3.4。
**记忆按 Agent 隔离**（第 30 节）：`save_memory` 落到 `.oryxos/agents/<name>/MEMORY.md`，由 `ToolExecutionContext` ThreadLocal 实现。

### 4.6 Storage

SQLite + JPA，6 张表（见 §3.5）。**审计 Day One 写入**是核心差异化能力：
`tool_invocations` 和 `llm_calls` 两张表核心阶段就必须写入（不需要查询接口，但写入不能省）。

### 4.7 Channel

当前只有 CLI 一个 Channel（§3.8），且没有 SPI。Web 是另一条触发源但不算 Channel 抽象。

### 4.8 插件化机制 ——「一个目录定义一个会自己跑的 Agent」

commit `a99f299 feat: 第29节：插件化 Agent 一个目录定义一个会自己跑的 Agent` 是本项目从「单 Agent 引擎」转向「Agent OS」的转折点。
它带来的插件化体现在四个维度：

| 维度 | 插件形态 | 落点 |
| --- | --- | --- |
| **Agent 本身** | 一个目录 + `AGENT.md`，零 Java 代码 | `.oryxos/agents/<name>/`，`AgentLoader` / `AgentLifecycleService` |
| **工具** | MCP server（零代码）或 `@Tool` Bean（重代码） | `.oryxos/mcp_servers.yaml` / `oryxos-tool` |
| **通知渠道** | `NotifyChannelAdapter` 实现 + `Map.of` 注册 | `oryxos-tool/notify/` |
| **记忆后端** | `LongTermMemoryStore` 实现 + switch case | `oryxos-memory` |
| **定时任务** | AGENT.md frontmatter 的 `schedules:` 字段，零代码 | `AgentScheduler` |

**关键：这些插件点全部不需要改 `oryxos-core`**（README:99 "Modules are decoupled through interfaces. Adding a new Channel or Tool requires only a new module — `oryxos-core` stays untouched."）。

### 4.9 完整能力面（用户视角）

1. `oryxos init` 一键初始化工作区
2. `oryxos chat` 交互式多轮对话（带记忆、带工具）
3. `oryxos serve` 一个进程同时提供 REST API + Web 管理台 + Swagger
4. Web 管理台：概览 / Agent 列表（CRUD + 一句话 LLM 生成草稿 + 文件浏览器）/ 定时任务（启停、立即执行、执行历史）/ 会话 / Provider / Tool / 沙箱白名单动态增删
5. 定时任务：AGENT.md 写 cron 即可，到点自动跑，状态与执行历史落库重启不丢
6. Agent 专属长期记忆，跨会话累积
7. 全链路审计：每次 LLM 调用与工具调用（含失败）都落 SQLite
8. 无状态调用 `POST /api/v1/agents/{name}/invoke` 供业务系统 HTTP 集成
9. 通知外推：飞书 / 企微 / 钉钉 / 通用 webhook
10. 沙箱白名单：文件路径 / shell 命令 / HTTP 域名三块独立、动态可管

---

## 5. 架构模式与扩展点

### 5.1 模块间通信方式

**没有任何消息总线、事件总线、RPC。** 模块间通信只有两种：

1. **接口调用（依赖倒置）**：core 定义接口，下游实现，装配方（`OryxOsRuntime`）用 `@Bean` 把实现塞进去。
   这是唯一的跨模块协作方式。共 8 个契约接口：
   `OryxTool`、`ProviderService`、`MemoryService`、`SessionManager`、`LlmCallAuditor`、`ToolInvocationAuditor`、
   `ScheduledTaskStore`、`SandboxWhitelist`
2. **Spring 容器注入**：`scanBasePackages="io.oryxos"` 扫普通 Bean + 显式 `@Bean` 工厂方法

**唯一的「异步」是两处基础设施守护线程**，且都刻意不进入请求链路（不违反「同步执行」原则）：
- `AgentScheduler` 的 `ThreadPoolTaskScheduler`（pool 2, daemon）
- `WorkspaceWatcher` 的 `ThreadPoolTaskExecutor`（1 线程 daemon）

### 5.2 分布式能力落在哪一层（复刻时最容易误判的点）

**当前不落在任何一层 —— 它还没实现。** 仓库里没有 MQ、没有服务注册、没有 RPC、没有分布式锁（`AgentScheduler` 的锁是进程内 `ReentrantLock`，注释明确"核心阶段单实例，非分布式锁"）。

它为分布式做的准备是**纯架构纪律**，共四条，全部可以复刻：

| 纪律 | 实现 | 出处 |
| --- | --- | --- |
| 状态外置 | 会话/审计/定时任务/长期记忆全落 SQLite 或文件系统，JVM 内存里只有 `ProfileRegistry`（可从磁盘重建） | `oryxos-storage/schema.sql` |
| 无状态实例 | `AgentService` 不持有任何会话状态；`Session` 每次从 DB restore | `core/agent/AgentService.java` |
| 契约上移 | 8 个接口全在 core，实现可替换（如 SQLite → Postgres，只需换 storage 模块） | §5.1 |
| 同步阻塞模型 | 不用 Reactor/WebFlux/CompletableFuture —— 避免异步上下文在多实例间无法透传的问题 | `CLAUDE.md` 原则七 |

### 5.3 配置体系

三层（见 §2.5）+ 一条**强校验纪律**：启动即校验、缺失点名报错、不静默失败。
`ProvidersProperties.validate()`、`ProfileLoader.fromMap`、`McpConfigLoader` 三处口径一致（`${ENV}` 缺失保留原样并 WARN）。

### 5.4 二次扩展点清单（复刻后新增能力该改哪）

| 想加什么 | 要写什么 | 要动哪里 | 是否要改 core |
| --- | --- | --- | --- |
| 一个内置工具 | 一个类，方法标 `@Tool`，**第一行 `sandbox.enforce(...)`** | `oryxos-tool/builtin/` + `OryxOsRuntime.toolRegistry()` 加一行 | 否 |
| 一个 MCP server | 零 Java 代码 | `.oryxos/mcp_servers.yaml` 加一条 | 否 |
| 一个 Provider | 零 Java 代码（OpenAI 兼容端点） | `config/application.yml` 的 `oryxos.providers` 加一条 | 否 |
| 一个非 OpenAI 兼容 Provider | 一个 ChatModel 构造分支 | `ProviderChatModelFactory` 加分支 | 否 |
| 一个通知渠道 | 一个 `NotifyChannelAdapter` 实现 | `oryxos-tool/notify/` + `OryxOsRuntime` 的 `Map.of` 加一项 | 否 |
| 一个记忆后端 | 一个 `LongTermMemoryStore` 实现 + 契约测试参数 | `oryxos-memory` + `OryxOsRuntime.longTermMemoryStore()` 加 case + `application.yml` | 否 |
| 一个搜索引擎 | 一个 `SearchProvider` 实现 | `oryxos-tool/web/` | 否 |
| 一个新 Channel（IM/Webhook） | **需要自建 SPI** —— core 没有 `Channel` 接口 | 新模块 | **是** |
| 沙箱第二档（如容器级） | 一个 `Sandbox` 实现 | `oryxos-tool/sandbox/` | 否 |

### 5.5 可观测性

- **审计落库**（`tool_invocations` / `llm_calls` / `task_executions`）—— 这是它区别于一般 Agent 框架的核心
- **结构化日志**：logback + LogstashEncoder 单行 JSON（prod profile），带 `%X{traceId}`
- **Actuator + Micrometer/Prometheus**：暴露 `health,info,prometheus,metrics`；`application.tags.application: oryxos`
- **陷阱**：`management.health.nacos-config/discovery.enabled: false` —— Nacos 是 spring-ai-alibaba-starter 传递带进来的，
  不用但健康指示器会报 DOWN（`application.yml:82-90`）。**复刻时若不用 Alibaba starter 可直接避开这个坑**

### 5.6 安全模型（三层）

1. **沙箱白名单**（应用层，`WhitelistSandbox`）：三块独立、deny-all 语义、路径归一化、域名点号边界、命令首 token
2. **凭证纪律**：全部走环境变量占位，绝不落盘；`config/application.yml` gitignored
3. **输入卫生**：所有日志参数 `sanitize()` 去 CR/LF（CWE-117）；`AgentStore`/`WorkspaceApiController` 防路径穿越；
   `WorkspaceWatcher`/`AgentLoader` 日志防伪造

**明确不做**（`CLAUDE.md:312`）：认证（假设内网）、SSE 流式、WebSocket、限流、RBAC。
`SandboxWhitelist` 接口注释里明确警告：动态改白名单 = 远程调整安全护栏，必须靠网络层（内网隔离/反代鉴权）兜底。

### 5.7 反模式警告：`XxxModule` 空标记类

每个模块都有一个与模块同名的**空类**（`StorageModule`/`WebModule`/`MemoryModule`/`ProviderModule`/`ToolModule`/`ChannelCliModule`/`ProviderModule`），
里面**只有 Javadoc 注释、零代码**。它们是「模块说明书」，不参与任何装配。

**真实装配点全仓唯一：`io.oryxos.cli.OryxOsRuntime`。** 复刻时不要照抄这个形态 —— 要么删掉这些空类，要么让它们真的承担模块装配职责。

---

## 6. specs/ 与 .specify/ 目录概览

> 本节只要概览（过程考古另有专人负责）。

### 6.1 `specs/` —— Spec-Kit 规格驱动开发（SDD）产物

11 个特性目录，**节号 ↔ 特性序号两套编号并存**：

```
specs/
├── 001-provider-abstraction/   (class-16)
├── 002-react-loop/             (class-17)
├── 003-cli-entry/              (class-18)
├── 004-notify-outbound/        (class-19)
├── 005-tool-system/            (class-20)
├── 006-memory-pluggable/       (class-22)
├── 007-sandbox-whitelist/      (class-24)
├── 008-scheduled-tasks/        (class-25)
├── 009-web-service/            (class-26)
├── 010-folder-agent/           (class-29)
└── 011-agent-lifecycle/        (class-30)
```

每个目录固定 8~9 件套：`spec.md`、`plan.md`、`tasks.md`、`research.md`、`data-model.md`、`quickstart.md`、
`contracts/<特性名>.md`、`checklists/requirements.md`、`acceptance-report.md`。
**例外**：`010-folder-agent/` 与 `011-agent-lifecycle/` 没有 `acceptance-report.md`（最后两个特性尚未走完验收）。

spec.md 的 `Input` 字段直接粘贴课件节的需求描述（含明确"边界：不做 XX"）。

### 6.2 `.specify/` —— Spec-Kit 0.12.3.dev0 脚手架

```
.specify/
├── feature.json               # 当前特性指针 → specs/011-agent-lifecycle
├── init-options.json          # ai=claude, ai_skills=true, feature_numbering=sequential
├── integration.json + integrations/{claude,speckit}.manifest.json
├── memory/constitution.md     # ★ 宪法 v1.1.0
├── scripts/bash/*.sh          # check-prerequisites / common / create-new-feature / setup-plan / setup-tasks
├── templates/                 # spec / plan / tasks / checklist / constitution 模板
└── workflows/                 # "Full SDD Cycle": specify → plan → tasks → implement
```

**宪法 v1.1.0**（`.specify/memory/constitution.md`，Ratified 2026-07-01 / Last Amended 2026-07-10）8 条原则 ——
与 `CLAUDE.md` 的「不可违背的原则」一一对应，是全仓的真正权威：

I. 自实现 ReAct 循环 (NON-NEGOTIABLE) · II. Spring AI 仅做协议转换与 Schema 生成 (NON-NEGOTIABLE) ·
III. Provider 显式映射 · IV. 一个目录 = 一个 Agent；AGENT.md 由 ContextLoader 加载，不作为 Tool ·
V. 审计 Day One 落库 (NON-NEGOTIABLE) · VI. 安全是地基：强制沙箱白名单，不用 SecurityManager (NON-NEGOTIABLE) ·
VII. 同步执行 + 虚拟线程，不引入异步编程模型 · VIII. 配置即 Agent，实例无状态、状态外置

### 6.3 `docs/` —— 纸面资产链

`docs/IndustryResearch.md`（业界调研）→ `docs/DemandAnalysis.md`（需求 What）→ `docs/TechnicalSolution.md`（技术方案 How，全仓最核心设计文档，863 行 15 章）
→ `docs/AiProgrammingGuide.md`（AI 编程实施思路：Spec-Kit + 手动提示词混合模式）→ `docs/CliGuide.md` / `docs/oryxos.md` / `docs/oryx-labs.md`
+ `docs/class/`（约 30 个课件文件，第 1~32 节，第 16 节起为 `.md`）

`docs/TechnicalSolution.md` 章节骨架：第一部分底座（1 方案概述 / 2 整体架构 / 3-7 五大核心能力 / 8 支撑模块 / 9 数据持久化 / 10 项目工程结构）
· 第二部分定义一个 Agent（11 一个目录 + Web Service）· 第三部分整合与验证（12 关键流程 / 13 实施节奏 / 14 性能与可扩展性 / 15 总结）

### 6.4 `website/` —— VitePress 官网

- **VitePress 1.6.4**，中英双语 locales（root=en-US，`/zh/`=zh-CN），`base: '/oryxos/'`，部署 GitHub Pages（`.github/workflows/deploy-pages.yml`）
- 首页是自定义 Vue 组件 `website/.vitepress/theme/components/Home.vue`（37 KB 整页营销首页）
- `website/.vitepress/theme/custom.css` 覆盖 token：`--vp-c-brand-1/2/3 = #c2550a / #ea6a00 / #f97316` —— **深色底 + 橙色主色**
- 12 篇文档页（what/why/quick-start/architecture/react-loop/provider/memory/tool/api/cli/profile/roadmap）中英各一份镜像
- `website/public/images/` 含大量 `class-16-1.svg`…`class-32-1.svg` —— **官网内嵌了课件里的图**
- 与管理台的关系：README:151 "same stack and dark-orange theme as the site" —— **共享同一套设计 token，但官网是 VitePress 文档站、管理台是 Vue3+Vite 打包进后端的 UI**

### 6.5 `.claude/` —— 14 个 skills，构成「课件 → 规格 → 代码」的完整 AI 工作流

只有 `skills/`，没有 agents/commands/settings.json。

| 组 | Skills |
| --- | --- |
| Speckit 命令组（11 个，spec-kit 官方技能本地副本） | `speckit-constitution` / `specify` / `clarify` / `plan` / `tasks` / `analyze` / `checklist` / `implement` / `converge` / `taskstoissues` |
| OryxOS 自研组（3 个） | `oryxos-init`（初始化 JDK21+SB3 企业单体地基：Maven 多模块、结构化日志、Actuator+Prometheus、虚拟线程 MVC、springdoc、统一响应体、Spotless+P3C+Checkstyle、SpotBugs+FindSecBugs+OWASP、CI/pre-commit）；`oryxos-lesson-dev`（★**核心编排器**：输入节号 16~31，自动跑 备料 → speckit-specify → clarify → plan → tasks（停等确认）→ implement → 节级验收报告，全程施加硬/软门禁）；`oryxos-admin-ui`（生成/扩展管理台前端，视觉与官网同源，内置设计 token、`base '/admin/'`、产物落 `static/admin`、SPA 回落等约定，只写前端不写后端） |

**工作流结论**：这是一套把「课程课件（docs/class/第N节）」当需求源的 SDD 流水线 —— `oryxos-lesson-dev` 作为顶层入口串起 11 个 speckit 步骤。
宪法 + 质量门禁（spotless/checkstyle/spotbugs）+ `acceptance-report.md` 三层兜底。
管理台与官网「同栈同主题」不是靠人自觉，是写进 `oryxos-admin-ui` skill 的硬约定。

---

## 7. CLAUDE.md 内容摘要

`vendors/oryxos/CLAUDE.md`（401 行，中文）是作者写给 AI 编程助手的**项目指南**，
也是「这个仓库本身就是一个 AI 编程方法论示范」的最直接证据。

结构：

| 节 | 内容 |
| --- | --- |
| 开篇 | 一段话定位（企业 Agent OS、目标 Apache 顶级项目）+ 指向 5 份 docs 详细背景 |
| 技术栈 | 与 README 一致的表格 |
| 模块结构（9 个）| 模块树 + **"模块结构可按需演进（宪法 v1.1.0）：模块划分跟随 Agent 的能力域，不锁死 9 个"** + "跨模块契约放 core，由下游实现（依赖倒置），禁止循环依赖" |
| **不可违背的原则（Constitution）** | **8 条，全大写标题，是最核心的部分**（见下） |
| 工作区结构 | `.oryxos/` 运行时目录树 + `MEMORY.md` vs `USER.md` 的区别（`USER.md` 用户手写只读；`MEMORY.md` Agent 通过 `save_memory` 写入） |
| 核心数据模型 | `AGENT.md` 完整实例 + 3 张 SQLite 表字段表 |
| ReAct Loop 工作机制 | ASCII 流程图（用户消息 → PromptBuilder 四段 → ProviderService → 分支 → ToolExecutor → 回填） |
| Tool 体系 | `OryxTool` 接口 + 9 个内置工具表 + Plugin Tool 三档表 |
| Web Service API | 10 个端点表 + **"核心阶段不做：认证/SSE/WebSocket/限流/RBAC"** |
| 命令行工具（12 个） | 子命令清单 |
| 配置加载规则 | 环境变量注入纪律 |
| 五大核心能力与验收 Demo | 能力 ↔ 核心组件 ↔ 验收 Demo 对照表 |
| 四周实施节奏 | 周次 ↔ 核心任务 ↔ 涉及模块 ↔ 验收 Demo |
| **常见陷阱** | **8 行表格：陷阱 / 症状 / 修复**（见下） |
| 设计原则 | 7 条 |

**8 条不可违背的原则**（与 `.specify/memory/constitution.md` v1.1.0 一一对应）：

1. **自实现 ReAct Loop** —— 不得用 Spring AI 的 Agent 抽象（`ChatClient.prompt().call()` 的自动工具执行）。核心循环约数十行 Java
2. **Spring AI 只用两件事 ⚠️** —— ①LLM Provider 协议转换 ②`@Tool` 注解的 JSON Schema 生成。**必须禁用自动 tool 执行**，违反会导致 tool 被调两次。附正误代码对照
3. **Provider 必须显式映射** —— 不得靠扫描 Spring 容器里的 `ChatModel` Bean 类型区分（Bean 类型相同），必须维护 `Map<String, ChatModel>`
4. **一个目录 = 一个 Agent；`AGENT.md` 归 `ContextLoader`，不是 Tool** —— 一个 Agent 目录永远不是一个可执行 Tool，不进 `ToolRegistry`、不放 `oryxos-tool`
5. **审计表 Day One 写入** —— `tool_invocations` + `llm_calls` 核心阶段就必须写入，不得以"日志够了"为由跳过
6. **不使用 Java SecurityManager** —— JDK 17 起废弃、JDK 21 已不可用；Sandbox 通过白名单实现（文件路径 / shell 命令首 token / HTTP 域名通配符）
7. **同步执行模型** —— 全程同步阻塞 + Virtual Thread；**不引入** Reactor/WebFlux/CompletableFuture（SSE 流式放扩展阶段）
8. **Tool 模块三合一** —— 内置 Tool + MCP Client 合并在一个 `oryxos-tool`，不拆成多个模块

**8 条常见陷阱表**（`CLAUDE.md:377-388`）—— 这是给 AI 助手的「踩坑备忘」，也是复刻时的最佳 checklist：

| 陷阱 | 症状 | 修复 |
| --- | --- | --- |
| Spring AI 自动执行 tool | Tool 被调两次，结果重复 | 禁用 `ChatClient` 自动执行，由 `ToolExecutor` 接管 |
| Provider 靠类型扫描区分 | 多 Provider 时路由错乱 | 改用显式 `Map<String, ChatModel>` |
| `AGENT.md`/子指令放进 Tool 模块 | Agent 目录被当 Tool 注册，执行时报错 | 归 `ContextLoader` |
| 审计表只写日志不落库 | 扩展阶段审计功能需要反解析日志 | 核心阶段就写入 SQLite |
| 用 `hibernate.ddl-auto=update` 迁移表结构 | SQLite ALTER TABLE 报错 | 手动维护建表脚本或引入 Flyway |
| 在 ReAct Loop 里用异步 | 复杂度激增，Virtual Thread 优势消失 | 保持同步阻塞 |
| `MEMORY.md` 超 4000 字不截断 | 注入 system prompt 超 context window | `truncateIfNeeded()` 超阈值保留最近内容 |
| Tool 模块拆成多个 | 模块间依赖混乱 | 内置 Tool + MCP Client 合并为一个 `oryxos-tool` |

> **对 YokeOS 的直接价值**：这份 CLAUDE.md 本身就是一份可复用的「AI 编程项目指南模板」——
> 定位 → 技术栈 → 模块结构 → 不可违背原则 → 工作区/数据模型 → 核心机制 → 能力清单 → 实施节奏 → 常见陷阱。
> 建议在 YokeOS 复刻时同步产一份同构的 CLAUDE.md。

---

## 8. 仓库规模统计

### 8.1 按 Maven 模块（Java，排除 `target/`）

| 模块 | 主代码文件 | 主代码行 | 测试文件 | 测试行 | 测试/主比 |
| --- | --- | --- | --- | --- | --- |
| `oryxos-core` | 36 | 2,113 | 17 | 1,963 | 93% |
| `oryxos-tool` | 34 | 1,476 | 14 | 1,606 | **109%** |
| `oryxos-web` | 35 | 1,240 | 7 | 856 | 69% |
| `oryxos-storage` | 17 | 1,104 | 6 | 487 | 44% |
| `oryxos-cli` | 11 | 876 | 0 | 0 | 0% |
| `oryxos-memory` | 8 | 473 | 5 | 427 | 90% |
| `oryxos-provider` | 7 | 345 | 5 | 413 | **120%** |
| `oryxos-channel-cli` | 2 | 65 | 0 | 0 | 0% |
| `oryxos-boot` | 1 | **11** | 7 | 956 | **8,691%** |
| **合计** | **151** | **7,663** | **61** | **6,708** | **88%** |

**观察**：
- **全仓 Java 主代码只有约 7.6K 行** —— 一个具备 REST + 管理台 + MCP + 定时任务 + 记忆 + 沙箱 + 审计的 Agent OS 内核。
  复刻规模可控。
- `oryxos-core` + `oryxos-tool` 两模块占主代码 47%，是真正的重心。
- `oryxos-boot` 主代码 11 行但测试 956 行 —— **boot 模块实际是 E2E 测试的载体**，不是业务代码。
- `oryxos-cli` 与 `oryxos-channel-cli` **零测试**（picocli 命令层未被测，轻命令走 NIO/裸 JDBC 也难以单测）——复刻时可改进。
- 测试密度最高的两个模块恰恰是 `oryxos-provider`(120%) 和 `oryxos-tool`(109%) —— 正是「最容易出现隐蔽 bug 的边界层」。

### 8.2 前端与管理台

| 项 | 值 |
| --- | --- |
| `oryxos-web/src/main/frontend/` | 3 个 `.vue` + 2 个 `.js` + 1 个 `.ts` + 2 个 `.css` |
| 其中 `App.vue` | **899 行（整个管理台单文件）** |
| 运行时依赖 | 仅 `vue@^3.5.13`（无 router / pinia / UI 库） |

### 8.3 全仓

| 项 | 值 |
| --- | --- |
| Git commit | 64 |
| Markdown 文件（全仓） | 173 |
| SVG | 67（官网课件图 + logo/架构图） |
| `docs/` 课件 | 约 30 个文件，第 1~32 节 |
| `specs/` 特性目录 | 11 个 |
| `.claude/skills/` | 14 个 |

---

## 9. 复刻要点与避坑清单

### 9.1 必须原样继承的 10 条

1. **core 零内部依赖 + 8 个契约接口**，下游实现、装配方注入 —— 依赖倒置，禁循环依赖
2. **禁用 Spring AI 自动 tool 执行的三道闸**（autoconfigure.exclude + `proxyToolCalls(TRUE)` + `SchemaOnlyCallback.call()` 抛异常）+ 回归测试钉死
3. **Provider 显式 `Map<String, ChatModel>`**，OpenAI 兼容端点统一工厂，换 provider 只改 YAML
4. **deny-all 沙箱语义 + 三类白名单物理隔离**：路径 `normalize().toAbsolutePath()` 后 `startsWith`；域名 `*.` 转 `.endsWith(".x.com")` 带点号边界；shell 只比命令首 token；`default` 分支 deny
5. **`schema.sql` 是表结构唯一权威**：`ddl-auto: none` + `sql.init.mode: always` + 幂等 DDL
6. **审计 Day One 落库，且审计自身失败不上抛**（可用性 > 完整性，取舍要明写进设计文档）
7. **`session_id` 拼接收敛为全库唯一一个私有方法**（`channel:user:profile`）
8. **`session_id` 生成、`archive` 返 boolean 不抛异常** —— core 不依赖 Web 异常语义，HTTP 状态码由 web 层翻译
9. **统一响应信封 `ApiResponse{code,message,data,timestamp}` + 异常即状态码 + 日志 sanitize**
10. **一个 Agent 目录 = 一个 Agent，且永远不是 Tool**；正文常驻 system prompt、子资源按需取用

### 9.2 必须避开的 7 个坑（上游自己踩过并写进注释）

| 坑 | 上游的解法 | 出处 |
| --- | --- | --- |
| `scanBasePackages` 不会带动 JPA 仓库/实体跨模块 | 显式 `@EnableJpaRepositories(basePackages="io.oryxos.storage")` + `@EntityScan(...)` | `oryxos-cli/.../OryxOsRuntime.java` 类注释 |
| Spring AI eager 建模型索要 key | `autoconfigure.exclude` 排掉 `OpenAiAutoConfiguration`/`DashScopeAutoConfiguration` | `oryxos-boot/.../application.yml:7-13` |
| SQLite 方言不在 hibernate-core | 额外引 `org.hibernate.orm:hibernate-community-dialects` | `oryxos-storage/pom.xml` |
| Nacos 健康指示器报 DOWN（Alibaba starter 传递依赖） | `management.health.nacos-config/discovery.enabled: false` | `application.yml:82-90`（复刻时不用 Alibaba starter 可直接避开） |
| google-java-format 在 JDK 21 需要 javac 深反射 | `.mvn/jvm.config` 5 条 `--add-exports` | `.mvn/jvm.config` |
| P3C 2.1.1 不支持 Java 18-21 **语法** | PMD `targetJdk=17` + ASM 升到 9.7；代码须避免 record patterns / pattern switch（record/sealed 可用） | `pom.xml:198-246` |
| macOS WatchService 不监听子目录内文件变更 | 写 `AGENT.md` 时显式走 `AgentLifecycleService.update`（写+校验+重注册） | `oryxos-web/.../WorkspaceApiController.java` |

### 9.3 不要照抄的 4 处（上游自身的粗糙处）

1. **`OryxOsApplication` 是死入口**，真装配类在 `oryxos-cli` 模块（`OryxOsRuntime`）—— 反直觉。建议复刻时把装配类放 boot 或独立 `oryxos-assembly` 模块，让模块职责名实相符
2. **`XxxModule` 7 个空标记类**零代码零装配 —— 建议删除或赋予真实职责
3. **core 没有 `Channel` 接口** —— 若 YokeOS 要做 IM/Webhook 多渠道，需要自建 SPI；照抄会卡在扩展点上
4. **轻命令硬编码路径常量**（`SessionListCommand` 裸 JDBC、`ToolListCommand` 硬编码工具名、`ProviderListCommand` 自己读 YAML）—— 建议用常量类收口；且 `oryxos-cli`/`oryxos-channel-cli` 零测试

### 9.4 复刻顺序建议（对齐上游的 4 周节奏，`CLAUDE.md:366-374`）

| 阶段 | 核心任务 | 模块 | 验收 Demo |
| --- | --- | --- | --- |
| 第一阶段 | Provider 抽象 + ReAct Loop | core / provider / channel-cli / cli | `oryxos chat` 跑通一个带工具的对话 |
| 第二阶段 | Memory + Tool 体系 | memory / tool | 跨对话记偏好；零代码接一个社区 MCP server |
| 第三阶段 | Web Service | web / storage | 全部 REST 端点跑通 + 管理台可看 |
| 第四阶段 | 多 Agent + 定时 + 生命周期 | 收尾 | 多 Agent 并存；Session 跨重启恢复；沙箱白名单；`MockChatModel` 全链路无 key E2E |

### 9.5 规模预估

上游 **7.6K 行 Java 主代码 + 6.7K 行测试 + 900 行 Vue** 就实现了完整 Phase 1。
若 YokeOS 目标是「产品层复刻」，Java 主代码量级预计在 **8K~12K 行**（加上 Channel SPI、认证、SSE 流式等上游明确列为扩展阶段的能力），
9~10 个 Maven 模块的结构可以直接沿用。

---

## 附录 A：全仓 Java 类清单（按模块）

<details>
<summary>展开</summary>

**oryxos-core**（36）：`OryxTool` `ToolResult` · agent: `AgentLifecycleService` `AgentLoader` `AgentMarkdown` `AgentScheduler` `AgentService` `AgentStore` `ProfileContext` `PromptBuilder` `ReActLoop` `ScheduledTaskStore` `ScheduledTaskView` `TaskExecutionView` `ToolExecutionContext` `ToolExecutor` `ToolInvocationAuditor` `WorkspaceWatcher` · context: `ContextLoader` · memory: `MemoryScope` `MemoryService` · profile: `Profile` `ProfileLoader` `ProfileRegistry` `ProfileValidationException` · provider: `LlmCallAuditor` `ProviderRequest` `ProviderResponse` `ProviderService` `ToolCallRequest` `Usage` · sandbox: `SandboxWhitelist` · session: `Message` `Session` `SessionManager` `SessionSummary`

**oryxos-provider**（7）：`MockChatModel` `ProviderChatModelFactory` `ProviderModule` `ProviderNotFoundException` `ProvidersProperties` `SpringAiProviderServiceImpl` `ToolSchemaAdapter`

**oryxos-memory**（8）：`InMemoryMemoryStore` `LongTermMemoryStore` `MarkdownMemoryStore` `Mem0MemoryStore` `MemoryModule` `MemoryServiceImpl` `SqliteMemoryStore` `builtin/MemoryTools`

**oryxos-tool**（34）：`AnnotatedToolAdapter` `ToolModule` `ToolRegistry` · builtin: `FileTools` `HttpTools` `InteractionTools` `NotifyTools` `ShellTools` `WebSearchTools` · interaction: `ConsoleUserInteraction` `InteractionUnavailableException` `UnsupportedUserInteraction` `UserInteraction` · mcp: `McpClientService` `McpConfigLoader` `McpServerConfig` `McpToolAdapter` · notify: `DingTalkNotifyAdapter` `FeishuNotifyAdapter` `NotifyChannelAdapter` `NotifyTarget` `WeComNotifyAdapter` `WebhookNotifyAdapter` · sandbox: `ActionType` `FileSandboxProperties` `HttpSandboxProperties` `PermissiveSandbox` `Sandbox` `SandboxAction` `SandboxViolationException` `ShellSandboxProperties` `WhitelistSandbox` · web: `DuckDuckGoSearchProvider` `SearchProvider`

**oryxos-storage**（17）：`JpaLlmCallAuditor` `JpaScheduledTaskStore` `JpaSessionManager` `JpaToolInvocationAuditor` `LlmCall` `LlmCallRepository` `MemoryEntry` `MemoryEntryRepository` `ScheduledTask` `ScheduledTaskRepository` `Session` `SessionRepository` `StorageModule` `TaskExecution` `TaskExecutionRepository` `ToolInvocation` `ToolInvocationRepository`

**oryxos-web**（35）：`GlobalExceptionHandler` `WebModule` · common: `ApiResponse` · config: `WebConfig` · controller: `AgentApiController` `ProfileApiController` `SandboxWhitelistController` `ScheduleApiController` `SessionApiController` `SystemApiController` `ToolApiController` `WorkspaceApiController` · dto: `AgentView` `CreateAgentRequest` `CreateSessionRequest` `ExecutionView` `FileNode` `GenerateFilesRequest` `GeneratedFilesView` `InfoView` `MessageRequest` `MessageResponse` `ProfileView` `SaveFilesRequest` `ScheduleView` `SessionSummaryView` `SessionView` `SetEnabledRequest` `ToolView` `UpdateAgentRequest` `WriteFileRequest` · error: `AgentTimeoutException` `ProviderUnavailableException` `ResourceNotFoundException` `SessionNotFoundException`

**oryxos-cli**（11）：`OryxOsCli` `OryxOsRuntime` · command: `ChatCommand` `GatewayCommand` `InitCommand` `ProfileCommand` `ProviderListCommand` `ServeCommand` `SessionListCommand` `StatusCommand` `ToolListCommand`

**oryxos-channel-cli**（2）：`ChannelCliModule` `CliChannel`

**oryxos-boot**（1）：`OryxOsApplication`

</details>

---

*报告完。所有结论均可在 `D:\Developer\Github\my-projects\YokeOS\vendors\oryxos\` 下对应路径核验；该目录为只读 git submodule，本调研未修改其中任何文件。*
