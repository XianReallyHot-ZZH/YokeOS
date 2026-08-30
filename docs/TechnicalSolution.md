# YokeOS 技术方案

本文档定义 YokeOS 的技术方案，回答 **How** 的问题。前置阅读《YokeOS 行业调研》（`docs/IndustryResearch.md`）、《YokeOS 产品定位》（`docs/yokeos.md`）与《YokeOS 需求文档》（`docs/DemandAnalysis.md`）。本文档以需求文档定义的六个核心能力（对接 LLM、ReAct 循环、Memory 记忆、工具体系、通知与定时、对外服务）为骨架展开，每个模块只给职责和功能说明，不展开代码细节。代码层面的实现细节在研发阶段补充。

> 承接需求文档的定位判断：第一阶段交付的是 Agent 底座的单机运行时内核，能力上对齐业界开源 Agent OS 的基础层；让 YokeOS 成为真正企业级 Agent 底座的治理层（多租户、SSO、完整审计、Tool 治理）在扩展和社区阶段补齐。本技术方案只覆盖第一阶段的运行时内核，并在架构上为治理层预留扩展点。技术栈与模块边界沿用参照实现 OryxOS 已验证的组合——同类、同栈、同锚点的后来者，不重复发明已被验证的部分（见调研第 5.3 节「复刻型起步」）。

> **文档结构提示：** 全文分三部分。第一部分（第 1-10 章）是**底座**——让任意 Agent 都能可靠运行的引擎、能力和支撑设施，本身不是某个具体的业务 Agent。第二部分（第 11 章）讲底座之上怎么真正**定义一个业务 Agent**（一个目录定义做什么、怎么跑，Web Service 是对外的定义入口）。第三部分（第 12-15 章）是两部分放到一起之后的整合验证、实施节奏和收尾。

---

# 第一部分：底座（Agent 底座内核）

## 1. 方案概述

YokeOS 是一个 **Spring Boot 3.5.x** 单体应用，跑在 **JDK 21** 上，基于 **Spring AI / Spring AI Alibaba** 做 LLM 调用，自己实现 **ReAct loop** 作为 Agent 核心。整个 YokeOS 是一个可执行 JAR，单二进制部署，扩展阶段引入 **GraalVM Native Image** 进一步压缩启动时间和内存占用（引入时机见 13.2 遗留未决事项）。

> **技术栈选型一句话总结**：JDK 21 + Spring Boot 3.5.x + Spring AI Alibaba + 自实现 ReAct loop + SQLite + Picocli 命令行 + Vue3 管理台。

### 1.1 关键技术决策

需求文档定义了六个核心能力，下面统一列出 8 个关键决策的取舍。先用一张表速览，再逐条展开。其中决策三、决策八是需求文档未决事项表移交本阶段决议的两项（Provider 抽象形态、底层存储选型）。

| # | 决策 | 选择 | 理由 |
|---|------|------|------|
| 1 | ReAct loop 实现方式 | 自实现，不依赖 Spring AI Agent 抽象 | 完全可控，保留未来定制循环行为的空间 |
| 2 | Spring AI 使用边界 | 只用 Provider 抽象 + 协议转换 + `@Tool` schema 生成，禁用自动 tool 执行 | 避免 tool 被调两次，ReAct 循环完全由 YokeOS 自己掌控 |
| 3 | Provider 抽象形态 | 在 `ChatClient`/`ChatModel` 之上加一层薄包装 `ProviderService`，内部维护 provider name 到 `ChatModel` 的显式映射 | 显式映射原则有唯一落点模块；LLM 审计与成本记录有统一收口；ReAct 循环不感知厂商差异 |
| 4 | 执行模型 | 同步阻塞 + Java 21 virtual thread | 直观简洁，无需响应式编程，单节点撑高并发 |
| 5 | Tool 注册机制 | `@Tool` 注解 + **`YokeTool`** 抽象层 | 统一内置 Tool 和 MCP Tool 接口，ReAct 循环不感知 Tool 来源 |
| 6 | HTTP 服务层 | Spring MVC + Java 21 virtual thread | 同步直观，单机撑千级并发，扩展阶段 `SseEmitter` 支持流式 |
| 7 | Sandbox 策略 | 接口先行：`Sandbox` 抽象 + `WhitelistSandbox`（应用层 Path/Pattern 白名单）实现，扩展阶段按容器→microVM 演进 | `SecurityManager` 在 JDK 17 起废弃、JDK 21 已不可用，与 JDK 21+ 要求冲突；接口独立于白名单实现，未来换重隔离方案不用改调用方 |
| 8 | 持久化方案 | SQLite + Spring Data JPA + `MEMORY.md` 文件（不选 H2） | 单二进制零外部进程，审计表 day one 写入，避免后期从日志反解析返工；与参照实现数据行为一致 |

**决策一：自己实现 ReAct loop。** Spring AI 负责 LLM 调用、Function Calling 的协议格式转换、Provider 抽象这些底层工作，ReAct loop 自己写，保证 Agent 核心完全可控，也保留未来定制循环行为的空间。这是定位文档「自实现核心，可控优先」原则的直接落地。

**决策二：明确划清 Spring AI 的使用边界。** 这是最容易埋 bug 的地方，单列为一条决策。Spring AI 自身带有一套完整的 tool calling 自动执行机制（能自动执行 tool 再把结果回灌给模型）。

YokeOS **不使用**这套自动执行，只用 Spring AI 的两件事：
- 一是 Provider 抽象和向各家 LLM 的协议转换
- 二是 `@Tool` 注解的 JSON Schema 生成

Tool 的实际调度和执行完全由 YokeOS 自己的 **`ReActLoop`** 加 **`ToolExecutor`** 控制。换句话说，Spring AI 在 YokeOS 里只做协议适配器和 schema 生成器，不做循环引擎。研发时必须禁用 Spring AI 的自动 tool 执行，否则会出现 tool 被调两次的问题。

**决策三：Provider 抽象加一层薄包装，不做零层也不做厚层。** 需求文档将此项移交本阶段决议，两个候选的取舍如下。直接用 Spring AI 的 `ChatClient`/`ChatModel`（零层）调用链最短，但多 Provider 并存时「哪个 Bean 是 deepseek」的显式映射、每次调用的 token 统计落库、Provider 状态查询这些横切关注点会散落在各个调用方，违反「统一收口」的工程直觉；在之上加厚层（重新定义一套 Provider 通信协议）则重复 Spring AI 已经做好的协议转换，违背「底层模型协议适配复用成熟库，不重复造轮子」的设计原则。取中间：`yokeos-provider` 模块内做一层**薄包装** `ProviderService`——转发调用、持有显式映射、记录审计，不碰协议（详见第 3 章）。

**决策四：同步执行模型。** 第一阶段采用同步阻塞执行模型，跟 Spring MVC 一致。一次消息从进来、ReAct loop 执行、Tool 调用、Provider 调用到最终响应返回，全程同步。这跟 Java 21 的 virtual thread 配合得很好，单节点支撑高并发不需要响应式编程。扩展阶段引入流式输出（SSE）和异步 Tool 调用。

**决策五：Tool 注册机制用 `@Tool` 注解加 `YokeTool` 抽象层。** Spring AI 注解负责扫描 Java 方法生成 JSON Schema，YokeOS 在其上加一层 **`YokeTool`** 抽象，统一内置 Tool 和 MCP Tool 的接口形式，让 ReAct loop 不感知 Tool 来源。注解的确切名称和用法以采用的 Spring AI 1.1.8 版本为准，研发前需对当前版本核实。

**决策六：HTTP 服务层用 Spring MVC 加 Java 21 virtual thread。** 同步直观的代码加 virtual thread 的高并发能力，单机轻松撑住几千并发。扩展阶段要 SSE 流式返回时，Spring MVC 的 `SseEmitter` 也能支持。

**决策七：Sandbox 先定接口，第一阶段只填一档实现。** 隔离强度和开销是一个跷跷板，从轻到重依次是应用层白名单校验、容器隔离（namespace + cgroups + seccomp）、microVM（Firecracker / Kata / gVisor）、完整虚拟机或物理隔离。为了不让第一阶段的实现选择绑死未来的架构，先抽象出一个 `Sandbox` 接口，表达"在受控环境里执行一个动作"这个意图，不携带任何一档实现特有的概念（不出现"容器镜像""VM 配置"字样）。第一阶段只实现 `WhitelistSandbox` 这一档：文件操作限制工作目录、Shell 命令白名单、HTTP 域名白名单，在应用层做校验，不使用 Java `SecurityManager`（它在 JDK 17 起已废弃、JDK 21 已不可用，与本项目 JDK 21+ 要求冲突）。扩展阶段按信号驱动升级：出现"要跑不可信代码或要多租户"时上容器隔离；出现"要跑完全不可信代码或要规模化多租户"时上 microVM。接口不随升级变化，新增的是实现类。

**决策八：持久化用 SQLite 加 Spring Data JPA，Memory 长期记忆用 `MEMORY.md` 文件加关键词检索。** 需求文档将「SQLite 还是 H2」移交本阶段决议。选 SQLite：它是嵌入式 C 实现，零外部进程、零部署依赖，与"单二进制、装好就跑"的定位一致；Agent 目录、Session、审计数据全部落在工作区一个 `.yokeos/yokeos.db` 文件里，备份即拷贝。H2 是纯 Java 实现、无 native 库依赖，但引入它与参照实现的数据行为不一致（SQL 方言、锁行为、日期函数均有差异），逐节对照时多一层噪音，且换来的只有"免 native 依赖"一点收益——Spring AI Alibaba 与 SQLite JDBC 的组合已被参照实现验证，直接继承。Agent 目录放 `.yokeos/agents/`，Session、Tool Invocation、LLM Call 落 SQLite。其中审计相关的 `tool_invocations` 和 `llm_calls` 两张表在第一阶段就做写入（不做查询接口），让可审计这个差异化能力的数据地基在 day one 就立起来，避免后期从日志反解析返工。完整的向量检索方案在扩展阶段升级（详见 9.1）。

### 1.2 整体技术栈

YokeOS 的完整技术栈（版本与参照实现逐项对齐，groupId 换为 `com.yokeos`）：

1. **JDK 21** 加 **Spring Boot 3.5.16**（parent；virtual thread 处理高并发）
2. **Spring AI 1.1.8** 加 **Spring AI Alibaba**（LLM Provider 抽象，复用现成的主流 LLM connector）
3. 自实现 **ReAct loop**（Agent 核心循环）
4. **Spring MVC**（HTTP API 服务层）
5. **Picocli 4.7.6**（命令行工具）
6. **SnakeYAML**（AGENT.md frontmatter 解析）
7. **SQLite**（sqlite-jdbc 3.53.2.1）加 **Spring Data JPA**（Session、审计和元数据持久化）
8. **MCP Java SDK**（MCP Client 集成，社区项目，可能需要部分自实现）
9. **Logback** 加 **SLF4J**（结构化日志）
10. **Vue3 + Vite**，经 **frontend-maven-plugin**（Node v20.18.0）构建（Web 管理台，产物托管在 `yokeos-web`）
11. **springdoc-openapi 2.6.0**（OpenAPI 文档）
12. **Micrometer** 加 **Prometheus**（指标采集，扩展阶段）

