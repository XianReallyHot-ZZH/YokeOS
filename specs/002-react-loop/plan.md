# Implementation Plan: ReAct 循环——Agent 的大脑（第17节）

**Branch**: `specs/017-react-loop` | **Date**: 2026-09-13 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-react-loop/spec.md`

## Summary

第 17 节交付 Agent 的大脑：自实现 ReAct 循环（宪法 1，约数十行调度代码）串起「追加用户消息 → 组装 prompt → 调 LLM → 无工具调用即收尾 / 有则顺序执行回填 → 下一轮」，`max_iterations` 默认 10 兜底。前置两笔改造：**契约上移**（core 立 `com.yokeos.core.provider` 中性协议，16 节 `ProviderService` 改名 `SpringAiProviderService` 换型实现，对外行为零变化——修正 001 research D5）与 **YokeTool 补 `execute(JsonNode)`** 执行语义（含 `ToolResult`）。工具执行收口 `ToolExecutor` 唯一路径：先解析后执行、成败双路先落 `tool_invocations` 再还结果（宪法 7 兑现 16 节建表承诺）、可重试失败指数退避（3 次尝试，超出参照、拍板③批准）。配一个内置 `HttpGetTool` 让循环有真东西可执行，`ContextLoader` 零缓存供给 system prompt，`Session` 内存版承载累积。验收 harness 八测试类 + boot 集成冒烟（真 key + 真 `http_get` + open-meteo）。

## Technical Context

**Language/Version**: Java 21（虚拟线程处理并发）

**Primary Dependencies**: Spring Boot 3.5.16；Spring AI 1.1.8——`spring-ai-openai` 裸依赖 + 手工构造（**不用 starter**：autoconfigure eager 装配强索 api-key，16 节陷阱表在案）；jackson-databind（**core 新补显式依赖**，版本走根 pom `jackson-bom` 2.21.5，记实施偏差——research D7）；sqlite-jdbc 3.53.2.1 + Spring Data JPA（storage 既有）

**Storage**: 无新表——`tool_invocations` 16 节已建（`db/schema-001-audit.sql`），本节起写入；内存：`Session` + `InMemorySessionManager`（`session_id` 公式与持久化归 18 节）；文件系统现读：Bootstrap 三文件 + `AGENT.md` 正文（零缓存，改完立即生效）

**Testing**: 八测试类对号（教学文档第四部分）——单测 `ReActLoopTest` / `PromptBuilderTest` / `ToolExecutorTest` / `AgentServiceTest` / `ContextLoaderTest`（core，mock 的是自己的 `ProviderService` 接口不 mock Spring AI 类型——契约上移的直接红利）、`ToolInvocationRepositoryTest`（storage，建表执行手工脚本）、`SpringAiProviderServiceTest`（provider，原 `ProviderServiceTest` 改名平移 + 补协议映射测试）；集成冒烟 `ReActSmokeIntegrationTest`（boot，`@Tag("integration")`，CI 与本地默认跳过、缺 key `assumeTrue` 跳过）。测试方法名英文、教学文档语义以 `@DisplayName` 保留。完成定义 = `mvn clean verify` 全绿（含 16 节全部测试回归——契约上移零行为变化的门禁）

**Target Platform**: JVM（Windows + macOS 双平台开发环境）

**Project Type**: Maven 多模块 library（本节触五模块：yokeos-core / yokeos-provider / yokeos-tool / yokeos-storage / yokeos-boot〔仅测试〕）

**Performance Goals**: 需求文档 §8 全局口径（单节点 ≥10 Agent、≥100 并发 Session），本节不设独立指标；同步阻塞 + 虚拟线程承载（宪法 4）

**Constraints**: 宪法 1（本节主角：循环自实现，不用框架 Agent 抽象）、宪法 2（`ToolExecutor` 唯一执行路径；`internalToolExecutionEnabled(false)` 与 `ToolSchemaAdapter.call()` 抛异常两道闸保持）、宪法 4（无 CompletableFuture / reactor / WebFlux，退避用同步 `Thread.sleep`）、宪法 7（审计 day one 兑现、无 ddl-auto）；语法禁区——避开 P3C/ASM 解析不了的 Java 18+ 语法形态、测试类名 ≤1 连续大写、日志消息编译期常量；**API 已实证**——Spring AI 1.1.8 提取/构造写法已 javap 核实（research D2，plan 期完成不留实现期悬念）

**Scale/Scope**: 单 Agent 单会话链路打通；Agent 数个位数；`Map<String, YokeTool>` 个位数工具（`ToolRegistry` 归 20 节）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | 原则 | 本节落点 | 状态 |
|---|------|---------|------|
| 1 | 自实现 ReAct 循环 | 本节主角：`ReActLoop` 自实现 for 循环（追加→组装→调用→判停→执行→回填），不 import 任何 Spring AI Agent 抽象；code review + 单测断言轮数钉死 | ✓ |
| 2 | Spring AI 只用两件事 | 契约上移后 Spring AI 类型不出 provider 模块（core grep 零命中）；`ToolExecutor` 是唯一执行路径；`internalToolExecutionEnabled(false)` 与 `ToolSchemaAdapter.call()` 抛异常两道闸原样保持（16 节测试平移钉死） | ✓ |
| 3 | Provider 显式映射 | 映射表与调用路径原样复用（`SpringAiProviderService` 换壳不换心）；路由测试平移保持绿 | ✓ |
| 4 | 同步执行 + 虚拟线程 | 循环 / 执行器 / 退避重试全同步阻塞（`Thread.sleep`）；`grep -rE "CompletableFuture\|reactor\|WebFlux"` 九模块零新增 | ✓ |
| 5 | Tool 三合一 | `HttpGetTool` 落 yokeos-tool（20 节 `ToolRegistry` / 24 节 Sandbox / 19 节 Notify 的同模块预留位）；不拆模块 | ✓ |
| 6 | Sandbox 接口先行 | 本节不建 Sandbox；`ToolExecutor` 留白名单校验注释位（24 节接线），不引入任何实现特有概念 | ✓ |
| 7 | SQLite + 审计 day one | `tool_invocations` 本节起写入（16 节建表承诺兑现）：成败都落、先落审计再还结果、一次调用请求一条最终态；Sandbox 拒绝路径 24 节复用此表 | ✓ |
| 8 | 一个目录 = 一个 Agent | `AGENT.md` 正文经 `ContextLoader` 注入 system prompt（上下文层，宪法 8 落点）；绝不进任何 Tool 注册体系；Skill 正文 29 节留注释位 | ✓ |
| 9 | 结构照抄，瑕疵不继承 | 模块落位镜像参照第 17 节（循环/组装/执行/会话落 core、HTTP Tool 落 tool、审计 JPA 落 storage）；契约上移 = YokeOS 模块规则（契约放 core）优先于逐点照抄 + 参照库 specs/002 D1 同款先例 + 用户拍板①——001 research D3 同型决策的再现 | ✓ |

Phase 1 设计后复检：无新增违规（见 Complexity Tracking——空）。重试纳入本节超出参照课件范围，经用户拍板③批准，验收报告记实施偏差。

## Project Structure

### Documentation (this feature)

```text
specs/002-react-loop/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command output)
├── data-model.md        # Phase 1 output (/speckit-plan command output)
├── quickstart.md        # Phase 1 output (/speckit-plan command output)
├── contracts/
│   └── java-api.md      # Phase 1 output：core 新公开 API + 各模块改造契约（逐字签名）
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
yokeos-core/src/main/java/com/yokeos/core/
├── provider/                        # 契约上移（本节新增；全普通 Java 类型，core 禁引 Spring AI）
│   ├── ProviderService.java         # 接口：chat(sessionId, Profile, ProviderRequest) → ProviderResponse
│   ├── ProviderRequest.java         # record：promptText + List<YokeTool> availableTools
│   ├── ProviderResponse.java        # record：text + List<ToolCallRequest> + hasToolCalls()
│   └── ToolCallRequest.java         # record：name + argumentsJson
├── agent/                           # 本节新增（技 §10 模块表 core 行）
│   ├── ReActLoop.java               # 主循环：调度 only，先累积再判停，maxIterations 兜底
│   ├── PromptBuilder.java           # 四段固定顺序组装；Clock 注入（日期时间行可测）
│   ├── ToolExecutor.java            # 唯一执行路径：解析→〔Sandbox 位〕→执行→审计；退避重试
│   ├── AgentService.java            # 编排入口：查 Profile → set 上下文 → run → save → finally clear
│   └── ProfileContext.java          # ThreadLocal 封装：set / current / clear（remove）
├── context/
│   └── ContextLoader.java           # system prompt 供给：identity + Bootstrap（零缓存现读）+ Skill 位 + AGENT.md 正文
├── session/
│   ├── Session.java                 # sessionId + profileName + 消息累积（append 三兄弟，严格发生序）
│   ├── Message.java                 # record：(role, content, toolName)，role 取 user/assistant/tool
│   ├── SessionManager.java          # 接口（本节仅 save；getOrCreate/持久化归 18 节）
│   └── InMemorySessionManager.java  # ConcurrentHashMap 兜底实现
├── audit/
│   └── ToolInvocationAuditor.java   # 跨模块契约接口（与 LlmCallAuditor 同包对称；storage 实现）
└── tool/
    ├── YokeTool.java                # 扩 execute(JsonNode) → ToolResult；16 节注释同步修订
    └── ToolResult.java              # record：content/success/errorMessage/retryable + ok/error 工厂

