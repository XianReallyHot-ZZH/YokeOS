# Implementation Plan: 插件化 Agent——一个目录定义一个会自己跑的 Agent（第29节）

**Branch**: `specs/014-plugin-agent` | **Date**: 2026-09-22 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/014-plugin-agent/spec.md`

## Summary

第 29 节是收口节：把 16/17/20/25 节铺好的零件接成「丢一个目录 = 上线一个 Agent」的完整机制。三处主代码增量——`ContextLoader` 兑现 17 节留位（点名公共 Skill 正文注入 system prompt：剥 frontmatter、带段头、按声明序、Bootstrap 后 AGENT.md 正文前、零缓存现取）；`ProfileRegistry` 兑现 16 节遗留决议（补 `exists`/`remove` 运行时方法，同名维持后到覆盖）；`AgentScheduler` 兑现 25 节句柄表消费面（`unregisterProfile`：taskId 同源派生 + `cancel(false)` + 句柄移除）。另交付示例 Agent 目录 `daily-reconcile` 四件套（测试 fixture 兼演示素材）。**零新依赖、零新表、零新端点、零新配置键**；`AgentLoader` / 装配层 / `PromptBuilder` / `ReAct` 一行不动（FR6）。技术路径：纯 `java.nio` 文件读取 + 既有类小改，全程同步阻塞。

## Technical Context

**Language/Version**: Java 21（虚拟线程处理并发）

**Primary Dependencies**: **零新增**——主代码只碰 `java.nio.file` 与既有类；`yokeos-core` 既有传递件足额（动手前 `mvn -pl yokeos-core -am dependency:resolve` 冒烟核实，H3 纪律照走）。本节无 Spring AI 面、无新第三方坐标。

**Storage**: 文件系统——`.yokeos/skills/<名>/SKILL.md`（本节新消费面，agentskills.io 兼容：frontmatter `name`/`description` + 正文）；`.yokeos/agents/<name>/`（既有）。SQLite **零变更**（无新表、无 schema 演进）。

**Testing**: JUnit 5 + Mockito 全单测（`TaskScheduler`/`ScheduledFuture` mock、`@TempDir` 现造目录 + 测试资源 fixture 复用）：`SkillInjectionTest` / `ProgressiveDisclosureTest` / `AgentScanRegisterTest` / `ProfileRegistryRuntimeTest` / `AgentSchedulerUnregisterTest`（五新类）；16 节存量 `AgentLoaderTest` 即参照 `DeriveProfileTest` 对号（不重复建类，拍板④）。无新增 `@Tag("integration")`——真模型钟推链路由 28 节存量承载，真目录演示归人工项。测试方法名英文 camelCase 无连续大写、语义以 `@DisplayName` 中文保留。完成定义 = `mvn clean verify` 九模块全绿。

**Target Platform**: JVM（macOS + Windows 双平台开发环境）

**Project Type**: Maven 多模块 library——本节只触 **yokeos-core** 一个模块（主代码 3 类小改 + 测试 5 新类 + 测试资源 fixture）

**Performance Goals**: 沿用需求 §8 全局口径，本节不设独立指标——Skill 注入 = 每次组装读 N 个小文件（零缓存现取，技 §8.3 既有语义），Skill 库条目与点名列表皆个位数。

**Constraints**: 宪法 8 是本节主条（Skill 不进 `ToolRegistry`、正文不预载、附属资源经既有工具按需取用——H4 grep 不变量：`yokeos-tool` 模块不出现 Skill 概念）；CRLF 日志门禁——WARN 消息编译期常量、Skill 名进异常堆栈（20 节 tools WARN 同款形态）；语法禁区——避开 P3C/ASM 解析不了的 Java 18+ 语法形态；测试类名 ≤1 连续大写。无 API 代差面（不碰 Spring AI）。

**Scale/Scope**: 公共 Skill 库条目个位数；Agent 数个位数；示例目录一套。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | 原则 | 本节落点 | 状态 |
|---|------|---------|------|
| 1 | 自实现 ReAct 循环 | 不触循环；Skill 注入发生在循环之前的 prompt 组装层 | ✓ |
| 2 | Spring AI 只用两件事 | 本节零 Spring AI 面（无调用、无 schema） | ✓ |
| 3 | Provider 显式映射 | 不触 Provider 层 | ✓ |
| 4 | 同步执行 + 虚拟线程 | `appendSkills` 同步文件读、`unregisterProfile` 同步注销，无异步模型 | ✓ |
| 5 | Tool 三合一 | 不触 `yokeos-tool`；Skill 是上下文资源不是可执行 Tool（宪法 8 联动守点） | ✓ |
| 6 | Sandbox 接口先行 | 本节无新涉外执行；附属资源按需取用走 24 节既有工具 + 沙箱 | ✓ |
| 7 | SQLite + 审计 day one | 零新表零 schema 变更；Skill 注入是 prompt 组装不产生新审计事件（审计锚在 LLM/Tool 调用，本节链路零改动） | ✓ |
| 8 | 一个目录 = 一个 Agent | **本节主条**：正文与所引用 Skill 的正文注入 system prompt；Skill 不进 `ToolRegistry`、正文不预载（零缓存现取）；附属资源经既有工具按需取用 | ✓ |
| 9 | 结构照抄，瑕疵不继承 | 机制镜像参照 29 节（扫描→派生→注册→注入→注销）；三处显式偏差记录在 research（公共库按名注入系技 §11.1 定案差异、不加 `hasScheduledTask` 公共探针、`DeriveProfileTest` 不单列） | ✓ |

Phase 1 设计后复检：无新增违规（见 Complexity Tracking——空）。

## Project Structure

### Documentation (this feature)

```text
specs/014-plugin-agent/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
yokeos-core/src/main/java/com/yokeos/core/
├── context/
│   └── ContextLoader.java              # Δ appendSkills：点名 Skill 正文注入（17 节留位兑现）
├── profile/
│   └── ProfileRegistry.java            # Δ exists / remove（幂等）+ 同名覆盖语义 javadoc（29 节定夺）
└── agent/
    └── AgentScheduler.java             # Δ unregisterProfile + taskId 派生从注册侧抽私有共用