平台基线说明：Spring AI 2.0 的硬性基线已升到 Spring Boot 4 / Framework 7，第一阶段锁定 Boot 3.5.x 是与参照实现保持逐节可比的刻意选择，如实记为已知技术债；升级到 Boot 4 + Spring AI 2.0 列为扩展阶段候选，作为一次真实的架构升级练习（见行业调研 4.5 节）。

---

## 2. 整体架构

YokeOS 的整体架构按"六个核心能力加支撑模块"组织。六个核心能力是 Agent 底座运行时内核的主体，支撑模块是让这些能力跑起来需要的工程基础设施。

整体上，YokeOS 是一个 Spring Boot 单体应用，对外有两个人工触发入口，加一个内部自动触发入口：

1. **CLI Channel** 用于本地交互和调试，**Web Service** 用于业务系统通过 REST API 集成，这两个是"人推"；**`AgentScheduler`**（8.5）按 cron 到点自动发起调用，是"钟推"。三个入口的消息最终都汇入同一个引擎，`AgentService` 作为统一入口不区分消息从哪个入口来。
2. 引擎是 **ReAct 循环**，它是整个系统的中枢，负责把"接收消息、组装 Prompt、调用 LLM、执行 Tool、回填结果、继续推理"这条链路驱动起来。引擎自己不直接干活，而是调度三块能力：
   1. **Provider** 负责 LLM 调用并向外对接各家大模型 API
   2. **Memory** 负责会话和长期记忆并读写本地文件
   3. **Tool** 负责工具执行并通过 MCP Client 向外对接外部 MCP server

这三块能力之下是存储层，Session 和审计数据落 SQLite，Agent 目录、Bootstrap、Skill、Memory、MCP 配置这些用户可维护的数据落文件系统。

这个架构有两个要点：

1. 所有能力收敛到一个引擎、一套存储、一个进程内，符合"单二进制、装好就跑"的定位，外部依赖（LLM 厂商 API、外部 MCP server）都在应用边界之外，YokeOS 自身不绑定任何一家。
2. 引擎和能力之间、能力和外部之间都通过抽象接口解耦，这让扩展阶段加新 Channel、新 Provider、新 Tool 时只需在边缘扩展，不动核心引擎。

### 2.1 分层视图

从上到下分四层：

1. **接入层**（CLI Channel、Web Service 的 REST API、`AgentScheduler` 定时触发），负责消息进出。
2. **引擎层**（`ReActLoop`、`PromptBuilder`、`ToolExecutor`），是 Agent 的大脑。
3. **能力层**（Provider、Memory、Tool），给引擎提供 LLM 调用、上下文、执行能力。
4. **基础层**（Agent 目录/Skill/Bootstrap 加载、Session 存储、SQLite、配置与密钥加载），是工程地基。

### 2.2 六个能力之间的关系

六个能力不是平行的功能模块，它们之间有明确的协作关系：

- **ReAct 循环（能力二）** 是引擎，负责把"用户消息到 LLM 思考到 Tool 执行到结果回填到继续"这件事跑起来。
- **Provider（能力一）** 给 ReAct 循环提供 LLM 调用能力，每轮思考都要调一次。
- **Memory（能力三）** 给 ReAct 循环提供上下文，每轮组装 prompt 时把会话历史和长期记忆注入进去。
- **Tool（能力四）** 给 ReAct 循环提供执行能力，LLM 决定调哪个 Tool 后由 ReAct 循环负责执行。
- **通知与定时（能力五）** 不是第四块供养能力，而是两个对称物：**Notify** 是出站通道——入站有 Channel 解决"消息怎么进来"，Notify 解决"Agent 跑完把结果送到哪"（见 6.8）；**定时任务** 是第三条触发路径——给 `AgentService` 加上"钟推"入口（见 8.5）。
- **Web Service（能力六）** 是这套内部能力的对外出口，把前四个能力包装成 REST API 供业务系统集成，它不参与 Agent 内部循环，而是循环的触发入口和结果出口之一（另外两个入口是 CLI Channel 和 `AgentScheduler` 定时触发，见 8.5）。

> **简化成一句话**：Provider、Memory、Tool 三个能力供养 ReAct 循环这个引擎；通知与定时给引擎补上出站通道和第三触发源；引擎跑出的能力通过 CLI、Web Service、定时任务三个入口对外提供。

---

## 3. 核心能力一：对接 LLM（Provider 抽象）

LLM 调用的复杂度都被 **Spring AI Alibaba** 吸收掉了。YokeOS 在其上做一层薄包装（决策三），把 Spring AI 的 `ChatModel` 转成 YokeOS 内部的 **`ProviderService`** 抽象。

### 3.1 模块组成

**`ProviderService` 模块。** 职责是统一管理所有 LLM Provider，对 ReAct 循环屏蔽不同 LLM 厂商的差异。ReAct 循环调 LLM 时传入 Profile 和 Prompt，`ProviderService` 按 Profile 配置选对应的底层 `ChatModel` 完成调用，并把每次调用的 token 使用量与耗时写入 `llm_calls` 表。

**Function Calling 适配模块。** 职责是把 YokeOS 内部的 **`YokeTool`** 抽象转成 Spring AI 的工具调用格式。Spring AI 已经做好了向各家 LLM 协议的转换（OpenAI tools、Anthropic tools、Gemini function declarations），YokeOS 不需要关心每家协议的差异。注意这里只用 Spring AI 的格式转换，不用它的自动执行（见决策二）。

**Provider 配置模块。** 通过 `application.yaml` 配置 Provider 的 API key 和 base URL，Spring AI Alibaba 根据配置创建对应的 `ChatModel` Bean。API key 一律用 `${ENV_VAR}` 占位从环境变量解析（见 8.8），不明文写进配置。

### 3.2 Provider 名到 ChatModel 的显式映射

这是一个需要讲清楚的关键点。Spring AI Alibaba 配多个 Provider 时，Spring 容器里会有多个 `ChatModel` Bean。仅靠"扫描容器里所有 `ChatModel`"无法可靠区分哪个是 deepseek、哪个是 kimi，因为 Bean 类型相同、Bean name 未必等于 provider name。

YokeOS 的做法是维护一份显式的 **provider name 到 `ChatModel` 的映射**，而不是靠类型扫描自动来。

具体是在 Provider 配置里为每个 Provider 声明唯一的 provider name（`deepseek`、`qwen`、`kimi` 等），`ProviderService` 启动时按这个 name 建立映射表，Profile 通过 provider name 引用。这样多 Provider 并存时不会有歧义。映射的具体实现方式（用 Spring 的 `@Qualifier`、还是自己维护配置表）在研发阶段定，但"**显式映射、不靠类型扫描**"这个原则要守住，否则多 Provider 跑不起来。

### 3.3 关键设计点

**第一阶段不做 fallback 和 hedge racing。** Provider 故障时直接报错给 Agent。fallback 链路、circuit breaker、hedge racing 放扩展阶段，通过 Profile 的 `fallback` 字段声明备用 Provider。

**成本透明在第一阶段做基础版。** 每次 LLM 调用记录 token 使用量、Provider、模型，写入 `llm_calls` 表（见第 9 章）。扩展阶段做完整的成本聚合和 Web 看板。

**一句话生成的系统默认 Provider 走独立配置键，不回退。** 一句话生成 Agent 定义（见 11.3）本身是一次 LLM 调用，它用的 Provider/模型与具体 Agent 的 Provider 配置是两回事，走 `application.yaml` 里的独立配置键 `yokeos.agent-generation.provider`（指向已注册的 provider name）加可选的 `yokeos.agent-generation.model`（缺省用该 Provider 的默认模型）。未配置时**不静默回退**到任何具体 Provider——调用生成端点时返回明确错误并提示配置方法，与"缺失或非法时清晰报错，不静默失败"的配置纪律一致。该键缺失不阻断进程启动（生成是运行时功能，不是启动依赖），报错发生在调用时。

---

## 4. 核心能力二：ReAct 循环

ReAct 循环是 YokeOS 最核心的一段代码。输入一条用户消息，输出 Agent 的最终响应，中间可能调用若干次 LLM 和若干次 Tool。

### 4.1 ReAct loop 算法

ReAct 是 **Reason** 加 **Act** 的简称。算法步骤：

1. 接到用户消息追加到 Session 对话历史
2. 组装 Prompt（system prompt 加 Bootstrap 加 Skill 加 Memory 加对话历史加可用 Tool 列表）
3. 调用 LLM Provider 获取响应
4. 如果响应**没有** Tool 调用，返回最终响应
5. 如果**有** Tool 调用，YokeOS 执行 Tool 并把结果作为 tool 消息追加到对话历史
6. 回到组装 Prompt 步骤继续循环
7. 达到最大迭代次数（默认 10 次）强制结束

### 4.2 模块组成

**`ReActLoop` 模块。** Agent 的核心循环引擎。输入 Session 和用户消息，输出最终响应。内部维护当前迭代次数，调用 `ProviderService` 调 LLM，调用 `ToolExecutor` 执行 Tool，把每轮的响应和工具结果累积到 Session 对话历史。核心循环逻辑精简，约数十行 Java，不依赖 Spring AI 的 Agent 抽象，让实现者完整掌握 Agent 的工作机制。

**`PromptBuilder` 模块。** 组装每轮 LLM 调用的 Prompt。按四部分顺序拼接：

1. system prompt（`AGENT.md` 正文（这个 Agent 的指令）加 Bootstrap 文件加引用到的 Skill 正文，由 `ContextLoader` 提供；末尾附当前日期时间——LLM 自己不知道今天几号，定时场景的"今天"全靠这一行）
2. Memory 注入（会话历史加长期记忆，由 `MemoryService` 提供）
3. 对话历史（按 `maxHistoryTurns` 截断后的 Session messages）
4. 当前 Profile 可用的 Tool 列表（按 Function Calling 格式）

**`ToolExecutor` 模块。** 执行 LLM 返回的 Tool 调用请求。从 `ToolRegistry` 找到对应 Tool，做 Sandbox 检查，执行 Tool，把结果包装成 `ToolResult` 返回给 ReAct 循环，并写入 `tool_invocations` 表。失败时按可重试策略返回错误信息，默认指数退避最多重试三次（对齐需求文档 8.2）。

**`AgentService` 模块。** 三种触发源共用的统一入口，也是一次处理的编排者：`process(Session, String)` 内部依次做——把当前 Profile 放进 `ProfileContext`（ThreadLocal，虚拟线程下每个请求天然独立）、调 `ReActLoop.run` 跑完循环、持久化 Session、`finally` 里清掉 `ProfileContext`。`ProfileContext` 解决的是"工具执行时怎么知道当前是哪个 Agent"：`YokeTool.execute` 的签名不带 Profile，`NotifyTools` 取当前 Agent 的通知渠道、按 Profile 过滤工具子集这类需求，都从 `ProfileContext` 读，不改工具接口。

### 4.3 关键设计点

**`MAX_ITERATIONS` 限制。** 第一阶段默认 10 次，防止 Agent 陷入 Tool 调用死循环，可在 Agent 定义里覆盖。

