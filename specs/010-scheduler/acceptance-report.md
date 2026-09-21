# 验收报告：定时任务——第三触发源（第 25 节 / specs/010-scheduler）

**日期**：2026-09-21 · **分支**：`specs/010-scheduler` · **课型**：代码课 · **教学文档**：`docs/class/025-scheduler.md`

## ① 全量门禁：`mvn clean verify` 九模块全绿

```
[INFO] Reactor Summary for YokeOS 0.1.0-SNAPSHOT:
[INFO] YokeOS ............................................. SUCCESS [  6.365 s]
[INFO] YokeOS Core ........................................ SUCCESS [ 16.156 s]   ← AgentSchedulerTest 12/12
[INFO] YokeOS Provider .................................... SUCCESS [  7.894 s]
[INFO] YokeOS Storage ..................................... SUCCESS [ 13.912 s]   ← JpaScheduledTaskStoreTest 8/8
[INFO] YokeOS Tool ........................................ SUCCESS [ 10.740 s]
[INFO] YokeOS Memory ...................................... SUCCESS [  9.146 s]
[INFO] YokeOS CLI Channel ................................. SUCCESS [  6.045 s]
[INFO] YokeOS Web ......................................... SUCCESS [  5.750 s]
[INFO] YokeOS CLI ........................................ SUCCESS [ 17.949 s]   ← YokeosRuntimeAssemblyTest 4/4（三 Bean 新装配）
[INFO] YokeOS Boot ........................................ SUCCESS [ 14.993 s]
[INFO] BUILD SUCCESS
```

三层门禁（Spotless + P3C + Checkstyle + SpotBugs/FSB + PMD）随 verify 全过。集成冒烟另跑（见⑥）。

## ② 教学文档 harness 对号表

| 教学文档四部分对号行 | 承载测试 | 结果 |
|---|---|---|
| 注册参数含 cron 与时区（固定 TriggerContext 比 nextExecution——坑九） | `AgentSchedulerTest.registerPassesCronAndZoneToTrigger` | ✅ |
| zone 缺省回退系统时区（analyze C2） | `AgentSchedulerTest.registerFallsBackToSystemZoneWhenZoneBlank` | ✅ |
| 锁被占跳过、不排队（坑二） | `AgentSchedulerTest.skipsWhenPreviousRunStillHoldingLock` | ✅ |
| 失败不外抛 + finally 放锁（二进宫）+ 失败留痕（坑三） | `AgentSchedulerTest.processFailureDoesNotPropagateAndReleasesLock` | ✅ |
| recordExecution 落库自身失败不外抛（坑三兜底） | `AgentSchedulerTest.recordExecutionFailureSwallowedAndLockReleased` | ✅ |
| 会话三元组固定、两次触发同一 Session（坑五） | `AgentSchedulerTest.runOnceUsesFixedSchedulerSessionTriple` | ✅ |
| 派生 taskId 唯一性（坑十） | `AgentSchedulerTest.taskIdDerivedFromProfileNameAndDeclarationIndex` | ✅ |
| 单条非法 cron 跳过其它照常（坑六） | `AgentSchedulerTest.invalidCronSkipsOnlyThatRule` | ✅ |
| 停用任务跳过且不记执行（analyze C1） | `AgentSchedulerTest.disabledTaskSkipsWithoutRecording` | ✅ |
| setUp 钉 isEnabled=true（坑八基建） | `AgentSchedulerTest.setUp` | ✅ |
| reconcile 不冲掉 enabled/run_count（坑十一） | `JpaScheduledTaskStoreTest.reconcileKeepsEnabledAndRunCount` | ✅ |
| recordExecution 历史一行 + 任务行更新 | `JpaScheduledTaskStoreTest.recordExecutionInsertsHistoryAndUpdatesTask` / `...FailureLeavesTrace` | ✅ |
| isEnabled 未登记 true（fail-open）/ setEnabled 切换 | `JpaScheduledTaskStoreTest.isEnabledFailsOpenForUnknownTask` / `setEnabledToggles` | ✅ |
| executions 倒序 + limit | `JpaScheduledTaskStoreTest.executionsReturnsMostRecentFirstWithLimit` | ✅ |
| schema-004 DDL 存在且幂等（宪法 7） | `JpaScheduledTaskStoreTest.schemaDdlIsIdempotent` | ✅ |
| runNow 找到执行 / 找不到点名报错 | `AgentSchedulerTest.runNowFindsTaskOrThrows` | ✅ |
| 无任何规则空跑不报错（analyze L1） | `AgentSchedulerTest.registerAllWithNoSchedulesDoesNotThrow` | ✅ |

## ③ 本节交付物存在性核对

- core 四类：`AgentScheduler.java` / `ScheduledTaskStore.java` / `ScheduledTaskView.java` / `TaskExecutionView.java`（`yokeos-core/.../agent/`，ls 证实）
- storage 六文件：`ScheduledTask.java` / `TaskExecution.java` / `ScheduledTaskRepository.java` / `TaskExecutionRepository.java` / `JpaScheduledTaskStore.java` + `db/schema-004-scheduler.sql`
- cli 装配：`YokeosRuntime.java` 三 Bean（taskScheduler daemon / scheduledTaskStore / agentScheduler initMethod=registerAll）+ `ServeCommand`/`GatewayCommand` 注释兑现
- 启动建表链：`application.yaml` schema-locations 含 schema-004（grep 计 1）
- 测试三类：`AgentSchedulerTest`（12 用例）/ `JpaScheduledTaskStoreTest`（8 用例）/ `SchedulerEndToEndIntegrationTest`（1 用例）

