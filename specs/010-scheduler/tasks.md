---
description: "Task list for feature implementation"
---

# Tasks: 定时任务——第三触发源（第25节）

**Input**: Design documents from `/specs/010-scheduler/`

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: TDD 纪律（显式喂入）：测试任务先于或伴随对应实现任务（验收 harness 先行），实现与测试在同一任务内闭环、该模块测试红了当场修；集成冒烟单列。测试方法名用英文 camelCase（避连续大写缩写），教学文档语义以 `@DisplayName` 保留。

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of the story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g. US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

九模块 Maven 多模块（plan.md Project Structure 节为准确布局）：`yokeos-core/src/main/java/com/yokeos/core/agent/`、`yokeos-storage/src/{main,test}/java/com/yokeos/storage/`、`yokeos-storage/src/main/resources/db/`、`yokeos-cli/src/main/java/com/yokeos/cli/`、`yokeos-boot/src/test/java/com/yokeos/boot/`。

---

## Phase 1: Foundational（契约与存储结构，BLOCKS 所有 story）

**Purpose**: 跨模块契约 + 两表 DDL + JPA 实体/仓库——US1（AgentScheduler 依赖接口）与 US4（JPA 实现依赖实体）的共同前置。九模块工程已立，无 Setup 需求。

- [x] T001 `yokeos-core/src/main/java/com/yokeos/core/agent/ScheduledTaskStore.java` + `ScheduledTaskView.java` + `TaskExecutionView.java`：六方法契约与两只读视图 record（签名逐字照 `contracts/scheduler.md`；纯 POJO 零框架注解；javadoc 记 reconcile 幂等语义与 fail-open 出处技 §8.5）
- [x] T002 [P] `yokeos-storage/src/main/resources/db/schema-004-scheduler.sql`：两表 DDL + `idx_task_executions_task` 索引（列定义逐字 `data-model.md`，头部注释 22 节 schema-003 同款三行——出处/宪法 7/语义说明）
- [x] T003 [P] `yokeos-storage/src/main/java/com/yokeos/storage/ScheduledTask.java` + `TaskExecution.java` + `ScheduledTaskRepository.java` + `TaskExecutionRepository.java`：JPA 实体（列映射 `data-model.md`）与仓库（`TaskExecutionRepository` 含按 `task_id` + `started_at` 倒序取 limit 条的派生查询）

**Checkpoint**: 契约与存储结构就位；US1（core 侧，依赖 T001）与 US4（storage 侧，依赖 T002/T003）可并行开工。

---

## Phase 2: User Story 1 - 到点自动发起、无人参与 (Priority: P1) 🎯 MVP

**Goal**: AgentScheduler 主干——注册（配置驱动 + 时区 + 单条隔离）、runOnce/execute 主干、runNow、派生 taskId、钟推会话。

**Independent Test**: `AgentSchedulerTest`（五协作者全 mock）注册/时区/会话/派生 id/runNow 用例全绿；`mvn -pl yokeos-core -am test` 过。

### Tests for User Story 1（先写，红着进实现）

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [x] T004 [US1] `yokeos-core/src/test/java/com/yokeos/core/agent/AgentSchedulerTest.java`：骨架（五协作者 mock + `when(taskStore.isEnabled(any())).thenReturn(true)` 钉死缺省启用——坑八）+ 用例：注册时 `CronTrigger` 带配置的 cron 与时区（ArgumentCaptor 抓 Trigger；时区用固定 `SimpleTriggerContext` 下 `nextExecution` 时刻证明——坑九，与同 cron 配置时区参照一致、与别的时区不一致）与 zone 缺省/空白回退系统时区（analyze C2）、会话三元组 `("scheduler","scheduler",profileName)` 且两次触发同一 `session_id`、派生 taskId 同 profile 两条不同号且跨 profile 前缀区分（经 `recordExecution`/`reconcile` 参数断言）、`runNow` 找到任务执行 / 找不到抛 `IllegalArgumentException`、单条非法 cron 该条跳过且其它条照常注册、注册时 `reconcile` 被调（登记）、**停用任务 `runOnce` 跳过且不调 `process` 不 `recordExecution`**（analyze C1，`when(isEnabled(taskId)).thenReturn(false)`）、无任何定时规则时 `registerAll` 空跑不报错（analyze L1）

### Implementation for User Story 1

- [x] T005 [US1] `yokeos-core/src/main/java/com/yokeos/core/agent/AgentScheduler.java`：纯 POJO 全主干——`registerAll`/`registerProfile`（逐条 `CronTrigger(cron, resolveZone(zone))` → `schedule` 句柄入表 + `taskStore.reconcile(taskIdOf(...), …, nextExecution(sc))`；单条 RuntimeException catch 记日志跳过——CRLF 门禁编译期常量形态）、`runOnce`（isEnabled false 跳过不记执行）、`execute` 主干（tryLock → `getOrCreate("scheduler","scheduler",name)` → `process(session, message)` → finally `unlock` + `recordExecution` 自带 try-catch）、`runNow`（按 taskId 遍历注册表、无视启用状态、找不到抛 `IllegalArgumentException`）、`lockFor`（public 可测）、`taskIdOf`（`{profileName}#{声明序号}` 拍板①）、`nextExecution`（非法返回 null）、`resolveZone`（空/blank 回退系统时区）→ T004 全绿；`mvn -pl yokeos-core -am test` 过

