# Implementation Plan: Notify——结果主动送出去的统一出口（第19节）

**Branch**: `specs/004-notify-outbound` | **Date**: 2026-09-15 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-notify-outbound/spec.md`

## Summary

第 19 节交付出站通知：`NotifyChannelAdapter` 接口先行（语汇零渠道特有词）+ `NotifyTarget` 值对象 + `WebhookNotifyAdapter` 唯一实现（JDK HttpClient 发 `{"content": ...}`，非 2xx 异常上抛）+ `NotifyTools` 内置 Tool（implements 既有 `YokeTool`，channel 参数按渠道名 name 匹配，`ProfileContext` 取当前 Agent 渠道配置）；`AgentLoader` 的 `notify.channels` type 从硬编码改为三态读取；`YokeosRuntime` 注册 `notify` 进 CLI 对话。审计零新增逻辑——发送失败经 `ToolExecutor` 既有「异常转失败结果 + tool_invocations 落账」路径；域名白名单按检查位注释留 24 节。

## Technical Context

**Language/Version**: Java 21（虚拟线程处理并发）

**Primary Dependencies**: Spring Boot 3.5.16；本节不碰 Spring AI（无 API 代差问题）；JDK 内置 `java.net.http.HttpClient`（同步阻塞，宪法 4；拍板③——不引入 spring-web 新依赖）与 `com.sun.net.httpserver.HttpServer`（仅测试假 webhook）；Jackson（`JsonNode` 入参，既有）；SnakeYAML（frontmatter，既有）；SLF4J（type 剔除错误日志，编译期常量消息）

**Storage**: 无新表、无 schema 变更——`tool_invocations` 既有路径承载审计（宪法 7 零新增审计逻辑）；文件系统——`AGENT.md` frontmatter `notify.channels` 段（16 节已解析，本节改 type 处理）

**Testing**: JUnit 5 + Mockito 单测主体（mock `NotifyChannelAdapter`）：`WebhookNotifyAdapterTest`（JDK HttpServer 假 webhook：POST 形态/URL 来自配置/5xx 上抛/缺 url 报错）+ `NotifyToolsTest`（渠道解析七态，`ProfileContext` set/clear 纪律，文件头注明 InOrder 顺序回归留 24 节）+ `AgentLoaderTest` 补 type 三态用例（行为断言为主 + ListAppender 断言错误日志，首次引入）。测试方法名英文、`@DisplayName` 保留教学文档中文语义。完成定义 = `mvn clean verify` 九模块全绿（18 节基线 125 测试 + 本节新增，前序零回归）；真群机器人推送为人工冒烟不进 CI

**Target Platform**: JVM（macOS + Windows 双平台开发环境）

**Project Type**: Maven 多模块 library + cli（本节触三个模块：yokeos-tool / yokeos-core / yokeos-cli）

**Performance Goals**: 按需求文档 §8 全局口径，本节不设独立指标；单次推送 connect timeout 10s + request timeout 10s（对端挂起不拖死 ReAct 循环，见 [research.md](./research.md) D7）

**Constraints**: 宪法 4（HttpClient.send 同步阻塞，无异步模型）、宪法 5（Notify 全落 yokeos-tool 不拆）、宪法 6（本节不建 Sandbox 概念，检查位注释留 24 节接线）、宪法 7（webhook URL 走 `${ENV_VAR}` 占位不落明文）；`ToolExecutor` 只 catch `RuntimeException`——HttpClient 的 checked `IOException` 必须包 `UncheckedIOException` 才能进既有失败路径（research D1）；notify 发送失败不可重试（`retryable=false` 语义，避免同一条消息重复推群，research D6）；语法禁区——避开 P3C/ASM 解析不了的 Java 18+ 语法形态；测试类名 ≤1 连续大写；日志消息编译期常量

**Scale/Scope**: 每 Agent 通知渠道个位数；第一阶段只装 webhook 一档 Adapter；无定时并发推送场景（25 节前）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | 原则 | 本节落点 | 状态 |
|---|------|---------|------|
| 1 | 自实现 ReAct 循环 | 不动循环；notify 经既有 `YokeTool`/`ToolExecutor` 执行，异常不炸循环 | ✓ |
| 2 | Spring AI 只用两件事 | 本节不碰 Spring AI（纯 JDK HttpClient + 既有 Tool 体系） | ✓ |
| 3 | Provider 显式映射 | 不涉及（无 ChatModel 交互） | ✓ |
| 4 | 同步执行 + 虚拟线程 | `HttpClient.send` 同步阻塞；无 Reactor/CompletableFuture | ✓ |
| 5 | Tool 三合一 | `NotifyChannelAdapter`/`NotifyTarget`/`WebhookNotifyAdapter`/`NotifyTools` 全落 yokeos-tool 一个模块，不拆 | ✓ |
| 6 | Sandbox 接口先行 | 本节不建任何 Sandbox 概念；域名白名单按检查位注释留 24 节（与 `HttpGetTool` 17 节同款），接口先行原则体现在 `NotifyChannelAdapter` 自身 | ✓ |
| 7 | SQLite + 审计 day one | 零新增审计逻辑：发送失败经 `ToolExecutor` 既有异常转失败路径落 `tool_invocations`（success=false）；webhook URL `${ENV_VAR}` 占位 | ✓ |
| 8 | 一个目录 = 一个 Agent | `notify.channels` 是 frontmatter→Profile 派生字段；AGENT.md 归 ContextLoader 体系不进 Tool | ✓ |
| 9 | 结构照抄，瑕疵不继承 | 落位镜像参照（notify 子包 + Tool 包根同构）；两处拍板偏差显式记录——JDK HttpClient 替 RestClient（拍板③）、channel 按 name 匹配（拍板①，参照渠道模型无 name 字段推理不成立）；参照「实现顺序说明」分批纪律照抄（InOrder 回归留 24 节） | ✓ |

Phase 1 设计后复检：无新增违规（见 Complexity Tracking——空）。

## Project Structure

### Documentation (this feature)

```text
specs/004-notify-outbound/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
│   └── notify.md        # 渠道适配接口 + notify Tool 契约
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
yokeos-tool/src/main/java/com/yokeos/tool/
├── notify/
│   ├── NotifyChannelAdapter.java   # 出站通知接口（send(NotifyTarget, String)，语汇零渠道特有词）
│   ├── NotifyTarget.java           # record：channelType + Map<String,String> config（compact ctor 防御副本）
│   └── WebhookNotifyAdapter.java   # 唯一实现：JDK HttpClient POST {"content":...}，
│                                   #   缺 url 抛 IllegalArgumentException、非 2xx 包 UncheckedIOException
└── NotifyTools.java                # implements YokeTool（与 HttpGetTool 同层）：
                                    #   渠道解析（name 匹配/default→第一个/不回退）→ Map<type,Adapter> 路由
                                    #   →〔24 节 Sandbox 检查位注释〕→ send → ToolResult