**消息累积。** 每次迭代都把 LLM 响应和 Tool 结果追加到 Session 的 messages 列表。Session 的对话历史包含完整的 LLM 调用链和 Tool 调用链，对外可查可审计。

**上下文长度管理。** 第一阶段策略简单：保留 system prompt 和最近 N 轮对话，超出部分丢弃，N 由 Agent 定义配置默认 20 轮。扩展阶段引入总结压缩。

**第一阶段不做：** Tool 调用并行（一次响应里多个 Tool 调用按顺序执行）、Agent 间任务委托、流式响应。这些放扩展阶段。

---

## 5. 核心能力三：Memory 两层记忆

Memory 是 Agent 底座区别于普通 chatbot 的核心能力。两层记忆是第一阶段的完整设计（会话 + 长期），情景记忆放扩展。

> **架构调整说明：** Memory 做成两层记忆的统一门面，对 ReAct 循环只暴露一个 `MemoryService` 接口，内部再分会话记忆和长期记忆。这样对外叙述的"两层记忆"和内部实现一致，ReAct 循环不需要分别去问 Session 和 `MEMORY.md` 两个地方。

### 5.1 模块组成

**`MemoryService` 模块（统一门面）。** 对 ReAct 循环暴露统一的记忆读写接口。内部把会话记忆委托给 `SessionManager`（底层是 SQLite 的 Session 存储），把长期记忆委托给 `LongTermMemoryStore` 后端（默认底层是 `MEMORY.md` 文件）。ReAct 循环组装 prompt 时只调 `MemoryService` 一个接口拿到完整上下文。这是避免 Memory 概念横跨两个模块却没有统一入口的关键调整。

**`LongTermMemoryStore` 后端接口（可插拔）。** 长期记忆抽成一个后端接口，把"长期记忆的读写契约"和"具体存哪、怎么存"解耦。对外三个方法：

- `append(content, scope)`（追加内容到指定分区，`scope` 取 `MemoryScope.CORE` 或 `ARCHIVAL`，默认 `ARCHIVAL`，自动加日期 header）
- `load`（返回核心记忆区全量 + 归档记忆区截断后的内容，核心区永远完整不截断；归档区超过 4000 字时保留最近内容，阈值可在配置调整）
- `recallByKeyword`（按关键词检索，只在归档记忆区做匹配，核心区不参与检索因为它本来就会被全量注入）

所有实现共同遵守四条行为契约：①不缓存（每次重新读文件/查库/调 API）；②核心记忆区永不被截断，截断只作用在归档区；③写核心还是写归档由 Agent 经 `scope` 显式指定，系统不猜；④`recall` 是关键词检索不做复杂化。第一阶段**不做自动抽取**，分区完全由 Agent 通过 `save_memory` 的调用时机和 `scope` 参数手动决定，这是信号驱动升级原则在 Memory 模块的体现——自动从对话历史提炼记忆放到扩展阶段，等有真实误判/遗漏的使用数据再决定要不要做。

**三档后端实现（第一阶段一次交付，靠配置 `memory.backend` 选一个）。**

- **`MarkdownMemoryStore`（默认）。** 底层操作 `.yokeos/memory/MEMORY.md` 一个 Markdown 文件，按 `## 核心记忆` / `## 归档记忆` 两个 header 分区（详见 5.2）；截断是字符串裁归档段，检索是关键词行匹配。零依赖、人可读、git 可跟踪，记忆量不大时的首选。**定位为单机档**：记忆本体在本地文件系统，多副本部署无法共享——分布式部署请选 sqlite 档或 mem0 档。
- **`SqliteMemoryStore`。** 记忆按条入库到 `memory_entries` 表（手工建表脚本，与 sessions/审计表同口径），截断变成归档查询的 `LIMIT N`、检索变成 SQL `LIKE`、核心区用 `WHERE scope='CORE'` 全量取。仍**零外部依赖**（复用已有 SQLite），记忆量上千、要结构化查询时的升级档。
- **`Mem0MemoryStore`。** 接一个**自托管** Mem0 记忆层（数据不出域），Java 侧走 REST 集成，`append/load/recall` 翻译成 Mem0 的 add/get/search——提炼、冲突消解、语义检索都交给 Mem0。凭证与地址走环境变量占位。这是"真需要智能记忆"时的外部集成档，且语义检索能力由外部服务承担，不在 YokeOS 进程内自建向量层。

换后端只改 `memory.backend` 一行配置，`MemoryService` 以上（`PromptBuilder`/`MemoryTools`/`ReActLoop`）一个字不动——这就是接口墙的价值兑现。`recallByKeyword` 也预留了语义升级空间（Mem0 档已是语义检索），切换底层不影响上层。

**`MemoryTools` 子模块。** 把长期记忆暴露给 Agent 调用，包含 `save_memory` 和 `recall_memory` 两个内置 Tool，标注 `@Tool` 注解自动注册到 `ToolRegistry`，跟其他内置 Tool 一视同仁。

**会话记忆。** 由 `SessionManager` 实现（见第 9 章），通过 SQLite 持久化，按 Channel 加用户加 Agent 联合标识管理。`MemoryService` 把它作为两层之一统一对外。

### 5.2 MEMORY.md 文件设计（默认后端 `MarkdownMemoryStore`）

默认后端的文件位置 `.yokeos/memory/MEMORY.md`，内部用两个一级分区组织，每条记忆带日期 header。格式不做更严格的规定，Agent 写什么 LLM 自己理解就行，简单但有效；两个分区只是组织方式上的区分，不引入独立文件或独立存储。换到 `SqliteMemoryStore` 时同一套"核心/归档"语义落到 `memory_entries` 表的 `scope` 列，换到 `Mem0MemoryStore` 时落到 Mem0 的 metadata——分区约定不变，存储形态随后端而变。

### 5.3 Memory 注入到 system prompt

ReAct 循环每次组装 prompt 时，`MemoryService` 把会话历史和长期记忆（核心记忆区加归档记忆区，经 `LongTermMemoryStore.load()` 取得）提供给 `PromptBuilder`。长期记忆每次重新读不做缓存（契约一），这样 Agent 调用 `save_memory` 后下一轮立刻能看到——Markdown 档每次读一个小文件、SQLite 档每次查库、Mem0 档每次调 API，性能都可接受。扩展阶段可在门面背后加 in-memory cache 加失效机制。

### 5.4 MEMORY.md 跟 USER.md 的区别

| 文件 | 来源 | 读写方 | 用途 |
|------|------|--------|------|
| `USER.md` | 用户手写 | YokeOS 只读不写 | 用户的"初始设定"（Bootstrap 文件） |
| `MEMORY.md` | Agent 通过 `save_memory` 写入 | YokeOS 读写 | Agent 的"成长记录"（长期记忆） |

两者都进 system prompt，但来源和生命周期不同。

### 5.5 第一阶段不做的部分

- 自动抽取（由 LLM 自己决定何时调 `save_memory`，Markdown/SQLite 两档不自动从对话提取；Mem0 档的自动抽取是其自带能力，用不用取决于是否切到该后端）
- 内置向量库（第一阶段三档都不在 YokeOS 进程内自建向量层；需要语义检索时切到 `Mem0MemoryStore` 由外部服务承担。知识库与语义记忆的整体方案按产品定位路线图放扩展阶段，升级路径见 9.1）
- 情景记忆（放扩展）
- Memory Wiki（结构化 claim/evidence、矛盾检测）
- 记忆压缩（超长简单截断，md 裁字符串、sqlite 用 LIMIT）

---

## 6. 核心能力四：工具体系

Tool 是 Agent 可以调用的外部能力。YokeOS 的 Tool 分两类：**内置 Tool** 由 YokeOS 提供，**扩展 Tool** 由业务方扩展。扩展 Tool 有三种接入方式，按门槛从低到高排。

> **模块说明：** 第一阶段 Tool 相关合并为一个 `yokeos-tool` 模块（内置 Tool、MCP Client、`ToolRegistry`、Sandbox 都在里面），不拆成 builtin/skill/mcp 三个模块。原因是它们共享同一个 `YokeTool` 抽象和 `ToolRegistry`，耦合度高，第一阶段没必要拆细。
>
> 另外，一个 Agent 目录（`AGENT.md` 及其附属资源）不是 Tool，而是上下文来源（正文与引用 Skill 正文注入 system prompt，附属资源经底座既有 Tool 按需取用，见 11.1），因此 `AgentLoader`/`ContextLoader` 不放在 Tool 体系里，归到上下文加载那一层（见 8.3）。

### 6.1 YokeTool 抽象

YokeOS 内部统一的 Tool 抽象接口。内置 Tool、`@Tool` 注解的扩展 Tool、MCP Tool 都被包装成 **`YokeTool`** 实例注册到 `ToolRegistry`，ReAct 循环不感知具体 Tool 的来源。

`YokeTool` 接口约定四个核心方法：

- `getName`
- `getDescription`
- `getInputSchema`（JSON Schema）
- `execute`（接收 JSON 输入返回 `ToolResult`）

`ToolResult` 包含成功标识、结果内容、错误信息、是否可重试。

### 6.2 内置 Tool（九个）

第一阶段提供九个内置 Tool，分五组：

- **`FileTools`**：`read_file`、`write_file`、`list_dir`，执行前调用 `Sandbox.enforce(...)` 做路径白名单检查
- **`ShellTools`**：`shell` Tool 执行命令，参数以 argv 直传（不经 Shell 解释拼接），带超时和命令白名单
- **`HttpTools`**：`http_get`、`http_post`，带域名白名单
- **`MemoryTools`**：`save_memory`、`recall_memory`（归 Memory 模块，但作为内置 Tool 注册）
- **`NotifyTools`**：`notify`（把消息推送到 Agent 配置好的通知渠道，详见 6.8）

这九个覆盖"让 Agent 能读写文件、跑命令、调外部 API、记事、往外推通知"的最短链路。

### 6.3 扩展 Tool 方式一：零代码 Agent 目录 加复用 MCP

YokeOS **主推**的接入方式。业务方不写代码，只写一个 Agent 目录描述要做的事，LLM 自己理解任务、自己组合调用 MCP 工具。

定义一个 Agent = 写一个目录 `.yokeos/agents/<name>/`（详见第 11 章）：`AGENT.md` = frontmatter（这个 Agent 自己的运行配置）加任务说明正文，外加可选附属资源（`scripts/` 脚本、`REFERENCE.md` 参考）。一个目录就是一个自足的 Agent，只调用底座的系统基础能力。

可复用的能力有两个来源：**公共 Skill**（`.yokeos/skills/<name>/` 下的 SKILL.md，frontmatter 按名引用、正文注入 system prompt，见 11.1）约束"怎么做好这件事"；**MCP server**（`mcp_servers.yaml` 配置）提供"调外部系统"的工具。LLM 读到注入的上下文后自己理解任务、自己决定调哪个工具、自己组合完成——YokeOS 不解析任务步骤、不做工作流引擎，所有逻辑交给 LLM。

> 注意 `AGENT.md` 的解析由 `AgentLoader` / `ContextLoader`（8.3）负责，不在 Tool 模块里——它是 prompt 的输入源、不是可执行 Tool。

### 6.4 扩展 Tool 方式二：自己写 MCP server