**Checkpoint**: 钟推主链路（注册→触发→执行→留痕调用）可独立验证。

---

## Phase 3: User Story 2 - 重叠触发直接跳过 (Priority: P2)

**Goal**: execute 的重叠防护行为钉死——锁被占跳过、跳过一次性、不排队不并发。

**Independent Test**: `AgentSchedulerTest` 重叠用例绿；`mvn -pl yokeos-core -am test` 全绿。

### Tests for User Story 2（伴随实现——主干已含 tryLock 结构，红了当场修）

- [x] T006 [US2] `AgentSchedulerTest.java` 补用例：`lockFor(taskId).lock()` 模拟上一次还在跑 → `runOnce` 不调 `process`（`verify(never())`，本次被跳过）；锁释放后再触发能正常进入（跳过一次性）

**Checkpoint**: US1+US2 均可独立验证（同一测试类分用例承载）。

---

## Phase 4: User Story 3 - 单次失败被隔离、留痕、不留死锁 (Priority: P3)

**Goal**: 失败路径钉死——不外抛、锁必放（二进宫证明）、失败记录落历史、落库自身失败不外抛。

**Independent Test**: `AgentSchedulerTest` 失败隔离用例绿；`mvn -pl yokeos-core -am test` 全绿。

### Tests for User Story 3（伴随实现——catch/finally 结构已含，红了当场修）

- [x] T007 [US3] `AgentSchedulerTest.java` 补用例：`process` 抛异常 → `runOnce` 不外抛（`assertDoesNotThrow`）+ 紧接再触发一次 `verify(process, times(2))`（二进宫证明锁在 finally 已放）+ `recordExecution` 记了 `success=false`；`recordExecution` 自身抛 RuntimeException → 不外抛且锁照样释放（再触发能进）

**Checkpoint**: US1~US3 全部可独立验证。

---

## Phase 5: User Story 4 - 任务状态与执行历史落库可查，重启不丢 (Priority: P4)

**Goal**: `JpaScheduledTaskStore` 六方法——幂等 reconcile、recordExecution 更新、fail-open、倒序 limit。

**Independent Test**: `JpaScheduledTaskStoreTest`（真 SQLite）全绿；`mvn -pl yokeos-storage -am test` 过。

### Tests for User Story 4（先写，红着进实现）

- [x] T008 [US4] `yokeos-storage/src/test/java/com/yokeos/storage/JpaScheduledTaskStoreTest.java`：真 SQLite（临时库 + `ScriptUtils` 跑 `schema-004` + `SQLiteConfig.setBusyTimeout`，22 节 `MemoryEntryRepositoryTest` 同款）+ 用例：reconcile 新增默认 `enabled=true`/`run_count=0`、reconcile 已存在只更新定义字段且**保留 enabled 与 run_count**（改 `setEnabled(false)` + 预置 run_count 后再 reconcile 验证不冲掉）、recordExecution 后历史恰多一行且任务行 `last_run_at`/`last_status`/`run_count+1`/`next_run_at` 更新、`isEnabled` 未登记返回 true（fail-open）、`setEnabled` 切换生效、`executions` 按 `started_at` 倒序 + limit、schema-004 两表 DDL 存在且幂等（重复执行脚本不报错——宪法 7 守点）

### Implementation for User Story 4

- [x] T009 [US4] `yokeos-storage/src/main/java/com/yokeos/storage/JpaScheduledTaskStore.java`：六方法实现（reconcile 幂等 upsert；recordExecution 先 insert 历史再更新任务行；isEnabled 查无返回 true；executions 走 T003 派生查询）→ T008 全绿；`mvn -pl yokeos-storage -am test` 过

**Checkpoint**: 四个 story 全部独立可验证；store 契约与调度器行为闭环。

---

## Phase 6: 装配与常驻（跨 story 收口）

**Purpose**: 三 Bean 进 `YokeosRuntime`，serve/gateway 调度常驻兑现。

- [x] T010 `yokeos-cli/src/main/java/com/yokeos/cli/YokeosRuntime.java`：+3 Bean——`ThreadPoolTaskScheduler`（poolSize 2、`threadNamePrefix "yokeos-sched-"`、`setDaemon(true)` 坑七、`initialize()`）、`ScheduledTaskStore`（注两仓库）、`AgentScheduler`（`@Bean(initMethod="registerAll")`）；`yokeos-cli/src/main/java/com/yokeos/cli/command/ServeCommand.java` + `GatewayCommand.java`：骨架留位注释兑现 + 启动文案提及调度常驻
- [x] T011 boot 上下文回归：`mvn -pl yokeos-boot -am test`（新 Bean 加入后 `YokeosBootApplicationLoadTest` 等既有测试不炸——chat/serve 共装配、daemon 不挂 JVM 的间接验证）

