# Implementation Plan: 定时任务——第三触发源（第25节）

**Branch**: `specs/010-scheduler` | **Date**: 2026-09-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/010-scheduler/spec.md`

## Summary

一个 `AgentScheduler`（`ThreadPoolTaskScheduler` + `CronTrigger` 动态注册、按任务 id 的进程内 `ReentrantLock` 防重叠、失败隔离留痕）+ 两张 SQLite 表（`scheduled_tasks`/`task_executions`，手工脚本 `schema-004-scheduler.sql`）+ `ScheduledTaskStore` 六方法契约（core 接口、storage JPA 实现，依赖倒置）+ `YokeosRuntime` 三 Bean 装配。钟推会话固定三元组 `(scheduler, scheduler, profileName)`，执行链路复用 `AgentService.process` 零改动。task_id 派生生成 `{profileName}#{声明序号}`（拍板①）。本节合并参照 25+28 的定时落库部分；调度管理 REST 端点不做（ADR 0008），`runNow` 保留为类级方法作人推补跑入口。

## Technical Context

**Language/Version**: Java 21（virtual thread 处理并发；调度线程池是宪法 4「全程同步」的唯一明文例外）

**Primary Dependencies**: Spring Boot 3.5.16 + Spring Context 6.2.8（`ThreadPoolTaskScheduler`/`CronTrigger`/`SimpleTriggerContext`/`TaskScheduler`——既有传递件，零新增第三方依赖）+ Spring Data JPA + sqlite-jdbc 3.53.2.1 + hibernate-community-dialects。Spring AI 1.1.8 不接触（本节无 LLM API 面，`process` 内部既有链路）。

**Storage**: SQLite（`.yokeos/yokeos.db`）——新增 `scheduled_tasks`、`task_executions` 两表，手工脚本 `db/schema-004-scheduler.sql`（宪法 7，禁 ddl-auto=update）。

**Testing**: JUnit 5 + Mockito（core 单测五协作者全 mock）+ 真 SQLite 临时库（storage，`ScriptUtils` 跑 schema-004，`SQLiteConfig.setBusyTimeout`）+ boot 集成（`@Tag("integration")` 真 DeepSeek，`assumeTrue` 缺 key 跳过）。实现完成的定义 = `mvn clean verify` 九模块全绿。

**Target Platform**: macOS/Linux/Windows JVM 单进程；常驻形态 = `yokeos serve`/`yokeos gateway`（骨架已立，本节兑现调度常驻）。

**Project Type**: Maven 多模块库 + CLI（九模块，本节触 core/storage/cli/boot 四模块测试位）。

**Performance Goals**: 调度线程池 poolSize=2（触发即交付执行线程，ReAct 跑在虚拟线程无关的调度线程上同步阻塞）；单实例 ≥10 Agent × 每 Agent 数条 cron 无压力。

**Constraints**: 宪法 4——除 `ThreadPoolTaskScheduler` 外全程同步，禁 Reactor/WebFlux/CompletableFuture；调度线程 daemon（chat 一次性命令跑完 JVM 可退）；不新增配置键（cron/zone/message 全在 AGENT.md frontmatter）。

**Scale/Scope**: 新增 core 4 类 + storage 6 文件 + cli 装配 3 Bean + 测试 3 类；Profile/AgentLoader 零改动。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 宪法条 | 判定 | 说明 |
|---|---|---|
| 1 自实现 ReAct | ✅ 不触碰 | 钟推复用 `AgentService.process`，ReActLoop 零改动 |
| 2 Spring AI 只用两件事 | ✅ 不接触 | 本节零 Spring AI API 面 |
| 3 Provider 显式映射 | ✅ 不触碰 | 不涉 Provider |
| 4 同步 + 虚拟线程 | ✅ **明文例外** | `ThreadPoolTaskScheduler` 是宪法原文点名的唯一例外（宪法 4 括号原文「唯一例外：第 25 节 ThreadPoolTaskScheduler 调度线程池」）；触发线程之外的执行链路（`process` 往下）仍纯同步阻塞 |
| 5 Tool 三合一 | ✅ 不触碰 | 不新增 Tool |
| 6 Sandbox 接口先行 | ✅ 不触碰 | 无新动作类型 |
| 7 SQLite 审计 day one + 手工脚本 | ✅ 遵守 | 两表走 `schema-004-scheduler.sql` 手工脚本（22 节 schema-003 同款演进）；执行留痕与审计两表同源精神 |
| 8 一个目录 = 一个 Agent | ✅ 遵守 | `schedules` 定义在 AGENT.md frontmatter，16 节已解析建全，本节只消费（Profile/AgentLoader 零改动） |
| 9 结构照抄，瑕疵不继承 | ✅ 遵守 | 镜像参照 AgentScheduler/ScheduledTaskStore/JpaScheduledTaskStore 结构；不继承：显式 id 无校验两瑕疵（拍板①派生 id 消掉）、ScheduleApiController 及 REST 驱动测试（ADR 0008 偏差） |