业务方用任何语言写 MCP server，通过 MCP 协议暴露工具，YokeOS 作为 MCP Client 连接进来。MCP server 配置在 `.yokeos/mcp_servers.yaml`，声明 `name`、`transport`、`command`、`env`。

**`McpClientService` 子模块。** MCP server 的连接维护和工具注册。YokeOS 启动时连接所有配置的 MCP server，调 `tools/list` 拿工具列表，把每个 MCP 工具包装成 `YokeTool` 注册到 `ToolRegistry`，处理 server 失联、超时、错误恢复。

**`McpToolAdapter` 子模块。** 把 MCP Tool 适配成 `YokeTool` 接口。Tool 调用时通过 MCP 协议（JSON-RPC over stdio 或 SSE）转发给对应 MCP server 执行，结果包装成 `ToolResult` 返回。

### 6.5 扩展 Tool 方式三：写 Java Spring Bean

用 Spring AI `@Tool` 注解标注 Java 方法，YokeOS 启动时自动扫描注册。工程量最大但集成深度最好，适合需要直接调用企业内部 Java 服务、复用现有 Spring Bean、跟 Spring Security 集成做权限控制的场景。写法跟 YokeOS 内置 Tool 完全一样，直接在进程内调用 Java 方法，不走 MCP 协议、不起独立进程、不序列化，性能最好。

### 6.6 ToolRegistry

统一管理所有 Tool。启动时通过 Spring 容器扫描所有 `@Tool` 注解的方法（内置 Tool 和方式三的扩展 Tool），加上 MCP Client 注册的工具（方式二），全部包装成 `YokeTool` 实例。Profile 启动 Agent 时按 `tools` 字段从 Registry 过滤出该 Profile 可用的 Tool 子集。

### 6.7 Sandbox 检查

Sandbox 遵循"接口先行"原则（决策七）：先定一个不携带任何实现细节的抽象接口，第一阶段只在接口后面挂一档实现，未来加重隔离方案时只新增实现类，不改接口、不改调用方。

**`Sandbox` 接口。** 只有一个方法，表达"在受控环境里执行一个动作"这个意图：

```text
Sandbox.enforce(SandboxAction action)

SandboxAction  = { type: ActionType, target: String }
ActionType     = FILE_READ | FILE_WRITE | SHELL_COMMAND | HTTP_REQUEST
```

> ActionType 取四值（文件读 / 文件写 / Shell 命令 / HTTP 请求）——文件读写分开便于未来按读/写分权限；`WhitelistSandbox` 的 `enforce` 把 `FILE_READ`、`FILE_WRITE` 两 case 同路由到 `checkFilePath`。

接口签名里不出现"白名单""容器镜像""VM 配置"这类某一档实现特有的词——用最重的 microVM 实现去反向套这个签名，也应该能干净套入，这是校验接口是否中立的办法。

**`WhitelistSandbox`（第一阶段唯一实现）。** 配置在 `application.yaml`（`file.allowed_paths`、`shell.allowed_commands`、`http.allowed_domains`），内部按 `ActionType` 路由到三个私有校验方法：

- `checkFilePath`（路径标准化后比对白名单，需处理 `../` 路径穿越；目标已存在时用真实路径校验仍位于白名单根内，新建路径校验最近存在父目录）
- `checkShellCommand`（精确比对可执行文件白名单）
- `checkHttpUrl`（解析 host 后做通配符匹配）

任意校验失败抛 `SandboxViolationException`，Tool 执行终止；异常信息直接复用 `ToolExecutor` 已有的失败审计路径写入 `tool_invocations`（`success=false`、`error_message`），不需要为 Sandbox 单独新增审计逻辑。

`FileTools`、`ShellTools`、`HttpTools` 在各自 `execute` 方法开头调用 `sandbox.enforce(...)`，校验通过才执行真正的 IO。

**扩展阶段按信号驱动升级，接口不变，只新增实现类：**

| 阶段 | 实现 | 升级信号 |
|------|------|------|
| 第一阶段 | `WhitelistSandbox`（应用层 Path/Pattern 白名单） | — |
| 扩展阶段一 | 容器隔离（namespace + cgroups + seccomp） | 要跑相对不可信代码，或要做多租户 |
| 扩展阶段二 | microVM（Firecracker / Kata / gVisor） | 要跑完全不可信代码，或要规模化多租户 |

> **要点一：** 应用层白名单是"劝阻级"防线，防的是模型犯傻误操作，防不住蓄意绕过，第一阶段不建议用它跑完全不可信的代码或对外做多租户。
>
> **要点二：** Sandbox 加白名单是第一阶段唯一的 Tool 治理手段，而 Profile 级的 Tool Policy（哪个 Agent 能用哪些 Tool）放在扩展阶段。第一阶段 Profile 的 `tools` 字段已经能限定 Agent 可用 Tool 子集，算是 Tool 治理的雏形，完整的 allow/deny 策略扩展阶段补。
>
> **要点三（解释器的信任边界）：** 把 Python、Bash 等解释器列入 `shell.allowed_commands`，等于授予模型 YokeOS 进程所属操作系统用户的代码执行权限——argv 直传只阻止 Shell 语法拼接，不会隔离解释器自身的文件与网络行为，Agent 附属脚本经解释器发起的网络请求也不经过 `http_get` 的域名白名单。所以**装一个带脚本的 Agent = 信任这个 Agent 的作者**，这条对使用者要诚实；对不可信或多租户代码，应使用扩展阶段基于容器/microVM 的 `Sandbox` 新实现。
>
> **要点四（白名单管理）：** 第一阶段白名单是配置文件形态（`application.yaml`），改白名单走配置变更。白名单的管理端点（查看/增删改）放扩展阶段（见 7.3 的显式偏差说明）。

### 6.8 通知推送（`NotifyTools`）

一句话：**入站有 Channel Adapter 负责"消息怎么进来"，出站现在缺一个对称的东西负责"消息怎么出去"——`NotifyTools` 补的就是这一块。**

在两个验收 Demo 出现之前，YokeOS 没有真正需要"主动往外推一条消息"这件事——CLI 和 Web Service 都是别人发消息进来、Agent 回一句话，回复方式是同步返回，不需要额外的推送机制。但"每日天气""每日科技日报"这类定时触发的场景不一样：`AgentScheduler` 到点自动跑，没有人在等着看响应，Agent 必须**主动**把结果送到人能看到的地方（企业 IM 群），这就需要一个"往外推"的能力。

**如果没有这个模块会怎样：** 每个业务方定义 Agent 时都要自己在正文里写"调 `http_post` 打这个 webhook URL"，或者自己去找一个企业微信/飞书的 MCP server 配上——每个 Agent 各写一份，重复且不统一，跟前面 Sandbox、Memory 强调的"接口先行"原则相反。

**`NotifyChannelAdapter` 接口。** 跟 6.7 Sandbox 同样的思路：先定一个不携带具体渠道细节的抽象接口，表达"把一条内容送到某个通知目标"这个意图：

```text
NotifyChannelAdapter.send(NotifyTarget target, String content)

NotifyTarget = { channelType: String, config: Map<String, String> }
```

**`WebhookNotifyAdapter`（第一阶段唯一实现）。** 用通用 HTTP webhook 承接所有场景——企业微信、飞书、钉钉、Slack 的群机器人都提供 webhook 地址，第一阶段不用逐家接它们的专用 API（签名算法、AccessToken 刷新这些认证细节第一阶段不做），直接把 `content` 包成对方 webhook 约定的 JSON 格式发一次 POST。发送前一样要过 `Sandbox.enforce(new SandboxAction(HTTP_REQUEST, url))` 域名白名单校验，跟 `http_post` 共享同一份 `http.allowed_domains` 配置，不新增 Sandbox 逻辑。

**`NotifyTools`（内置 Tool，归 `yokeos-tool`）：**

```text
@Tool notify(content: String, channel: String = 默认渠道)
```

`channel` 参数对应 Agent frontmatter 的 `notify.channels` 字段（声明这个 Agent 能推送到哪些目标，每项含 `name`、`type: webhook`、`config`，见 8.2）；LLM 大多数时候只需要传 `content`，不需要知道具体 webhook 地址——地址是运行时配置，不是对话里的信息，这跟 Sandbox 域名白名单"配置在配置文件、不暴露在接口签名里"是同一个设计考虑。`NotifyTools` 从 `ProfileContext` 取当前 Agent 的通知渠道解析适配器和 URL。

**跟已有机制的关系：**

- **审计：** `notify` 跟其他 Tool 一样走 `ToolExecutor` 现有的成功/失败审计路径，写入 `tool_invocations`，不新增审计逻辑。
- **Sandbox：** 复用已有的 `Sandbox.enforce(HTTP_REQUEST, ...)`，不新增沙箱概念。
- **跟入站 Channel 的对称关系，但不是同一个东西：** `ChannelAdapter`（8.4）解决"什么触发 Agent 开始跑"，`NotifyChannelAdapter` 解决"Agent 跑完把结果送到哪"——语义方向相反，所以分开建模，不合并成一个抽象；同一个企业微信群，可能同时是某个 Agent 的入站 Channel、又是另一个 Agent 的出站通知目标。
- **和扩展 Tool 方式二（MCP）的边界：** 如果业务方需要企业微信官方富文本卡片消息这种更复杂的格式，`notify` 简单场景之外仍然可以走 MCP 方式二自己接一个专用 MCP server，两条路并存，`notify` 只是把"最常见的纯文本/webhook 推送"这个重复劳动统一掉，不是要吃掉 MCP 方式二的场景。

---

## 7. 核心能力六：对外服务（Web Service）

Web Service 是 YokeOS 的对外完整门面，业务系统通过 REST API 接入。前面各能力是 YokeOS 的内部能力，Web Service 是对外暴露。没有 Web Service，YokeOS 只是一个 CLI 工具，无法跟企业现有业务系统集成。这也是 YokeOS 区别于偏个人定位的 OpenClaw、Hermes 的关键能力。

### 7.1 模块组成

**`WebServer` 模块。** 启动 Spring MVC 服务器，`yokeos serve` 命令触发，默认端口 `8080`，开启 Java 21 virtual thread。

**`ApiController` 集合。** 按资源分七个 Controller：`SessionApiController`（会话管理）、`AgentApiController`（Agent 调用与动态管理）、`WorkspaceApiController`（工作区只读浏览）、`ProfileApiController`（Profile 查询）、`MemoryApiController`（Memory 查询）、`ToolApiController`（Tool 信息）、`SystemApiController`（系统状态）。每个 Controller 只做参数校验、响应包装、错误处理，实际逻辑委托给核心层的服务。

**`GlobalExceptionHandler` 模块。** 统一异常处理，把异常转成标准 JSON 响应信封 `ApiResponse`（`code`、`message`、`data`、`timestamp`；成功与错误共用一个信封）。管理台与 REST 共用这一套信封与错误提示。

**OpenAPI 文档模块。** 通过 `springdoc-openapi` 自动生成 OpenAPI 3.0 文档，暴露在 `/swagger-ui`。

