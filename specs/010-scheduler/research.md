# Research: 定时任务（第25节）

**Phase 0 产出**（2026-09-21）。技术栈无 NEEDS CLARIFICATION 项——全部研究点在 H0 备料与本文件落盘前完成实证。逐决策记录（Decision / Rationale / Alternatives）：

## D1 · Spring 调度 API 形态（javap 实证，Spring Context 6.2.8）

- **Decision**: `new CronTrigger(cron, ZoneId)` 构造 + `trigger.nextExecution(new SimpleTriggerContext())` 返回 `java.time.Instant` + `taskScheduler.schedule(Runnable, Trigger)` 返回 `ScheduledFuture<?>`。
- **Rationale**: javap 实证签名（`~/.m2/.../spring-context-6.2.8.jar`）：`CronTrigger(String, ZoneId)` 存在；`nextExecution(TriggerContext)` → `Instant`；`SimpleTriggerContext()` 无参构造可用。写法直接落，无代差。
- **Alternatives**: `CronTrigger(String, TimeZone)`（旧形态，弃——`ZoneId` 是现代形态且 frontmatter `zone` 语义直接对应）。

## D2 · task_id 派生生成 `{profileName}#{声明序号}`（用户拍板①）

- **Decision**: 派生 id，不加 frontmatter 字段；技 §9.2 已同步修订。
- **Rationale**: 参照研究（钉版树 + specs/008）结论——显式 id 的完整收益在其 28 节管理端点 REST 路径（本仓 ADR 0008 列扩展阶段）；25/29/30 节窗口内派生功能等价（注销按旧 Profile 派生 id 全量清、不依赖新旧对齐）；参照 id 无必填校验（缺 id 静默跳过）与无唯一性校验（同 id 表行/句柄互相覆盖）两瑕疵随派生整个消掉；扩展阶段可加可选 id 字段缺省回退派生（平滑演进）。
- **Alternatives**: 显式 id 字段（改 16 节定稿 Profile/AgentLoader + CLAUDE.md 数据模型段，属设计变更，用户否决）。

## D3 · store 契约首方法名 `reconcile`（技 §8.5 字面）

- **Decision**: 六方法 `reconcile/recordExecution/isEnabled/setEnabled/list/executions`，首方法用技 §8.5 字面 `reconcile`。
- **Rationale**: 技术方案粒度到接口签名级（CLAUDE.md 文档定位），文档链字面优先；语义 = 幂等登记/更新（已存在保留 enabled 与 run_count，不存在插入默认启用），比参照 `register` 多含「重启时从文件重新协调」的完整语义。
- **Alternatives**: 参照名 `register`（语义偏单次登记，弃）。

## D4 · 调度线程 daemon + chat 模式行为

- **Decision**: `ThreadPoolTaskScheduler` Bean：poolSize=2、`threadNamePrefix "yokeos-sched-"`、`setDaemon(true)`、`initialize()`；AgentScheduler 三 Bean 无条件进 `YokeosRuntime`（chat/serve/gateway 共用装配）。
- **Rationale**: daemon 是「一次性命令跑完 JVM 可退」的解法（参照钉版树同款 + 其课件 Edge Case 明文）；chat 会话期间到点也会真触发一次——与参照行为一致，写进 spec Assumptions；不为 chat 加调度排除开关（新增配置键触软门禁①，文档链未定义）。
- **Alternatives**: chat 模式排除装配（条件化 Bean——新增开关概念，无文档链背书，弃）。

## D5 · `runNow(taskId)` 类级方法，不挂 REST

- **Decision**: 保留参照的 `runNow`（按 id 遍历注册表、无视启用状态、找不到抛 `IllegalArgumentException`），第一阶段零 REST 暴露。
- **Rationale**: 三用途——验收 harness 确定性驱动（免等 cron）+ 31 节「人推补跑」入口（[需 §11] 验收硬条件「都是钟推、支持人推补跑」）+ 30 节端点预留位；ADR 0008 只挡 REST 端点不挡类方法。
- **Alternatives**: 不建 runNow、测试直接调 `execute(profile, sc)`（绕开注册表查找，测试要自己拿 Profile 对象，弃）。

## D6 · E2E 确定性驱动：cron 设每年 1 月 1 日 + `runNow`