**Checkpoint**: 常驻形态可用（`yokeos serve` 起来即注册全部 schedules）。

---

## Phase 7: 集成冒烟（单列——TDD 纪律）

**Purpose**: 真模型端到端：启动即登记 → runNow 驱动 → 两表对账 + 审计同构。

- [x] T012 `yokeos-boot/src/test/java/com/yokeos/boot/SchedulerEndToEndIntegrationTest.java`：`@Tag("integration")` 真 DeepSeek（`assumeTrue` 缺 key 跳过不失败；24 节 `SandboxEndToEndIntegrationTest` 同款形态）——`@TempDir` 工作区 seed 带 `schedules` 的 AGENT.md（cron `"0 0 0 1 1 *"` 每年 1 月 1 日，测试窗口内绝不自然触发——research D6；`yokeos.root` 指向临时区）→ 起真 `YokeosRuntime` 上下文 → 断言：①`scheduled_tasks` 有登记行（enabled、run_count=0）；②`agentScheduler.runNow(taskId)` 后 `task_executions` 一条 success、`run_count=1`、`last_status="success"`；③钟推 Session 落库且 `session_id` 为三元组拼接；④`llm_calls` 有该 session 的调用记录（审计同构）。跑法：`source ~/.zshrc && mvn -pl yokeos-boot -am test -Dgroups=integration -DexcludedGroups= -Dtest=SchedulerEndToEndIntegrationTest`

**Checkpoint**: 端到端证据链齐（对账口径 = quickstart.md）。

---

## Phase 8: Polish & 收尾（六项证据 DoD）

- [x] T013 全量门禁：`mvn clean verify` 九模块全绿（贴关键输出与测试数进验收报告）；前序节回归绿（本分支跑）
- [x] T014 `specs/010-scheduler/acceptance-report.md`：六项证据（全量门禁输出 / harness 对号表 / 交付物存在性 ls+grep 证据 / 前序回归 / H4 七条全局不变量自查 / 人工项——真 key 冒烟 + `grep -r 'sk-'` 凭证卫生 + 到点观察 + chat 可退出）+ 实施偏差节；CLAUDE.md 常见陷阱表回填本节新坑（若有）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Foundational)**: 无前置，立即开工；**BLOCKS 全部 story**
- **Phase 2~5 (US1~US4)**: 各依赖 Phase 1——US1 线（T004→T005→T006→T007 同文件顺序）与 US4 线（T008→T009）**可并行**（core 与 storage 不同模块不同文件）
- **Phase 6 (装配)**: 依赖 Phase 2 + Phase 5（AgentScheduler 与 JpaScheduledTaskStore 均在）
- **Phase 7 (集成冒烟)**: 依赖 Phase 6（三 Bean 装配完才能起真上下文）
- **Phase 8 (收尾)**: 依赖全部

### User Story Dependencies

- **US1 (P1)**: 依赖 T001；MVP = Phase 1 + Phase 2（钟推主链路即可演示）
- **US2 (P2)** / **US3 (P3)**: 依赖 US1 实现（同一 `execute` 方法的增量行为用例）
- **US4 (P4)**: 仅依赖 T002/T003，与 US1~US3 完全并行

### Within Each User Story

- Tests MUST be written and FAIL before implementation（T004→T005、T008→T009）；US2/US3 用例伴随主干（结构已含行为，红当场修）
- 契约/实体先于服务；core 先于装配；装配先于集成

### Parallel Opportunities

- T002 ∥ T003（Phase 1 内，不同文件）
- US1 线（core）∥ US4 线（storage）：T004/T005 与 T008/T009 不同模块可并行
- T010 与 T012 的测试编写可并行（不同文件）——但执行验证 T012 须在 T010/T011 后

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1（契约 + DDL + 实体）
2. Phase 2（US1：harness 先行 + AgentScheduler 主干）
3. STOP and VALIDATE：`mvn -pl yokeos-core -am test` 绿 = 钟推主链路独立可测

### Incremental Delivery

1. Foundation → 2. US1（MVP）→ 3. US2/US3（行为钉死）∥ US4（落库）→ 4. 装配 → 5. 集成冒烟 → 6. 收尾六项证据

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- 命令形态纪律：单模块构建必带 `-am`（reactor）；集成冒烟显式 `-Dgroups=integration -DexcludedGroups=`；真 key 缺失 `assumeTrue` 跳过不失败
- 每任务完成即勾 `[x]`；不自动 commit/push（合流由人决定）
- Spotless/Checkstyle/SpotBugs/PMD 三层门禁在 `mvn clean verify` 内统一把关（T013），写码时按 19~24 节实证形态 preempt（编译期常量日志、camelCase 测试名、`@DisplayName` 中文）