**Web 管理台第一版。** 与 REST 同端口、同进程托管的 Vue3 单页应用，Vite 构建、经 frontend-maven-plugin 在 Maven 构建期编译、产物托管在 `yokeos-web` 的静态资源目录（`/admin/` 路径，SPA 内部路径刷新回落到入口页，不影响 REST 路由）。第一版页面：**只读观察五页**（会话、Agent（Profile）、Tool、长期记忆、系统状态含各 Provider 连通情况，数据全部来自只读端点，界面不设写入口）、**Agent 管理页**（列表 + 查看/编辑/删除 + 「一句话新建 → 预览可改 → 创建」流程）、**工作区页**（目录树 + 只读浏览文件）。管理台只调用 7.2 的同一组 REST 端点，不带独立后端逻辑。

### 7.2 第一阶段 18 个端点

统一前缀 `/api/v1`，按五组组织（与需求文档 5.10 一致）：

**会话管理（4 个）：**

1. `POST /api/v1/sessions`（创建）
2. `POST /api/v1/sessions/{id}/messages`（发消息，触发 ReAct 循环）
3. `GET /api/v1/sessions/{id}`（查历史）
4. `DELETE /api/v1/sessions/{id}`（归档）

**Agent 调用与动态管理（7 个）：**

1. `POST /api/v1/agents/generate`（一句话生成定义草稿，不落盘、不注册，人在环预览）
2. `POST /api/v1/agents`（创建 Agent，落盘 + 注册，免重启）
3. `GET /api/v1/agents`（列出全部 Agent）
4. `GET /api/v1/agents/{name}`（查看 Agent 定义）
5. `PUT /api/v1/agents/{name}`（更新 Agent 定义）
6. `DELETE /api/v1/agents/{name}`（删除 Agent）
7. `POST /api/v1/agents/{name}/invoke`（无状态调用）

**工作区（2 个）：**

1. `GET /api/v1/workspace/tree`（工作区目录树）
2. `GET /api/v1/workspace/file`（只读查看工作区文件）

**信息查询（3 个）：**

1. `GET /api/v1/profiles`（列运行配置）
2. `GET /api/v1/memory`（查长期记忆）
3. `GET /api/v1/tools`（列可用 Tool）

**系统状态（2 个）：**

1. `GET /api/v1/health`（健康检查）
2. `GET /api/v1/info`（运行信息 + Provider 状态）

### 7.3 扩展阶段补齐的端点

Memory 的 append/clear/search；Tool describe 和调用历史；LLM call 历史和 token 统计；**`AgentScheduler` 的调度管理端点**（查任务与状态、查执行历史、立即补跑一次、启用/停用——定义的增改仍走 `PUT /api/v1/agents/{name}`，这里补的是运行态控制）；Webhook 触发；SSE 流式响应；Prometheus metrics；OpenAPI spec 导出；**白名单管理端点**。

> **与参照实现的一处显式偏差（上游赢）：** 参照实现在其窗口内交付了定时任务管理的四个运行态端点（`/api/v1/schedules` 前缀的查询/执行历史/立即执行/启停）、管理台"定时任务"页，以及沙箱白名单管理端点。YokeOS 需求文档的核心端点清单（18 个）与管理台页面清单均未包含它们——"定时执行历史可查"由 `scheduled_tasks`/`task_executions` 落库加 Session 查询与审计表承接，白名单走配置文件形态，第一阶段的可查性与管控已经够用。按"上游赢、理由写进文档"的纪律，这两组端点与对应管理页列入扩展阶段，不悄悄加进第一阶段范围。

### 7.4 关键设计点

- **错误码规范：** 标准 HTTP 状态码加内部错误码（400 参数错误、404 资源不存在、500 内部错误、503 Provider 故障）。
- **CORS：** 第一阶段开放所有源方便调试，扩展阶段加白名单。
- **请求大小限制：** 单条消息最大 32KB，Session 历史返回最多最近 100 条。
- **超时：** Agent 调用最长 60 秒超时返回 504。

### 7.5 第一阶段不做的部分

- 认证机制（无认证假设内网，扩展补 Web 认证：API Key 加 JWT）
- 流式响应 SSE
- WebSocket
- RBAC 权限
- 限流

这些放扩展阶段。

### 7.6 业务系统集成场景

- **同步调用**（最常用，业务系统调 invoke 等返回，适合 stateless 短任务）
- **会话保持**（先创建 Session 再多次发消息，适合连续对话）
- **Webhook 触发**（告警系统、CI/CD、定时任务调 Agent，打通监控的感知到分析到行动闭环；端点放扩展阶段）
- **跨语言集成**（任何能发 HTTP 请求的语言都能接，第一阶段不出 SDK，扩展阶段才出）

---

## 8. 支撑模块

六个核心能力之外，YokeOS 还有几个支撑模块让整个系统跑起来。这些不是运行时内核的核心能力，但缺一不可。

### 8.1 工作区初始化

**`InitCommand` 模块。** `yokeos init` 命令的执行逻辑，创建 `.yokeos/` 工作目录及完整结构：

```
.yokeos/
├── agents/            # 每个子目录 = 一个 Agent（AGENT.md + 可选附属资源）
├── skills/            # 公共 Skill 库（每个子目录一个 SKILL.md + 可选附属资源）
├── output/            # Agent 产出物
├── memory/
│   └── MEMORY.md      # 长期记忆
├── sessions/          # 会话导出与备用位（会话真相源在 SQLite，见 9.2）
├── logs/              # 结构化日志
├── AGENTS.md          # Bootstrap：项目级 agent 行为说明
├── SOUL.md            # Bootstrap：默认 agent 人格定义
├── USER.md            # Bootstrap：用户偏好（只读，agent 不写）
├── mcp_servers.yaml   # MCP 配置
└── yokeos.db          # SQLite
```

创建目录、写默认模板。`yokeos init` 幂等：已存在的目录和文件一律不覆盖。

### 8.2 Agent 定义与 Profile

**`AgentLoader` 模块。** 扫 `.yokeos/agents/` 各子目录，`deriveProfile` 把每个 `AGENT.md` 的 frontmatter 派生成一个 `Profile`，注册到 `ProfileRegistry`。启动时做合法性校验：Provider 是否存在、Tool 是否注册、Channel 是否支持、Bootstrap 文件是否存在、引用的 Skill 是否在公共库中。校验失败的 Agent 不阻断启动但记录错误日志。

**`ProfileRegistry` 模块。** Agent 派生 `Profile` 的内存索引，按 name 提供快速查找。Channel 接收消息时通过它拿到具体 Profile。派生自 `AGENT.md` frontmatter 的字段（与需求文档 5.2 一致）：`name`、`description`、`identity`（`agent_name`、`prompt`）、`provider`（`name`、`model`、`temperature`）、`tools`、`skills`（按名引用公共 Skill 库）、`mcp_servers`、`channels`、`notify.channels`（每项 `name`/`type`/`config`，供 `NotifyTools` 用，见 6.8）、`schedules`、`bootstrap`、`settings`（`max_iterations`、`max_history_turns`）。第一阶段支持多个 Agent 并存，同一实例上同时可用，这是"OS"在第一阶段的最小体现。

### 8.3 上下文加载（Bootstrap + AGENT.md 正文 + Skill 正文）

**`ContextLoader` 模块。** 每次组装 prompt 时重新加载、不做缓存，用户修改后立即生效。加载三类内容，按固定顺序拼接进 system prompt：

1. **Bootstrap 文件**（按 Profile 的 `bootstrap` 字段读取）：注入顺序固定为 **`AGENTS.md`（项目级行为说明）→ `SOUL.md`（Agent 人格）→ `USER.md`（用户偏好）**，每段前带角色 header（如 `## 项目约定（AGENTS.md）`），无覆盖语义——后注入的不改写先注入的，冲突消解交给模型按 header 语义自行判断。frontmatter 的 `bootstrap` 列表只能裁剪条目（如某 Agent 不需要 `USER.md`），不能改变相对顺序；文件缺失时跳过并记录告警，不阻断启动。
2. **引用 Skill 正文**：`skills` 字段按名引用的公共 Skill，`ContextLoader` 从 `.yokeos/skills/<名>/` 读取 SKILL.md 正文整段注入。引用了不存在的 Skill 记入 8.2 的启动校验告警。
3. **`AGENT.md` 正文**（这个 Agent 的任务指令）。

把 `AGENT.md` 归到上下文这一层而不是 Tool 模块，是因为它是 prompt 的输入、不是可执行的 Tool。

### 8.4 Channel 接入

Channel 是 Agent 对外的消息接入入口，主要解决"消息进来、响应出去"。HTTP 接入归 Web Service，不在 Channel 范畴内。

**`CliChannel` 模块。** `yokeos chat` 命令的实现，读 stdin 写 stdout 实现交互式对话，维护当前 Session，每次输入调 `AgentService.process`，支持多轮对话、查看上下文、查看 Tool 调用记录。扩展阶段补企业微信、飞书、钉钉、Slack 等 IM Channel，每个通过 Channel Adapter 插件机制扩展，所有 IM Channel 底层都调 Web Service 的 Agent 接口，不重复实现 Agent 逻辑。Channel 模块边界第一阶段从简（单一 CliChannel 直连 `AgentService`），Channel 降维重设计为 SPI 列为扩展阶段候选。

### 8.5 定时任务（第三种触发源）

定时任务不是新增的核心能力，而是给 `AgentService` 加第三条触发路径。CLI 和 Web Service 都是"人推"——需要有人发起一次调用；`AgentScheduler` 是"钟推"——按 cron 表达式到点自动生成一条消息，调用链路跟人推完全一样，`ReActLoop` 不感知消息从哪个入口来。

**`AgentScheduler` 模块（归 `yokeos-core`）。** 基于 Spring 的 `ThreadPoolTaskScheduler` 加 `CronTrigger` 动态注册任务，不用静态的 `@Scheduled` 注解，因为触发规则要按 Agent 定义动态生成，编译期写死的注解做不到。frontmatter 的 `schedules` 字段声明 cron 表达式、时区、要发给 Agent 的消息内容；YokeOS 启动时扫描所有 Profile 的 `schedules` 字段逐个注册。

**并发控制。** 每个定时任务用一把进程内的 `ReentrantLock`（按任务 id 维度）防止同一任务重叠执行——上一次还没跑完，下一次触发点到了就跳过，不排队、不并行跑两份。第一阶段是单实例部署，这把锁只解决"同一进程内不重叠"，**不是**分布式锁，多实例部署下的分布式协调放扩展阶段。

**失败处理。** 定时任务执行失败只记日志，不能让调度器本身崩溃、影响后续任务触发；失败的这次调用依然走 `AgentService.process` 内部完整的 `llm_calls`/`tool_invocations` 审计路径，跟人推触发的失败没有区别。

**会话身份。** 钟推也要落 Session：`session_id` 沿用既有公式（channel + user + agent 联合生成，见 9.2），channel 和 user 都固定为 `scheduler`。即同一个 Agent 的历次定时触发复用同一个 Session，对话历史自然累积、靠 `max_history_turns` 截断兜底——不为钟推新设任何概念。

**状态持久化。** 光"到点自动跑"还不够——"到点没跑、跑了什么、结果如何"要有据可查（需求文档 5.8）。为此把任务状态和执行历史落 SQLite，重启不丢：