- **Decision**: 集成测试 seed 的 AGENT.md cron 用 `"0 0 0 1 1 *"`（测试窗口内绝不自然触发），执行只由 `runNow` 显式驱动。
- **Rationale**: 参照 `ScheduledTaskE2ETest` 同款确定性手法；真等 cron 只能人工项（教学文档第五部分）。
- **Alternatives**: 真等每分钟 cron（测试慢且 flaky，弃）；经 REST POST run 驱动（参照形态，本仓无该端点——ADR 0008，弃）。

## D7 · 句柄表本节留位，注销方法归 29 节

- **Decision**: `AgentScheduler` 持 `任务id → ScheduledFuture` 句柄表（注册时填入），本节不建 `unregisterProfile`；29 节接运行时注册/注销（技 §13 29 节行「AgentScheduler 运行时方法」）。
- **Rationale**: 节序纪律——§13 25 节行交付物不含注销方法；句柄表先立是「结构照抄」（参照 25 节同款留位注释），29 节补方法时零结构改动。
- **Alternatives**: 本节一并建 unregisterProfile（超 25 节交付物范围，弃）。

## D8 · 本节合并参照 25+28 的定时落库部分

- **Decision**: 两表 + `ScheduledTaskStore` 契约 + JPA 实现全在 25 节交付。
- **Rationale**: 技 §13 25 节行明文（「`scheduled_tasks`/`task_executions` 建表、`ScheduledTaskStore` 契约与 JPA 实现」）；[需 §11] 当节可演示成果「执行历史可查」是后半个验收口径，不推迟。参照把落库放 28 节是其节奏，本仓文档链已拍板差异。
- **Alternatives**: 照参照 28 节再落库（违背本仓技 §13，弃）。

## D9 · `nextExecution` 非法配置返回 null

- **Decision**: 下次触发时刻计算失败（cron/zone 非法）返回 null，落库列可空；不影响本次执行。
- **Rationale**: FR7 语义——注册阶段已对非法规则记日志跳过，此路径兜「执行后算下次时刻」的失败；技 §9.2 `next_run_at` 列未标非空。
- **Alternatives**: 抛异常中断执行（违背失败隔离，弃）。

## D10 · 测试基建三坑的解法（教学文档坑八/九 + 通配符）

- **Decision**: ① setUp 钉 `when(taskStore.isEnabled(any())).thenReturn(true)`（Mockito boolean 缺省 false，不钉则 runOnce 全被「停用」跳过、测试白绿）；② 时区断言不靠 `CronTrigger.equals`（其只比 cron 不比时区）——固定 `SimpleTriggerContext` 下比 `nextExecution` 时刻：与「同 cron + 配置时区」参照一致、与别的时区不一致；③ `ScheduledFuture<?>` 通配符 stub 用 `doReturn(future).when(taskScheduler).schedule(...)` 避免 `thenReturn` 类型捕获问题。
- **Rationale**: 参照钉版树测试同款解法 + 本仓 19~24 节测试纪律（camelCase + `@DisplayName`）。
- **Alternatives**: 无（坑的解法唯一性已在参照与本仓前序节反复实证）。

## 参照物对照清单（结构照抄 / 瑕疵不继承 / 显式偏差）

| 参照物 | 处置 |
|---|---|
| `AgentScheduler` 结构（runOnce/execute 拆分、锁表、finally 放锁、recordExecution 包 try-catch） | 照抄 |
| `ScheduledTaskStore` 六方法切面 | 照抄（首方法名改 `reconcile`，D3） |
| `JpaScheduledTaskStore`（幂等 upsert、fail-open、倒序 limit） | 照抄 |
| `ThreadPoolTaskScheduler` daemon 装配 + `@Bean(initMethod="registerAll")` | 照抄（threadNamePrefix 改 `yokeos-sched-`） |
| 显式 id 无必填/唯一性校验 | **不继承**（派生 id 消掉，D2） |
| `ScheduleApiController` + REST 驱动的 `SchedulerFlowIT`/`ScheduledTaskE2ETest` | **显式偏差**（ADR 0008；E2E 改 `runNow` 直调驱动，D5/D6） |
| 参照 28 节才落库的节奏 | **偏差**（本仓 25 节一步到位，技 §13，D8） |