设计后复检（Phase 1 后）：契约落 core、实现落 storage（依赖倒置）✅；core 类纯 POJO 零框架注解 ✅；无循环依赖（core 不依赖 storage，反向依赖）✅。无违宪项，Complexity Tracking 不填。

## Project Structure

### Documentation (this feature)

```text
specs/010-scheduler/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
│   └── scheduler.md     # ScheduledTaskStore 六方法 + AgentScheduler 公开面
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
yokeos-core/src/
├── main/java/com/yokeos/core/agent/
│   ├── AgentScheduler.java          # 新增：调度层（纯 POJO，registerAll/registerProfile/
│   │                                 #   runOnce/runNow/execute/lockFor + 锁表 + 句柄表 + taskIdOf）
│   ├── ScheduledTaskStore.java      # 新增：六方法契约（reconcile/recordExecution/isEnabled/
│   │                                 #   setEnabled/list/executions）
│   ├── ScheduledTaskView.java       # 新增：任务状态只读视图（record）
│   └── TaskExecutionView.java       # 新增：执行历史只读视图（record）
└── test/java/com/yokeos/core/agent/
    └── AgentSchedulerTest.java      # 新增：四坑 + 派生 id + 会话身份 + 失败留痕全覆盖

yokeos-storage/src/
├── main/java/com/yokeos/storage/
│   ├── ScheduledTask.java           # 新增：JPA 实体（scheduled_tasks 表）
│   ├── TaskExecution.java           # 新增：JPA 实体（task_executions 表）
│   ├── ScheduledTaskRepository.java # 新增：JPA 仓库
│   ├── TaskExecutionRepository.java # 新增：JPA 仓库（executions 倒序 limit 查询）
│   └── JpaScheduledTaskStore.java   # 新增：契约实现（幂等 reconcile / fail-open）
├── main/resources/db/
│   └── schema-004-scheduler.sql     # 新增：两表 DDL + 索引（宪法 7 手工脚本）
└── test/java/com/yokeos/storage/
    └── JpaScheduledTaskStoreTest.java # 新增：真 SQLite 契约语义 + DDL 守点

yokeos-cli/src/main/java/com/yokeos/cli/
├── YokeosRuntime.java               # 修改：+3 Bean（ThreadPoolTaskScheduler daemon/
│                                   #   ScheduledTaskStore / AgentScheduler initMethod=registerAll）
└── command/
    ├── ServeCommand.java            # 修改：骨架留位注释兑现（调度已常驻）
    └── GatewayCommand.java          # 修改：同上

yokeos-boot/src/test/java/com/yokeos/boot/
└── SchedulerEndToEndIntegrationTest.java # 新增：@Tag("integration") 真 DeepSeek 端到端
```

**Structure Decision**: 教学文档第三部分定稿落位——契约与调度器归 `yokeos-core` `com.yokeos.core.agent` 包（`AgentService` 同包，跨模块契约放 core 的宪法模块规则）；实体与 JPA 实现归 `yokeos-storage`（22 节 `MemoryEntry` 同款平铺组织）；装配归 `yokeos-cli` `YokeosRuntime`（16 节起显式装配定式）；boot 只加集成测试（模块测试覆盖不例外，宪法 9）。

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

无违宪项，不填。