- **两张表**（手工建表脚本，见 9.2）：`scheduled_tasks` 存任务登记信息与运行状态（`task_id` 主键、`profile_name`、`cron`、`zone`、`message`、`enabled`、`next_run_at`、`last_run_at`、`last_status`、`run_count`、`updated_at`），`task_executions` 存每次执行的历史，成功失败都记（`task_id`、`session_id`、`started_at`、`success`、`error_message`、`duration_ms`）。定义来源仍是 frontmatter 的 `schedules`——这两张表只存"状态 + 历史"，不作为定义源，重启时从文件重新协调。
- **契约在 core、实现在 storage**（依赖倒置）：`ScheduledTaskStore` 接口（`reconcile`/`recordExecution`/`isEnabled`/`setEnabled`/`list`/`executions`）放 `yokeos-core`，`AgentScheduler` 依赖它；JPA 实现 `JpaScheduledTaskStore` 放 `yokeos-storage`。`AgentScheduler` 启动扫描时顺带 `reconcile` 登记，每次执行成功失败都 `recordExecution` 留痕（与审计两表同源）。

**第一阶段的边界。** `schedules` **定义**写在 `AGENT.md` frontmatter 里，运行期经 `PUT /api/v1/agents/{name}` 或直接改目录（丢目录即上线，见 11.2/11.3）变更——定义变更免重启（`AgentScheduler` 的运行时注册/注销方法在第一阶段就立好，`schedules` 变更先注销旧句柄再注册新任务）；**运行态控制**（查看任务列表与状态、执行历史查询、立即补跑、临时启停）的 REST 端点与管理台页面放扩展阶段（见 7.3 显式偏差说明），第一阶段"可查"由两张表落库加 `GET /api/v1/sessions/{id}`（钟推 Session）与审计表承接。

### 8.6 三种运行模式

| 命令 | 模式 | 说明 |
|------|------|------|
| `yokeos chat` | 交互对话 | 本地 CLI 交互 |
| `yokeos serve` | Web Service | 启动 REST API 服务与管理台（定时任务随 `serve`/`gateway` 一起常驻调度） |
| `yokeos gateway` | 守护进程 | 同时挂多个 Channel |

三种模式共享同一份 Agent 配置和 Session 存储，差异只是接入层。

### 8.7 命令行工具

**`YokeOsCli` 模块。** Picocli 命令行入口，整个 YokeOS 的 `main` 函数，注册 12 个子命令（与需求文档 5.13 一致）：

```
init
status
chat [--profile <name>]
serve
gateway
profile list / create / show / delete
provider list
tool list
session list
```

命令组名沿用 `profile`，操作的是 `.yokeos/agents/` 下的 Agent 目录。每个子命令一个 `@Command` 类。不需要 Spring 上下文的命令（`init`、`profile list`）直接走文件操作启动快；需要 LLM 调用的命令（`chat`、`serve`、`gateway`）启动 Spring 上下文。

### 8.8 配置与密钥加载

**`ConfigLoader` 模块** 负责统一加载 LLM API key、Provider 凭证、MCP server 凭证、一句话生成的系统默认 Provider（3.3）等敏感与全局配置。第一阶段做基础版：敏感配置通过环境变量注入或独立的本地配置文件加载，不明文写死在 `AGENT.md` frontmatter 里（配置里用 `${ENV_VAR}` 占位，加载时从环境变量解析）；配置加载时做必填项和格式的基础校验，缺失或非法时给清晰报错，不静默失败。完整的加密存储、密钥轮转、对接企业 KMS/Vault 放扩展阶段。单列这个模块，是因为对企业级底座，配置和密钥的加载校验是 day one 该有的，不能散落各模块无人负责。

---

## 9. 数据持久化

### 9.1 持久化选型说明

第一阶段选 **SQLite** 加 **Spring Data JPA** 做关系型持久化（决策八），**`MEMORY.md`** 文件加关键词检索做长期记忆（可插拔三档，见 5.1）。

**为什么第一阶段不用向量数据库：** LanceDB 在向量加全文检索上做得好，是 Memory 自然的升级方向，但它的 Java 本地嵌入式支持还在开发中，当前 Java SDK 只支持远程的 Cloud 或 Enterprise，不符合 YokeOS 单二进制部署的定位。其他向量库（Qdrant、Chroma、Milvus）都需要外部进程，pgvector 要外部 PostgreSQL。JVector 这种纯 Java 嵌入式向量库是另一条路但成熟度待验证。

第一阶段的判断是先用 SQLite 加 `MEMORY.md` 跑通最短链路，把运行时内核的机制做扎实，语义检索这种检索体验优化放扩展阶段。

**扩展阶段升级路径：**

- **方案 A：** 等 LanceDB Java 本地嵌入式 GA 后切换，保持单二进制
- **方案 B：** 接 PostgreSQL pgvector，企业部署多起一个 PG 服务，社区最成熟
- **方案 C：** 用 JVector 纯 Java 嵌入式向量索引跟 SQLite 双写，保持单二进制

具体选哪个扩展阶段决议。第一阶段 `LongTermMemoryStore` 接口已预留升级空间（`recallByKeyword` 可升级为带 `mode` 的 `recall`；语义检索属于产品定位路线图阶段二"知识库与语义记忆"的承诺范围），切换底层不影响上层 Tool。

### 9.2 SQLite 关系型数据

通过 Spring Data JPA 集成，`application.yaml` 配置数据源指向 `.yokeos/yokeos.db`。

> **工程风险提示：** SQLite 本身 `ALTER TABLE` 能力有限，`hibernate.ddl-auto=update` 在 SQLite 上对表结构演进的支持很弱。第一阶段首次建表用 `update` 可以，但表结构后续演进时不要依赖 `update` 自动迁移，需要手动维护建表脚本或引入 Flyway/Liquibase。

核心表六张：

1. **`sessions`**：Session 元数据加 JSON 序列化的对话历史
2. **`tool_invocations`**：每次 Tool 调用记录（审计，day one 写入）
3. **`llm_calls`**：每次 LLM 调用记录（审计，day one 写入）
4. **`scheduled_tasks`**：定时任务登记信息与运行状态（见 8.5）
5. **`task_executions`**：定时任务每次执行的历史，成功失败都记（见 8.5）
6. **`memory_entries`**：长期记忆条目（仅 `SqliteMemoryStore` 档使用，手工建表脚本）

> **相对原方案的调整：** `tool_invocations` 和 `llm_calls` 在第一阶段就做写入（不一定做查询接口），因为"可审计"是 YokeOS 的差异化卖点之一，审计数据的地基应该 day one 就立起来，纯靠日志后期要做审计还得反解析返工。查询接口和审计报表放扩展阶段，但写入第一阶段就有。

**`sessions` 实体字段：**

| 字段 | 说明 |
|------|------|
| `session_id` | 主键，channel 加 user 加 agent 联合生成 |
| `agent_name` | 关联 Agent |
| `channel` | 接入 Channel |
| `user_id` | 用户标识 |
| `messages_json` | JSON 序列化的对话历史 |
| `status` | `active` / `archived` |
| `created_at` | 创建时间 |
| `last_active_at` | 最后活跃时间 |
| `archived_at` | 归档时间 |

**`tool_invocations` 实体字段：**

| 字段 | 说明 |
|------|------|
| `id` | 主键，自增 |
| `session_id` | 关联 Session |
| `tool_name` | Tool 名称 |
| `input_json` | 调用参数（JSON） |
| `result_json` | 执行结果（JSON） |
| `success` | 是否成功 |
| `error_message` | 错误信息（可空） |
| `duration_ms` | 执行耗时（毫秒） |
| `created_at` | 调用时间 |

**`llm_calls` 实体字段：**

| 字段 | 说明 |
|------|------|
| `id` | 主键，自增 |
| `session_id` | 关联 Session |
| `provider` | Provider 名称 |
| `model` | 模型名 |
| `prompt_tokens` | 输入 token 数 |
| `completion_tokens` | 输出 token 数 |
| `total_tokens` | 总 token 数 |
| `duration_ms` | 调用耗时（毫秒） |
| `created_at` | 调用时间 |

**`scheduled_tasks` 实体字段（见 8.5）：**

| 字段 | 说明 |
|------|------|
| `task_id` | 主键，schedule 的 id（frontmatter `schedules` 里声明） |
| `profile_name` | 归属 Profile |
| `cron` | cron 表达式 |
| `zone` | 时区 |
| `message` | 到点发给 Agent 的消息 |
| `enabled` | 是否启用（默认启用） |
| `next_run_at` | 下次触发时刻 |
| `last_run_at` | 上次触发时刻 |
| `last_status` | 上次结果 `success` / `failed` |
| `run_count` | 累计触发次数 |
| `updated_at` | 状态更新时间 |

**`task_executions` 实体字段（见 8.5）：**

| 字段 | 说明 |
|------|------|
| `id` | 主键，自增 |
| `task_id` | 关联 `scheduled_tasks` |
| `session_id` | 本次触发所用的钟推 Session |
| `started_at` | 开始时间 |
| `success` | 是否成功 |
| `error_message` | 失败信息（可空） |
| `duration_ms` | 执行耗时 |

**`memory_entries` 实体字段（仅 `SqliteMemoryStore` 档）：**

| 字段 | 说明 |
|------|------|
| `id` | 主键，自增 |
| `scope` | `CORE` / `ARCHIVAL`（核心/归档分区语义，见 5.1） |
| `content` | 记忆内容 |
| `created_at` | 写入时间 |

### 9.3 文件系统数据

`.yokeos/` 里几类数据放文件系统不放 SQLite：Agent 目录（`AGENT.md` + 附属资源）、公共 Skill 库、Bootstrap 文件、Memory 默认档（`MEMORY.md`）、MCP 配置、日志。文件系统的优势是用户可以直接编辑、git 跟踪、备份。Agent 目录、Skill 和 Bootstrap 这种用户主动维护的数据放文件系统比放数据库友好。

---

## 10. 项目工程结构

YokeOS 是 Maven 多模块项目，由 9 个模块组成（模块边界与参照实现一致，groupId 为 `com.yokeos`）：

| 模块名 | 职责 |
|--------|------|
| `yokeos-core` | 核心抽象和接口：`YokeTool` 接口、`Session`、`Profile`、`ContextLoader`、`AgentLoader`（扫 `.yokeos/agents/`、`deriveProfile`）、`ReActLoop`、`PromptBuilder`、`ToolExecutor`、`AgentService`、`AgentScheduler`（定时触发）、`AgentLifecycleService`（编排"定义一个 Agent"：目录落盘 + 派生 Profile + 注册 + Scheduler 注册）、`ScheduledTaskStore` 接口（实现见 `yokeos-storage`） |
| `yokeos-provider` | 核心能力一：`ProviderService`、Function Calling 适配、Provider 配置（provider name 到 `ChatModel` 显式映射） |
| `yokeos-memory` | 核心能力三：`MemoryService` 统一门面、`LongTermMemoryStore` 三档后端、`MemoryTools`（`save_memory` / `recall_memory`） |
| `yokeos-tool` | 核心能力四：内置 Tool（`FileTools`、`ShellTools`、`HttpTools`、`NotifyTools`）、`McpClientService`、`McpToolAdapter`、`ToolRegistry`、`Sandbox` 接口 + `WhitelistSandbox` 实现、`NotifyChannelAdapter` 接口 + `WebhookNotifyAdapter` 实现（三合一模块） |
| `yokeos-channel-cli` | CLI Channel：`CliChannel`、`yokeos chat` 命令实现 |
| `yokeos-web` | 核心能力六：`WebServer`、7 个 `ApiController`、`GlobalExceptionHandler`、OpenAPI 文档、Web 管理台静态资源托管 |
| `yokeos-storage` | 持久化层：SQLite、`SessionRepository`、`ToolInvocationRepository`、`LlmCallRepository`、`JpaScheduledTaskStore` |
| `yokeos-cli` | 命令行入口：Picocli 主入口、12 个子命令、`ConfigLoader` |
| `yokeos-boot` | Spring Boot 启动模块：主类、自动配置、依赖聚合 |