yokeos-core/src/test/java/com/yokeos/core/
├── context/
│   ├── SkillInjectionTest.java         # 新：点名注入五守点（坑①②③④ + 即时生效）
│   └── ProgressiveDisclosureTest.java  # 新：正文进 prompt、附属资源零预载、改正文即时生效
├── profile/
│   ├── AgentScanRegisterTest.java      # 新：扫 N 得 N、带定时交 scheduler、坏目录有声跳过
│   └── ProfileRegistryRuntimeTest.java # 新：可见性、remove 幂等、同名覆盖、同源校验
└── agent/
    └── AgentSchedulerUnregisterTest.java # 新：句柄移除 + cancel(false)、taskId 同源、空跑、重复注销

yokeos-core/src/test/resources/fixture/029/          # 示例 Agent 目录（fixture 兼演示素材，research D4）
├── workspace-example/
│   ├── agents/daily-reconcile/
│   │   ├── AGENT.md                   # frontmatter（skills/schedules/notify 占位）+ 四步任务正文
│   │   ├── REFERENCE.md               # 字段字典 + 已知可接受差异
│   │   └── scripts/reconcile.py        # 纯标准库 CSV 比对
│   └── skills/report-format/SKILL.md   # agentskills.io 兼容 frontmatter + 组稿规范
```

**Structure Decision**: 只触 `yokeos-core`（依赖方向零变化：core 不依赖任何下游，Skill 概念不进 `yokeos-tool`——宪法 8 结构守点）。`AgentLoader` / `YokeosRuntime` 装配 / `PromptBuilder` / `ReAct` 零改动（FR6：运行时与启动扫描同走 `deriveProfile` 一条代码路径的结构性事实由 harness 断言钉死，不靠改代码实现）。测试资源 fixture 归测试域，不进生产 classpath、不改 `yokeos init` 模板（18 节既有目录清单含 `skills/` 已足）。

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

无违规，无条目。
