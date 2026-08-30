# 架构概览

***YokeOS 是一个 Spring Boot 单体应用：对外两个人工触发入口加一个自动触发入口，汇入同一个引擎；引擎调度三块能力；能力之下是一套存储。所有能力收敛到一个进程——「单二进制、装好就跑」。***

![YokeOS 整体架构：接入层→引擎层→能力层→基础层，三个触发源汇入同一个 AgentService](/images/docs-architecture.svg)

## 四层架构

从上到下分四层：

1. **接入层**（CLI Channel、Web Service 的 REST API、`AgentScheduler` 定时触发），负责消息进出
2. **引擎层**（`ReActLoop`、`PromptBuilder`、`ToolExecutor`），是 Agent 的大脑
3. **能力层**（Provider、Memory、Tool），给引擎提供 LLM 调用、上下文、执行能力
4. **基础层**（Agent 目录/Skill/Bootstrap 加载、Session 存储、SQLite、配置与密钥加载），是工程地基

## 三个触发源：「人推」与「钟推」

CLI 和 Web Service 都是「人推」——需要有人发起一次调用；`AgentScheduler` 是「钟推」——按 cron 表达式到点自动生成一条消息发起调用。三个入口的消息最终都汇入同一个 `AgentService`，作为统一入口不区分消息从哪来，走同一条执行链路，行为一致、审计同构。

## 六个能力之间的关系

六个能力不是平行的功能模块，它们有明确的协作关系：

- **ReAct 循环**（能力二）是引擎，负责把「用户消息到 LLM 思考到 Tool 执行到结果回填到继续」跑起来
- **Provider**（能力一）给引擎提供 LLM 调用能力，每轮思考都要调一次
- **Memory**（能力三）给引擎提供上下文，每轮组装 prompt 时注入会话历史和长期记忆
- **Tool**（能力四）给引擎提供执行能力，LLM 决定调哪个工具后由引擎负责执行
- **通知与定时**（能力五）是两个对称物：Notify 是**出站通道**——入站有 Channel 解决「消息怎么进来」，Notify 解决「Agent 跑完把结果送到哪」；定时任务是**第三条触发路径**
- **Web Service**（能力六）是内部能力的对外出口，把前四个能力包装成 REST API 供业务系统集成

> **简化成一句话**：Provider、Memory、Tool 三个能力供养 ReAct 循环这个引擎；通知与定时给引擎补上出站通道和第三触发源；引擎跑出的能力通过 CLI、Web Service、定时任务三个入口对外提供。

## 模块组成

YokeOS 是 Maven 多模块项目，九个模块（模块边界与参照实现一致）：

| 模块 | 职责 |
|------|------|
| `yokeos-core` | 核心抽象：`YokeTool` 接口、`Session`、`Profile`、`AgentLoader`、`ContextLoader`、`ReActLoop`、`PromptBuilder`、`ToolExecutor`、`AgentService`、`AgentScheduler` |
| `yokeos-provider` | 能力一：`ProviderService`、Function Calling 适配、provider name 显式映射 |
| `yokeos-memory` | 能力三：`MemoryService` 统一门面、`LongTermMemoryStore` 三档后端、`MemoryTools` |
| `yokeos-tool` | 能力四：内置 Tool、MCP Client、`ToolRegistry`、`Sandbox` 接口 + 白名单实现、通知适配器 |
| `yokeos-channel-cli` | CLI Channel：`yokeos chat` 实现 |
| `yokeos-web` | 能力六：REST API 控制器、Web 管理台托管、统一异常处理、OpenAPI |
| `yokeos-storage` | 持久化：SQLite、Session/审计/定时任务仓库 |
| `yokeos-cli` | 命令行入口：Picocli 主入口、12 个子命令、`ConfigLoader` |
| `yokeos-boot` | Spring Boot 启动模块：主类、自动配置、依赖聚合 |

模块之间通过接口解耦：跨模块契约放 `yokeos-core`、由下游模块实现（依赖倒置），禁止循环依赖。扩展阶段加新 Channel 或新 Tool 只加新模块，不改 core。

## 两个架构要点

1. **收敛**：所有能力收敛到一个引擎、一套存储、一个进程内，外部依赖（LLM 厂商 API、外部 MCP server）都在应用边界之外，YokeOS 自身不绑定任何一家
2. **解耦**：引擎和能力之间、能力和外部之间都通过抽象接口解耦，这让扩展阶段加新 Channel、新 Provider、新 Tool 时只需在边缘扩展，不动核心引擎

## 关键决策一览

| 决策 | 选择 |
|------|------|
| ReAct loop 实现方式 | 自实现，不依赖框架的 Agent 抽象 |
| 框架使用边界 | 只用协议转换 + `@Tool` schema 生成，禁用自动 tool 执行 |
| Provider 抽象 | 薄包装 `ProviderService` + 显式 name → ChatModel 映射 |
| 执行模型 | 同步阻塞 + Java 21 虚拟线程 |
| 沙箱策略 | 接口先行：`Sandbox` 抽象 + `WhitelistSandbox` 实现 |
| 持久化 | SQLite + Spring Data JPA + `MEMORY.md` 文件 |

## 下一步

各层的设计细节：[Provider 路由](./provider) · [ReAct 循环](./react-loop) · [记忆系统](./memory) · [工具体系与沙箱](./tool-sandbox) · [通知与定时](./notify) · [对外服务](./web-service)