模块之间通过接口解耦（跨模块契约放 `yokeos-core`，下游模块实现，禁止循环依赖）。扩展阶段加新 Channel 或新 Tool 实现只加新模块不改 core，所有 Channel 模块底层都调 `yokeos-web` 的 Agent 接口。

仓库根另有一个 **`my-agent/` 示例工作区**（非 Maven 模块）：一份可直接复制使用的 `.yokeos/` 工作区样例（三份 Bootstrap 文件加示例 Agent 目录），供新用户 `cp -r my-agent/.yokeos .` 快速起步，也是文档示例的实物对应物。

测试纪律：九个模块每个都有测试覆盖，命令行入口模块不例外——入口逻辑（参数解析、幂等初始化、错误提示）是用户第一次接触 YokeOS 的地方，也是回归最频繁的地方。

打包：

```bash
mvn clean package
```

生成 fat JAR，`java -jar` 启动，扩展阶段通过 **GraalVM Native Image** 编译成原生二进制（引入时机见 13.2）。

---

# 第二部分：定义一个 Agent（业务能力）

## 11. 定义一个 Agent：一个目录 + Web Service

前面十章是**底座**——让任意 Agent 都能可靠运行的引擎、能力、支撑设施，本身不是某个具体的业务 Agent。这一章讲底座之上怎么真正"定义出一个业务 Agent"，以及这个动作通过哪个入口对外暴露。形态**借鉴 Anthropic Agent Skills**（目录 + 渐进式披露的开放形态，兼容 agentskills.io 的 SKILL.md 格式），但在 YokeOS 里，这样一个目录定义的是一个 **Agent**。

### 11.1 术语：一个目录 = 一个 Agent

**底座 / Agent 两层，分清楚。** 这是这一章最关键的一条：

- **底座 = 系统基础能力**：Provider、ReAct、内置 Tool（`read_file`/`shell`/`http_get`/`notify`/`save_memory`…）、Memory、Sandbox、定时、Web（第 1~10 章）。所有 Agent 共享。
- **Agent = 一个目录** `.yokeos/agents/<name>/`：`AGENT.md`（frontmatter = 这个 Agent 自己的运行配置；正文 = 任务指令）+ 可选附属资源（`scripts/` 脚本、`REFERENCE.md` 参考）。一个自足的业务 Agent，**自带一切、不另写配置文件**。

**派生 Profile**：底座（第 1~10 章的一切）都吃 `Profile`，所以 `AgentLoader.deriveProfile(agentDir)` 把 `AGENT.md` 的 frontmatter 映射成一个 `Profile`，让 Agent 目录**零改动复用整台底座**。

**公共 Skill 库**：可复用的能力实体存 `.yokeos/skills/<name>/`（每个子目录一个 SKILL.md，兼容 agentskills.io 开放标准），由 YokeOS 提供 CRUD 管理。Agent 在 `AGENT.md` frontmatter 用 `skills: [名]` 按名引用，`ContextLoader` 组装 system prompt 时把引用到的 Skill 正文**注入**来强约束产出。边界清晰：Skill 不是可执行 Tool、不进 `ToolRegistry`，加载/注入归 `yokeos-core` 的 `ContextLoader`。Skill 正文整段注入是第一阶段的形态；按需加载的渐进式披露（元数据先注入、正文按需取）放扩展阶段。

**附属资源按需取用**：Agent 目录里的 `scripts/`、`REFERENCE.md` 不预载，按正文指引**用底座既有能力按需取**——读参考用 `read_file`；脚本经 `shell` 调用管理员显式白名单内的解释器运行（信任边界见 6.7 要点三：脚本产出进上下文、脚本代码不进，装一个带脚本的 Agent = 信任这个 Agent 的作者）。没有新工具、没有能力库、没有全局索引。

> **底线**：`AGENT.md` 正文与引用 Skill 正文由 `ContextLoader` 注入 system prompt（与 Bootstrap 文件同层）；**一个 Agent 目录不是一个可执行 Tool**——它的附属资源经底座既有的 `read_file`/`shell` 取用，不新造机制。

### 11.2 第一阶段：一个目录定义一个 Agent（文件系统 + 运行时注册）

第一阶段走文件系统，一步到位：

1. 在 `.yokeos/agents/<name>/` 放一个目录：至少一份 `AGENT.md`，需要附属资源就加 `scripts/`、`REFERENCE.md`。
2. `AgentLoader` 扫 `.yokeos/agents/`，对每个目录 `deriveProfile` → `ProfileRegistry.register`；有 `schedules` 的交 `AgentScheduler`。
3. 运行时：正文与引用 Skill 正文进 system prompt，附属资源按需经 `read_file`/`shell` 取用；`ContextLoader` 每次现取、不缓存，改正文即时生效。

**运行时注册在第一阶段就立好**：`ProfileRegistry` 的 `register`/`remove`/`exists` 与 `AgentScheduler` 的 `registerProfile`/`unregisterProfile` 是运行时方法而非仅启动期方法——启动扫描和运行期新增走的是同一段注册代码。目录就位即上线、删除即下线，全程免重启（入口见 11.3）。

### 11.3 动态管理：`/api/v1/agents` + 一句话生成 + 实时监听 + 文件浏览器

业务系统 / 运营要完全通过 API 或页面管理、不摸文件系统。对外只有**一类资源——Agent**（一个目录）。`AgentLifecycleService`（归 `yokeos-core`，`AgentApiController` 调用）：

- `POST /api/v1/agents/generate`：一句话经 LLM 生成一份 **`AGENT.md` 草稿**原样返回（**不落盘、不注册**），供页面预览、修改（尤其定时时刻、工具权限这类敏感项要人过一眼）。生成动作落 `llm_calls` 审计；LLM 产出非法定义时返回明确的校验错误，不静默失败；所用的系统默认 Provider 走独立配置键（见 3.3）
- `POST /api/v1/agents`：写 Agent 目录（校验 + `AGENT.md`[+ 附属资源] 落盘）→ `deriveProfile` → 注册
- `GET /api/v1/agents` / `GET /{name}`：查询已定义的 Agent
- `PUT /api/v1/agents/{name}`：更新正文、provider、`notify.channels`（覆写即时生效）和/或 `schedules`（变则先注销旧定时句柄再注册新的）
- `DELETE /api/v1/agents/{name}`：注销定时 → 移出索引 → **整个 Agent 目录**归档 `.yokeos/archive/`（不物理删）
- `POST /api/v1/agents/{name}/invoke`：无状态调用

**一个目录、两条录入路径 + 实时监听。** `.yokeos/agents/` 是**唯一真相源**，填充它两条路殊途同归：API 创建（校验 + 写 Agent 目录 + 注册）、手工丢目录（scp/git/编辑器）。`WorkspaceWatcher`（装配层一个守护线程，用 JDK `WatchService`；启动全量扫 + 之后实时监听 `.yokeos/agents/` 变更）是**统一注册入口**：任何 Agent 目录新增/改/删都调 `AgentLifecycleService.register(agentDir)`（与 API 创建写完目录后调的是**同一个方法**）或注销。于是"上传即上线 = 丢目录即上线、全程免重启"。此外 `WorkspaceApiController` 提供**只读**的工作区文件浏览（`GET /workspace/tree` 列目录树、`GET /workspace/file?path=` 读文件内容，**必做防目录穿越**：`normalize()` 后 `startsWith(root)` 校验），供管理台"工作区"页钻进一个 Agent 目录看它的 `AGENT.md`/附属资源。

### 11.4 为什么这几件事要打包在一起交付

单独做任何一件都拼不出"说一句话 / 调一次 API / 丢一个目录就上线一个会自动定时运行的新 Agent"这个闭环：少了目录写盘，派生不了 Profile；少了运行时注册，新 Agent 要等重启；少了定时注册，"会自己跑"是空话；少了一句话生成，运营还得手写 frontmatter。所以放在同一批一起交付，不拆开先做一半。

### 11.5 两个例子

下一章（12.1、12.2）的两个 Demo 各演一种 Agent 定义丰富度：每日天气 Agent 是光杆 `AGENT.md`；每日科技日报 Agent 在 frontmatter 按名引用公共组稿 Skill、配 MCP。

---

# 第三部分：整合与验证

## 12. 关键流程

早期设计曾按"一个 Demo 验证一个能力"拆过五个流程，但真实场景里能力从来不是分开跑的——一个能打动人的 Agent，一定是多个能力叠在一起、自己到点跑起来的。最终收敛成两个**每日自动运行**的端到端流程（与需求文档 13 章验收一致），横向串起全部六个核心能力加定时任务这个第三触发源。两个流程本身就是第 11 章"定义一个 Agent"的具体产出：底座和 Agent 定义两部分在这里合到一起跑通。

### 12.1 Demo 一：每日天气（光杆 AGENT.md）

**场景：** 每天早上 8 点，Agent 自动查天气、生成穿搭建议，经 Webhook 推送到企业 IM 群机器人，不需要人工发起。

1. `AgentScheduler` 按 `AGENT.md` frontmatter 里 `schedules` 声明的 cron 表达式到点触发，生成一条消息，调 `AgentService.process`——跟 `CliChannel`/`ApiController` 调用的是同一个方法，`ReActLoop` 不感知这次触发是"钟推"
2. `ReActLoop` 第一轮，`PromptBuilder` 通过 `ContextLoader` 组装 system prompt（`AGENT.md` 正文即这个 Agent 的指令 + Bootstrap）
3. `ProviderService` 调 DeepSeek，返回包含 `http_get` 的 Tool 调用
4. `ToolExecutor` 执行，`HttpTools` 调用 `Sandbox.enforce(...)` 检查 URL 通过，拿到天气 JSON，并写 `tool_invocations`
5. 结果追加到 Session 进入第二轮，DeepSeek 看到天气生成穿搭建议，按 `AGENT.md` 正文指定的渠道名称调用 `notify(channel="team-im", content="...")`；`NotifyTools` 从 frontmatter 的 `notify.channels` 解析适配器和 URL
6. `ToolExecutor` 再次执行，`NotifyTools` 委托给 `WebhookNotifyAdapter`，发送前同样先过 `Sandbox.enforce(...)` 域名白名单校验，推送成功后写第二条 `tool_invocations`
7. 无更多 Tool 调用，循环结束，最终响应留在这次自动触发的 Session 里