## ④ 前序节回归

九模块 `mvn clean verify`（①的输出）即本分支上的前序节全量回归——16~24 节全部测试含于其中，全绿。

## ⑤ H4 七条全局不变量自查

1. **Spring AI 自动执行未启用**：本节零 Spring AI 接触面；grep `CompletableFuture|reactor|WebFlux` 于 AgentScheduler = 0 命中 → ✅
2. **Provider 显式映射未动**：`providerMap` 零改动 → ✅
3. **同步 + 虚拟线程**：唯一新线程池 = `ThreadPoolTaskScheduler`（宪法 4 明文例外），`setDaemon(true)` 在 `YokeosRuntime:290`；执行链路（process 往下）纯同步 → ✅
4. **session_id 拼接单点（18 节判）**：钟推经 `SessionManager.getOrCreate("scheduler","scheduler",name)`，`AgentScheduler` 不生成 session_id；E2E 断言 session_id = `SessionIds` 三元组拼接 → ✅
5. **审计两表 day one**：钟推失败/成功均走 process 内既有审计，E2E 断言 llm_calls 有关联记录 → ✅
6. **Sandbox 已接线（24 节）**：本节无新动作类型，`http_get` 等工具过 Sandbox 的链路未触碰 → ✅
7. **新触发入口按 17 节判（复用 AgentService）**：钟推 `runOnce→execute→process`，与 CLI/Web 同一入口，ReActLoop 零改动 → ✅

## ⑥ 人工项（当场跑完）+ 剩余项

| 人工项 | 证据 | 结果 |
|---|---|---|
| 真实到点触发一次 | 真实 `YokeOsCli serve`（手拼 classpath）常驻 95s，cron `"0 * * * * *"`：`scheduled_tasks` → `sched-manual#1, enabled=1, run_count=2, last_status=success`；`task_executions` 2 条 success（duration 1285/1253ms）且 session_id 均为 `scheduler:scheduler:sched-manual`；`llm_calls` 该 session 成功调用（total_tokens 111/78）；`sessions` 表钟推 Session 落库 | ✅ |
| 改 cron 免编译重启生效 | AGENT.md cron 改 `"30 * * * * *"` 后重启 serve（**旧库未删**）：cron 字段更新、`run_count 2→3` 延续（reconcile 定义更新 + 状态延续并存——坑十一真机复现） | ✅ |
| chat 一次性命令可退出 | daemon 生效的强证据链：全部 `@SpringBootTest`（含真上下文 `YokeosBootApplicationLoadTest`、`CliFullFlowTest`）跑完 surefire fork 正常收尾未被调度线程挂住；serve 进程 kill 后连接干净释放（kill 后 sqlite 立即可查） | ✅ |
| 凭证卫生 | `grep -r 'sk-' .yokeos/ --include='*.yaml'` = 0；`grep -rn 'sk-[a-zA-Z0-9]{20}' yokeos-*/src/main/resources/` = 0 | ✅ |
| 集成冒烟（真 key） | `mvn -pl yokeos-boot -am test -Dgroups=integration -DexcludedGroups= -Dtest=SchedulerEndToEndIntegrationTest`：**Tests run: 1, Failures: 0, Errors: 0**（11.1s 真 DeepSeek：启动即登记 → runNow 驱动 → 两表对账 + Session + llm_calls 审计同构） | ✅ |
| 端到端预演（31 节地基） | 上两行合计覆盖「到点自动触发 → 完整循环 → 审计 + 执行历史两表可查」 | ✅ |

**剩余项清单：空**（理想达成）。

## 实施偏差

1. **yokeos-core 增 spring-context 显式依赖**（BOM 管版本、非新第三方坐标）：plan 原表述「零新增第三方依赖（spring-context 既有传递件）」在模块层不成立——core 主代码此前无任何 Spring 依赖（仅 test 域经 starter-test 间接可见），`AgentScheduler` 引 `TaskScheduler/CronTrigger` 编译即红。软门禁⑥口径：BOM 管理坐标的显式化放行，pom 注释记理由。
2. **`application.yaml` schema-locations 补 schema-004**：T010 装配实施中发现启动建表链必须登记新脚本（schema-001~003 同款路径），属交付物清单内动作的实现细节补全。
3. **`JpaScheduledTaskStoreTest` 加 `@BeforeEach` 清两表**：22 节静态单例库形态无全表断言故无需清理，本测试有「恰一行/唯一任务」断言，跨用例数据必须清（新坑，已回填 CLAUDE.md）。
4. **`AgentSchedulerTest` matcher 形态**：`recordExecution` 的 primitive 参数（boolean/long）必须 `anyBoolean()/anyLong()`（新坑，已回填）；重叠跳过用例须另一线程占锁 + 双闩（参照钉版树同款，新坑已回填）。
5. **手跑真 serve 的三连坑**（未打 fat JAR 前的 `java -cp` 形态）：boot pom mainClass 硬编码 CLI 入口且 XML 优先于 `-D` 覆盖；classpath 引 m2 旧 jar 时 24 节新类 CNFE（须先 `mvn install`）；boot fat jar 嵌套结构不进 `-cp`（application.yaml 丢失 → datasource「no driver」），须前置 `yokeos-boot/target/classes`——对 31 节 fat JAR 打包课有直接预警价值，已回填 CLAUDE.md。

## 结论

六项证据全过、剩余人工项为零：**第 25 节完成**。可演示成果口径（需 §11 第 25 节行）「Agent 按 cron 到点自跑，执行历史可查」由⑥前两行直接兑现。commit、合流（`--no-ff` + 合流点重跑全量门禁）、push 由人决定。