yokeos-tool/src/test/java/com/yokeos/tool/
├── notify/
│   └── WebhookNotifyAdapterTest.java   # JDK HttpServer 假 webhook 四态
└── NotifyToolsTest.java                # 渠道解析七态；文件头注明 InOrder 顺序回归留 24 节

yokeos-core/src/main/java/com/yokeos/core/profile/
└── AgentLoader.java                # 修改：notify.channels 的 type 三态（读取/缺省 webhook/不支持剔除记错误日志）

yokeos-core/src/test/java/com/yokeos/core/profile/
└── AgentLoaderTest.java            # 补 type 三态用例（行为断言 + ListAppender 日志断言）

yokeos-cli/src/main/java/com/yokeos/cli/
└── YokeosRuntime.java              # 修改：tools() Map 注册 "notify"
```

**Structure Decision**: 九模块既有骨架内落位，本节触 tool / core / cli 三模块。依赖方向不变：tool → core（消费 `YokeTool`/`ToolResult`/`ProfileContext`/`Profile`，既有）、cli 装配（既有）；core 无新依赖。`NotifyTools` 落 `com.yokeos.tool` 包根与 `HttpGetTool` 同层（本仓无参照的 `builtin` 子包，结构照抄按本仓既有形态）；适配三件落 `com.yokeos.tool.notify` 子包镜像参照 `io.oryxos.tool.notify`。

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

无违规，无条目。