**验收要点：** 全程不需要人工触发；两次涉外调用都过 Sandbox 白名单且都有审计记录；`GET /api/v1/sessions/{id}` 查得到完整对话；同一个 Agent 也能通过 `yokeos chat` 或 `POST /agents/{name}/invoke` 手动补跑一次，验证"人推"和"钟推"复用同一条链路。光杆 `AGENT.md`、不带附属资源。

涉及能力一（Provider）+ 能力二（ReAct）+ 能力四（内置 HTTP Tool + `NotifyTools` + Sandbox）+ 能力五（定时任务 + Notify）+ 能力六（Session 查询兜底）。

### 12.2 Demo 二：每日科技日报（AGENT.md + 公共 Skill + MCP）

**场景：** 每天早上 9 点，Agent 自动汇总当日科技新闻并推送，日报内容会体现用户之前说过的关注方向（比如「更关注 AI 和芯片」）。业务方全程不写 Java 代码。

1. 业务方创建 `.yokeos/agents/daily-tech-digest/AGENT.md`（frontmatter：`identity` + `tools`（含 `http_get`、`notify`）+ `skills: [digest-format]` 按名引用公共组稿 Skill + 每天 09:00 的 `schedules` + `notify.channels`；正文写任务指令），公共组稿规范放 `.yokeos/skills/digest-format/SKILL.md`；需要新闻聚合 MCP 就在 `mcp_servers.yaml` 配一条
2. 用户此前说过"更关注 AI 和芯片方向"，DeepSeek 调 `save_memory` 写入 `MEMORY.md` 归档区
3. 到点后 system prompt 注入 `AGENT.md` 正文、记忆，以及 digest-format 的组稿规范正文（按名引用、整段注入）——业务方全程只写 markdown 配置
4. LLM 自己决定调新闻工具（`http_get` 或新闻 MCP，`McpToolAdapter` 转发）拉当日科技新闻；因为看到记忆里的偏好，组稿时自然侧重 AI 和芯片方向的条目——YokeOS 不解析任务步骤
5. LLM 调内置 `notify(content="...")` 推送，`ToolExecutor` 写 `tool_invocations`
6. 无更多 Tool 调用，循环结束

**验收要点：** 业务方全程不写一行 Java 代码，只写 `AGENT.md`（含 `schedules` 与按名引用的 Skill）并配置 `mcp_servers.yaml`；日报内容能体现 `MEMORY.md` 里记住的偏好，验证长期记忆真正在跨天场景里生效；`notify` 的推送有独立的 `tool_invocations` 审计记录；`GET /api/v1/agents` 查得到 `daily-tech-digest`；同一个 Agent 可被人工触发验证。

涉及公共 Skill 按名引用（正文注入）+ 能力四方式二（MCP）+ 内置 `NotifyTools` + 能力三（Memory）+ 能力五（定时任务）。

---

## 13. 实施节奏

实施按参照实现公开构建过程的课程节序组织（第 16→31 节），**节奏自定、顺序不乱**，不设日历时间盒。每节一个原子交付物；每节的「能力主线」与「可演示成果」以需求文档第 11 章的节序映射表为准，本节只补各节的**技术交付物**（新增模块组件、表、端点）：

| 节 | 技术交付物 |
|----|-----------|
| 16 | Maven 九模块骨架、`yokeos init`、frontmatter 解析；`ProviderService` + provider name 显式映射（先跑通 DeepSeek 或 Kimi）；`tool_invocations`/`llm_calls` 建表开始写入 |
| 17 | `ReActLoop` + `PromptBuilder` + `ToolExecutor`、一个内置 HTTP Tool；Session 内存版 |
| 18 | `CliChannel`、`yokeos chat` 命令、上下文与 Tool 调用记录查看 |
| 19 | `NotifyChannelAdapter` 接口 + `WebhookNotifyAdapter`、`NotifyTools`、frontmatter `notify.channels` |
| 20 | `FileTools`/`ShellTools`/`HttpTools`、`ToolRegistry`、`McpClientService`/`McpToolAdapter`、`mcp_servers.yaml` |
| 21 | Memory 设计评审（评审课，不产码）：三档后端与接口契约定稿 |
| 22 | `MemoryService` 门面 + `LongTermMemoryStore` 三档（`memory.backend`）、`MemoryTools`、`memory_entries` 建表脚本、`PromptBuilder` 加 Memory 注入 |
| 23 | Sandbox 设计评审（评审课，不产码）：接口签名与白名单校验规则定稿 |
| 24 | `Sandbox` 接口 + `WhitelistSandbox`、三条校验方法、异常走既有审计路径 |
| 25 | `AgentScheduler`（`ThreadPoolTaskScheduler` + `CronTrigger`）、并发锁、`scheduled_tasks`/`task_executions` 建表、`ScheduledTaskStore` 契约与 JPA 实现 |
| 26 | `WebServer` + 18 个端点 + `GlobalExceptionHandler` + OpenAPI；Web 管理台第一版（只读观察五页 + Agent 管理页 + 工作区页） |
| 27 | 全流程串联（串联课，不开新规格）：CLI → ReAct → Tool → Notify 端到端打通 |
| 28 | 全流程串联（串联课）：端到端链路固化为 `@Tag("integration")` 集成测试，稳定复跑 |
| 29 | 一个目录 = 一个 Agent：`AgentLoader.deriveProfile`、公共 Skill 库与按名引用注入、运行时注册（`ProfileRegistry`/`AgentScheduler` 运行时方法） |
| 30 | 动态管理：`/api/v1/agents` 七端点、一句话生成（独立配置键默认 Provider）、`WorkspaceWatcher`、workspace 只读浏览、管理台 Agent 管理页闭环 |
| 31 | 打包发布（fat JAR）、两个日跑 Demo 真实环境上线、项目主页上线 |

审计两表（`tool_invocations`、`llm_calls`）从第 16 节起就写入，不以「日志够了」为由推迟；每一节的完成判据是需求文档第 11 章对应行的「可演示成果」。

### 13.2 遗留未决事项

需求文档未决事项表中移交本阶段决议的四项已全部落定（见 1.1 决策三/决策八、8.3、3.3）。本篇新立的遗留未决：

| 事项 | 说明 | 决议时间 |
|------|------|---------|
| GraalVM Native Image 引入时机 | Java 启动 2~4 秒对 CLI 偏慢；等真实使用数据证明必要性 | 第一阶段结束后 |
| 向量检索扩展方案三选一 | LanceDB Java GA / pgvector / JVector（见 9.1） | 扩展阶段启动时 |
| Boot 4 + Spring AI 2.0 基线升级 | 已知技术债，作为一次真实的架构升级练习（见 1.2） | 扩展阶段 |
| 调度管理端点与白名单管理端点 | 与参照窗口末态的显式偏差，扩或不扩、何时扩（见 7.3） | 扩展阶段 |

---

## 14. 性能和可扩展性考虑

性能目标在需求文档第 8 章已定义（单节点 ≥10 个 Agent、≥100 并发 Session、Session 创建 P99 ≤200ms、内部转发开销 ≤50ms），这里说明怎么达到。

**Java 21 virtual thread 撑高并发。** 每个 Agent 是内存里的 Profile 对象加 Session 列表占用极少，virtual thread 让每个并发请求跑在独立虚拟线程，OS 线程数维持几十个就够支撑千级并发，LLM 调用 IO 阻塞时 virtual thread 自动让出 OS 线程。

**100 个并发 Session 内存可控。** 100 个 Session 平均 50KB 共 5MB，余量充足。SQLite 写入主要由 Session 追加消息和审计表写入触发，第一阶段每次都写，压测发现瓶颈再优化成批量落盘。

**Memory 文件 IO。** 每次组装 prompt 读一次 `MEMORY.md`，文件几 KB 到几十 KB 每次读 1 到 2ms，百级并发可接受；SQLite 档是毫秒级查询、Mem0 档是一次局域网 REST 调用，同量级。扩展阶段加 cache 加失效机制。

**启动时间。** Spring Boot 在 JDK 21 下启动 2 到 4 秒，对常驻服务没问题，对 CLI 工具太慢。第一阶段 CLI 命令分两类，不需要 Spring 的直接用标准 API 操作文件，需要的才启动 Spring。扩展阶段用 GraalVM Native Image 把启动降到 100ms 以下（引入时机见 13.2）。

---

## 15. 总结

YokeOS 技术方案核心：**JDK 21 + Spring Boot 3.5.x** 单体应用，自实现 ReAct loop，基于 **Spring AI Alibaba** 做 LLM 调用（薄包装 `ProviderService` 加显式 provider name 映射，只用其协议转换和 schema 生成，不用其自动 tool 执行），SQLite 持久化加 `MEMORY.md` 文件（可插拔三档后端），Picocli 命令行，Vue3 管理台。

方案围绕六个核心能力展开：

1. **能力一** 对接 LLM（Provider 抽象加显式 provider name 映射，`ProviderService` 薄包装收口审计与成本记录）
2. **能力二** ReAct 循环（Agent 的大脑，引擎约数十行 Java）
3. **能力三** Memory 两层记忆（统一门面，长期记忆三档后端一次交付，向量检索放扩展，接口预留升级空间）
4. **能力四** 工具体系（内置 9 个 Tool 加扩展 Tool 三档接入，主推 Agent 目录 加 MCP 零代码，`NotifyTools` 对称补上出站通知能力，第一阶段 Tool 相关三合一为一个模块）
5. **能力五** 通知与定时（Notify 是出站通道、`AgentScheduler` 是第三触发源，与人推复用同一条执行链路）
6. **能力六** 对外服务（REST API 五组操作第一阶段 18 个端点，业务系统集成的唯一通道，附 Web 管理台第一版）

六个能力加支撑模块是**底座**（第一部分），本身不是某个具体的业务 Agent。真正定义一个业务 Agent 靠的是**一个目录**（`AGENT.md` frontmatter 配置加任务指令正文，可选公共 Skill 按名引用与附属资源），第一阶段丢目录即上线、改定义免重启，动态管理经 `/api/v1/agents` 一组端点完成（第二部分，第 11 章）。

实施按参照实现的课程节序（第 16→31 节）组织，节奏自定、顺序不乱、不设日历时间盒；审计两表从第 16 节起写入；末段以两个日跑 Demo（每日天气、每日科技日报）作为第一阶段的端到端验收。

**存储选型：** 第一阶段 SQLite + `MEMORY.md` + 关键词检索跑通最短链路，语义检索放扩展（LanceDB Java GA、pgvector、JVector 三选一），`LongTermMemoryStore` 接口预留升级空间。

**承接定位：** 第一阶段交付运行时内核，能力上对齐业界开源 Agent OS 基础层，企业级治理差异化在扩展阶段补齐。架构上为治理层预留扩展点（Tool Policy、多租户、审计查询、SSO、知识库与语义记忆、底座分布式都有对应的预留位置）。

**核心理念不变：** YokeOS 六个能力扎实落地，业务方放一个 Agent 目录（一份 `AGENT.md`）加 MCP server 就能解决业务问题，通过 Web Service 接入已有系统，不需要写 Agent 后端代码。