yokeos-provider/src/main/java/com/yokeos/provider/
└── SpringAiProviderService.java     # 16 节 ProviderService 改名 implements core 接口
                                    #（git mv 保历史；显式映射/LlmCall 审计路径原样复用；
                                    #  ProviderRequest⇄Prompt / ChatResponse⇄ProviderResponse 双向映射）

yokeos-tool/src/main/java/com/yokeos/tool/
└── HttpGetTool.java                 # http_get：HttpClient GET、连接/读取 10s、8000 字符截断；白名单 24 节注释位

yokeos-storage/src/main/java/com/yokeos/storage/
├── ToolInvocation.java              # JPA 实体（列定义逐字技 §9.2；无新表，映射既有 tool_invocations）
├── ToolInvocationRepository.java    # JpaRepository（含按 session_id 查询）
└── JpaToolInvocationAuditor.java    # ToolInvocationAuditor 实现（依赖倒置，形态照 JpaLlmCallAuditor）

yokeos-boot/src/test/java/com/yokeos/boot/
└── ReActSmokeIntegrationTest.java   # @Tag("integration")：手工装配 core+provider+tool+storage 全链路
```

**Structure Decision**: 九模块既有骨架内落位，本节触 core（新增五包扩一包）、provider（一类改名换型）、tool（新增一类）、storage（新增三件）、boot（仅集成测试）。依赖方向全部合规不新增环：core 零依赖下游（唯一新外部依赖 jackson-databind，D7）；provider → core（实现契约接口）；tool → core（实现 YokeTool）；storage → core（实现审计契约）；boot 测试 → 四模块聚合（冒烟跨四模块故落 boot，拍板④）。16 节 `ProviderService.java` 与 `ProviderServiceTest.java` 以改名方式消失（`SpringAiProviderService` / `SpringAiProviderServiceTest` 顶上，git mv 语义），16 节测试断言语义逐条平移保留——「零行为变化」的可审计搬移。参照第 17 节落位镜像一致；唯一显式偏差仍是契约上移（YokeOS 模块规则优先，拍板①）。

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

无违规，无条目。
